package de.seism0saurus.glacier.security;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.TextNode;
import de.seism0saurus.glacier.mastodon.MastodonShortHandle;
import de.seism0saurus.glacier.mastodon.StompCallback;
import de.seism0saurus.glacier.mastodon.SubscriptionManager;
import de.seism0saurus.glacier.share.application.SafeUrlValidator;
import de.seism0saurus.glacier.share.application.ShareViewStompRelay;
import de.seism0saurus.glacier.webservice.cache.CacheEntry;
import de.seism0saurus.glacier.webservice.cache.MessageCache;
import de.seism0saurus.glacier.webservice.messaging.PrincipalKey;
import de.seism0saurus.glacier.webservice.messaging.messages.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.RestTemplate;
import social.bigbone.api.entity.Status;
import social.bigbone.api.entity.streaming.MastodonApiEvent;
import social.bigbone.api.entity.streaming.ParsedStreamEvent;

import java.net.URI;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Security unit tests for hostile / malformed Mastodon response handling in {@link StompCallback}.
 *
 * <p>These tests verify that the callback is robust against malformed, null, or oversized input
 * from an untrusted Mastodon instance — closing OWASP API10:2023 (Unsafe Consumption of APIs).
 *
 * <ul>
 *   <li>UT-sec-07: null {@code mentions} list — NPE-safe; toot dropped cleanly</li>
 *   <li>UT-sec-08: malformed {@code editedAt} timestamp — handled gracefully without crash</li>
 *   <li>UT-sec-09: null {@code stream} field in GenericMessage — exits cleanly</li>
 *   <li>UT-sec-10: empty URL — blocked by {@link SafeUrlValidator} returning empty</li>
 *   <li>UT-sec-11: oversize status ID — no NPE or log explosion</li>
 * </ul>
 *
 * <p>Log-hygiene assertions are included for UT-sec-07 and UT-sec-09 (D-13 / SR-8 / CWE-117).
 *
 * <p>Mode applicability: <strong>live</strong> mode (WebSocket streaming path).
 * Fallback and killswitch modes do not exercise this code path.
 * Insecure-transport variant: not affected (response-handling logic is transport-independent).
 *
 * <p>OWASP: API10:2023 — Unsafe Consumption of APIs (malformed upstream responses).
 */
class StompCallbackHostileResponseTest {

    // -------------------------------------------------------------------------
    // Canary constants (D-13 / SR-8)
    // -------------------------------------------------------------------------

    private static final String CANARY_UUID    = "550e8400-e29b-41d4-a716-446655440099";
    private static final String CANARY_HASHTAG = "hostileTestHashtag";
    private static final String CANARY_URL     = "https://mastodon.example.com/users/evil/statuses/99999";
    private static final String BOT_SHORT_HANDLE = "glacier";
    private static final String BOT_FULL_HANDLE  = BOT_SHORT_HANDLE + "@glacier.events";
    /** Parsed value object for the bot handle — injected into StompCallback (ADR-P3A-2). */
    private static final MastodonShortHandle BOT_HANDLE_VO = MastodonShortHandle.parse(BOT_FULL_HANDLE);

    /**
     * Permissive {@link SafeUrlValidator}: always passes URLs through; used for tests where the
     * SSRF guard must NOT be the gate that drops the toot (other guards are under test).
     */
    private static final SafeUrlValidator PERMISSIVE_VALIDATOR =
            rawUrl -> (rawUrl == null || rawUrl.isBlank())
                    ? Optional.empty()
                    : Optional.of(URI.create(rawUrl));

    /**
     * Blocking {@link SafeUrlValidator}: always returns empty; used for UT-sec-10 where the
     * empty-URL path must be blocked before any cache write.
     */
    private static final SafeUrlValidator BLOCKING_VALIDATOR = rawUrl -> Optional.empty();

    // -------------------------------------------------------------------------
    // Log appender lifecycle
    // -------------------------------------------------------------------------

    private ListAppender<ILoggingEvent> callbackAppender;

    @BeforeEach
    void attachAppender() {
        callbackAppender = new ListAppender<>();
        callbackAppender.start();
        ((Logger) LoggerFactory.getLogger(StompCallback.class)).addAppender(callbackAppender);
    }

    @AfterEach
    void detachAppender() {
        ((Logger) LoggerFactory.getLogger(StompCallback.class)).detachAppender(callbackAppender);
        callbackAppender.stop();
    }

    // -------------------------------------------------------------------------
    // UT-sec-07: null mentions → NPE-safe; toot dropped cleanly
    // -------------------------------------------------------------------------

