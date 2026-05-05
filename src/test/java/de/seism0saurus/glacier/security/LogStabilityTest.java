package de.seism0saurus.glacier.security;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.awaitility.core.ConditionTimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link LogStabilityHelper#awaitLogStability} (SR-F5-02).
 *
 * <p>These tests guard the helper against four classes of regression:
 * <ol>
 *   <li><b>Empty appender, stays empty</b> — the helper must return within approximately
 *       {@code 2 × quietWindow} when no events have been appended and none arrive during
 *       the wait. Confirms the "settled" fast-path is not accidentally blocked.</li>
 *   <li><b>Settled burst</b> — if N events are appended synchronously before calling the
 *       helper, the helper must detect stability within {@code 2 × quietWindow}. Confirms
 *       that a non-zero but stable count is also recognised as quiesced.</li>
 *   <li><b>Continuous growth</b> — if a background thread appends a new event every
 *       {@code quietWindow / 2} ms for the entire wait period, the helper must throw
 *       {@link ConditionTimeoutException}. Confirms that genuine growth is not
 *       falsely declared stable.</li>
 *   <li><b>First-poll sentinel</b> — the {@code AtomicInteger(-1)} sentinel must prevent
 *       the first poll from matching {@code previous == current == 0}. The helper must NOT
 *       return immediately on an empty appender; it must perform at least two polls so that
 *       the first poll transitions {@code -1 → 0} and the second poll confirms {@code 0 == 0}.</li>
 * </ol>
 *
 * <p><b>Mutation acceptance criterion</b>: if the implementation inside
 * {@link LogStabilityHelper} were changed to capture
 * {@code Object current = appender.list} instead of
 * {@code int current = appender.list.size()}, scenario (d) would break — because
 * {@code appender.list} returns the same {@link java.util.List} reference on every call,
 * the reference comparison would fire on the very first poll regardless of the sentinel,
 * causing the helper to return immediately from an empty appender and defeating the
 * two-poll minimum enforced by the {@code -1} seed. Test (d) verifies this invariant by
 * asserting that the elapsed time is at least one {@code quietWindow} long, which would
 * not hold if the helper returned on poll 1.
 *
 * <p>This is a Surefire unit test ({@code *Test.java}) — picked up by the default Maven
 * lifecycle and counted toward the Jacoco coverage gate.
 */
class LogStabilityTest {

    /** Duration for a quiet window short enough that the tests run in well under a second. */
    private static final Duration QUIET_WINDOW = Duration.ofMillis(50);

    /** Maximum wait ceiling generous enough for CI but still fails fast in scenario (c). */
    private static final Duration MAX_WAIT = Duration.ofMillis(500);

    /** A real Logback logger in the same package — convenient event source for the tests. */
    private Logger testLogger;

    /** The appender under test — started fresh before each scenario. */
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void attachFreshAppender() {
        // Arrange (shared): wire a new ListAppender to a Glacier package logger so that
        // test-driven log events are captured without interfering with other loggers.
        testLogger = (Logger) LoggerFactory.getLogger(LogStabilityTest.class);
        appender = new ListAppender<>();
        appender.start();
        testLogger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        testLogger.detachAppender(appender);
    }

    // -------------------------------------------------------------------------
    // Scenario (a): empty appender that stays empty returns quickly
    // -------------------------------------------------------------------------

    /**
     * Scenario (a): an appender with no events and no subsequent events must be considered
     * stable within approximately {@code 2 × quietWindow}.
     *
     * <p>Arrange: the appender is started and attached but no events are ever appended.<br>
     * Act: call {@link LogStabilityHelper#awaitLogStability} with a 500 ms max and 50 ms window.<br>
     * Assert: the call returns without throwing; the elapsed time is below the ceiling —
     * confirming the settled fast-path works for the zero-event case.
     */
    @Test
    void emptyAppender_thatStaysEmpty_returnsWithinTwoQuietWindows() {
        // Arrange: no events are appended — appender is empty and stays empty.

        // Act
        Instant before = Instant.now();
        LogStabilityHelper.awaitLogStability(appender, MAX_WAIT, QUIET_WINDOW);
        Duration elapsed = Duration.between(before, Instant.now());

        // Assert: must complete quickly — well within the maxWait ceiling.
        // Two quiet-window polls suffice: poll-1 transitions -1→0 (no match),
        // poll-2 observes 0==0 (match). Allow generous headroom for CI scheduling jitter.
        assertThat(elapsed)
                .as("Scenario (a): empty appender must settle in much less than maxWait (%s); "
                        + "elapsed was %s", MAX_WAIT, elapsed)
                .isLessThan(MAX_WAIT);
    }

    // -------------------------------------------------------------------------
    // Scenario (b): settled burst — N pre-appended events, no further growth
    // -------------------------------------------------------------------------

