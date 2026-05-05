package de.seism0saurus.glacier.security;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.TextNode;
import de.seism0saurus.glacier.mastodon.StompCallback;
import de.seism0saurus.glacier.mastodon.SubscriptionManager;
import de.seism0saurus.glacier.share.application.SafeUrlValidator;
import de.seism0saurus.glacier.share.application.ShareViewStompRelay;
import de.seism0saurus.glacier.util.LogScrubber;
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
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Security unit tests for the bot opt-in enforcement in {@link StompCallback} (ADR-PT-A04-01).
 *
 * <p>These tests verify that the {@code isOptedIn} helper is enforced on ALL three event paths:
 * <ul>
 *   <li>UT-sec-01 / UT-sec-02: GenericMessage path ({@code sendMessage}) — existing guard</li>
 *   <li>UT-sec-03: domain-only mention is not sufficient (exact-match semantics)</li>
 *   <li>UT-sec-04: typed {@code StatusCreated} path ({@code processStatusCreatedEvent})</li>
 *   <li>UT-sec-04b: typed {@code StatusEdited} path ({@code processStatusEditedEvent})</li>
 *   <li>UT-sec-04c: structural regression guard covering both paths simultaneously</li>
 * </ul>
 *
 * <p>Log-hygiene assertions are included for UT-sec-01 and UT-sec-04 (D-13 / SR-8 / CWE-117):
 * no raw wallId UUID, no raw hashtag, and no raw URL must appear in any log line produced
 * when the opt-in check drops a toot.
 *
 * <p>Mode applicability: <strong>live</strong> mode (WebSocket streaming path).
 * Fallback and killswitch modes do not use {@link StompCallback} directly — they rely on
 * the REST polling fallback path, which is mode-neutral w.r.t. this class.
 * Insecure-transport mode: not affected (opt-in logic is transport-independent).
 *
 * <p>OWASP: A04:2021 — Insecure Design (missing defence-in-depth guard on typed event path).
 */
class StompCallbackOptInEnforcementTest {

    // -------------------------------------------------------------------------
    // Canary constants (D-13 / SR-8)
    // -------------------------------------------------------------------------

    private static final String CANARY_UUID    = "550e8400-e29b-41d4-a716-446655440000";
    private static final String CANARY_HASHTAG = "secTestHashtag";
    private static final String CANARY_URL     = "https://mastodon.example.com/users/alice/statuses/12345";
    private static final String BOT_SHORT_HANDLE = "glacier";
    private static final String BOT_FULL_HANDLE  = BOT_SHORT_HANDLE + "@glacier.events";

    /**
     * Permissive {@link SafeUrlValidator}: always passes the URL through so SSRF guard
     * does not interfere with opt-in tests.
     */
    private static final SafeUrlValidator PERMISSIVE_VALIDATOR =
            rawUrl -> Optional.of(URI.create(rawUrl));

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
    // UT-sec-01: GenericMessage without bot mention is dropped
    // -------------------------------------------------------------------------