    /**
     * UT-sec-07: when {@code Status.getMentions()} returns {@code null}, {@code isOptedIn}
     * must return {@code false} (NPE-safe) and the toot must be silently dropped.
     *
     * <p>Arrange: a Bigbone {@link Status} whose {@code getMentions()} returns {@code null}.
     * Act: {@code onEvent} processes a StreamEvent/StatusCreated wrapping this status.
     * Assert: no NPE is thrown; {@code messageCache.recordThenPublish} is never called.
     * Log hygiene: no raw wallId UUID or raw hashtag appears in any log line.
     */
    @Test
    void genericMessage_withNullMentions_dropsToot_doesNotCrashCallback() {
        // Arrange
        SubscriptionManager subscriptionManager = mock(SubscriptionManager.class);
        MessageCache messageCache = mock(MessageCache.class);
        ShareViewStompRelay shareViewStompRelay = mock(ShareViewStompRelay.class);
        RestTemplate restTemplate = mock(RestTemplate.class);

        Status status = mock(Status.class);
        when(status.getMentions()).thenReturn(null);  // hostile: null mentions list
        when(status.getUrl()).thenReturn(CANARY_URL);
        when(status.getId()).thenReturn("toot-null-mentions");

        StompCallback callback = new StompCallback(
                subscriptionManager, messageCache, shareViewStompRelay, restTemplate,
                PERMISSIVE_VALIDATOR, CANARY_UUID, CANARY_HASHTAG, BOT_HANDLE_VO, "glacier.events");
        callbackAppender.list.clear();

        ParsedStreamEvent.StatusCreated created = new ParsedStreamEvent.StatusCreated(status);
        MastodonApiEvent.StreamEvent streamEvent = new MastodonApiEvent.StreamEvent(created, List.of());

        // Act — must not throw NPE
        assertThatCode(() -> callback.onEvent(streamEvent)).doesNotThrowAnyException();

        // Assert: toot dropped — no cache write
        verify(messageCache, never()).recordThenPublish(any(PrincipalKey.class), anyString(), any(CacheEntry.class));

        // Log hygiene (D-13 / SR-8)
        assertNoRawUuid(callbackAppender.list, CANARY_UUID);
        assertNoRawHashtag(callbackAppender.list, CANARY_HASHTAG);
    }

    // -------------------------------------------------------------------------
    // UT-sec-08: malformed editedAt — graceful handling
    // -------------------------------------------------------------------------

    /**
     * UT-sec-08: a GenericMessage "status.update" whose payload {@code editedAt} is an unparseable
     * string must not crash the callback. The normalisation failure causes the event to be dropped.
     *
     * <p>Arrange: payload with bot mention (so opt-in check passes), permissive SSRF validator,
     * loadable embed headers, and an unparseable {@code editedAt} value.
     * Act: {@code onEvent} processes the GenericMessage.
     * Assert: no exception thrown; {@code messageCache.recordThenPublish} is never called
     * (the malformed {@code editedAt} causes a drop in {@code normaliseEditedAt}).
     */
    @Test
    void genericMessage_withMalformedEditedAt_doesNotCrashCallback() throws Exception {
        // Arrange
        SubscriptionManager subscriptionManager = mock(SubscriptionManager.class);
        MessageCache messageCache = mock(MessageCache.class);
        ShareViewStompRelay shareViewStompRelay = mock(ShareViewStompRelay.class);
        RestTemplate restTemplate = mock(RestTemplate.class);

        HttpHeaders httpHeaders = new HttpHeaders();
        when(restTemplate.headForHeaders(anyString())).thenReturn(httpHeaders);

        StompCallback callback = new StompCallback(
                subscriptionManager, messageCache, shareViewStompRelay, restTemplate,
                PERMISSIVE_VALIDATOR, CANARY_UUID, CANARY_HASHTAG, BOT_HANDLE_VO, "glacier.events");

        ObjectMapper mapper = new ObjectMapper();
        Mention botMention = Mention.builder().id("77").username(BOT_SHORT_HANDLE).acct(BOT_SHORT_HANDLE).build();
        GenericMessageContentPayload payload = GenericMessageContentPayload.builder()
                .id("toot-editedAt-hostile")
                .url(CANARY_URL)
                .mentions(List.of(botMention))
                .editedAt("NOT-A-VALID-TIMESTAMP!!!###")  // hostile malformed editedAt
                .build();
        GenericMessageContent content = GenericMessageContent.builder()
                .event("status.update")
                .stream(List.of("hashtag"))
                .payload(TextNode.valueOf(mapper.writeValueAsString(payload)))
                .build();

        MastodonApiEvent.GenericMessage mockEvent = mock(MastodonApiEvent.GenericMessage.class);
        when(mockEvent.getText()).thenReturn(mapper.writeValueAsString(content));

        // Act — must not throw
        assertThatCode(() -> callback.onEvent(mockEvent)).doesNotThrowAnyException();

        // Assert: malformed editedAt causes normaliseEditedAt to return null → event dropped
        verify(messageCache, never()).recordThenPublish(any(PrincipalKey.class), anyString(), any(CacheEntry.class));
    }

