package de.seism0saurus.glacier.webservice;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import de.seism0saurus.glacier.mastodon.SubscriptionManager;
import de.seism0saurus.glacier.util.LogScrubber;
import de.seism0saurus.glacier.webservice.messaging.messages.SubscriptionMessage;
import de.seism0saurus.glacier.webservice.messaging.messages.TerminationMessage;
import jakarta.validation.Validator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.support.MessageBuilder;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Canary unit tests for sessionId hashing hygiene in {@link SubscriptionController}
 * null-principal guard paths (SR-MED-02-01 through SR-MED-02-06).
 *
 * <p>Security controls verified:
 * <ul>
 *   <li>SR-MED-02-01: raw sessionId does not appear in LOGGER output on the subscribe null-principal path.</li>
 *   <li>SR-MED-02-02: raw sessionId does not appear in LOGGER output on the unsubscribe null-principal path.</li>
 *   <li>SR-MED-02-03: structured-log key is renamed to {@code sessionId-hash=} at both sites.</li>
 *   <li>SR-MED-02-04: AUDIT event is emitted with {@code stomp.subscribe.rejected reason=null_principal}.</li>
 *   <li>SR-MED-02-05: AUDIT event is emitted with {@code stomp.terminate.rejected reason=null_principal}.</li>
 *   <li>SR-MED-02-06: behavioral canary uses a non-UUID token; both LOGGER and AUDIT appenders are asserted.</li>
 * </ul>
 *
 * <p>Design note: STOMP session IDs are NOT UUID-format, so {@code assertNoRawUuid()} would be
 * vacuous for this leak class.  The canary {@code "raw-stomp-session-canary-abc123def456"} is a
 * non-UUID string that would appear verbatim in log output if the hashing guard were absent (SR-MED-02-06).
 *
 * <p>References: D-13/SR-8 (glacier-structured-logging-logback), OWASP A09 (Logging &amp; Monitoring Failures).
 */
public class SubscriptionControllerSessionIdScrubbingTest {

    /**
     * Non-UUID canary — STOMP session IDs are opaque, non-UUID strings.
     * This string would appear verbatim in a log line if hashing were absent.
     * SR-MED-02-06: canary shape must not match UUID format, making {@code assertNoRawUuid()} vacuous.
     */
    private static final String CANARY_SESSION_ID = "raw-stomp-session-canary-abc123def456";

    // -------------------------------------------------------------------------
    // Appenders — attached per-test; both must be asserted (SR-MED-02-06)
    // -------------------------------------------------------------------------

    private ListAppender<ILoggingEvent> controllerAppender;
    private ListAppender<ILoggingEvent> auditAppender;

    private SubscriptionController controller;

    @BeforeEach
    void setUp() {
        // Attach controller LOGGER appender
        controllerAppender = new ListAppender<>();
        controllerAppender.start();
        Logger controllerLogger = (Logger) LoggerFactory.getLogger(SubscriptionController.class);
        controllerLogger.addAppender(controllerAppender);

        // Attach AUDIT logger appender (SR-MED-02-06)
        auditAppender = new ListAppender<>();
        auditAppender.start();
        Logger auditLogger = (Logger) LoggerFactory.getLogger("AUDIT");
        auditLogger.addAppender(auditAppender);

        // Build controller with mocked collaborators
        SubscriptionManager subscriptionManager = mock(SubscriptionManager.class);
        Validator validator = mock(Validator.class);
        // Return empty violation set so validation does not short-circuit before the principal check
        // is reached (note: principal check fires BEFORE validation, but we configure this for safety)
        when(validator.validate(any())).thenReturn(Collections.emptySet());

        controller = new SubscriptionController(subscriptionManager, validator, 10);
    }

    @AfterEach
    void tearDown() {
        Logger controllerLogger = (Logger) LoggerFactory.getLogger(SubscriptionController.class);
        controllerLogger.detachAppender(controllerAppender);
        controllerAppender.stop();

        Logger auditLogger = (Logger) LoggerFactory.getLogger("AUDIT");
        auditLogger.detachAppender(auditAppender);
        auditAppender.stop();
    }

    // -------------------------------------------------------------------------
    // Helper: build a SimpMessageHeaderAccessor with the canary session ID and no principal
    // -------------------------------------------------------------------------