    /**
     * UT-sec-01: {@code sendMessage} with a GenericMessage whose {@code Status.getMentions()}
     * does NOT contain the bot {@code shortHandle} must be silently dropped.
     *
     * <p>Arrange: construct a GenericMessage "update" event whose payload mentions a different
     * user (not the bot). Permissive SSRF validator and loadable embed headers are configured
     * so the only gate that can cause a drop is the opt-in check.
     * Act: {@code onEvent} processes the GenericMessage.
     * Assert: {@code messageCache.recordThenPublish} is NEVER called (the toot was dropped).
     * Log hygiene: no raw wallId UUID, no raw hashtag, no raw URL appears in any log line.
     */
    @Test
    void genericMessage_withoutBotMention_isDropped() throws Exception {
        // Arrange
        SubscriptionManager subscriptionManager = mock(SubscriptionManager.class);
        MessageCache messageCache = mock(MessageCache.class);
        ShareViewStompRelay shareViewStompRelay = mock(ShareViewStompRelay.class);
        RestTemplate restTemplate = mock(RestTemplate.class);

        // HEAD returns permissive headers so frame-ancestor check passes
        HttpHeaders httpHeaders = new HttpHeaders();
        when(restTemplate.headForHeaders(anyString())).thenReturn(httpHeaders);

        StompCallback callback = new StompCallback(
                subscriptionManager, messageCache, shareViewStompRelay, restTemplate,
                PERMISSIVE_VALIDATOR, CANARY_UUID, CANARY_HASHTAG, BOT_FULL_HANDLE, "glacier.events");
        callbackAppender.list.clear();

        // GenericMessage whose payload mentions someone OTHER than the bot
        ObjectMapper mapper = new ObjectMapper();
        Mention otherMention = Mention.builder().id("1").username("alice").acct("alice").build();
        GenericMessageContentPayload payload = GenericMessageContentPayload.builder()
                .id("toot-001")
                .url(CANARY_URL)
                .mentions(List.of(otherMention))
                .build();
        GenericMessageContent content = GenericMessageContent.builder()
                .event("update")
                .stream(List.of("hashtag"))
                .payload(TextNode.valueOf(mapper.writeValueAsString(payload)))
                .build();

        MastodonApiEvent.GenericMessage mockEvent = mock(MastodonApiEvent.GenericMessage.class);
        when(mockEvent.getText()).thenReturn(mapper.writeValueAsString(content));

        // Act
        callback.onEvent(mockEvent);

        // Assert: toot was dropped — cache never written
        verify(messageCache, never()).recordThenPublish(any(PrincipalKey.class), anyString(), any(CacheEntry.class));

        // Log hygiene assertions (D-13 / SR-8)
        assertNoRawUuid(callbackAppender.list, CANARY_UUID);
        assertNoRawHashtag(callbackAppender.list, CANARY_HASHTAG);
        assertNoRawUrl(callbackAppender.list, CANARY_URL);
    }

    // -------------------------------------------------------------------------
    // UT-sec-02: GenericMessage WITH bot mention is published
    // -------------------------------------------------------------------------

    /**
     * UT-sec-02: {@code sendMessage} with a GenericMessage whose payload DOES mention the
     * bot's {@code shortHandle} must reach the cache write.
     *
     * <p>Arrange: same as UT-sec-01 but mention's {@code acct} equals {@code BOT_SHORT_HANDLE}.
     * Permissive SSRF validator + loadable embed headers so only the opt-in gate determines outcome.
     * Act: {@code onEvent} processes the GenericMessage.
     * Assert: {@code messageCache.recordThenPublish} IS called exactly once.
     */
    @Test
    void genericMessage_withBotMentionMatchingShortHandle_isPublished() throws Exception {
        // Arrange
        SubscriptionManager subscriptionManager = mock(SubscriptionManager.class);
        MessageCache messageCache = mock(MessageCache.class);
        ShareViewStompRelay shareViewStompRelay = mock(ShareViewStompRelay.class);
        RestTemplate restTemplate = mock(RestTemplate.class);

        // HEAD returns permissive headers so frame-ancestor check passes
        HttpHeaders httpHeaders = new HttpHeaders();
        when(restTemplate.headForHeaders(anyString())).thenReturn(httpHeaders);
        CacheEntry storedEntry = new CacheEntry(de.seism0saurus.glacier.webservice.cache.EventType.CREATED,
                "toot-002", CANARY_URL + "/embed", null, 0L);
        when(messageCache.recordThenPublish(any(), anyString(), any())).thenReturn(storedEntry);

        StompCallback callback = new StompCallback(
                subscriptionManager, messageCache, shareViewStompRelay, restTemplate,
                PERMISSIVE_VALIDATOR, CANARY_UUID, CANARY_HASHTAG, BOT_FULL_HANDLE, "glacier.events");

        // GenericMessage whose payload mentions the bot
        ObjectMapper mapper = new ObjectMapper();
        Mention botMention = Mention.builder().id("99").username(BOT_SHORT_HANDLE).acct(BOT_SHORT_HANDLE).build();
        GenericMessageContentPayload payload = GenericMessageContentPayload.builder()
                .id("toot-002")
                .url(CANARY_URL)
                .mentions(List.of(botMention))
                .build();
        GenericMessageContent content = GenericMessageContent.builder()
                .event("update")
                .stream(List.of("hashtag"))
                .payload(TextNode.valueOf(mapper.writeValueAsString(payload)))
                .build();

        MastodonApiEvent.GenericMessage mockEvent = mock(MastodonApiEvent.GenericMessage.class);
        when(mockEvent.getText()).thenReturn(mapper.writeValueAsString(content));

        // Act
        callback.onEvent(mockEvent);

        // Assert: cache was written once
        verify(messageCache, times(1)).recordThenPublish(any(PrincipalKey.class), anyString(), any(CacheEntry.class));
    }

