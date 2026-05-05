package de.seism0saurus.glacier.security;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.awaitility.Awaitility;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Package-private test utility: polls a Logback {@link ListAppender} until its event
 * count has not changed across two consecutive polls separated by a quiet window.
 *
 * <p><b>Why scalar size, not reference equality?</b><br>
 * {@code appender.list} returns the same {@link java.util.List} object on every call.
 * Comparing the reference would be vacuous — {@code list == list} is always {@code true}
 * and would make the predicate fire immediately on the first poll regardless of growth.
 * Only a snapshot of the scalar {@code int} size can detect that new events have arrived.
 *
 * <p>Mutation acceptance criterion: if the implementation were changed to capture
 * {@code Object current = appender.list} instead of {@code int current = appender.list.size()},
 * scenario (d) in {@link LogStabilityTest} would break — a sentinel-seeded
 * {@link AtomicInteger} set to {@code -1} would never match because the list reference is
 * always the same object, causing the predicate to fire on the very first poll and return
 * immediately even when the appender is empty, defeating the sentinel logic entirely.
 *
 * <p>This class is intentionally package-private — it is test infrastructure, not part of
 * the production API.
 */
final class LogStabilityHelper {

    /** Utility class; no instances. */
    private LogStabilityHelper() {
    }

    /**
     * Polls {@code appender} until its log-event count has not changed between two
     * consecutive polls separated by {@code quietWindow}.
     *
     * <p>The {@link AtomicInteger} sentinel is seeded with {@code -1} so that the first
     * poll — which transitions {@code previous = -1} to {@code current = 0} for an empty
     * appender — always yields {@code false}. This prevents an empty appender at t=0 from
     * accidentally satisfying the predicate before any logging could have occurred.
     *
     * <p>Throws {@link org.awaitility.core.ConditionTimeoutException} (unchecked) if the
     * appender is still growing after {@code maxWait} has elapsed.
     *
     * @param appender    the Logback {@link ListAppender} to observe; must be started and
     *                    attached to its logger before this method is called
     * @param maxWait     maximum time to wait before Awaitility declares a timeout
     * @param quietWindow poll interval; two identical size readings separated by this
     *                    duration are interpreted as "the appender has quiesced"
     */
    static void awaitLogStability(
            ListAppender<ILoggingEvent> appender,
            Duration maxWait,
            Duration quietWindow) {
        AtomicInteger lastSize = new AtomicInteger(-1);
        Awaitility.await()
                .atMost(maxWait)
                .pollDelay(Duration.ZERO)
                .pollInterval(quietWindow)
                .until(() -> {
                    int current = appender.list.size();
                    int previous = lastSize.getAndSet(current);
                    return previous >= 0 && current == previous;
                });
    }
}