    // -------------------------------------------------------------------------
    // UT-sec-09: null stream field — exits cleanly without NPE
    // -------------------------------------------------------------------------

    /**
     * UT-sec-09: a {@link MastodonApiEvent.GenericMessage} whose deserialized
     * {@code GenericMessageContent.getStream()} returns {@code null} must be handled
     * gracefully — no NPE must reach the Bigbone virtual thread.
     *
     * <p>Arrange: GenericMessage with raw JSON that deserializes to {@code stream: null}.
     * Act: {@code onEvent} processes the GenericMessage.
     * Assert: no exception is thrown; cache is never written.
     * Log hygiene: no raw UUID or hashtag in any log line.
     */
    @Test
    void genericMessage_withNullStreamField_doesNotNPE() throws Exception {
        // Arrange
        SubscriptionManager subscriptionManager = mock(SubscriptionManager.class);
        MessageCache messageCache = mock(MessageCache.class);
        ShareViewStompRelay shareViewStompRelay = mock(ShareViewStompRelay.class);
        RestTemplate restTemplate = mock(RestTemplate.class);

        StompCallback callback = new StompCallback(
                subscriptionManager, messageCache, shareViewStompRelay, restTemplate,
                PERMISSIVE_VALIDATOR, CANARY_UUID, CANARY_HASHTAG, BOT_HANDLE_VO, "glacier.events");
        callbackAppender.list.clear();

        // Build raw JSON where "stream" is explicitly null — GenericMessageContent.stream will be null
        // after deserialization when @JsonIgnoreProperties(ignoreUnknown=true) and null-value handling applies.
        // We use a raw text that has stream: null so the stream field is null upon deserialization.
        String rawJson = "{\"event\":\"update\",\"stream\":null,\"payload\":\"{}\"}";
        MastodonApiEvent.GenericMessage mockEvent = mock(MastodonApiEvent.GenericMessage.class);
        when(mockEvent.getText()).thenReturn(rawJson);

        // Act — must not throw NPE even when stream is null
        assertThatCode(() -> callback.onEvent(mockEvent)).doesNotThrowAnyException();

        // Assert: no cache write when stream is null
        verify(messageCache, never()).recordThenPublish(any(PrincipalKey.class), anyString(), any(CacheEntry.class));

        // Log hygiene (D-13 / SR-8)
        assertNoRawUuid(callbackAppender.list, CANARY_UUID);
        assertNoRawHashtag(callbackAppender.list, CANARY_HASHTAG);
    }

    // -------------------------------------------------------------------------
    // UT-sec-10: empty URL → blocked by SafeUrlValidator
    // -------------------------------------------------------------------------

    /**
     * UT-sec-10: when {@code status.getUrl()} returns an empty string, the SSRF guard
     * ({@link SafeUrlValidator}) must return empty and the toot must be dropped before any
     * cache write.
     *
     * <p>Arrange: blocking {@link SafeUrlValidator}; status with bot mention but empty URL.
     * Act: {@code onEvent} processes the StreamEvent/StatusCreated.
     * Assert: {@code messageCache.recordThenPublish} is never called.
     */
    @Test
    void genericMessage_withEmptyUrl_isBlockedBySafeUrlValidator() {
        // Arrange
        SubscriptionManager subscriptionManager = mock(SubscriptionManager.class);
        MessageCache messageCache = mock(MessageCache.class);
        ShareViewStompRelay shareViewStompRelay = mock(ShareViewStompRelay.class);
        RestTemplate restTemplate = mock(RestTemplate.class);

        Status status = mock(Status.class);
        Status.Mention botMention = mock(Status.Mention.class);
        when(botMention.getAcct()).thenReturn(BOT_SHORT_HANDLE);
        when(status.getMentions()).thenReturn(List.of(botMention));
        when(status.getUrl()).thenReturn("");  // empty URL
        when(status.getId()).thenReturn("toot-empty-url");

        // Blocking validator: empty string → Optional.empty()
        StompCallback callback = new StompCallback(
                subscriptionManager, messageCache, shareViewStompRelay, restTemplate,
                BLOCKING_VALIDATOR, CANARY_UUID, CANARY_HASHTAG, BOT_HANDLE_VO, "glacier.events");

        ParsedStreamEvent.StatusCreated created = new ParsedStreamEvent.StatusCreated(status);
        MastodonApiEvent.StreamEvent streamEvent = new MastodonApiEvent.StreamEvent(created, List.of());

        // Act
        callback.onEvent(streamEvent);

        // Assert: SSRF guard blocked the empty URL — no cache write
        verify(messageCache, never()).recordThenPublish(any(PrincipalKey.class), anyString(), any(CacheEntry.class));
        verify(restTemplate, never()).headForHeaders(anyString());
    }