    // -------------------------------------------------------------------------
    // UT-sec-03: Domain-qualified mention does NOT satisfy shortHandle (exact match)
    // -------------------------------------------------------------------------

    /**
     * UT-sec-03: a mention whose {@code acct} is {@code "glacier@otherinstance.social"} must NOT
     * satisfy a {@code shortHandle} of {@code "glacier"}.
     *
     * <p>The comparison must use {@code String#equals}, not {@code startsWith} or {@code contains}.
     * A domain-qualified mention on a different instance must be treated as a different identity.
     *
     * <p>Arrange: GenericMessage whose payload mention has {@code acct = "glacier@otherinstance.social"}.
     * Act: {@code onEvent} processes the event.
     * Assert: {@code messageCache.recordThenPublish} is NEVER called.
     */
    @Test
    void genericMessage_withMentionMatchingDomainOnly_isDropped() throws Exception {
        // Arrange
        SubscriptionManager subscriptionManager = mock(SubscriptionManager.class);
        MessageCache messageCache = mock(MessageCache.class);
        ShareViewStompRelay shareViewStompRelay = mock(ShareViewStompRelay.class);
        RestTemplate restTemplate = mock(RestTemplate.class);

        HttpHeaders httpHeaders = new HttpHeaders();
        when(restTemplate.headForHeaders(anyString())).thenReturn(httpHeaders);

        StompCallback callback = new StompCallback(
                subscriptionManager, messageCache, shareViewStompRelay, restTemplate,
                PERMISSIVE_VALIDATOR, CANARY_UUID, CANARY_HASHTAG, BOT_FULL_HANDLE, "glacier.events");

        ObjectMapper mapper = new ObjectMapper();
        // acct = "glacier@otherinstance.social" — different instance from the configured bot
        Mention foreignInstanceMention = Mention.builder()
                .id("2").username(BOT_SHORT_HANDLE).acct(BOT_SHORT_HANDLE + "@otherinstance.social").build();
        GenericMessageContentPayload payload = GenericMessageContentPayload.builder()
                .id("toot-003")
                .url(CANARY_URL)
                .mentions(List.of(foreignInstanceMention))
                .build();
        GenericMessageContent content = GenericMessageContent.builder()
                .event("update")
                .stream(List.of("hashtag"))
                .payload(TextNode.valueOf(mapper.writeValueAsString(payload)))
                .build();

        MastodonApiEvent.GenericMessage mockEvent = mock(MastodonApiEvent.GenericMessage.class);
        when(mockEvent.getText()).thenReturn(mapper.writeValueAsString(content));

        // Act
        callback.onEvent(mockEvent);

        // Assert: foreign instance mention does not satisfy exact-match check
        verify(messageCache, never()).recordThenPublish(any(PrincipalKey.class), anyString(), any(CacheEntry.class));
    }

    // -------------------------------------------------------------------------
    // UT-sec-04: Typed StatusCreated without bot mention is dropped
    // -------------------------------------------------------------------------