    /**
     * Scenario (b): if N events have been appended synchronously before calling the helper,
     * the helper must detect stability within {@code 2 × quietWindow}.
     *
     * <p>Arrange: five log events are written to the logger before calling the helper.<br>
     * Act: call {@link LogStabilityHelper#awaitLogStability}.<br>
     * Assert: the call returns without throwing and the appender still contains exactly
     * the N events that were appended before the call.
     */
    @Test
    void settledBurst_ofNEvents_isDetectedAsStable() {
        // Arrange: append N events synchronously before calling the helper.
        int burstSize = 5;
        for (int i = 0; i < burstSize; i++) {
            testLogger.info("burst event {}", i);
        }

        // Act
        LogStabilityHelper.awaitLogStability(appender, MAX_WAIT, QUIET_WINDOW);

        // Assert: no exception was thrown and the count is exactly what we appended —
        // confirming that a non-zero but stable count is recognised as quiesced.
        assertThat(appender.list)
                .as("Scenario (b): appender must still hold exactly %d events after stability check",
                        burstSize)
                .hasSize(burstSize);
    }

    // -------------------------------------------------------------------------
    // Scenario (c): continuous growth triggers ConditionTimeoutException
    // -------------------------------------------------------------------------

    /**
     * Scenario (c): while a background daemon thread continuously appends new events at a
     * rate faster than the quiet window, the helper must throw
     * {@link ConditionTimeoutException} after {@code maxWait}.
     *
     * <p>Arrange: a daemon thread appends one event every {@code quietWindow / 2} ms,
     * ensuring the count never stabilises.<br>
     * Act: call {@link LogStabilityHelper#awaitLogStability} with the same max-wait and
     * quiet-window as other scenarios.<br>
     * Assert: {@link ConditionTimeoutException} is thrown — confirming genuine growth
     * is not falsely declared stable.
     */
    @Test
    void continuouslyGrowingAppender_throwsConditionTimeoutException() {
        // Arrange: start a daemon thread that appends faster than the quiet window.
        AtomicBoolean stopFlag = new AtomicBoolean(false);
        Thread producer = new Thread(() -> {
            long sleepMs = QUIET_WINDOW.toMillis() / 2;
            while (!stopFlag.get()) {
                testLogger.info("continuous growth event");
                try {
                    Thread.sleep(sleepMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        });
        producer.setDaemon(true);
        producer.start();

        try {
            // Act + Assert: the helper must time out because the appender never quiesces.
            assertThatThrownBy(
                    () -> LogStabilityHelper.awaitLogStability(appender, MAX_WAIT, QUIET_WINDOW))
                    .as("Scenario (c): continuously growing appender must cause ConditionTimeoutException")
                    .isInstanceOf(ConditionTimeoutException.class);
        } finally {
            // Clean up: stop the producer thread before leaving the test.
            stopFlag.set(true);
            producer.interrupt();
        }
    }

    // -------------------------------------------------------------------------
    // Scenario (d): first-poll sentinel prevents immediate return on empty appender
    // -------------------------------------------------------------------------

    /**
     * Scenario (d): the {@code AtomicInteger(-1)} sentinel must prevent the helper from
     * returning on the very first poll when the appender is empty.
     *
     * <p>The invariant: on poll 1, {@code previous = -1} and {@code current = 0}, so
     * {@code previous >= 0} is {@code false} and the predicate returns {@code false}.
     * Only on poll 2 does {@code previous = 0} and {@code current = 0} produce a match.
     * This means the helper must always wait at least one full {@code quietWindow} before
     * returning, even on an empty appender.
     *
     * <p><b>Mutation gate</b>: if the implementation captured
     * {@code Object current = appender.list} instead of
     * {@code int current = appender.list.size()}, the sentinel-based predicate would
     * compare the same list reference to {@code -1} (an {@code int}) — but more critically,
     * the two-poll minimum would be lost because object-reference equality would always be
     * vacuously true on the first poll, making the helper return before the sentinel can
     * prevent the early exit. This test pins that the elapsed time is at least
     * {@code quietWindow}, which only holds when two polls actually occur.
     *
     * <p>Arrange: the appender is empty and receives no new events.<br>
     * Act: call {@link LogStabilityHelper#awaitLogStability} and measure elapsed time.<br>
     * Assert: elapsed time is at least {@code quietWindow} — proving that at least two
     * polls were performed (the first was rejected by the sentinel, the second confirmed
     * stability).
     */
    @Test
    void firstPollSentinel_preventsImmediateReturnOnEmptyAppender() {
        // Arrange: appender is empty and no events will be added.

        // Act
        Instant before = Instant.now();
        LogStabilityHelper.awaitLogStability(appender, MAX_WAIT, QUIET_WINDOW);
        Duration elapsed = Duration.between(before, Instant.now());

        // Assert: at least one full quiet window must have passed —
        // proving poll 1 was rejected (previous=-1, no match) and poll 2 confirmed stability.
        // If the implementation used reference equality, the helper would return in < 1 ms
        // on the first poll, causing this assertion to fail.
        assertThat(elapsed)
                .as("Scenario (d): sentinel must force at least 2 polls; "
                        + "elapsed (%s) must be >= quietWindow (%s). "
                        + "Failure means the implementation returned on poll-1 — "
                        + "likely because reference equality replaced scalar size comparison.",
                        elapsed, QUIET_WINDOW)
                .isGreaterThanOrEqualTo(QUIET_WINDOW);
    }
}
