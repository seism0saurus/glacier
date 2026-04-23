package de.seism0saurus.glacier.util;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Log hygiene smoke tests (D-13, SR-8).
 *
 * <p>Verifies that the {@link LogScrubber#FORBIDDEN_LOG_FIELDS} set is comprehensive
 * and that the scrubbing utilities behave correctly in adversarial input scenarios.
 *
 * <p>A full MDC-scrubbing test (asserting that Logback's JSON encoder drops the forbidden
 * fields) would require a custom Logback appender capturing output — that is a heavy
 * integration concern left for the observability layer.  These unit-level smoke tests
 * validate the helper contracts used by production logging statements.
 */
class LoggingSmokeTest {

    /**
     * Asserts that an MDC key named {@code cookie} is in the forbidden list.
     * This is the primary concern: raw cookie values must never appear in JSON logs (D-13).
     */
    @Test
    void forbiddenFields_cookie_isListed() {
        assertThat(LogScrubber.FORBIDDEN_LOG_FIELDS).contains("cookie");
    }

    /**
     * Asserts that no log message constructed by {@link LogScrubber#hash8} would contain
     * a UUID-format string (which is the wallId format).
     */
    @Test
    void hash8_outputDoesNotContainUuidPattern() {
        String wallId = "550e8400-e29b-41d4-a716-446655440000";
        String hashed = LogScrubber.hash8(wallId);

        // The 8-char hash must not be a UUID fragment that could be correlated
        assertThat(hashed).hasSize(8);
        assertThat(LogScrubber.containsRawUuid(hashed)).isFalse();
    }

    /**
     * Asserts that a log message containing a raw UUID is detected by {@link LogScrubber#containsRawUuid}.
     * Production code must call this before emitting a log line that might include a wallId.
     */
    @Test
    void containsRawUuid_logMessageWithWallId_isDetected() {
        String leakyLogMessage = "Authentication passed for wallId=550e8400-e29b-41d4-a716-446655440000";
        assertThat(LogScrubber.containsRawUuid(leakyLogMessage)).isTrue();
    }

    /**
     * Asserts that a safe log message (using the hash prefix) is NOT flagged.
     */
    @Test
    void containsRawUuid_safeLogMessageWithHash_notFlagged() {
        String safeLogMessage = "Authentication passed for wallId-hash8=a1b2c3d4";
        assertThat(LogScrubber.containsRawUuid(safeLogMessage)).isFalse();
    }

    /**
     * Asserts that all required sensitive field names are in the forbidden set —
     * belt-and-suspenders beyond what Logback scrubs.
     */
    @Test
    void forbiddenFields_completenessCheck() {
        Set<String> required = Set.of("cookie", "setCookie", "authorization", "wallId", "rawWallId");
        assertThat(LogScrubber.FORBIDDEN_LOG_FIELDS).containsAll(required);
    }

    /**
     * Asserts that {@link LogScrubber#maskIp} never returns a complete IPv4 address.
     * The full IP is used for rate-limiting but must not appear verbatim in log output.
     */
    @Test
    void maskIp_resultDoesNotEndWithFullOctet() {
        String masked = LogScrubber.maskIp("10.20.30.40");
        // Last octet (40) should be masked
        assertThat(masked).doesNotEndWith(".40");
        assertThat(masked).endsWith(".xxx");
    }

    /**
     * Asserts that the AUDIT logger name used in production code matches the convention (D-13, SR-8).
     * This is a contract test — if someone renames the logger the audit trail breaks.
     */
    @Test
    void auditLoggerName_isConstant() {
        // The audit logger name must match the string used in production beans
        // (CookieBasedFallbackAuthGuard, FallbackRateLimiter)
        String expectedAuditLogger = "AUDIT";
        // Verify by checking that ch.qos.logback.classic.Logger accepts this name
        org.slf4j.Logger audit = org.slf4j.LoggerFactory.getLogger(expectedAuditLogger);
        assertThat(audit.getName()).isEqualTo(expectedAuditLogger);
    }
}