    /**
     * UT-sec-04: {@code processStatusCreatedEvent} with a typed {@link ParsedStreamEvent.StatusCreated}
     * whose status has NO bot mention must be dropped.
     *
     * <p>This proves that the typed path now enforces the opt-in invariant via {@code isOptedIn()}
     * (ADR-PT-A04-01). Before the fix, the typed path did NOT check opt-in.
     *
     * <p>Arrange: a {@link Status} mock whose {@code getMentions()} returns a list with only
     * a non-bot mention.  Permissive SSRF validator and loadable embed headers configured so
     * only the opt-in gate causes a drop.
     * Act: {@code onEvent} processes the StreamEvent wrapping StatusCreated.
     * Assert: {@code messageCache.recordThenPublish} is NEVER called.
     * Log hygiene: no raw wallId UUID, no raw hashtag appears in any log line.
     */
    @Test
    void typedStatusCreated_withoutBotMention_isDropped() {
        // Arrange
        SubscriptionManager subscriptionManager = mock(SubscriptionManager.class);
        MessageCache messageCache = mock(MessageCache.class);
        ShareViewStompRelay shareViewStompRelay = mock(ShareViewStompRelay.class);
        RestTemplate restTemplate = mock(RestTemplate.class);

        Status status = mock(Status.class);
        Status.Mention otherMention = mock(Status.Mention.class);
        when(otherMention.getAcct()).thenReturn("alice");
        when(status.getMentions()).thenReturn(List.of(otherMention));
        when(status.getUrl()).thenReturn(CANARY_URL);
        when(status.getId()).thenReturn("toot-sc-01");

        StompCallback callback = new StompCallback(
                subscriptionManager, messageCache, shareViewStompRelay, restTemplate,
                PERMISSIVE_VALIDATOR, CANARY_UUID, CANARY_HASHTAG, BOT_FULL_HANDLE, "glacier.events");
        callbackAppender.list.clear();

        ParsedStreamEvent.StatusCreated created = new ParsedStreamEvent.StatusCreated(status);
        MastodonApiEvent.StreamEvent streamEvent = new MastodonApiEvent.StreamEvent(created, List.of());

        // Act
        callback.onEvent(streamEvent);

        // Assert: toot was dropped — no cache write
        verify(messageCache, never()).recordThenPublish(any(PrincipalKey.class), anyString(), any(CacheEntry.class));
        // SSRF gate must not have been reached either — no HEAD request
        verify(restTemplate, never()).headForHeaders(anyString());

        // Log hygiene: no raw UUID or raw hashtag in any log line
        assertNoRawUuid(callbackAppender.list, CANARY_UUID);
        assertNoRawHashtag(callbackAppender.list, CANARY_HASHTAG);
    }

    // -------------------------------------------------------------------------
    // UT-sec-04b: Typed StatusEdited without bot mention is dropped
    // -------------------------------------------------------------------------

    /**
     * UT-sec-04b: {@code processStatusEditedEvent} with a typed {@link ParsedStreamEvent.StatusEdited}
     * whose status has NO bot mention must be dropped.
     *
     * <p>Proves that the opt-in invariant is enforced on the edit path as well (ADR-PT-A04-01).
     *
     * <p>Arrange: {@link Status} mock without bot mention. No HEAD request is expected
     * (opt-in check fires first). Act: {@code onEvent} processes the StreamEvent.
     * Assert: {@code messageCache.recordThenPublish} is NEVER called.
     */
    @Test
    void typedStatusEdited_withoutBotMention_isDropped() {
        // Arrange
        SubscriptionManager subscriptionManager = mock(SubscriptionManager.class);
        MessageCache messageCache = mock(MessageCache.class);
        ShareViewStompRelay shareViewStompRelay = mock(ShareViewStompRelay.class);
        RestTemplate restTemplate = mock(RestTemplate.class);

        Status status = mock(Status.class);
        Status.Mention nonBotMention = mock(Status.Mention.class);
        when(nonBotMention.getAcct()).thenReturn("bob");
        when(status.getMentions()).thenReturn(List.of(nonBotMention));
        when(status.getUrl()).thenReturn(CANARY_URL);
        when(status.getId()).thenReturn("toot-se-01");

        StompCallback callback = new StompCallback(
                subscriptionManager, messageCache, shareViewStompRelay, restTemplate,
                PERMISSIVE_VALIDATOR, CANARY_UUID, CANARY_HASHTAG, BOT_FULL_HANDLE, "glacier.events");

        ParsedStreamEvent.StatusEdited edited = new ParsedStreamEvent.StatusEdited(status);
        MastodonApiEvent.StreamEvent streamEvent = new MastodonApiEvent.StreamEvent(edited, List.of());

        // Act
        callback.onEvent(streamEvent);

        // Assert: opt-in check drops the event — no cache write and no SSRF check
        verify(messageCache, never()).recordThenPublish(any(PrincipalKey.class), anyString(), any(CacheEntry.class));
        verify(restTemplate, never()).headForHeaders(anyString());
    }

