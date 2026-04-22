package de.seism0saurus.glacier.webservice.cache;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link PerTagRing}.
 */
class PerTagRingTest {

    private static CacheEntry partial(final String id) {
        return new CacheEntry(EventType.CREATED, id, "https://example.com/" + id + "/embed", null, 0L);
    }

    // -----------------------------------------------------------------
    // Basic capacity and FIFO eviction
    // -----------------------------------------------------------------

    @Test
    void append_21Entries_retains20AndEvictsOldest() {
        PerTagRing ring = new PerTagRing(20);

        for (int i = 1; i <= 21; i++) {
            ring.append(partial("id-" + i));
        }

        assertThat(ring.size()).isEqualTo(20);
        // Snapshot should NOT contain the first entry (seq=1) but must contain seq=21
        Snapshot snap = ring.snapshotSince(null);
        List<Long> seqs = snap.events().stream().map(CacheEntry::sequence).toList();
        assertThat(seqs).doesNotContain(1L);
        assertThat(seqs).contains(21L);
    }

    @Test
    void append_assignsStrictlyIncreasingSequences() {
        PerTagRing ring = new PerTagRing(5);
        List<Long> seqs = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            seqs.add(ring.append(partial("id-" + i)).sequence());
        }
        assertThat(seqs).isSorted();
        assertThat(seqs.getFirst()).isEqualTo(1L);
        assertThat(seqs.getLast()).isEqualTo(5L);
    }

    // -----------------------------------------------------------------
    // snapshotSince — various cursor positions
    // -----------------------------------------------------------------

    @Test
    void snapshotSince_nullCursor_returnsAllEntries() {
        PerTagRing ring = new PerTagRing(5);
        ring.append(partial("a"));
        ring.append(partial("b"));

        Snapshot snap = ring.snapshotSince(null);

        assertThat(snap.events()).hasSize(2);
        assertThat(snap.gap()).isFalse();
        assertThat(snap.nextSince()).isEqualTo(2L);
    }

    @Test
    void snapshotSince_emptyRing_returnsEmptyListAndNoGap() {
        PerTagRing ring = new PerTagRing(5);

        Snapshot snap = ring.snapshotSince(null);

        assertThat(snap.events()).isEmpty();
        assertThat(snap.gap()).isFalse();
        assertThat(snap.nextSince()).isEqualTo(0L);
    }

    @Test
    void snapshotSince_cursorAtHead_returnsEmptyAndNoGap() {
        PerTagRing ring = new PerTagRing(5);
        ring.append(partial("a"));
        ring.append(partial("b"));

        Snapshot snap = ring.snapshotSince(2L);

        assertThat(snap.events()).isEmpty();
        assertThat(snap.gap()).isFalse();
        assertThat(snap.nextSince()).isEqualTo(2L);
    }

    @Test
    void snapshotSince_cursorInRange_returnsOnlyNewer() {
        PerTagRing ring = new PerTagRing(5);
        ring.append(partial("a")); // seq=1
        ring.append(partial("b")); // seq=2
        ring.append(partial("c")); // seq=3

        Snapshot snap = ring.snapshotSince(1L);

        assertThat(snap.events()).hasSize(2);
        assertThat(snap.events().stream().map(CacheEntry::statusId).toList())
                .containsExactly("b", "c");
        assertThat(snap.gap()).isFalse();
    }

    @Test
    void snapshotSince_cursorBeforeOldest_gapTrueAndFullBuffer() {
        PerTagRing ring = new PerTagRing(3);
        // Fill beyond capacity so oldest seq is evicted
        ring.append(partial("a")); // seq=1 — will be evicted
        ring.append(partial("b")); // seq=2 — will be evicted
        ring.append(partial("c")); // seq=3
        ring.append(partial("d")); // seq=4
        ring.append(partial("e")); // seq=5

        // oldest in ring = seq=3; request since=1 → gap
        Snapshot snap = ring.snapshotSince(1L);

        assertThat(snap.gap()).isTrue();
        assertThat(snap.events()).hasSize(3);
        assertThat(snap.nextSince()).isEqualTo(5L);
    }

    @Test
    void snapshotSince_zeroCursor_returnsFullBuffer() {
        PerTagRing ring = new PerTagRing(5);
        ring.append(partial("a"));
        ring.append(partial("b"));

        Snapshot snap = ring.snapshotSince(0L);

        assertThat(snap.events()).hasSize(2);
        assertThat(snap.gap()).isFalse();
    }

    @Test
    void constructing_withCapacityLessThanOne_throwsException() {
        assertThatThrownBy(() -> new PerTagRing(0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // -----------------------------------------------------------------
    // Concurrency torture test
    // -----------------------------------------------------------------

    /**
     * 10 writer threads each append 100 entries (1 000 total), while concurrently
     * 10 reader threads each take 100 snapshots.  Asserts:
     * <ul>
     *   <li>All assigned sequence numbers are strictly increasing (no duplicates).</li>
     *   <li>Each snapshot returns a result whose events are in ascending sequence order.</li>
     *   <li>No snapshot contains duplicate entries (torn reads).</li>
     * </ul>
     */
    @Test
    void concurrency_thousandAppendsTenThreadsThenSnapshot_strictlyIncreasingNoTornReads() throws Exception {
        PerTagRing ring = new PerTagRing(20);
        int writerCount = 10;
        int appendsPerWriter = 100;
        int readerCount = 10;
        int snapshotsPerReader = 100;

        CountDownLatch startGate = new CountDownLatch(1);
        ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();

        // Collect all assigned sequences from writers
        List<Future<List<Long>>> writerFutures = new ArrayList<>();
        for (int w = 0; w < writerCount; w++) {
            final int writerIndex = w;
            writerFutures.add(pool.submit(() -> {
                startGate.await();
                List<Long> assigned = new ArrayList<>();
                for (int i = 0; i < appendsPerWriter; i++) {
                    CacheEntry e = ring.append(partial("w" + writerIndex + "-" + i));
                    assigned.add(e.sequence());
                }
                return assigned;
            }));
        }

        // Readers take snapshots and assert internal ordering
        List<Future<Void>> readerFutures = new ArrayList<>();
        for (int r = 0; r < readerCount; r++) {
            readerFutures.add(pool.submit(() -> {
                startGate.await();
                for (int i = 0; i < snapshotsPerReader; i++) {
                    Snapshot snap = ring.snapshotSince(null);
                    List<Long> seqs = snap.events().stream().map(CacheEntry::sequence).toList();
                    // Entries in a snapshot must be in ascending order
                    for (int k = 1; k < seqs.size(); k++) {
                        assertThat(seqs.get(k)).isGreaterThan(seqs.get(k - 1));
                    }
                    // No duplicates within a snapshot
                    Set<Long> seen = new HashSet<>(seqs);
                    assertThat(seen).hasSameSizeAs(seqs);
                }
                return null;
            }));
        }

        startGate.countDown();

        // Collect all writer sequences
        Set<Long> allSequences = new HashSet<>();
        for (Future<List<Long>> f : writerFutures) {
            List<Long> assigned = f.get();
            // No duplicates across writers
            for (long seq : assigned) {
                assertThat(allSequences.add(seq))
                        .as("Duplicate sequence %d assigned by two writers", seq)
                        .isTrue();
            }
        }

        // All reader snapshots must have completed without assertion failures
        for (Future<Void> f : readerFutures) {
            f.get();
        }

        pool.shutdown();

        // Total distinct sequences assigned must equal writerCount × appendsPerWriter
        assertThat(allSequences).hasSize(writerCount * appendsPerWriter);
    }
}
