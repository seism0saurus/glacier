package de.seism0saurus.glacier.share.application;

import de.seism0saurus.glacier.share.domain.ShareLinkId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link ShareLinkViewerCounter}.
 *
 * <p>Security: SR-SHARE-05 (per-link viewer cap enforcement),
 * OWASP API4 (Unrestricted Resource Consumption).
 *
 * <p>Covers:
 * <ul>
 *   <li>Increment returns correct successive counts.</li>
 *   <li>Decrement reduces the count; never goes below zero.</li>
 *   <li>Independent counters per link — cross-link isolation.</li>
 *   <li>Thread-safety under concurrent increment from multiple threads.</li>
 *   <li>Increment-then-decrement cycle frees the slot (cap refund pattern).</li>
 * </ul>
 */
class ShareLinkViewerCounterTest {

    // Two well-formed ShareLinkIds (43 base64url chars each) for isolation tests
    private static final ShareLinkId LINK_A = ShareLinkId.fromUrlPath("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA");
    private static final ShareLinkId LINK_B = ShareLinkId.fromUrlPath("BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB");

    private ShareLinkViewerCounter counter;

    @BeforeEach
    void setUp() {
        counter = new ShareLinkViewerCounter();
    }

    // -----------------------------------------------------------------------
    // Basic increment / get / decrement behaviour
    // -----------------------------------------------------------------------

    @Test
    void initialCountIsZero() {
        assertThat(counter.get(LINK_A)).isZero();
    }

    @Test
    void incrementReturnsOneForFirstCall() {
        assertThat(counter.increment(LINK_A)).isEqualTo(1);
    }

    @Test
    void incrementReturnsSuccessiveCounts() {
        assertThat(counter.increment(LINK_A)).isEqualTo(1);
        assertThat(counter.increment(LINK_A)).isEqualTo(2);
        assertThat(counter.increment(LINK_A)).isEqualTo(3);
    }

    @Test
    void getReflectsCurrentCountAfterIncrements() {
        counter.increment(LINK_A);
        counter.increment(LINK_A);
        assertThat(counter.get(LINK_A)).isEqualTo(2);
    }

    @Test
    void decrementReducesCount() {
        counter.increment(LINK_A);
        counter.increment(LINK_A);
        counter.decrement(LINK_A);
        assertThat(counter.get(LINK_A)).isEqualTo(1);
    }

    @Test
    void decrementOnZeroStaysAtZero() {
        // SR-SHARE-05: spurious decrements (e.g. duplicate disconnect events) must not
        // drive the counter negative, which would allow bypassing the cap.
        counter.decrement(LINK_A);
        assertThat(counter.get(LINK_A)).isZero();
    }

    @Test
    void decrementOnUnknownLinkIsNoOp() {
        // Counter has no entry for LINK_B yet; decrement must not throw
        counter.decrement(LINK_B);
        assertThat(counter.get(LINK_B)).isZero();
    }

    @Test
    void fullIncrementDecrementCycleFreesSlot() {
        // Simulate the cap-refund pattern: increment, detect cap exceeded, decrement.
        counter.increment(LINK_A);
        int before = counter.get(LINK_A);
        counter.decrement(LINK_A);
        assertThat(counter.get(LINK_A)).isEqualTo(before - 1);
    }

    // -----------------------------------------------------------------------
    // Cross-link isolation
    // -----------------------------------------------------------------------

    @Test
    void linksHaveIndependentCounters() {
        counter.increment(LINK_A);
        counter.increment(LINK_A);
        counter.increment(LINK_B);

        assertThat(counter.get(LINK_A)).isEqualTo(2);
        assertThat(counter.get(LINK_B)).isEqualTo(1);
    }

    @Test
    void decrementOnLinkADoesNotAffectLinkB() {
        counter.increment(LINK_A);
        counter.increment(LINK_B);
        counter.decrement(LINK_A);

        assertThat(counter.get(LINK_A)).isZero();
        assertThat(counter.get(LINK_B)).isEqualTo(1);
    }

    // -----------------------------------------------------------------------
    // Thread-safety: concurrent increments
    // -----------------------------------------------------------------------

    /**
     * 20 threads each increment the same link 50 times.
     * Expected final count: 1000.
     *
     * <p>This guards against lost-update races in the counter's ConcurrentHashMap /
     * AtomicInteger implementation under high concurrency.
     */
    @Test
    void concurrentIncrementsAreThreadSafe() throws InterruptedException {
        int threads = 20;
        int incrementsPerThread = 50;
        int expected = threads * incrementsPerThread;

        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    startGate.await();
                    for (int j = 0; j < incrementsPerThread; j++) {
                        counter.increment(LINK_A);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        startGate.countDown(); // release all threads simultaneously
        done.await();
        pool.shutdown();

        assertThat(counter.get(LINK_A)).isEqualTo(expected);
    }

    /**
     * 10 threads increment and 10 threads decrement concurrently — the final count must
     * stay between 0 and the number of increments (never negative).
     */
    @Test
    void concurrentIncrementAndDecrementNeverGoesNegative() throws InterruptedException {
        int incrementThreads = 10;
        int decrementThreads = 10;
        int opsPerThread = 30;

        // Pre-seed so decrements don't all hit zero immediately
        for (int i = 0; i < incrementThreads * opsPerThread; i++) {
            counter.increment(LINK_A);
        }
        int seededCount = counter.get(LINK_A);

        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(incrementThreads + decrementThreads);
        ExecutorService pool = Executors.newFixedThreadPool(incrementThreads + decrementThreads);
        AtomicInteger minObserved = new AtomicInteger(Integer.MAX_VALUE);

        for (int i = 0; i < incrementThreads; i++) {
            pool.submit(() -> {
                try {
                    startGate.await();
                    for (int j = 0; j < opsPerThread; j++) {
                        counter.increment(LINK_A);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        for (int i = 0; i < decrementThreads; i++) {
            pool.submit(() -> {
                try {
                    startGate.await();
                    for (int j = 0; j < opsPerThread; j++) {
                        counter.decrement(LINK_A);
                        int v = counter.get(LINK_A);
                        minObserved.updateAndGet(current -> Math.min(current, v));
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        startGate.countDown();
        done.await();
        pool.shutdown();

        // The counter must never have gone negative
        assertThat(minObserved.get()).isGreaterThanOrEqualTo(0);
        // Final count must also be non-negative
        assertThat(counter.get(LINK_A)).isGreaterThanOrEqualTo(0);
    }
}