    // -------------------------------------------------------------------------
    // UT-sec-04c: Structural regression guard — both paths enforce isOptedIn
    // -------------------------------------------------------------------------

    /**
     * UT-sec-04c: structural regression guard confirming that BOTH the typed
     * ({@code StatusCreated} and {@code StatusEdited}) and the generic ({@code GenericMessage})
     * event handlers enforce the opt-in check via the {@code isOptedIn} helper.
     *
     * <p>Sends one GenericMessage "update" event AND one StreamEvent/StatusCreated event,
     * both WITHOUT bot mention and both with permissive SSRF and loadable embed headers.
     * Asserts that {@code messageCache.recordThenPublish} is NEVER called for either event —
     * confirming no path can bypass the opt-in gate.
     *
     * <p>This test is the structural regression guard: if any future refactoring removes the
     * opt-in check from a path, this test will fail by observing a cache write where none is
     * expected.
     */
    @Test
    void allEventHandlers_callIsOptedIn_structurally() throws Exception {
        // Arrange
        SubscriptionManager subscriptionManager = mock(SubscriptionManager.class);
        MessageCache messageCache = mock(MessageCache.class);
        ShareViewStompRelay shareViewStompRelay = mock(ShareViewStompRelay.class);
        RestTemplate restTemplate = mock(RestTemplate.class);

        // Permissive embed headers so only opt-in gate can cause a drop
        HttpHeaders httpHeaders = new HttpHeaders();
        when(restTemplate.headForHeaders(anyString())).thenReturn(httpHeaders);

        StompCallback callback = new StompCallback(
                subscriptionManager, messageCache, shareViewStompRelay, restTemplate,
                PERMISSIVE_VALIDATOR, CANARY_UUID, CANARY_HASHTAG, BOT_FULL_HANDLE, "glacier.events");

        // --- GenericMessage path: mention is non-bot ---
        ObjectMapper mapper = new ObjectMapper();
        Mention otherMention = Mention.builder().id("3").username("carol").acct("carol").build();
        GenericMessageContentPayload genericPayload = GenericMessageContentPayload.builder()
                .id("toot-gc-struct")
                .url(CANARY_URL)
                .mentions(List.of(otherMention))
                .build();
        GenericMessageContent content = GenericMessageContent.builder()
                .event("update")
                .stream(List.of("hashtag"))
                .payload(TextNode.valueOf(mapper.writeValueAsString(genericPayload)))
                .build();
        MastodonApiEvent.GenericMessage genericMsg = mock(MastodonApiEvent.GenericMessage.class);
        when(genericMsg.getText()).thenReturn(mapper.writeValueAsString(content));

        // --- Typed StatusCreated path: mention is non-bot ---
        Status typedStatus = mock(Status.class);
        Status.Mention nonBot = mock(Status.Mention.class);
        when(nonBot.getAcct()).thenReturn("dave");
        when(typedStatus.getMentions()).thenReturn(List.of(nonBot));
        when(typedStatus.getUrl()).thenReturn(CANARY_URL);
        when(typedStatus.getId()).thenReturn("toot-tc-struct");
        ParsedStreamEvent.StatusCreated created = new ParsedStreamEvent.StatusCreated(typedStatus);
        MastodonApiEvent.StreamEvent streamEvent = new MastodonApiEvent.StreamEvent(created, List.of());

        // Act: send both events
        callback.onEvent(genericMsg);
        callback.onEvent(streamEvent);

        // Assert: neither event reached the cache — opt-in check fired on both paths
        verify(messageCache, never()).recordThenPublish(any(PrincipalKey.class), anyString(), any(CacheEntry.class));
    }

