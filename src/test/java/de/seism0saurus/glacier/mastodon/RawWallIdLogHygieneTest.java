package de.seism0saurus.glacier.mastodon;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.TextNode;
import de.seism0saurus.glacier.share.application.SafeUrlValidator;
import de.seism0saurus.glacier.share.application.ShareViewStompRelay;
import de.seism0saurus.glacier.util.LogScrubber;
import de.seism0saurus.glacier.webservice.cache.MessageCache;
import de.seism0saurus.glacier.webservice.messaging.SubscriptionListener;
import de.seism0saurus.glacier.webservice.messaging.messages.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageHeaders;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.socket.messaging.SessionConnectedEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;
import social.bigbone.api.entity.streaming.MastodonApiEvent;
import social.bigbone.api.entity.streaming.ParsedStreamEvent;
import social.bigbone.api.entity.streaming.TechnicalEvent;
import social.bigbone.api.entity.streaming.WebSocketEvent;

import java.net.URI;
import java.security.Principal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Canary harness: D-13 / SR-8 Raw Logging Cleanup (F-6).
 *
 * <p>Lane B of the F-6 pipeline. This class verifies that:
 * <ul>
 *   <li>No raw wallId (UUID), sessionId, URL, or hashtag leaks through any log line.</li>
 *   <li>event.toString() is never emitted at INFO or above (T2 — ADR-F6-02).</li>
 *   <li>GenericMessage event names pass through the safeEventName allowlist correctly (T3a/T3b).</li>
 *   <li>Raw toot URLs are not present in any log line (T3c — ADR-F6-03).</li>
 *   <li>Stream list is logged as a size, not as the full raw list (T3d — ADR-F6-06).</li>
 * </ul>
 *
 * <p>Security controls: D-13, SR-8, CWE-117 (log injection), OWASP A09 (Logging &amp; Monitoring Failures).
 *
 * <p>All assertions check BOTH {@code getFormattedMessage()} and {@code getArgumentArray()} so
 * that structured-logging frameworks that lazy-render arguments do not escape the canary check
 * (Gap 2 — SR-F6-10).
 */
class RawWallIdLogHygieneTest {

    // -------------------------------------------------------------------------
    // Canary constants (SR-F6-08, SR-F6-10)
    // -------------------------------------------------------------------------

    /** Known UUID principal — appears verbatim in no log line after fixes. */
    private static final String CANARY_UUID = "550e8400-e29b-41d4-a716-446655440000";

    /**
     * Canary URL — must never appear in any log line; only its host hash may appear
     * (SR-F6-03, ADR-F6-03).
     */
    private static final String CANARY_URL = "https://canary.example.com/suspicious/path?q=evil";

    /**
     * Canary hashtag — must never appear verbatim in any log line; only its length may appear
     * (SR-F6-01, D-13).
     */
    private static final String CANARY_HASHTAG = "canaryHashtag";

    /**
     * Canary session identifier — must not appear verbatim in any log line after fixes.
     * (SR-F6-08, ADR-F6-01).
     */
    private static final String CANARY_SESSION = "canary-session-uuid-12345";

    /**
     * Permissive {@link SafeUrlValidator} — always passes URLs through for non-SSRF canary tests.
     */
    private static final SafeUrlValidator PERMISSIVE_VALIDATOR =
            rawUrl -> Optional.of(URI.create(rawUrl));

    // -------------------------------------------------------------------------
    // Appenders — attached per-test to the relevant Logback loggers
    // -------------------------------------------------------------------------

    private ListAppender<ILoggingEvent> stompCallbackAppender;
    private ListAppender<ILoggingEvent> subscriptionListenerAppender;
    private ListAppender<ILoggingEvent> auditAppender;

    @BeforeEach
    void attachAppenders() {
        stompCallbackAppender = attachAppender(StompCallback.class);
        subscriptionListenerAppender = attachAppender(SubscriptionListener.class);

        // Gap 3: attach AUDIT logger so AUDIT events are captured in the same list
        // (SR-F6-08 — audit events must also not expose raw values)
        auditAppender = new ListAppender<>();
        auditAppender.start();
        Logger auditLogger = (Logger) LoggerFactory.getLogger("AUDIT");
        auditLogger.addAppender(auditAppender);
    }

    @AfterEach
    void detachAppenders() {
        detach(StompCallback.class, stompCallbackAppender);
        detach(SubscriptionListener.class, subscriptionListenerAppender);

        Logger auditLogger = (Logger) LoggerFactory.getLogger("AUDIT");
        auditLogger.detachAppender(auditAppender);
        auditAppender.stop();
    }

    // -------------------------------------------------------------------------
    // T1 — StompCallback constructor must not log raw UUID principal
    // -------------------------------------------------------------------------

    /**
     * T1 (SR-F6-08, ADR-F6-01): constructing StompCallback must log principal-hash=, not the raw UUID.
     *
     * <p>Arrange: construct a StompCallback with CANARY_UUID as the principal and CANARY_HASHTAG as the hashtag.
     * Act: construction itself triggers the INFO log.
     * Assert: no log line from StompCallback exposes CANARY_UUID (UUID canary check) or CANARY_HASHTAG
     *         (raw hashtag check); structured fields principal-hash= and hashtag-len= are present.
     */
    @Test
    void T1_stompCallback_constructor_doesNotLogRawPrincipalOrHashtag() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        SubscriptionManager subscriptionManager = mock(SubscriptionManager.class);
        MessageCache messageCache = mock(MessageCache.class);
        ShareViewStompRelay shareViewStompRelay = mock(ShareViewStompRelay.class);

        // Act — constructor triggers INFO log
        new StompCallback(subscriptionManager, messageCache, shareViewStompRelay, restTemplate,
                PERMISSIVE_VALIDATOR, CANARY_UUID, CANARY_HASHTAG, MastodonShortHandle.parse("glacier@example.com"), "glacier.example.com");

        List<ILoggingEvent> events = stompCallbackAppender.list;

        // Raw values must not appear in any log line
        assertNoRawUuid(events, "T1 StompCallback constructor — raw UUID");
        assertNoRawHashtag(events, CANARY_HASHTAG);

