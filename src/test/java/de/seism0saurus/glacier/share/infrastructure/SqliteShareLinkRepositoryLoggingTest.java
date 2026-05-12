package de.seism0saurus.glacier.share.infrastructure;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import de.seism0saurus.glacier.share.domain.ShareLink;
import de.seism0saurus.glacier.share.domain.ShareLinkId;
import de.seism0saurus.glacier.util.LogScrubber;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Log hygiene test for the SQLite share-link repository layer.
 *
 * <p>Asserts that no raw share-link token or IP address appears in any log event's
 * {@link ILoggingEvent#getFormattedMessage()} or {@link ILoggingEvent#getArgumentArray()}
 * across the {@code AUDIT} logger and the infrastructure-package class loggers.
 *
 * <p>Uses the Glacier logback canary pattern (see {@code RawWallIdLogHygieneTest}):
 * a known canary token and canary IP are used; any appearance of these verbatim values
 * in log output causes the test to fail.
 *
 * <p>This test defines the logging contract that {@code SqliteShareLinkRepository}
 * (implemented in Lane 3) must honour. For Lane 2, the test verifies the shared
 * infrastructure classes (share-link ID, AUDIT logger) do not inadvertently log raw tokens.
 *
 * <p>References: SR-SQLITE-01 (raw token never in log); SR-SQLITE-04 (raw IP never in log);
 * D-13; SR-8; glacier-structured-logging-logback skill; OWASP A09:2021.
 */
class SqliteShareLinkRepositoryLoggingTest {

    /**
     * Canary token — a valid-format base64url token that must never appear verbatim in logs.
     * 44 chars = 264 bits — well above the 43-char minimum.
     */
    private static final String CANARY_TOKEN = "CANARY_TOKEN_MUST_NOT_APPEAR_IN_LOGS_123456A";

    /**
     * Canary IP — a full IPv4 address that must never appear verbatim in logs.
     */
    private static final String CANARY_IP = "203.0.113.42";

    private ListAppender<ILoggingEvent> auditAppender;
    private Logger auditLogger;

    @BeforeEach
    void attachAuditAppender() {
        auditLogger = (Logger) LoggerFactory.getLogger("AUDIT");
        auditAppender = new ListAppender<>();
        auditAppender.start();
        auditLogger.addAppender(auditAppender);
    }

    @AfterEach
    void detachAuditAppender() {
        if (auditLogger != null && auditAppender != null) {
            auditLogger.detachAppender(auditAppender);
            auditAppender.stop();
        }
    }

    // -------------------------------------------------------------------------
    // ShareLinkId — log hygiene (no raw token in hash8() output)
    // -------------------------------------------------------------------------

    @Test
    void shareLinkId_hash8_doesNotContainRawToken() {
        ShareLinkId id = ShareLinkId.fromUrlPath(CANARY_TOKEN);
        String hash8 = id.hash8();

        // hash8 is a SHA-256 prefix — must not be the raw token
        assertThat(hash8)
                .as("ShareLinkId.hash8() must not contain the raw token")
                .doesNotContain(CANARY_TOKEN)
                .hasSize(8);
    }

    // -------------------------------------------------------------------------
    // AUDIT logger canary: correct logging pattern does not emit raw tokens
    // -------------------------------------------------------------------------

    @Test
    void correctAuditLogPattern_doesNotEmitRawToken() {
        // Simulate the correct AUDIT log pattern used by SqliteShareLinkRepository
        // (SR-SQLITE-01; SR-SQLITE-06)
        ShareLinkId id = ShareLinkId.fromUrlPath(CANARY_TOKEN);
        String hash8 = id.hash8();

        auditLogger.info("share-link-repository=sqlite path-hash8={}", hash8);
        auditLogger.info("share.link.save id-hash8={}", hash8);

        assertNoRawTokenOrIpInLogs(auditAppender.list, "AUDIT logger correct pattern");
    }

    @Test
    void correctAuditLogPattern_doesNotEmitRawIp() {
        // Simulate correct IP logging — must use maskIp, not raw IP (SR-SQLITE-04)
        String maskedIp = LogScrubber.maskIp(CANARY_IP);
        auditLogger.info("share.link.save ip-mask={}", maskedIp);

        assertNoRawTokenOrIpInLogs(auditAppender.list, "AUDIT logger correct IP pattern");
    }

    // -------------------------------------------------------------------------
    // LogScrubber helpers verify canary scrubbing contract
    // -------------------------------------------------------------------------

    @Test
    void logScrubber_maskIp_doesNotReturnRawIp() {
        String masked = LogScrubber.maskIp(CANARY_IP);

        assertThat(masked)
                .as("LogScrubber.maskIp() must not return the raw IP (SR-SQLITE-04; D-13)")
                .doesNotContain(CANARY_IP);
    }

    @Test
    void logScrubber_hash8_doesNotReturnRawToken() {
        String hashed = LogScrubber.hash8(CANARY_TOKEN);

        assertThat(hashed)
                .as("LogScrubber.hash8() must not return the raw token value (SR-SQLITE-01)")
                .doesNotContain(CANARY_TOKEN)
                .hasSize(8);
    }

    @Test
    void logScrubber_forErrorMessage_doesNotReturnRawToken() {
        String scrubbed = LogScrubber.forErrorMessage(CANARY_TOKEN);

        assertThat(scrubbed)
                .as("LogScrubber.forErrorMessage() must not expose raw token (SR-SQLITE-03)")
                .doesNotContain(CANARY_TOKEN);
    }

    // -------------------------------------------------------------------------
    // ShareLink domain object does not log raw token during construction
    // -------------------------------------------------------------------------

    @Test
    void shareLinkCreate_doesNotEmitRawTokenToAuditLogger() {
        ShareLink link = ShareLink.create(
                ShareLinkId.fromUrlPath(CANARY_TOKEN),
                "sharer-wall-id-fixture-value",
                CANARY_IP,
                Instant.now(),
                Duration.ofDays(7));

        // Touch the link to generate any lazy log output
        link.id().hash8(); // safe — returns hash
        link.status(Instant.now());

        // The AUDIT logger must not have been triggered with raw token or IP
        assertNoRawTokenOrIpInLogs(auditAppender.list, "ShareLink.create()");
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Asserts that no log event in {@code events} contains the canary token or IP
     * verbatim in either the formatted message or the argument array.
     *
     * @param events  the captured log events to inspect
     * @param context a description of what was being exercised (for assertion messages)
     */
    private static void assertNoRawTokenOrIpInLogs(
            final List<ILoggingEvent> events, final String context) {

        for (ILoggingEvent event : events) {
            // Check formatted message
            String msg = event.getFormattedMessage();
            assertThat(msg)
                    .as("Log message from [%s] must not contain raw share-link token (SR-SQLITE-01)\nLine: %s",
                            context, msg)
                    .doesNotContain(CANARY_TOKEN);

            assertThat(msg)
                    .as("Log message from [%s] must not contain raw creator IP (SR-SQLITE-04)\nLine: %s",
                            context, msg)
                    .doesNotContain(CANARY_IP);

            // Check argument array (SLF4J deferred interpolation)
            Object[] args = event.getArgumentArray();
            if (args != null) {
                String argsStr = Arrays.toString(args);
                assertThat(argsStr)
                        .as("Log argumentArray from [%s] must not contain raw token (SR-SQLITE-01)\nArgs: %s",
                                context, argsStr)
                        .doesNotContain(CANARY_TOKEN);

                assertThat(argsStr)
                        .as("Log argumentArray from [%s] must not contain raw IP (SR-SQLITE-04)\nArgs: %s",
                                context, argsStr)
                        .doesNotContain(CANARY_IP);
            }
        }
    }
}