    // -------------------------------------------------------------------------
    // UT-sec-04d: Source-inspection guard — all event-handler paths call isOptedIn
    // -------------------------------------------------------------------------

    /**
     * UT-sec-04d: structural regression guard via source text inspection.
     *
     * <p>SR-OI-02 requires that "test verifies via reflection that all paths call {@code isOptedIn}".
     * Reads {@code StompCallback.java} from the source tree (located relative to the compiled
     * class output) and asserts that each of the three event-handling methods that carry toot
     * content — {@code processStatusCreatedEvent}, {@code processStatusEditedEvent}, and
     * {@code sendMessage} — contains a call to {@code isOptedIn(} within the method body.
     *
     * <p>Note: {@code procesStatusDeletedEvent} is intentionally exempt because deletion events
     * carry only a status ID (no toot URL, no content to gate) — the opt-in check is not
     * semantically meaningful there.
     *
     * <p>A future refactor that removes {@code isOptedIn} from one of the three methods will
     * cause this test to fail even if the behavioral tests (UT-sec-04, UT-sec-04b, UT-sec-04c)
     * still pass (e.g. because a new helper indirection is introduced that does not delegate
     * back to {@code isOptedIn}).
     *
     * <p>Standard: SR-OI-02 / A06:2021.
     */
    @Test
    void allEventHandlerMethods_invokeIsOptedIn_bySourceInspection() throws Exception {
        // Locate StompCallback.class output and resolve the source file relative to it.
        // Maven convention: getCodeSource().getLocation() returns the root of the compiled
        // output directory, i.e. "target/classes/" — two levels up from there is the
        // project root, and then src/main/java/... resolves to the source file.
        URL classLocation = StompCallback.class.getProtectionDomain().getCodeSource().getLocation();
        Path src = Paths.get(classLocation.toURI())
                .resolve("../../src/main/java/de/seism0saurus/glacier/mastodon/StompCallback.java")
                .normalize();

        assertThat(src.toFile())
                .as("UT-sec-04d: StompCallback.java source file must be present at %s", src)
                .exists();

        String body = Files.readString(src);

        // These three methods process toot content and MUST invoke isOptedIn before publishing.
        // procesStatusDeletedEvent carries only a status ID — no content gate needed (exempt).
        //
        // Strategy: find the method DEFINITION (i.e. "private void methodName(" or
        // "private void sendMessage(") rather than any call site, then scan the method body
        // for isOptedIn(. This ensures we check the definition region, not a switch-case
        // dispatch that may occur earlier in the file.
        for (String method : List.of("processStatusCreatedEvent", "processStatusEditedEvent", "sendMessage")) {
            // Look for the private method definition — "private void processStatusCreatedEvent("
            // or "private void sendMessage(" — to skip over call sites.
            String defSignature = "private void " + method + "(";
            int methodStart = body.indexOf(defSignature);
            assertThat(methodStart)
                    .as("UT-sec-04d: private void '%s(' definition must exist in StompCallback.java (SR-OI-02)",
                            method)
                    .isGreaterThan(0);

            // Scan up to 4000 characters after the method definition for an isOptedIn call.
            // 4000 characters comfortably covers even the longest method body in this file.
            String methodRegion = body.substring(methodStart, Math.min(methodStart + 4000, body.length()));
            assertThat(methodRegion)
                    .as("UT-sec-04d: method '%s' must call isOptedIn(...) before processing the event "
                            + "(SR-OI-02 — structural guard: a refactor removing the opt-in gate "
                            + "from this path must not go undetected)", method)
                    .contains("isOptedIn(");
        }
    }

