package de.seism0saurus.glacier.webservice.cache;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the {@link Snapshot} record.
 *
 * <p>Verifies the equality contract (record-based structural equality) and that the
 * {@code gap} flag is set correctly to signal ring-buffer overflow to callers.
 */
class SnapshotTest {

    // -----------------------------------------------------------------------
    // Equality — record structural equality
    // -----------------------------------------------------------------------

    /**
     * Two {@link Snapshot} instances with the same fields must be considered equal
     * (standard Java record equality).
     *
     * <p>Arrange: two instances with identical field values.
     * <p>Act:     call {@code equals}.
     * <p>Assert:  equal; hash codes also match.
     */
    @Test
    void equality_byFields() {
        CacheEntry entry = new CacheEntry(EventType.CREATED, "s1", "https://ex.com/s1/embed", null, 1L);
        Snapshot a = new Snapshot(List.of(entry), 1L, false);
        Snapshot b = new Snapshot(List.of(entry), 1L, false);

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    // -----------------------------------------------------------------------
    // gap flag — signals ring-buffer overflow
    // -----------------------------------------------------------------------

    /**
     * When {@code gap} is {@code true}, the snapshot signals that the requested
     * {@code since} cursor predated the oldest entry still in the ring buffer,
     * meaning some events were silently dropped.  Callers display a gap warning.
     *
     * <p>Arrange: a snapshot with {@code gap = true}.
     * <p>Act:     read the {@code gap()} accessor.
     * <p>Assert:  {@code true} is returned, indicating ring-drop condition.
     */
    @Test
    void gapTrue_signalsRingDrop() {
        CacheEntry entry = new CacheEntry(EventType.CREATED, "s2", "https://ex.com/s2/embed", null, 3L);
        Snapshot snapshot = new Snapshot(List.of(entry), 3L, true);

        assertThat(snapshot.gap()).isTrue();
        assertThat(snapshot.events()).hasSize(1);
        assertThat(snapshot.nextSince()).isEqualTo(3L);
    }
}
