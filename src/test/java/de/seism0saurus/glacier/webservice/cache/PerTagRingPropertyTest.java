package de.seism0saurus.glacier.webservice.cache;

import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * jqwik property-based tests for {@link PerTagRing} (SR-TEST-06).
 *
 * <p>Asserts the ring-buffer invariants that the live STOMP fan-out relies on for
 * ordering and bounded memory, across arbitrary capacity/append counts rather than
 * the few sizes the example-based {@code PerTagRingTest} checks:
 * <ul>
 *   <li>occupancy is always {@code min(appended, capacity)} (bounded memory);</li>
 *   <li>{@code append} assigns strictly monotonic 1-based sequences regardless of
 *       eviction (so the frontend, which orders by arrival, never regresses).</li>
 * </ul>
 */
class PerTagRingPropertyTest {

    private static CacheEntry partial(final int i) {
        return new CacheEntry(EventType.CREATED, "s" + i, "https://ex.com/" + i + "/embed", null, 0L);
    }

    @Property
    void occupancyNeverExceedsCapacity(
            @ForAll @IntRange(min = 1, max = 64) final int capacity,
            @ForAll @IntRange(min = 0, max = 300) final int appended) {
        PerTagRing ring = new PerTagRing(capacity);
        for (int i = 0; i < appended; i++) {
            ring.append(partial(i));
        }
        assertThat(ring.size())
                .as("ring occupancy must equal min(appended, capacity)")
                .isEqualTo(Math.min(appended, capacity));
    }

    @Property
    void appendAssignsStrictlyMonotonicOneBasedSequences(
            @ForAll @IntRange(min = 1, max = 64) final int capacity,
            @ForAll @IntRange(min = 1, max = 300) final int appended) {
        PerTagRing ring = new PerTagRing(capacity);
        long previous = 0L;
        for (int i = 0; i < appended; i++) {
            long seq = ring.append(partial(i)).sequence();
            assertThat(seq)
                    .as("each appended sequence must be exactly one greater than the previous")
                    .isEqualTo(previous + 1);
            previous = seq;
        }
    }

    @Property
    void capacityBelowOneIsRejected(@ForAll @IntRange(min = -10, max = 0) final int badCapacity) {
        try {
            new PerTagRing(badCapacity);
            assertThat(false).as("capacity < 1 must be rejected").isTrue();
        } catch (IllegalArgumentException expected) {
            // fail-fast on invalid capacity — expected
            assertThat(expected).isInstanceOf(IllegalArgumentException.class);
        }
    }
}