    // -------------------------------------------------------------------------
    // T-empty-shorthandle: getShortHandle rejects handles that yield an empty short part
    // -------------------------------------------------------------------------

    /**
     * T-empty-shorthandle-01: {@code getShortHandle("@@server.example")} must throw
     * {@link IllegalArgumentException}.
     *
     * <p>After stripping the leading {@code @}, the remaining string is {@code "@server.example"}.
     * {@code indexOf('@')} returns {@code 0}, so {@code substring(0, 0)} produces an empty string.
     * The downstream opt-in check uses {@code shortHandle.equals(mention.getAcct())} — an empty
     * shortHandle never equals any real Mastodon acct, so opt-in defaults to {@code false} (fail-
     * closed). However, the empty shortHandle is still invalid as an operator-configured identity
     * and must be rejected early (C3, ASVS V2.1, WSTG-INPV-01, SR-NEW-02).
     */
    @Test
    void getShortHandle_doubleAtPrefix_throwsIllegalArgumentException() {
        // Arrange & Act & Assert — constructor exercises getShortHandle via this.shortHandle = getShortHandle(handle)
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> new StompCallback(
                        mock(SubscriptionManager.class),
                        mock(MessageCache.class),
                        mock(ShareViewStompRelay.class),
                        mock(RestTemplate.class),
                        PERMISSIVE_VALIDATOR,
                        CANARY_UUID, CANARY_HASHTAG,
                        "@@server.example",   // T-empty-shorthandle-01: double-@ handle
                        "glacier.events"),
                "T-empty-shorthandle-01: handle '@@server.example' must cause getShortHandle to throw "
                        + "because stripping the leading @ leaves '@server.example', "
                        + "whose indexOf('@') == 0 produces an empty short handle (SR-NEW-02)");
    }

    /**
     * T-empty-shorthandle-02: {@code getShortHandle("@@")} must throw
     * {@link IllegalArgumentException}.
     *
     * <p>Minimal double-@ edge case: after stripping the leading {@code @}, the remaining
     * string is {@code "@"} — {@code indexOf('@')} returns {@code 0}, yielding an empty
     * short handle (SR-NEW-02, C3, ASVS V2.1).
     */
    @Test
    void getShortHandle_doubleAtOnly_throwsIllegalArgumentException() {
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> new StompCallback(
                        mock(SubscriptionManager.class),
                        mock(MessageCache.class),
                        mock(ShareViewStompRelay.class),
                        mock(RestTemplate.class),
                        PERMISSIVE_VALIDATOR,
                        CANARY_UUID, CANARY_HASHTAG,
                        "@@",                 // T-empty-shorthandle-02: bare double-@
                        "glacier.events"),
                "T-empty-shorthandle-02: handle '@@' must throw because the short-handle part is empty");
    }

    /**
     * T-empty-shorthandle-03: {@code getShortHandle("@")} must throw
     * {@link IllegalArgumentException}.
     *
     * <p>A single {@code @} contains no server part. The existing guard for
     * {@code !tmpHandle.contains("@")} fires AFTER stripping the leading {@code @},
     * meaning the stripped result {@code ""} does not contain {@code @} at all —
     * so the existing guard already fires here. This test confirms that invariant
     * is preserved by the SR-NEW-02 implementation (SR-NEW-02, ASVS V2.1).
     */
    @Test
    void getShortHandle_singleAt_throwsIllegalArgumentException() {
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> new StompCallback(
                        mock(SubscriptionManager.class),
                        mock(MessageCache.class),
                        mock(ShareViewStompRelay.class),
                        mock(RestTemplate.class),
                        PERMISSIVE_VALIDATOR,
                        CANARY_UUID, CANARY_HASHTAG,
                        "@",                  // T-empty-shorthandle-03: single @ (no server part)
                        "glacier.events"),
                "T-empty-shorthandle-03: handle '@' must throw (no server part at all)");
    }

    /**
     * T-empty-shorthandle-04: {@code getShortHandle("")} must throw
     * {@link IllegalArgumentException}.
     *
     * <p>Empty string is not a valid Mastodon handle — it has no leading {@code @},
     * no name part, and no server part. Must be rejected at boundary validation
     * (SR-NEW-02, C3, ASVS V2.1, fail-fast principle).
     */
    @Test
    void getShortHandle_emptyString_throwsIllegalArgumentException() {
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> new StompCallback(
                        mock(SubscriptionManager.class),
                        mock(MessageCache.class),
                        mock(ShareViewStompRelay.class),
                        mock(RestTemplate.class),
                        PERMISSIVE_VALIDATOR,
                        CANARY_UUID, CANARY_HASHTAG,
                        "",                   // T-empty-shorthandle-04: blank handle
                        "glacier.events"),
                "T-empty-shorthandle-04: empty handle must throw (not a valid Mastodon handle)");
    }

    /**
     * T-empty-shorthandle-05: the {@link IllegalArgumentException} message must NOT contain
     * the input string (CWE-117 — log injection prevention).
     *
     * <p>Echoing attacker-controlled input into exception messages creates a log injection
     * vector when the exception is caught and logged (D-13 / SR-8 / CWE-117). The message
     * must be a static, developer-authored string.
     *
     * <p>Uses the {@code "@@server.example"} input because it is the canonical empty-short-handle
     * edge case that SR-NEW-02 was designed to guard against.
     */
    @Test
    void getShortHandle_exceptionMessage_doesNotContainInputString() {
        // Arrange
        String maliciousHandle = "@@server.example";

        // Act
        IllegalArgumentException caught = null;
        try {
            new StompCallback(
                    mock(SubscriptionManager.class),
                    mock(MessageCache.class),
                    mock(ShareViewStompRelay.class),
                    mock(RestTemplate.class),
                    PERMISSIVE_VALIDATOR,
                    CANARY_UUID, CANARY_HASHTAG,
                    maliciousHandle,
                    "glacier.events");
        } catch (IllegalArgumentException ex) {
            caught = ex;
        }

        // Assert: exception must be thrown
        assertThat(caught)
                .as("T-empty-shorthandle-05: StompCallback constructor with '@@server.example' must throw IAE (SR-NEW-02)")
                .isNotNull();

        // Assert: message must NOT echo the input (CWE-117 — log injection prevention)
        assertThat(caught.getMessage())
                .as("T-empty-shorthandle-05 (CWE-117, D-13, SR-8): IAE message must be a static developer string "
                        + "and must not contain the attacker-controlled input '@@server.example'. "
                        + "Echoing user input into exception messages creates a log injection vector.")
                .doesNotContain(maliciousHandle);
    }

    // -------------------------------------------------------------------------
    // Log-hygiene helper methods (same pattern as RawWallIdLogHygieneTest)
    // -------------------------------------------------------------------------

    /** UUID regex pattern — used to detect raw wallId in log lines. */
    private static final Pattern UUID_PATTERN =
            Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}",
                    Pattern.CASE_INSENSITIVE);

    /**
     * Asserts that the raw UUID does not appear in any formatted message or argument array
     * of the captured log events (D-13 / SR-8 / CWE-117).
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
     * Asserts that the raw hashtag does not appear in any formatted message or argument array
     * of the captured log events (D-13 / SR-8).
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

    /**
     * Asserts that the raw URL does not appear in any formatted message or argument array
     * of the captured log events (D-13 / SR-8 / CWE-117).
     */
    private static void assertNoRawUrl(List<ILoggingEvent> events, String url) {
        for (ILoggingEvent event : events) {
            assertThat(event.getFormattedMessage())
                    .as("Raw URL must not appear in log message")
                    .doesNotContain(url);
            Object[] args = event.getArgumentArray();
            if (args != null) {
                for (Object arg : args) {
                    assertThat(String.valueOf(arg))
                            .as("Raw URL must not appear in log argument")
                            .doesNotContain(url);
                }
            }
        }
    }
}
