package de.seism0saurus.glacier.webservice.cache;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A bounded, thread-safe ring buffer of {@link CacheEntry} objects for a single
 * {@code (principal, hashtag)} subscription.
 *
 * <p>Implementation notes:
 * <ul>
 *   <li>Capacity is fixed at construction time; once full the oldest entry is
 *       evicted to make room for the new one (FIFO ring semantics).</li>
 *   <li>All mutating and reading operations are guarded by a {@link ReentrantLock}
 *       so that sequence numbers are strictly monotonic and snapshots never observe
 *       a partially-written entry.</li>
 *   <li>Sequence numbers start at 1; a {@code since} value of {@code null} or
 *       {@code 0} means "give me everything".</li>
 * </ul>
 *
 * <p>This class is package-private; outside the cache package use
 * {@link MessageCache} instead.
 */
class PerTagRing {

    private final int capacity;
    private final ArrayDeque<CacheEntry> ring;
    private long nextSequence;
    private final ReentrantLock lock;

    /**
     * Creates a new ring buffer with the given capacity.
     *
     * @param capacity maximum number of entries retained; must be &ge; 1
     */
    PerTagRing(final int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("Capacity must be at least 1");
        }
        this.capacity = capacity;
        this.ring = new ArrayDeque<>(capacity);
        this.nextSequence = 1L;
        this.lock = new ReentrantLock();
    }

    /**
     * Appends a new entry to the ring, assigning the next monotonic sequence number.
     *
     * <p>When the ring is full the oldest entry is silently evicted before inserting
     * the new one, preserving the bounded-memory invariant.
     *
     * @param partial a {@link CacheEntry} whose {@code sequence} field is ignored;
     *                the ring assigns the real sequence
     * @return a new {@link CacheEntry} equal to {@code partial} but with the
     *         assigned sequence number set
     */
    CacheEntry append(final CacheEntry partial) {
        lock.lock();
        try {
            long seq = nextSequence++;
            CacheEntry entry = new CacheEntry(
                    partial.type(),
                    partial.statusId(),
                    partial.url(),
                    partial.editedAt(),
                    seq
            );
            if (ring.size() >= capacity) {
                ring.poll();
            }
            ring.addLast(entry);
            return entry;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns all entries whose sequence number is greater than {@code since},
     * together with the current head sequence and a gap flag.
     *
     * <p>A gap is reported when the requested cursor predates the oldest entry
     * still in the ring, i.e. some events were evicted before the client could
     * read them.  In that case the full buffer content is returned so the client
     * can resync to the most recent 20 entries.
     *
     * @param since the cursor from the client's last successful poll; {@code null}
     *              or {@code 0} is treated as "give me everything"
     * @return a {@link Snapshot} — never {@code null}; events list may be empty
     */
    Snapshot snapshotSince(final Long since) {
        lock.lock();
        try {
            if (ring.isEmpty()) {
                long head = nextSequence - 1;
                return new Snapshot(List.of(), head, false);
            }

            long head = ring.peekLast().sequence();
            long oldest = ring.peekFirst().sequence();

            long effectiveSince = (since == null) ? 0L : since;

            boolean gap = effectiveSince > 0 && effectiveSince < (oldest - 1);

            List<CacheEntry> result;
            if (gap) {
                // Full buffer to allow client resync
                result = new ArrayList<>(ring);
            } else {
                result = new ArrayList<>();
                for (CacheEntry entry : ring) {
                    if (entry.sequence() > effectiveSince) {
                        result.add(entry);
                    }
                }
            }

            return new Snapshot(List.copyOf(result), head, gap);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns the number of entries currently held in the ring (for testing).
     */
    int size() {
        lock.lock();
        try {
            return ring.size();
        } finally {
            lock.unlock();
        }
    }
}