        // Structured fields must be present
        assertThat(events)
                .anySatisfy(e -> {
                    String msg = e.getFormattedMessage();
                    assertThat(msg).contains("principal-hash=");
                    assertThat(msg).contains("hashtag-len=" + CANARY_HASHTAG.length());
                });
    }

    // -------------------------------------------------------------------------
    // T2 — event.toString() must not appear at INFO or above (ADR-F6-02, SR-F6-02)
    // -------------------------------------------------------------------------

    /**
     * T2 (ADR-F6-02, SR-F6-02): {@code event.toString()} must be demoted to DEBUG and NEVER appear
     * at INFO or above.
     *
     * <p>The old code did {@code LOGGER.info("... event={}", event)} which would embed raw toot URLs,
     * account handles, and status content. Fix #2 demoted this to
     * {@code LOGGER.debug("stream.event type={}", event.getClass().getSimpleName())}.
     *
     * <p>Arrange: construct StompCallback with a canary principal.
     * Act: send any WebSocketEvent (TechnicalEvent.Closed).
     * Assert:
     * <ul>
     *   <li>No INFO-level message contains "MastodonApiEvent" or "StreamEvent" — these come from
     *       {@code event.toString()} dumps.</li>
     *   <li>No INFO-level message contains raw class names as produced by event.toString().</li>
     * </ul>
     */
    @Test
    void T2_stompCallback_onEvent_noEventDumpAtInfoOrAbove() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        SubscriptionManager subscriptionManager = mock(SubscriptionManager.class);
        MessageCache messageCache = mock(MessageCache.class);
        ShareViewStompRelay shareViewStompRelay = mock(ShareViewStompRelay.class);

        StompCallback callback = new StompCallback(subscriptionManager, messageCache, shareViewStompRelay, restTemplate,
                PERMISSIVE_VALIDATOR, CANARY_UUID, CANARY_HASHTAG, MastodonShortHandle.parse("glacier@example.com"), "glacier.example.com");
        stompCallbackAppender.list.clear();

        TechnicalEvent.Closed closed = mock(TechnicalEvent.Closed.class);
        callback.onEvent(closed);

        // Gap 4: assert log levels — no INFO-or-above message must be an event.toString() dump
        List<ILoggingEvent> infoAndAbove = stompCallbackAppender.list.stream()
                .filter(e -> e.getLevel().isGreaterOrEqual(Level.INFO))
                .toList();

        // SR-F6-02: the raw StreamEvent dump must not appear at INFO or above
        assertThat(infoAndAbove)
                .noneSatisfy(e -> assertThat(e.getFormattedMessage())
                        .contains("MastodonApiEvent"));
        assertThat(infoAndAbove)
                .noneSatisfy(e -> assertThat(e.getFormattedMessage())
                        .contains("StreamEvent"));
        // The event class simple name may appear in DEBUG — but must not appear at INFO as part of toString() output
        assertThat(infoAndAbove)
                .noneSatisfy(e -> assertThat(e.getFormattedMessage())
                        .matches(".*toString.*"));
    }

    // -------------------------------------------------------------------------
    // T3a — known Mastodon event name passes through verbatim (ADR-F6-05, SR-F6-05)
    // -------------------------------------------------------------------------

    /**
     * T3a (ADR-F6-05, SR-F6-05): a known allowlisted event name is rendered verbatim in the log.
     *
     * <p>Arrange: build a GenericMessage with event="notification" (allowlisted) and a stream "user"
     *             so neither update/delete branches fire — the unhandled branch emits the structured log.
     * Act: onEvent processes the message.
     * Assert: log contains "event=notification" (verbatim pass-through) and "streams-size=1";
     *         the raw canary URL from the payload is NOT in any log line.
     */
    @Test
    void T3a_knownMastodonEvent_streamGenericUnhandled_eventNamePassesThrough() throws Exception {
        RestTemplate restTemplate = mock(RestTemplate.class);
        SubscriptionManager subscriptionManager = mock(SubscriptionManager.class);
        MessageCache messageCache = mock(MessageCache.class);
        ShareViewStompRelay shareViewStompRelay = mock(ShareViewStompRelay.class);

        StompCallback callback = new StompCallback(subscriptionManager, messageCache, shareViewStompRelay, restTemplate,
                PERMISSIVE_VALIDATOR, CANARY_UUID, CANARY_HASHTAG, MastodonShortHandle.parse("glacier@example.com"), "glacier.example.com");
        stompCallbackAppender.list.clear();

        MastodonApiEvent.GenericMessage mockEvent = mock(MastodonApiEvent.GenericMessage.class);
        ObjectMapper mapper = new ObjectMapper();
        JsonNode payloadNode = TextNode.valueOf("{\"url\":\"" + CANARY_URL + "\"}");
        GenericMessageContent content = GenericMessageContent.builder()
                .event("notification")   // known allowlisted event
                .stream(List.of("user")) // not "hashtag" -> unhandled branch
                .payload(payloadNode)
                .build();
        when(mockEvent.getText()).thenReturn(mapper.writeValueAsString(content));

        callback.onEvent(mockEvent);

        List<ILoggingEvent> events = stompCallbackAppender.list;

        // T3a: known event name passes verbatim through the allowlist
        assertThat(events)
                .anySatisfy(e -> assertThat(e.getFormattedMessage()).contains("event=notification"));

        // Structured field streams-size is present
        assertThat(events)
                .anySatisfy(e -> assertThat(e.getFormattedMessage()).contains("streams-size=1"));

        // SR-F6-03: raw canary URL must NOT appear in any log line
        assertNoRawUrl(events, CANARY_URL);
    }

    // -------------------------------------------------------------------------
    // T3b — injected/unknown event name is bounded by safeEventName (CWE-117, SR-F6-05)
    // -------------------------------------------------------------------------

    /**
     * T3b (ADR-F6-05, SR-F6-05, CWE-117): an unknown/injected event name must be bounded to
     * {@code unknown(len=N)} — raw attacker-controlled bytes must not reach the log encoder.
     *
     * <p>Arrange: build a GenericMessage with a CRLF-injected event name.
     * Act: onEvent processes the message.
     * Assert:
     * <ul>
     *   <li>Log contains "unknown(len=...)" — the safeEventName guard rendered the fallback.</li>
     *   <li>Log does NOT contain the injection string or any CR/LF characters in the log output.</li>
     *   <li>Log does NOT contain the raw payload canary URL.</li>
     * </ul>
     */
    @Test
    void T3b_injectedEventName_streamGenericUnhandled_isBounded() throws Exception {
        RestTemplate restTemplate = mock(RestTemplate.class);
        SubscriptionManager subscriptionManager = mock(SubscriptionManager.class);
        MessageCache messageCache = mock(MessageCache.class);
        ShareViewStompRelay shareViewStompRelay = mock(ShareViewStompRelay.class);

        StompCallback callback = new StompCallback(subscriptionManager, messageCache, shareViewStompRelay, restTemplate,
                PERMISSIVE_VALIDATOR, CANARY_UUID, CANARY_HASHTAG, MastodonShortHandle.parse("glacier@example.com"), "glacier.example.com");
        stompCallbackAppender.list.clear();

        // CWE-117 log injection payload: CRLF attempt embedded in event name
        String injectedEventName = "update\r\nFAKE_LOG_ENTRY: injected";
        MastodonApiEvent.GenericMessage mockEvent = mock(MastodonApiEvent.GenericMessage.class);
        ObjectMapper mapper = new ObjectMapper();
        JsonNode payloadNode = TextNode.valueOf("{\"url\":\"" + CANARY_URL + "\"}");
        GenericMessageContent content = GenericMessageContent.builder()
                .event(injectedEventName)     // NOT in allowlist
                .stream(List.of("user"))      // not "hashtag" -> unhandled branch
                .payload(payloadNode)
                .build();
        when(mockEvent.getText()).thenReturn(mapper.writeValueAsString(content));

        callback.onEvent(mockEvent);

        List<ILoggingEvent> events = stompCallbackAppender.list;

        // T3b: safeEventName renders the bounded fallback — the unhandled warn line must be present
        // with "unknown(len=N)" replacing the raw injected event name
        assertThat(events)
                .anySatisfy(e -> assertThat(e.getFormattedMessage()).contains("unknown(len="));

        // CWE-117: raw injection characters must not appear in any formatted message
        for (ILoggingEvent e : events) {
            String msg = e.getFormattedMessage();
            assertThat(msg).doesNotContain("\r");
            assertThat(msg).doesNotContain("\n");
            assertThat(msg).doesNotContain("FAKE_LOG_ENTRY");
        }

        // Also check argument arrays (Gap 2)
        for (ILoggingEvent e : events) {
            Object[] args = e.getArgumentArray();
            if (args != null) {
                for (Object arg : args) {
                    String argStr = String.valueOf(arg);
                    assertThat(argStr).doesNotContain("FAKE_LOG_ENTRY");
                    assertThat(argStr).doesNotContain("\r");
                    assertThat(argStr).doesNotContain("\n");
                }
            }
        }

        // SR-F6-03: raw canary URL must not appear in any log line
        assertNoRawUrl(events, CANARY_URL);
    }

    // -------------------------------------------------------------------------
    // T3c — raw toot URL is never logged (ADR-F6-03, SR-F6-03)
    // -------------------------------------------------------------------------

    /**
     * T3c (ADR-F6-03, SR-F6-03): a raw toot URL must never appear in any log line.
     *
     * <p>Arrange: build a GenericMessage with event="delete" (handled branch) whose
     *             content carries the canary URL in the payload.  Log lines emitted
     *             on the success path must not include the URL.
     * Act: onEvent processes the delete message.
     * Assert: {@code assertNoRawUrl(events, CANARY_URL)} passes for all captured log events.
     */
    @Test
    void T3c_genericMessage_rawUrlNotLogged() throws Exception {
        RestTemplate restTemplate = mock(RestTemplate.class);
        SubscriptionManager subscriptionManager = mock(SubscriptionManager.class);
        MessageCache messageCache = mock(MessageCache.class);
        ShareViewStompRelay shareViewStompRelay = mock(ShareViewStompRelay.class);

        StompCallback callback = new StompCallback(subscriptionManager, messageCache, shareViewStompRelay, restTemplate,
                PERMISSIVE_VALIDATOR, CANARY_UUID, CANARY_HASHTAG, MastodonShortHandle.parse("glacier@example.com"), "glacier.example.com");
        stompCallbackAppender.list.clear();

        // Build a GenericMessage delete event whose serialized payload looks like a toot ID but whose
        // GenericMessageContent stream contains "hashtag" so the delete branch fires.
        // The raw CANARY_URL is embedded inside the serialized payload text.
        MastodonApiEvent.GenericMessage mockEvent = mock(MastodonApiEvent.GenericMessage.class);
        ObjectMapper mapper = new ObjectMapper();
        // The delete event payload for GenericMessage is a plain toot ID (string)
        // but we construct the full event JSON manually to embed the canary URL inside the stream field
        // so the URL ends up in the deserialized GenericMessageContent text — we use the unhandled branch
        // to ensure the warn log is emitted with the raw content stripped out.
        String payloadText = mapper.writeValueAsString("toot-id-12345");
        JsonNode payloadNode = TextNode.valueOf(payloadText);
        GenericMessageContent contentWithCanaryPayload = GenericMessageContent.builder()
                .event("delete")
                .stream(List.of("hashtag"))
                .payload(payloadNode)
                .build();
        when(mockEvent.getText()).thenReturn(mapper.writeValueAsString(contentWithCanaryPayload));

        callback.onEvent(mockEvent);

        // Also exercise the unhandled branch with the canary URL inside the payload JSON
        MastodonApiEvent.GenericMessage mockEvent2 = mock(MastodonApiEvent.GenericMessage.class);
        GenericMessageContent unhandledWithUrl = GenericMessageContent.builder()
                .event("filters_changed")   // known but goes to unhandled branch (not update/delete/hashtag)
                .stream(List.of("user"))
                .payload(TextNode.valueOf("{\"url\":\"" + CANARY_URL + "\"}"))
                .build();
        when(mockEvent2.getText()).thenReturn(mapper.writeValueAsString(unhandledWithUrl));
        callback.onEvent(mockEvent2);

        List<ILoggingEvent> events = stompCallbackAppender.list;

        // SR-F6-03: raw URL must never appear in any log line (message OR argument array)
        assertNoRawUrl(events, CANARY_URL);
    }

    // -------------------------------------------------------------------------
    // T3d — stream list is logged as size, not raw list (ADR-F6-06, SR-F6-06)
    // -------------------------------------------------------------------------

    /**
     * T3d (ADR-F6-06, SR-F6-06): the Mastodon stream list must be logged as a size integer,
     * never as the raw list or its string representation.
     *
     * <p>The raw stream list content may include user-controlled stream names.  Emitting it in full
     * would allow a hostile server to inject content via CWE-117.
     *
     * <p>Arrange: build a GenericMessage with event="announcement" (known, unhandled branch) and a
     *             stream list containing a recognisable canary stream name.
     * Act: onEvent processes the message.
     * Assert:
     * <ul>
     *   <li>Log contains "streams-size=2".</li>
     *   <li>Log does NOT contain "canary_stream" or the raw list representation "["canary_stream"...]".</li>
     * </ul>
     */
    @Test
    void T3d_genericMessage_streamSizeLoggedNotRawList() throws Exception {
        RestTemplate restTemplate = mock(RestTemplate.class);
        SubscriptionManager subscriptionManager = mock(SubscriptionManager.class);
        MessageCache messageCache = mock(MessageCache.class);
        ShareViewStompRelay shareViewStompRelay = mock(ShareViewStompRelay.class);

        StompCallback callback = new StompCallback(subscriptionManager, messageCache, shareViewStompRelay, restTemplate,
                PERMISSIVE_VALIDATOR, CANARY_UUID, CANARY_HASHTAG, MastodonShortHandle.parse("glacier@example.com"), "glacier.example.com");
        stompCallbackAppender.list.clear();

        // Use a canary stream name that should never appear in the log verbatim
        String canaryStream = "canary_stream_xyz";
        MastodonApiEvent.GenericMessage mockEvent = mock(MastodonApiEvent.GenericMessage.class);
        ObjectMapper mapper = new ObjectMapper();
        GenericMessageContent content = GenericMessageContent.builder()
                .event("announcement")          // known event, unhandled branch
                .stream(List.of(canaryStream, "user")) // 2-element list with canary
                .payload(TextNode.valueOf("{}"))
                .build();
        when(mockEvent.getText()).thenReturn(mapper.writeValueAsString(content));

        callback.onEvent(mockEvent);

        List<ILoggingEvent> events = stompCallbackAppender.list;

        // T3d: streams-size=2 must appear (size only, not raw list)
        assertThat(events)
                .anySatisfy(e -> assertThat(e.getFormattedMessage()).contains("streams-size=2"));

        // SR-F6-06: raw stream name must NOT appear in any log line or argument array
        for (ILoggingEvent e : events) {
            assertThat(e.getFormattedMessage())
                    .as("Raw canary stream name must not appear in log message")
                    .doesNotContain(canaryStream);
            Object[] args = e.getArgumentArray();
            if (args != null) {
                for (Object arg : args) {
                    assertThat(String.valueOf(arg))
                            .as("Raw canary stream name must not appear in log argument")
                            .doesNotContain(canaryStream);
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // T4 — SubscriptionListener.onConnectedEvent must not log raw UUID or sessionId
    // -------------------------------------------------------------------------

    /**
     * T4 (ADR-F6-01, SR-F6-08): SubscriptionListener.onConnectedEvent must log hashed values only.
     *
     * <p>Fix #7a/b: both the sessionId and the principal (username) are hashed before logging.
     * The raw values must not appear in any log line.
     */
    @Test
    void T4_subscriptionListener_onConnectedEvent_doesNotLogRawPrincipalOrSession() {
        SubscriptionManager subscriptionManager = mock(SubscriptionManager.class);
        MessageCache messageCache = mock(MessageCache.class);
        SubscriptionListener listener = new SubscriptionListener(subscriptionManager, messageCache, 50_000L, 300_000L, 2.0);

        SessionConnectedEvent event = mock(SessionConnectedEvent.class);
        MessageHeaders headers = new MessageHeaders(null);
        //noinspection unchecked
        Message<byte[]> message = mock(Message.class);
        when(message.getHeaders()).thenReturn(headers);
        when(event.getMessage()).thenReturn(message);

        Principal principal = () -> CANARY_UUID;
        when(event.getUser()).thenReturn(principal);

        listener.onConnectedEvent(event);

        List<ILoggingEvent> events = subscriptionListenerAppender.list;

        // SR-F6-08: no raw UUID in any log line or argument array
        assertNoRawUuid(events, "T4 SubscriptionListener.onConnectedEvent");
    }

    // -------------------------------------------------------------------------
    // T5 — SubscriptionListener.onConnectedEvent with no principal must not log raw sessionId
    // -------------------------------------------------------------------------

    /**
     * T5 (ADR-F6-01, SR-F6-08): when onConnectedEvent fires without a user principal,
     * the warn path must still not log a raw sessionId.
     *
     * <p>Fix (ADR-T5b-01): the header map now injects CANARY_SESSION via
     * {@code SimpMessageHeaderAccessor.SESSION_ID_HEADER} so the production code sees a real
     * value.  The previous {@code new MessageHeaders(null)} caused {@code getSessionId()} to
     * return {@code null} — the canary was never at risk and the negative assertion passed
     * vacuously.  The positive assertion below guards against silent deletion (SR-T5-positive).
     */
    @Test
    void T5_subscriptionListener_onConnectedEvent_noPrincipal_doesNotLogRawSession() {
        SubscriptionManager subscriptionManager = mock(SubscriptionManager.class);
        MessageCache messageCache = mock(MessageCache.class);
        SubscriptionListener listener = new SubscriptionListener(subscriptionManager, messageCache, 50_000L, 300_000L, 2.0);

        SessionConnectedEvent event = mock(SessionConnectedEvent.class);
        // ADR-T5b-01: inject CANARY_SESSION so getSessionId() returns it — canary is now at real risk
        MessageHeaders headers = new MessageHeaders(Map.of(SimpMessageHeaderAccessor.SESSION_ID_HEADER, CANARY_SESSION));
        //noinspection unchecked
        Message<byte[]> message = mock(Message.class);
        when(message.getHeaders()).thenReturn(headers);
        when(event.getMessage()).thenReturn(message);
        when(event.getUser()).thenReturn(null);

        listener.onConnectedEvent(event);

        List<ILoggingEvent> events = subscriptionListenerAppender.list;

        // SR-F6-08: sessionId canary must not appear in any log line or argument array
        assertNoRawSessionId(events, CANARY_SESSION);
        // Confirm a WARN was emitted (early-return path)
        assertThat(events).anySatisfy(e ->
                assertThat(e.getLevel()).isEqualTo(Level.WARN));
        // SR-T5-positive: session-hash= must appear with the hashed canary value — guards silent deletion
        assertThat(events).anySatisfy(e ->
                assertThat(e.getFormattedMessage()).contains(LogScrubber.hash8(CANARY_SESSION)));
    }

    // -------------------------------------------------------------------------
    // T5b — SubscriptionListener.onDisconnectEvent with no principal must not log raw sessionId
    // -------------------------------------------------------------------------

    /**
     * T5b (ADR-T5b-01..05, SR-T5b-01..08): when {@code onDisconnectEvent} fires without a user
     * principal, the warn path at line 177–179 of {@code SubscriptionListener.java} must hash the
     * sessionId and must not expose the raw canary value.
     *
     * <p>Fix site 7c: the production code logs
     * {@code "Client with session-hash={} disconnected but has no user associated with it"}
     * using {@code LogScrubber.hash8(headerAccessor.getSessionId())} — the test verifies all
     * eight security requirements for this path.
     *
     * <p>Arrange: build a {@code SessionDisconnectEvent} whose message headers carry
     *   {@code CANARY_SESSION} under {@code SimpMessageHeaderAccessor.SESSION_ID_HEADER}
     *   (ADR-T5b-01).  {@code event.getUser()} returns {@code null} to trigger the early-return warn.
     * Act: {@code subscriptionListener.onDisconnectEvent(event)}.
     * Assert (all 8 SRs):
     * <ul>
     *   <li>SR-T5b-01: {@code getFormattedMessage()} does NOT contain {@code CANARY_SESSION}.</li>
     *   <li>SR-T5b-02: {@code getArgumentArray()} elements do not contain {@code CANARY_SESSION}
     *       (defence-in-depth via {@link #assertNoRawSessionId}).</li>
     *   <li>SR-T5b-03: {@code getFormattedMessage()} DOES contain {@code "session-hash="} AND
     *       {@code LogScrubber.hash8(CANARY_SESSION)} (silent-deletion guard).</li>
     *   <li>SR-T5b-04: {@code getLevel()} == {@code Level.WARN}.</li>
     *   <li>SR-T5b-05: {@code getThrowableProxy()} is {@code null} — no exception on this path.</li>
     *   <li>SR-T5b-06: {@code getLoggerName()} == {@code SubscriptionListener.class.getName()}.</li>
     *   <li>SR-T5b-07: {@code getMessage()} (pre-format pattern) ==
     *       {@code "Client with session-hash={} disconnected but has no user associated with it"}.</li>
     *   <li>SR-T5b-08: exactly 1 event on {@code subscriptionListenerAppender}; AUDIT appender
     *       has 0 canary events.</li>
     * </ul>
     */
    @Test
    void T5b_subscriptionListener_onDisconnectEvent_noPrincipal_doesNotLogRawSession() {
        SubscriptionManager subscriptionManager = mock(SubscriptionManager.class);
        MessageCache messageCache = mock(MessageCache.class);
        SubscriptionListener listener = new SubscriptionListener(subscriptionManager, messageCache, 50_000L, 300_000L, 2.0);

        SessionDisconnectEvent event = mock(SessionDisconnectEvent.class);
        // ADR-T5b-01: inject CANARY_SESSION so the production code's getSessionId() sees the real value
        MessageHeaders headers = new MessageHeaders(Map.of(SimpMessageHeaderAccessor.SESSION_ID_HEADER, CANARY_SESSION));
        //noinspection unchecked
        Message<byte[]> message = mock(Message.class);
        when(message.getHeaders()).thenReturn(headers);
        when(event.getMessage()).thenReturn(message);
        // Trigger the early-return warn branch (lines 175–179 of SubscriptionListener.java)
        when(event.getUser()).thenReturn(null);

        listener.onDisconnectEvent(event);

        List<ILoggingEvent> captured = subscriptionListenerAppender.list;

        // SR-T5b-08 (part 1): exactly one event emitted on the subscription-listener appender
        assertThat(captured)
                .as("SR-T5b-08: exactly one log event expected from the no-user warn path")
                .hasSize(1);

        ILoggingEvent entry = captured.get(0);

        // SR-T5b-01 + SR-T5b-02: raw session canary must not appear in formatted message or argument array
        assertNoRawSessionId(captured, CANARY_SESSION);

        // SR-T5b-03: hashed session value AND label must appear — guards silent deletion
        assertThat(entry.getFormattedMessage())
                .as("SR-T5b-03: formatted message must contain 'session-hash=' label")
                .contains("session-hash=");
        assertThat(entry.getFormattedMessage())
                .as("SR-T5b-03: formatted message must contain the hash of CANARY_SESSION")
                .contains(LogScrubber.hash8(CANARY_SESSION));

        // SR-T5b-04: the warn path must emit at WARN level
        assertThat(entry.getLevel())
                .as("SR-T5b-04: log level must be WARN")
                .isEqualTo(Level.WARN);

        // SR-T5b-05: no throwable — this is a control-flow warn, not an exception path
        assertThat(entry.getThrowableProxy())
                .as("SR-T5b-05: no throwable expected on the no-user warn path")
                .isNull();

        // SR-T5b-06: event must originate from SubscriptionListener, not a helper or delegated class
        assertThat(entry.getLoggerName())
                .as("SR-T5b-06: logger must be SubscriptionListener")
                .isEqualTo(SubscriptionListener.class.getName());

        // SR-T5b-07: the pre-format pattern (before {} substitution) must match exactly
        assertThat(entry.getMessage())
                .as("SR-T5b-07: pre-format message pattern must be the exact warn template")
                .isEqualTo("Client with session-hash={} disconnected but has no user associated with it");

        // SR-T5b-08 (part 2): AUDIT appender must have no events containing the raw canary
        List<ILoggingEvent> auditEvents = auditAppender.list;
        assertThat(auditEvents)
                .as("SR-T5b-08: AUDIT appender must not contain any canary events from the no-user disconnect path")
                .noneSatisfy(e -> assertThat(e.getFormattedMessage()).contains(CANARY_SESSION));
    }

    // -------------------------------------------------------------------------
    // T6 — SubscriptionListener.onDisconnectEvent outer method must not log raw UUID
    // -------------------------------------------------------------------------

    /**
     * T6 (ADR-F6-01, SR-F6-08): SubscriptionListener.onDisconnectEvent outer method log lines
     * (the synchronous INFO at disconnect) must use hashed values only.
     *
     * <p>Fix #7c/d: both sessionId and principal are hashed before logging in the synchronous
     * outer method body. This test scopes verification to those lines only — the timer-lambda
     * log lines (emitted on a virtual thread after the timeout) are captured ONLY before the timer
     * fires so the test remains deterministic.
     *
     * <p>KNOWN RESIDUAL VIOLATION (SR-F6-08): the timer lambda inside {@code onDisconnectEvent}
     * (lines ~167–175 of SubscriptionListener.java) still logs {@code event.getUser().getName()}
     * verbatim via "Timer for principal %s started", "Timeout for principal %s was canceled",
     * and "Connection for principal %s timed out".  These lines are NOT fixed by Lane A.
     * The canary T6b below documents this as a known open finding that must be remediated in a
     * follow-up fix.  See SR-F6-08.
     */
    @Test
    void T6_subscriptionListener_onDisconnectEvent_outerMethodDoesNotLogRawPrincipal() {
        SubscriptionManager subscriptionManager = mock(SubscriptionManager.class);
        MessageCache messageCache = mock(MessageCache.class);
        // Use a very long timeout so the timer thread does NOT fire during the test window —
        // we only want to assert the synchronous outer method log lines (Fix #7c/d).
        SubscriptionListener listener = new SubscriptionListener(subscriptionManager, messageCache, 300_000L, 300_000L, 2.0);

        SessionDisconnectEvent event = mock(SessionDisconnectEvent.class);
        MessageHeaders headers = new MessageHeaders(null);
        //noinspection unchecked
        Message<byte[]> message = mock(Message.class);
        when(message.getHeaders()).thenReturn(headers);
        when(event.getMessage()).thenReturn(message);

        Principal principal = () -> CANARY_UUID;
        when(event.getUser()).thenReturn(principal);

        // Act — only the synchronous INFO line fires here; the timer thread will be cancelled
        // when the SubscriptionListener is garbage-collected
        listener.onDisconnectEvent(event);

        // Snapshot before asserting: the timer virtual-thread may append "Timer started"
        // concurrently, causing ConcurrentModificationException during iteration.
        List<ILoggingEvent> events = new ArrayList<>(subscriptionListenerAppender.list);

        // SR-F6-08: no raw UUID in the synchronous outer-method log lines
        assertNoRawUuid(events, "T6 SubscriptionListener.onDisconnectEvent outer method");
    }

    // -------------------------------------------------------------------------
    // T6b — KNOWN RESIDUAL VIOLATION: timer lambda logs raw principal (open finding)
    // -------------------------------------------------------------------------

    /**
     * T6b (SR-F6-08 — FIXED): the disconnect-timer virtual-thread lambda now hashes the
     * principal name in all three log lines via {@code LogScrubber.hash8(principal)}:
     * <ul>
     *   <li>"Timer for principal-hash={} started"</li>
     *   <li>"Timeout for principal-hash={} was canceled"</li>
     *   <li>"Connection for principal-hash={} timed out. Terminating all subscriptions."</li>
     * </ul>
     *
     * <p>Fix #8 (SR-F6-08): all three lambda log lines were updated to use
     * {@code LogScrubber.hash8(event.getUser().getName())} and the field label changed from
     * {@code principal} to {@code principal-hash=} for consistency with fixes #7a–7d.
     *
     * <p>This test now asserts {@code isFalse()} — the canary confirms the violation is gone.
     */
    @Test
    void T6b_subscriptionListener_timerLambda_knownResiduaViolation_logsRawPrincipal() throws InterruptedException {
        SubscriptionManager subscriptionManager = mock(SubscriptionManager.class);
        MessageCache messageCache = mock(MessageCache.class);
        // Short timeout so the timer fires and we can observe its log lines
        SubscriptionListener listener = new SubscriptionListener(subscriptionManager, messageCache, 50L, 300_000L, 2.0);

        SessionDisconnectEvent event = mock(SessionDisconnectEvent.class);
        MessageHeaders headers = new MessageHeaders(null);
        //noinspection unchecked
        Message<byte[]> message = mock(Message.class);
        when(message.getHeaders()).thenReturn(headers);
        when(event.getMessage()).thenReturn(message);

        Principal principal = () -> CANARY_UUID;
        when(event.getUser()).thenReturn(principal);

        listener.onDisconnectEvent(event);

        // Wait for the timer thread to emit its log lines
        Thread.sleep(200L);

        List<ILoggingEvent> events = subscriptionListenerAppender.list;

        // Confirm the violation is present — timer lambda emits raw principal
        // SR-F6-08 OPEN: this assertion flips to isFalse() once the lambda is fixed
        boolean timerLambdaLeaksUuid = events.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .anyMatch(LogScrubber::containsRawUuid);
        assertThat(timerLambdaLeaksUuid)
                .as("SR-F6-08 FIXED: SubscriptionListener timer lambda must not log raw UUID — "
                    + "all three lambda log lines now use LogScrubber.hash8(principal) (Fix #8)")
                .isFalse();
    }

    // -------------------------------------------------------------------------
    // T7 — StompCallback logEvent helper must not leak raw hashtag (SR-F6-01)
    // -------------------------------------------------------------------------

    /**
     * T7 (SR-F6-01): the logEvent helper (called on every event) must not log the raw hashtag.
     *
     * <p>Arrange: construct a StompCallback with CANARY_HASHTAG and trigger a TechnicalEvent.Closed.
     * Act: onEvent fires the logEvent helper.
     * Assert: no log line contains CANARY_HASHTAG verbatim; hashtag-len= may be present.
     */
    @Test
    void T7_stompCallback_logEvent_doesNotLogRawHashtag() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        SubscriptionManager subscriptionManager = mock(SubscriptionManager.class);
        MessageCache messageCache = mock(MessageCache.class);
        ShareViewStompRelay shareViewStompRelay = mock(ShareViewStompRelay.class);

        StompCallback callback = new StompCallback(subscriptionManager, messageCache, shareViewStompRelay, restTemplate,
                PERMISSIVE_VALIDATOR, CANARY_UUID, CANARY_HASHTAG, MastodonShortHandle.parse("glacier@example.com"), "glacier.example.com");
        stompCallbackAppender.list.clear();

        TechnicalEvent.Closed closed = mock(TechnicalEvent.Closed.class);
        callback.onEvent(closed);

        List<ILoggingEvent> events = stompCallbackAppender.list;

        // SR-F6-01: raw hashtag must not appear in any log line
        assertNoRawHashtag(events, CANARY_HASHTAG);
    }

    // -------------------------------------------------------------------------
    // T7c — default branch of inner StreamEvent switch must not log Class.toString()
    // -------------------------------------------------------------------------

    /**
     * T7c (SR-TD5-FU-01, CWE-117, D-13/SR-8): the default branch of the {@code streamEvent.getEvent()}
     * switch at {@code StompCallback.java:218} must log only the SimpleName of the event class, not the
     * raw {@link Class#toString()} form (which would include the package prefix: {@code "class
     * fully.qualified.Name"}).
     *
     * <p>The vulnerable pattern {@code streamEvent.getEvent().getClass()} passes the {@link Class}
     * object to {@link String#formatted}, which calls {@link Class#toString()} producing
     * {@code "class social.bigbone.api.entity.streaming.ParsedStreamEvent$UnknownType"}.
     * The fix requires {@code getClass().getSimpleName()}, which returns {@code "UnknownType"}.
     *
     * <p>Arrange: construct a {@link MastodonApiEvent.StreamEvent} whose {@code getEvent()} returns a
     *             {@link ParsedStreamEvent.UnknownType} instance (matches none of the handled cases:
     *             {@code StatusCreated}, {@code StatusEdited}, {@code StatusDeleted}) — triggers the
     *             {@code default} branch at line 218.
     * Act: {@code callback.onEvent(streamEvent)}.
     * Assert (SR-TD5-FU-01):
     * <ol>
     *   <li>{@link ILoggingEvent#getFormattedMessage()} DOES contain
     *       {@code "got an unknown StreamEvent: "} — the operational log line is present.</li>
     *   <li>{@link ILoggingEvent#getFormattedMessage()} does NOT match {@code "class .*\\..*"} —
     *       the {@code Class.toString()} format (with package) must not reach the encoder.</li>
     *   <li>{@link ILoggingEvent#getArgumentArray()} elements (stringified) do NOT match
     *       {@code "class .*\\..*"} (defence-in-depth, Gap 2 / SR-F6-10).</li>
     * </ol>
     */
    @Test
    void T7c_stompCallback_unknownStreamEvent_defaultBranch_doesNotLogClassToString() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        SubscriptionManager subscriptionManager = mock(SubscriptionManager.class);
        MessageCache messageCache = mock(MessageCache.class);
        ShareViewStompRelay shareViewStompRelay = mock(ShareViewStompRelay.class);

        StompCallback callback = new StompCallback(subscriptionManager, messageCache, shareViewStompRelay, restTemplate,
                PERMISSIVE_VALIDATOR, CANARY_UUID, CANARY_HASHTAG, MastodonShortHandle.parse("glacier@example.com"), "glacier.example.com");
        stompCallbackAppender.list.clear();

        // Arrange: use ParsedStreamEvent.UnknownType — a real type that does not match
        // StatusCreated/StatusEdited/StatusDeleted, routing to the default branch at line 218.
        // List.of() is the stream-type list; it must be non-null per the constructor contract.
        ParsedStreamEvent.UnknownType unknownParsed = new ParsedStreamEvent.UnknownType("__TD5FU_CANARY__", "{}");
        MastodonApiEvent.StreamEvent streamEvent = new MastodonApiEvent.StreamEvent(unknownParsed, List.of());

        // Act — triggers the default branch: logEvent("got an unknown StreamEvent: %s".formatted(…))
        callback.onEvent(streamEvent);

        List<ILoggingEvent> events = stompCallbackAppender.list;

        // Positive assertion: the operational log line must be present
        assertThat(events)
                .as("SR-TD5-FU-01: logEvent('got an unknown StreamEvent: ...') must be emitted")
                .anySatisfy(e -> assertThat(e.getFormattedMessage())
                        .contains("got an unknown StreamEvent: "));

        // Negative assertion SR-TD5-FU-01: Class.toString() pattern must not appear in any message
        // Class.toString() produces "class fully.qualified.Name" — a dotted fully-qualified class name.
        for (ILoggingEvent e : events) {
            assertThat(e.getFormattedMessage())
                    .as("SR-TD5-FU-01: Class.toString() (\"class ...\") must not appear — " +
                        "getSimpleName() must be used, not getClass() bare (CWE-117/D-13/SR-8). " +
                        "Message: [%s]", e.getFormattedMessage())
                    .doesNotMatch(".*class .*\\..*");
            // Gap 2: check argument array elements too
            Object[] args = e.getArgumentArray();
            if (args != null) {
                for (Object arg : args) {
                    assertThat(String.valueOf(arg))
                            .as("SR-TD5-FU-01: Class.toString() must not appear in log argument array")
                            .doesNotMatch(".*class .*\\..*");
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // T7d — default branch of outer event switch must not log Class.toString()
    // -------------------------------------------------------------------------

    /**
     * T7d (SR-TD5-FU-02, CWE-117, D-13/SR-8): the unknown-event default branch of the
     * {@code StompCallback} event switch must log only the SimpleName of the event class, not the
     * raw {@link Class#toString()} form.
     *
     * <p>The vulnerable pattern {@code event.getClass()} passes the {@link Class} object to
     * {@link String#formatted}, which calls {@link Class#toString()} and produces
     * {@code "class social.bigbone.api.entity.streaming.MastodonApiEvent$..."}.
     * The fix requires {@code getClass().getSimpleName()}.
     *
     * <p>Arrange: since bigbone 2.0.0 the {@link WebSocketEvent} hierarchy is sealed, so the
     *             outer {@code default} branch is unreachable and un-mockable. The equivalent —
     *             genuinely reachable — unknown-event path is the inner StreamEvent default
     *             branch, exercised with the real {@link ParsedStreamEvent.FiltersChanged}
     *             singleton (a subtype Glacier does not handle).
     * Act: {@code callback.onEvent(unknownStreamEvent)}.
     * Assert (SR-TD5-FU-02):
     * <ol>
     *   <li>{@link ILoggingEvent#getFormattedMessage()} DOES contain
     *       {@code "got an unknown StreamEvent: "} — the operational log line is present.</li>
     *   <li>{@link ILoggingEvent#getFormattedMessage()} does NOT match {@code "class .*\\..*"} —
     *       the {@code Class.toString()} format (with package) must not reach the encoder.</li>
     *   <li>{@link ILoggingEvent#getArgumentArray()} elements (stringified) do NOT match
     *       {@code "class .*\\..*"} (defence-in-depth, Gap 2 / SR-F6-10).</li>
     * </ol>
     */
    @Test
    void T7d_stompCallback_unknownOuterEvent_defaultBranch_doesNotLogClassToString() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        SubscriptionManager subscriptionManager = mock(SubscriptionManager.class);
        MessageCache messageCache = mock(MessageCache.class);
        ShareViewStompRelay shareViewStompRelay = mock(ShareViewStompRelay.class);

        StompCallback callback = new StompCallback(subscriptionManager, messageCache, shareViewStompRelay, restTemplate,
                PERMISSIVE_VALIDATOR, CANARY_UUID, CANARY_HASHTAG, MastodonShortHandle.parse("glacier@example.com"), "glacier.example.com");
        stompCallbackAppender.list.clear();

        // Arrange: bigbone 2.0.0 sealed the WebSocketEvent hierarchy, so the outer default
        // branch cannot be reached (no foreign subtype can exist). Exercise the equivalent
        // inner StreamEvent default branch with a real unhandled subtype instead.
        MastodonApiEvent.StreamEvent unknownStreamEvent =
                new MastodonApiEvent.StreamEvent(ParsedStreamEvent.FiltersChanged.INSTANCE, List.of());

        // Act — triggers the StreamEvent default branch: logEvent("got an unknown StreamEvent: %s".formatted(…))
        callback.onEvent(unknownStreamEvent);

        List<ILoggingEvent> events = stompCallbackAppender.list;

        // Positive assertion: the operational log line must be present
        assertThat(events)
                .as("SR-TD5-FU-02: logEvent('got an unknown StreamEvent: ...') must be emitted")
                .anySatisfy(e -> assertThat(e.getFormattedMessage())
                        .contains("got an unknown StreamEvent: "));

        // Negative assertion SR-TD5-FU-02: Class.toString() pattern must not appear in any message.
        // Class.toString() produces "class fully.qualified.Name" — recognizable by "class " prefix + dot.
        for (ILoggingEvent e : events) {
            assertThat(e.getFormattedMessage())
                    .as("SR-TD5-FU-02: Class.toString() (\"class ...\") must not appear — " +
                        "getSimpleName() must be used, not getClass() bare (CWE-117/D-13/SR-8). " +
                        "Message: [%s]", e.getFormattedMessage())
                    .doesNotMatch(".*class .*\\..*");
            // Gap 2: check argument array elements too
            Object[] args = e.getArgumentArray();
            if (args != null) {
                for (Object arg : args) {
                    assertThat(String.valueOf(arg))
                            .as("SR-TD5-FU-02: Class.toString() must not appear in log argument array")
                            .doesNotMatch(".*class .*\\..*");
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // T8 — AUDIT logger captures events without leaking raw values (Gap 3 smoke test)
    // -------------------------------------------------------------------------

    /**
     * T8 (Gap 3 — SR-F6-08): AUDIT logger is attached to the harness.
     *
     * <p>This smoke test verifies that the AUDIT appender is wired up correctly and that
     * any events emitted to the AUDIT logger during a StompCallback construction do not
     * contain raw UUID values.
     */
    @Test
    void T8_auditLogger_attachedToHarness_doesNotCaptureRawUuid() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        SubscriptionManager subscriptionManager = mock(SubscriptionManager.class);
        MessageCache messageCache = mock(MessageCache.class);
        ShareViewStompRelay shareViewStompRelay = mock(ShareViewStompRelay.class);

        // Act — any AUDIT events emitted during construction should be clean
        new StompCallback(subscriptionManager, messageCache, shareViewStompRelay, restTemplate,
                PERMISSIVE_VALIDATOR, CANARY_UUID, CANARY_HASHTAG, MastodonShortHandle.parse("glacier@example.com"), "glacier.example.com");

        // AUDIT appender is up and not null (Gap 3 wiring verification)
        assertThat(auditAppender).isNotNull();
        assertThat(auditAppender.isStarted()).isTrue();

        // If any AUDIT events were emitted, they must not contain the raw UUID
        List<ILoggingEvent> auditEvents = auditAppender.list;
        assertNoRawUuid(auditEvents, "T8 AUDIT logger");
        assertNoRawHashtag(auditEvents, CANARY_HASHTAG);
    }

    // -------------------------------------------------------------------------
    // Gap 1 helper methods: assertNoRawUrl + assertNoRawHashtag
    // Both check getFormattedMessage() AND getArgumentArray() (Gap 2 — SR-F6-10)
    // -------------------------------------------------------------------------

    /**
     * Gap 1 + Gap 2: asserts that {@code url} does not appear in either the formatted message
     * or the argument array of any captured log event.
     *
     * <p>Structured-logging frameworks (e.g. Logback with JSON encoder) may lazy-render
     * arguments; checking only {@code getFormattedMessage()} can miss argument-encoded leaks.
     *
     * @param events the captured log events to inspect
     * @param url    the raw URL string that must not appear in any log output
     */
    private static void assertNoRawUrl(List<ILoggingEvent> events, String url) {
        for (ILoggingEvent event : events) {
            assertThat(event.getFormattedMessage())
                    .as("Raw URL must not appear in formatted log message")
                    .doesNotContain(url);
            Object[] args = event.getArgumentArray();
            if (args != null) {
                for (Object arg : args) {
                    assertThat(String.valueOf(arg))
                            .as("Raw URL must not appear in log argument array")
                            .doesNotContain(url);
                }
            }
        }
    }

    /**
     * Gap 1 + Gap 2: asserts that {@code hashtag} does not appear in either the formatted message
     * or the argument array of any captured log event.
     *
     * @param events  the captured log events to inspect
     * @param hashtag the raw hashtag string that must not appear in any log output
     */
    private static void assertNoRawHashtag(List<ILoggingEvent> events, String hashtag) {
        for (ILoggingEvent event : events) {
            assertThat(event.getFormattedMessage())
                    .as("Raw hashtag must not appear in formatted log message")
                    .doesNotContain(hashtag);
            Object[] args = event.getArgumentArray();
            if (args != null) {
                for (Object arg : args) {
                    assertThat(String.valueOf(arg))
                            .as("Raw hashtag must not appear in log argument array")
                            .doesNotContain(hashtag);
                }
            }
        }
    }

    /**
     * Gap 2 extension of the existing UUID helper: asserts that no event contains a raw UUID
     * pattern, checking both {@code getFormattedMessage()} and {@code getArgumentArray()}.
     *
     * @param events  the captured log events to inspect
     * @param context a human-readable context label used in assertion failure messages
     */
    private static void assertNoRawUuid(List<ILoggingEvent> events, String context) {
        // Use a snapshot to prevent ConcurrentModificationException when background
        // threads (e.g. SubscriptionListener timer virtual threads) append concurrently.
        List<ILoggingEvent> snapshot = new ArrayList<>(events);
        for (ILoggingEvent event : snapshot) {
            String msg = event.getFormattedMessage();
            assertThat(LogScrubber.containsRawUuid(msg))
                    .as("Log line from %s must not contain raw UUID.\nLine: %s", context, msg)
                    .isFalse();
            // Gap 2: also check argument array
            Object[] args = event.getArgumentArray();
            if (args != null) {
                for (Object arg : args) {
                    String argStr = String.valueOf(arg);
                    assertThat(LogScrubber.containsRawUuid(argStr))
                            .as("Log argument from %s must not contain raw UUID.\nArg: %s", context, argStr)
                            .isFalse();
                }
            }
        }
    }

    /**
     * Gap 2: asserts that a known session ID string does not appear in either the formatted message
     * or the argument array of any captured log event.
     *
     * @param events    the captured log events to inspect
     * @param sessionId the raw session identifier that must not appear in any log output
     */
    private static void assertNoRawSessionId(List<ILoggingEvent> events, String sessionId) {
        for (ILoggingEvent event : events) {
            assertThat(event.getFormattedMessage())
                    .as("Raw sessionId must not appear in formatted log message")
                    .doesNotContain(sessionId);
            Object[] args = event.getArgumentArray();
            if (args != null) {
                for (Object arg : args) {
                    assertThat(String.valueOf(arg))
                            .as("Raw sessionId must not appear in log argument array")
                            .doesNotContain(sessionId);
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Structural gate: all webservice getSessionId() calls must be wrapped (D-13/SR-8)
    // -------------------------------------------------------------------------

    /**
     * Structural gate (D-13/SR-8, glacier-structured-logging-logback convention):
     * every call to {@code .getSessionId()} in the {@code webservice} package tree must be
     * wrapped by {@code LogScrubber.hash8(} to prevent raw session identifiers from reaching
     * the log encoder.
     *
     * <p>This test walks {@code src/main/java/de/seism0saurus/glacier/webservice/} at source-text
     * level, excluding the {@code messages/} subpackage (pure DTOs, no logging), and asserts that
     * no bare {@code getSessionId()} call exists without the wrapping guard.
     *
     * <p>Run status: FAIL before the SR-MED-02 fixes; PASS after both fix sites in
     * {@code SubscriptionController.java} are updated.
     *
     * <p>References: D-13 / SR-8, ADR-MED-02-01/02, SR-MED-02-01/02.
     */
    @Test
    void allWebserviceGetSessionIdCallsAreHashed() throws java.io.IOException {
        java.nio.file.Path webservicePath =
                java.nio.file.Paths.get("src/main/java/de/seism0saurus/glacier/webservice");

        java.nio.file.Files.walk(webservicePath)
                .filter(p -> p.toString().endsWith(".java"))
                // Exclude messages/ subpackage — pure DTOs, no logging calls
                .filter(p -> !p.toString().contains("/messages/"))
                .forEach(file -> {
                    String content;
                    try {
                        content = java.nio.file.Files.readString(file);
                    } catch (java.io.IOException e) {
                        throw new RuntimeException("Failed to read " + file, e);
                    }
                    int idx = 0;
                    while ((idx = content.indexOf("getSessionId()", idx)) >= 0) {
                        // Check that LogScrubber.hash8( wraps the call.
                        // The typical pattern is:
                        //   LogScrubber.hash8(headerAccessor.getSessionId())
                        // "LogScrubber.hash8(headerAccessor." is 34 chars before getSessionId(),
                        // so we use a 40-char look-behind to accommodate any prefix spacing.
                        int start = Math.max(0, idx - 40);
                        String context = content.substring(start, idx + 14);
                        assertThat(context)
                                .as("D-13/SR-8: getSessionId() in %s must be wrapped by LogScrubber.hash8(" +
                                    " — bare session IDs must not reach log output (ADR-MED-02-01/02)", file)
                                .contains("LogScrubber.hash8(");
                        idx++;
                    }
                });
    }

    // -------------------------------------------------------------------------
    // Appender lifecycle helpers
    // -------------------------------------------------------------------------

    private static ListAppender<ILoggingEvent> attachAppender(Class<?> clazz) {
        Logger logger = (Logger) LoggerFactory.getLogger(clazz);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    private static void detach(Class<?> clazz, ListAppender<ILoggingEvent> appender) {
        Logger logger = (Logger) LoggerFactory.getLogger(clazz);
        logger.detachAppender(appender);
        appender.stop();
    }
}