    /**
     * Constructs a {@link SimpMessageHeaderAccessor} carrying {@code CANARY_SESSION_ID}
     * as the {@code SESSION_ID_HEADER} but no user principal.
     *
     * <p>The {@code getUser()} call on the resulting accessor returns {@code null} because
     * no principal is set — this is the condition that triggers the null-principal guard paths.
     */
    private static SimpMessageHeaderAccessor buildNullPrincipalAccessor() {
        Map<String, Object> rawHeaders = new HashMap<>();
        rawHeaders.put(SimpMessageHeaderAccessor.SESSION_ID_HEADER, CANARY_SESSION_ID);
        rawHeaders.put(SimpMessageHeaderAccessor.MESSAGE_TYPE_HEADER, SimpMessageType.SUBSCRIBE);
        return SimpMessageHeaderAccessor.wrap(
                MessageBuilder.withPayload(new byte[0]).copyHeaders(rawHeaders).build());
    }

    // -------------------------------------------------------------------------
    // Subscribe null-principal path
    // -------------------------------------------------------------------------

    /**
     * SR-MED-02-01/03/04/06 — subscribe null-principal path hashes sessionId and emits AUDIT event.
     *
     * <p>Arrange: a STOMP accessor with {@code CANARY_SESSION_ID} and no principal.
     * Act: {@link SubscriptionController#subscribe(SimpMessageHeaderAccessor, SubscriptionMessage)}.
     * Assert (7 assertions):
     * <ol>
     *   <li>Controller LOGGER has ≥ 1 event containing {@code "sessionId-hash="} (SR-MED-02-03 key rename).</li>
     *   <li>No controller LOGGER event's formatted message contains the raw canary (SR-MED-02-01).</li>
     *   <li>No controller LOGGER event's argument array element contains the raw canary (Gap 2 / SR-MED-02-01).</li>
     *   <li>AUDIT logger has ≥ 1 event containing {@code "stomp.subscribe.rejected"} (SR-MED-02-04).</li>
     *   <li>AUDIT event contains {@code "reason=null_principal"} (SR-MED-02-04).</li>
     *   <li>AUDIT event contains {@code "sessionId-hash="} (SR-MED-02-03 on AUDIT).</li>
     *   <li>No AUDIT event contains the raw canary (SR-MED-02-01 on AUDIT).</li>
     * </ol>
     */
    @Test
    void subscribeWithNullPrincipal_hashesSessionId_andEmitsAuditEvent() {
        SimpMessageHeaderAccessor headerAccessor = buildNullPrincipalAccessor();

        SubscriptionMessage subscriptionMessage = mock(SubscriptionMessage.class);
        when(subscriptionMessage.getHashtag()).thenReturn("test");

        controller.subscribe(headerAccessor, subscriptionMessage);

        List<ILoggingEvent> controllerEvents = controllerAppender.list;
        List<ILoggingEvent> auditEvents = auditAppender.list;

        // 1. Controller LOGGER must contain the hashed key (SR-MED-02-03 — key rename)
        assertThat(controllerEvents)
                .as("SR-MED-02-03: controller LOGGER must emit 'sessionId-hash=' on null-principal subscribe path")
                .anySatisfy(e -> assertThat(e.getFormattedMessage()).contains("sessionId-hash="));

        // 2. Controller LOGGER formatted messages must NOT contain the raw canary (SR-MED-02-01)
        for (ILoggingEvent event : controllerEvents) {
            assertThat(event.getFormattedMessage())
                    .as("SR-MED-02-01: raw STOMP session ID must not appear in controller LOGGER formatted message")
                    .doesNotContain(CANARY_SESSION_ID);
        }

        // 3. Controller LOGGER argument arrays must NOT contain the raw canary (Gap 2 / SR-MED-02-01)
        for (ILoggingEvent event : controllerEvents) {
            Object[] args = event.getArgumentArray();
            if (args != null) {
                for (Object arg : args) {
                    assertThat(String.valueOf(arg))
                            .as("SR-MED-02-01 Gap2: raw STOMP session ID must not appear in controller LOGGER argument array")
                            .doesNotContain(CANARY_SESSION_ID);
                }
            }
        }

        // 4. AUDIT logger must have at least one event with 'stomp.subscribe.rejected' (SR-MED-02-04)
        assertThat(auditEvents)
                .as("SR-MED-02-04: AUDIT logger must emit 'stomp.subscribe.rejected' on null-principal subscribe path")
                .anySatisfy(e -> assertThat(e.getFormattedMessage()).contains("stomp.subscribe.rejected"));

        // 5. AUDIT event must contain 'reason=null_principal' (SR-MED-02-04)
        assertThat(auditEvents)
                .as("SR-MED-02-04: AUDIT logger must include 'reason=null_principal' in the subscribe rejection event")
                .anySatisfy(e -> assertThat(e.getFormattedMessage()).contains("reason=null_principal"));

        // 6. AUDIT event must contain 'sessionId-hash=' (SR-MED-02-03 on AUDIT)
        assertThat(auditEvents)
                .as("SR-MED-02-03: AUDIT logger must use 'sessionId-hash=' key, not raw sessionId")
                .anySatisfy(e -> assertThat(e.getFormattedMessage()).contains("sessionId-hash="));

        // Silent-deletion guards: assert that the actual hash VALUE of the canary appears in output,
        // not just the key name. If hash8() were replaced with a no-op returning "", the key assertion
        // above would still pass but these would fail (SR-T5-positive guard pattern).
        assertThat(controllerEvents)
                .as("SR-MED-02-03 silent-deletion guard: controller LOGGER must contain the hash value of canary sessionId")
                .anySatisfy(e -> assertThat(e.getFormattedMessage())
                        .contains(LogScrubber.hash8(CANARY_SESSION_ID)));
        assertThat(auditEvents)
                .as("SR-MED-02-03 silent-deletion guard: AUDIT logger must contain the hash value of canary sessionId")
                .anySatisfy(e -> assertThat(e.getFormattedMessage())
                        .contains(LogScrubber.hash8(CANARY_SESSION_ID)));

        // 7. No AUDIT event must contain the raw canary (SR-MED-02-01 on AUDIT)
        for (ILoggingEvent event : auditEvents) {
            assertThat(event.getFormattedMessage())
                    .as("SR-MED-02-01: raw STOMP session ID must not appear in AUDIT logger output")
                    .doesNotContain(CANARY_SESSION_ID);
            Object[] args = event.getArgumentArray();
            if (args != null) {
                for (Object arg : args) {
                    assertThat(String.valueOf(arg))
                            .as("SR-MED-02-01 Gap2: raw STOMP session ID must not appear in AUDIT logger argument array")
                            .doesNotContain(CANARY_SESSION_ID);
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Unsubscribe null-principal path
    // -------------------------------------------------------------------------

    /**
     * SR-MED-02-02/03/05/06 — unsubscribe null-principal path hashes sessionId and emits AUDIT event.
     *
     * <p>Arrange: a STOMP accessor with {@code CANARY_SESSION_ID} and no principal.
     * Act: {@link SubscriptionController#unsubscribe(SimpMessageHeaderAccessor, TerminationMessage)}.
     * Assert (7 assertions):
     * <ol>
     *   <li>Controller LOGGER has ≥ 1 event containing {@code "sessionId-hash="} (SR-MED-02-03 key rename).</li>
     *   <li>No controller LOGGER event's formatted message contains the raw canary (SR-MED-02-02).</li>
     *   <li>No controller LOGGER event's argument array element contains the raw canary (Gap 2 / SR-MED-02-02).</li>
     *   <li>AUDIT logger has ≥ 1 event containing {@code "stomp.terminate.rejected"} (SR-MED-02-05).</li>
     *   <li>AUDIT event contains {@code "reason=null_principal"} (SR-MED-02-05).</li>
     *   <li>AUDIT event contains {@code "sessionId-hash="} (SR-MED-02-03 on AUDIT).</li>
     *   <li>No AUDIT event contains the raw canary (SR-MED-02-02 on AUDIT).</li>
     * </ol>
     */
    @Test
    void unsubscribeWithNullPrincipal_hashesSessionId_andEmitsAuditEvent() {
        SimpMessageHeaderAccessor headerAccessor = buildNullPrincipalAccessor();

        TerminationMessage terminationMessage = mock(TerminationMessage.class);
        when(terminationMessage.getHashtag()).thenReturn("test");

        controller.unsubscribe(headerAccessor, terminationMessage);

        List<ILoggingEvent> controllerEvents = controllerAppender.list;
        List<ILoggingEvent> auditEvents = auditAppender.list;

        // 1. Controller LOGGER must contain the hashed key (SR-MED-02-03 — key rename)
        assertThat(controllerEvents)
                .as("SR-MED-02-03: controller LOGGER must emit 'sessionId-hash=' on null-principal unsubscribe path")
                .anySatisfy(e -> assertThat(e.getFormattedMessage()).contains("sessionId-hash="));

        // 2. Controller LOGGER formatted messages must NOT contain the raw canary (SR-MED-02-02)
        for (ILoggingEvent event : controllerEvents) {
            assertThat(event.getFormattedMessage())
                    .as("SR-MED-02-02: raw STOMP session ID must not appear in controller LOGGER formatted message")
                    .doesNotContain(CANARY_SESSION_ID);
        }

        // 3. Controller LOGGER argument arrays must NOT contain the raw canary (Gap 2 / SR-MED-02-02)
        for (ILoggingEvent event : controllerEvents) {
            Object[] args = event.getArgumentArray();
            if (args != null) {
                for (Object arg : args) {
                    assertThat(String.valueOf(arg))
                            .as("SR-MED-02-02 Gap2: raw STOMP session ID must not appear in controller LOGGER argument array")
                            .doesNotContain(CANARY_SESSION_ID);
                }
            }
        }

        // 4. AUDIT logger must have at least one event with 'stomp.terminate.rejected' (SR-MED-02-05)
        assertThat(auditEvents)
                .as("SR-MED-02-05: AUDIT logger must emit 'stomp.terminate.rejected' on null-principal unsubscribe path")
                .anySatisfy(e -> assertThat(e.getFormattedMessage()).contains("stomp.terminate.rejected"));

        // 5. AUDIT event must contain 'reason=null_principal' (SR-MED-02-05)
        assertThat(auditEvents)
                .as("SR-MED-02-05: AUDIT logger must include 'reason=null_principal' in the terminate rejection event")
                .anySatisfy(e -> assertThat(e.getFormattedMessage()).contains("reason=null_principal"));

        // 6. AUDIT event must contain 'sessionId-hash=' (SR-MED-02-03 on AUDIT)
        assertThat(auditEvents)
                .as("SR-MED-02-03: AUDIT logger must use 'sessionId-hash=' key in the terminate rejection event")
                .anySatisfy(e -> assertThat(e.getFormattedMessage()).contains("sessionId-hash="));

        // Silent-deletion guards: assert that the actual hash VALUE of the canary appears in output,
        // not just the key name. If hash8() were replaced with a no-op returning "", the key assertion
        // above would still pass but these would fail (SR-T5-positive guard pattern).
        assertThat(controllerEvents)
                .as("SR-MED-02-03 silent-deletion guard: controller LOGGER must contain the hash value of canary sessionId")
                .anySatisfy(e -> assertThat(e.getFormattedMessage())
                        .contains(LogScrubber.hash8(CANARY_SESSION_ID)));
        assertThat(auditEvents)
                .as("SR-MED-02-03 silent-deletion guard: AUDIT logger must contain the hash value of canary sessionId")
                .anySatisfy(e -> assertThat(e.getFormattedMessage())
                        .contains(LogScrubber.hash8(CANARY_SESSION_ID)));

        // 7. No AUDIT event must contain the raw canary (SR-MED-02-02 on AUDIT)
        for (ILoggingEvent event : auditEvents) {
            assertThat(event.getFormattedMessage())
                    .as("SR-MED-02-02: raw STOMP session ID must not appear in AUDIT logger output")
                    .doesNotContain(CANARY_SESSION_ID);
            Object[] args = event.getArgumentArray();
            if (args != null) {
                for (Object arg : args) {
                    assertThat(String.valueOf(arg))
                            .as("SR-MED-02-02 Gap2: raw STOMP session ID must not appear in AUDIT logger argument array")
                            .doesNotContain(CANARY_SESSION_ID);
                }
            }
        }
    }
}