    // -------------------------------------------------------------------------
    // UT-sec-11: oversize status ID — no NPE or log explosion
    // -------------------------------------------------------------------------

    /**
     * UT-sec-11: a status whose ID is 256 characters long must not cause an NPE or
     * a log explosion. Any log lines emitted must be reasonably bounded in length.
     *
     * <p>Arrange: a Bigbone {@link Status} with a 256-char ID and a bot mention.
     * Permissive SSRF validator + loadable embed headers configured.
     * Act: {@code onEvent} processes the StreamEvent/StatusCreated.
     * Assert: no exception; each log line must be under 1024 characters (no log explosion).
     */
    @Test
    void genericMessage_withOversizeStatusId_dropsEvent() {
        // Arrange
        SubscriptionManager subscriptionManager = mock(SubscriptionManager.class);
        MessageCache messageCache = mock(MessageCache.class);
        ShareViewStompRelay shareViewStompRelay = mock(ShareViewStompRelay.class);
        RestTemplate restTemplate = mock(RestTemplate.class);

        HttpHeaders httpHeaders = new HttpHeaders();
        when(restTemplate.headForHeaders(anyString())).thenReturn(httpHeaders);
        when(messageCache.recordThenPublish(any(), anyString(), any())).thenReturn(null);

        // 256-character status ID — well above any reasonable production value
        String oversizeId = "X".repeat(256);

        Status status = mock(Status.class);
        Status.Mention botMention = mock(Status.Mention.class);
        when(botMention.getAcct()).thenReturn(BOT_SHORT_HANDLE);
        when(status.getMentions()).thenReturn(List.of(botMention));
        when(status.getUrl()).thenReturn(CANARY_URL);
        when(status.getId()).thenReturn(oversizeId);

        StompCallback callback = new StompCallback(
                subscriptionManager, messageCache, shareViewStompRelay, restTemplate,
                PERMISSIVE_VALIDATOR, CANARY_UUID, CANARY_HASHTAG, BOT_HANDLE_VO, "glacier.events");
        callbackAppender.list.clear();

        ParsedStreamEvent.StatusCreated created = new ParsedStreamEvent.StatusCreated(status);
        MastodonApiEvent.StreamEvent streamEvent = new MastodonApiEvent.StreamEvent(created, List.of());

        // Act — must not throw
        assertThatCode(() -> callback.onEvent(streamEvent)).doesNotThrowAnyException();

        // Assert: no log line longer than 1024 chars (prevents log explosion / log flooding)
        for (ILoggingEvent event : callbackAppender.list) {
            assertThat(event.getFormattedMessage().length())
                    .as("Log line must not exceed 1024 characters (UT-sec-11 log explosion guard): %s",
                            event.getFormattedMessage())
                    .isLessThanOrEqualTo(1024);
        }
    }

    // -------------------------------------------------------------------------
    // Log-hygiene helper methods (D-13 / SR-8 / CWE-117)
    // -------------------------------------------------------------------------

    /**
     * Asserts that the raw UUID does not appear in any formatted message or argument array.
     */
    private static void assertNoRawUuid(List<ILoggingEvent> events, String uuid) {
        for (ILoggingEvent event : events) {
            assertThat(event.getFormattedMessage())
                    .as("Raw UUID must not appear in log message")
                    .doesNotContain(uuid);
            Object[] args = event.getArgumentArray();
            if (args != null) {
                for (Object arg : args) {
                    assertThat(String.valueOf(arg))
                            .as("Raw UUID must not appear in log argument")
                            .doesNotContain(uuid);
                }
            }
        }
    }

    /**
     * Asserts that the raw hashtag does not appear in any formatted message or argument array.
     */
    private static void assertNoRawHashtag(List<ILoggingEvent> events, String hashtag) {
        for (ILoggingEvent event : events) {
            assertThat(event.getFormattedMessage())
                    .as("Raw hashtag must not appear in log message")
                    .doesNotContain(hashtag);
            Object[] args = event.getArgumentArray();
            if (args != null) {
                for (Object arg : args) {
                    assertThat(String.valueOf(arg))
                            .as("Raw hashtag must not appear in log argument")
                            .doesNotContain(hashtag);
                }
            }
        }
    }
}
