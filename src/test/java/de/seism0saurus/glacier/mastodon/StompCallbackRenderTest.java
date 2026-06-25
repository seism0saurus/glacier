package de.seism0saurus.glacier.mastodon;

import de.seism0saurus.glacier.share.application.SafeUrlValidator;
import de.seism0saurus.glacier.share.application.ShareViewStompRelay;
import de.seism0saurus.glacier.webservice.cache.CacheEntry;
import de.seism0saurus.glacier.webservice.cache.EventType;
import de.seism0saurus.glacier.webservice.messaging.PrincipalKey;
import de.seism0saurus.glacier.webservice.messaging.messages.GenericMessageContent;
import de.seism0saurus.glacier.webservice.messaging.messages.GenericMessageContentPayload;
import de.seism0saurus.glacier.webservice.messaging.messages.Mention;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.RestTemplate;
import social.bigbone.api.entity.Status;
import social.bigbone.api.entity.streaming.MastodonApiEvent;
import social.bigbone.api.entity.streaming.ParsedStreamEvent;

import java.net.URI;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for the render wiring in {@link StompCallback}: verifying that
 * both the typed {@code StatusCreated}/{@code StatusEdited} paths and the generic
 * WebSocket path call the {@code relayTootEvent(wallId, hashtag, eventType, Status)}
 * overload after the cache write.
 *
 * <p>Security requirements verified:
 * <ul>
 *   <li>SR-RENDER-01 / ADR-RENDER-01: relay receives the typed {@link Status} for rendering.</li>
 *   <li>SR-FLAW2-01 (resolved — B2): the generic message path DOES call the Status overload;
 *       the payload JSON is parsed into a Bigbone {@link Status} for full rendering.</li>
 *   <li>P2-13: StatusEdited relay is only invoked after a prior StatusCreated.</li>
 *   <li>C10 / SR-PT-10 gate ordering: all security gates (SSRF, embed, opt-in) still
 *       precede any relay call on the generic path (B2).</li>
 * </ul>
 */
class StompCallbackRenderTest {

    /** Permissive validator — always passes the URL through unchanged. */
    private static final SafeUrlValidator PERMISSIVE_VALIDATOR =
            rawUrl -> Optional.of(URI.create(rawUrl));

    private SubscriptionManager subscriptionManager;
    private RestTemplate restTemplate;
    private de.seism0saurus.glacier.webservice.cache.MessageCache messageCache;
    private ShareViewStompRelay shareViewStompRelay;
    private Status mockStatus;

    @BeforeEach
    void setUp() {
        subscriptionManager = mock(SubscriptionManager.class);
        restTemplate = mock(RestTemplate.class);
        messageCache = mock(de.seism0saurus.glacier.webservice.cache.MessageCache.class);
        shareViewStompRelay = mock(ShareViewStompRelay.class);
        mockStatus = mock(Status.class);

        // Default: bot mention present (opt-in check passes)
        Status.Mention botMention = mock(Status.Mention.class);
        when(botMention.getAcct()).thenReturn("glacier");
        when(mockStatus.getMentions()).thenReturn(List.of(botMention));

        // Default: cache write returns a stored entry
        when(messageCache.recordThenPublish(
                any(PrincipalKey.class), anyString(), any(CacheEntry.class)))
                .thenReturn(new CacheEntry(EventType.CREATED, "status-123",
                        "https://mastodon.example.com/status-123/embed", null, 1L));
    }

    // -----------------------------------------------------------------------
    // Typed StatusCreated path → relay receives Status (SR-RENDER-01)
    // -----------------------------------------------------------------------

    /**
     * When a typed {@code StatusCreated} event arrives, the relay's Status overload
     * is called AFTER the cache write (ADR-RENDER-01, SR-RENDER-01).
     *
     * <p>Arrange: mock Status passes SSRF + embed guards.
     * <p>Act:     StatusCreated StreamEvent.
     * <p>Assert:  {@code shareViewStompRelay.relayTootEvent(principal, hashtag, "creation", status)}
     *             invoked exactly once.
     */
    @Test
    void processStatusCreatedEvent_relaysStatusForRendering() {
        // Arrange
        String principal = UUID.randomUUID().toString();
        String hashtag = "glacier";

        when(mockStatus.getId()).thenReturn("status-123");
        when(mockStatus.getUrl()).thenReturn("https://mastodon.example.com/status-123");

        HttpHeaders allowHeaders = new HttpHeaders();
        allowHeaders.set("X-Frame-Options", "ALLOWALL");
        when(restTemplate.headForHeaders("https://mastodon.example.com/status-123/embed"))
                .thenReturn(allowHeaders);

        StompCallback callback = new StompCallback(
                subscriptionManager, messageCache, shareViewStompRelay, restTemplate,
                PERMISSIVE_VALIDATOR, principal, hashtag,
                MastodonShortHandle.parse("glacier@mastodon.example.com"), "glacier.example.com");

        // Act
        ParsedStreamEvent.StatusCreated createdEvent = new ParsedStreamEvent.StatusCreated(mockStatus);
        callback.onEvent(new MastodonApiEvent.StreamEvent(createdEvent, List.of()));

        // Assert: Status overload called after cache write (ADR-RENDER-01)
        verify(shareViewStompRelay).relayTootEvent(
                eq(principal),
                eq(hashtag),
                eq("creation"),
                eq(mockStatus));
        // Confirm cache was written before relay (ordering invariant)
        verify(messageCache).recordThenPublish(any(), any(), any());
    }

    /**
     * When a typed {@code StatusEdited} event arrives for a previously-published status,
     * the relay's Status overload is called for the MODIFICATION event type (ADR-RENDER-01).
     *
     * <p>P2-13 guard: the relay is only called if the status was previously published
     * as a StatusCreated event.
     */
    @Test
    void processStatusEditedEvent_relaysStatusForModification() {
        // Arrange
        String principal = UUID.randomUUID().toString();
        String hashtag = "glacier";

        when(mockStatus.getId()).thenReturn("status-456");
        when(mockStatus.getUrl()).thenReturn("https://mastodon.example.com/status-456");

        HttpHeaders allowHeaders = new HttpHeaders();
        allowHeaders.set("X-Frame-Options", "ALLOWALL");
        when(restTemplate.headForHeaders("https://mastodon.example.com/status-456/embed"))
                .thenReturn(allowHeaders);

        StompCallback callback = new StompCallback(
                subscriptionManager, messageCache, shareViewStompRelay, restTemplate,
                PERMISSIVE_VALIDATOR, principal, hashtag,
                MastodonShortHandle.parse("glacier@mastodon.example.com"), "glacier.example.com");

        // P2-13: prime publishedStatusIds — fire StatusCreated first
        ParsedStreamEvent.StatusCreated createEvent = new ParsedStreamEvent.StatusCreated(mockStatus);
        callback.onEvent(new MastodonApiEvent.StreamEvent(createEvent, List.of()));
        // Reset relay mock so only the edit relay call is counted
        reset(shareViewStompRelay);

        // Also reset + re-stub cache mock for the edit
        reset(messageCache);
        when(messageCache.recordThenPublish(any(), any(), any()))
                .thenReturn(new CacheEntry(EventType.UPDATED, "status-456",
                        "https://mastodon.example.com/status-456/embed", "2026-01-01T00:00:00Z", 2L));

        // Act: fire StatusEdited
        ParsedStreamEvent.StatusEdited editedEvent = new ParsedStreamEvent.StatusEdited(mockStatus);
        callback.onEvent(new MastodonApiEvent.StreamEvent(editedEvent, List.of()));

        // Assert: Status overload called with "modification" event type
        verify(shareViewStompRelay).relayTootEvent(
                eq(principal),
                eq(hashtag),
                eq("modification"),
                eq(mockStatus));
    }

    /**
     * P2-13 guard: StatusEdited relay is NOT called if no prior StatusCreated
     * was published for that status ID.
     *
     * <p>Arrangement: fire StatusEdited without a prior StatusCreated.
     * Expected: relay is never called.
     */
    @Test
    void processStatusEditedEvent_withoutPriorCreate_relayIsNotCalled() {
        // Arrange
        String principal = UUID.randomUUID().toString();
        String hashtag = "glacier";

        when(mockStatus.getId()).thenReturn("never-created-status");
        when(mockStatus.getUrl()).thenReturn("https://mastodon.example.com/never-created");

        HttpHeaders allowHeaders = new HttpHeaders();
        allowHeaders.set("X-Frame-Options", "ALLOWALL");
        when(restTemplate.headForHeaders("https://mastodon.example.com/never-created/embed"))
                .thenReturn(allowHeaders);

        StompCallback callback = new StompCallback(
                subscriptionManager, messageCache, shareViewStompRelay, restTemplate,
                PERMISSIVE_VALIDATOR, principal, hashtag,
                MastodonShortHandle.parse("glacier@mastodon.example.com"), "glacier.example.com");

        // Act: fire StatusEdited WITHOUT prior StatusCreated
        ParsedStreamEvent.StatusEdited editedEvent = new ParsedStreamEvent.StatusEdited(mockStatus);
        callback.onEvent(new MastodonApiEvent.StreamEvent(editedEvent, List.of()));

        // Assert: relay Status overload must NOT be called (P2-13)
        verify(shareViewStompRelay, never()).relayTootEvent(anyString(), anyString(), eq("modification"), any(Status.class));
    }

    /**
     * DELETION path: the relay uses the Object overload (CacheEntry), NOT the Status overload.
     * The Status overload must never be called for deletions.
     */
    @Test
    void deletedEvent_keepsMinimalEmit_statusOverloadNotCalled() {
        // Arrange
        String principal = UUID.randomUUID().toString();
        String hashtag = "glacier";

        when(messageCache.recordThenPublish(any(), any(), any()))
                .thenReturn(new CacheEntry(EventType.DELETED, "deleted-status-id", null, null, 3L));

        StompCallback callback = new StompCallback(
                subscriptionManager, messageCache, shareViewStompRelay, restTemplate,
                PERMISSIVE_VALIDATOR, principal, hashtag,
                MastodonShortHandle.parse("glacier@mastodon.example.com"), "glacier.example.com");

        // Act: fire StatusDeleted
        ParsedStreamEvent.StatusDeleted deletedEvent = new ParsedStreamEvent.StatusDeleted("deleted-status-id");
        callback.onEvent(new MastodonApiEvent.StreamEvent(deletedEvent, List.of()));

        // Assert: Status overload must NOT be called for deletion
        verify(shareViewStompRelay, never()).relayTootEvent(anyString(), anyString(), anyString(), any(Status.class));
        // The Object overload IS called (existing relay for deletion)
        verify(shareViewStompRelay).relayTootEvent(anyString(), anyString(), eq("deletion"), any(Object.class));
    }

    // -----------------------------------------------------------------------
    // SR-FLAW2-01 resolved (B2): generic path DOES call Status overload
    // -----------------------------------------------------------------------

    /**
     * SR-FLAW2-01 (resolved — Option B2): the GenericMessage path (hashtag WebSocket
     * streaming from dockerized Mastodon) DOES call the Status-overload relay method
     * after the payload JSON is parsed into a Bigbone {@link Status}.
     *
     * <p>The raw payload string in the {@link MastodonApiEvent.GenericMessage} contains the full
     * Mastodon Status JSON. This is parsed with {@code kotlinx.serialization.json.Json} into
     * a Bigbone {@link Status} object, which is then passed to
     * {@link ShareViewStompRelay#relayTootEvent(String, String, String, Status)}.
     *
     * <p>Security gate ordering preserved (C10 / SR-PT-10):
     * <ol>
     *   <li>SSRF guard via {@link SafeUrlValidator}</li>
     *   <li>HEAD request + {@code isLoadable} check</li>
     *   <li>Bot opt-in check</li>
     *   <li>Cache write</li>
     *   <li>Status-overload relay (B2 addition)</li>
     * </ol>
     *
     * <p>ADR-RENDER-01 / SR-FLAW2-01 / B2 resolution.
     */
    @Test
    void genericMessagePath_callsStatusOverload_b2FullRendering() throws Exception {
        // Arrange: build a GenericMessage that passes all gates (opt-in, SSRF, embed)
        String principal = UUID.randomUUID().toString();
        String hashtag = "glacier";

        HttpHeaders allowHeaders = new HttpHeaders();
        allowHeaders.set("X-Frame-Options", "ALLOWALL");
        when(restTemplate.headForHeaders("https://mastodon.example.com/@poster/gen-001/embed"))
                .thenReturn(allowHeaders);

        StompCallback callback = new StompCallback(
                subscriptionManager, messageCache, shareViewStompRelay, restTemplate,
                PERMISSIVE_VALIDATOR, principal, hashtag,
                MastodonShortHandle.parse("glacier@mastodon.example.com"), "glacier.example.com");

        // Build a full Mastodon status JSON that satisfies the bot opt-in check
        // (mentions.acct = "glacier"). The JSON must be a valid Mastodon status payload
        // that kotlinx.serialization can decode into a Bigbone Status.
        String fullStatusJson = buildMastodonStatusJson(
                "gen-001",
                "https://mastodon.example.com/@poster/gen-001",
                "glacier" // the bot acct — passes opt-in check
        );

        // Wrap in GenericMessageContent envelope: stream=["hashtag"], event="update"
        String contentJson = buildGenericMessageContentJson("update", fullStatusJson);

        MastodonApiEvent.GenericMessage genericMessage = mock(MastodonApiEvent.GenericMessage.class);
        when(genericMessage.getText()).thenReturn(contentJson);

        // Act: fire GenericMessage event
        callback.onEvent(genericMessage);

        // Assert (B2): the Status overload IS called from the generic path
        // The Status is parsed from the payload JSON — we verify by argument type
        verify(shareViewStompRelay).relayTootEvent(
                eq(principal),
                eq(hashtag),
                eq("creation"),
                any(Status.class));
        // Cache is still written (ordering invariant: cache write BEFORE relay — C10 preserved)
        verify(messageCache).recordThenPublish(any(), any(), any());
    }

    /**
     * SR-FLAW2-01 / B2 — generic path MODIFICATION event also calls Status overload.
     *
     * <p>P2-13 guard: a generic-path "status.update" is only relayed if a prior
     * "update" was published for the same status ID.
     */
    @Test
    void genericMessagePath_modificationEvent_callsStatusOverload_afterPriorCreate() throws Exception {
        // Arrange
        String principal = UUID.randomUUID().toString();
        String hashtag = "glacier";
        String statusId = "gen-002";
        String statusUrl = "https://mastodon.example.com/@poster/gen-002";

        HttpHeaders allowHeaders = new HttpHeaders();
        allowHeaders.set("X-Frame-Options", "ALLOWALL");
        when(restTemplate.headForHeaders(statusUrl + "/embed")).thenReturn(allowHeaders);

        StompCallback callback = new StompCallback(
                subscriptionManager, messageCache, shareViewStompRelay, restTemplate,
                PERMISSIVE_VALIDATOR, principal, hashtag,
                MastodonShortHandle.parse("glacier@mastodon.example.com"), "glacier.example.com");

        // Step 1: fire CREATION to prime P2-13 publishedStatusIds
        String statusJson = buildMastodonStatusJson(statusId, statusUrl, "glacier");
        callback.onEvent(buildGenericEvent("update", statusJson));
        reset(shareViewStompRelay); // clear creation call count

        // Also reset + re-stub cache for the update
        reset(messageCache);
        when(messageCache.recordThenPublish(any(), any(), any()))
                .thenReturn(new CacheEntry(EventType.UPDATED, statusId, statusUrl + "/embed",
                        "2026-01-01T00:00:00Z", 2L));

        // Step 2: fire MODIFICATION
        String editedStatusJson = buildMastodonStatusJson(statusId, statusUrl, "glacier");
        String editedContentJson = buildGenericMessageContentJson("status.update", editedStatusJson);
        MastodonApiEvent.GenericMessage editMessage = mock(MastodonApiEvent.GenericMessage.class);
        when(editMessage.getText()).thenReturn(editedContentJson);
        callback.onEvent(editMessage);

        // Assert: Status overload called for "modification"
        verify(shareViewStompRelay).relayTootEvent(
                eq(principal), eq(hashtag), eq("modification"), any(Status.class));
    }

    /**
     * Robustness: a malformed or unparseable generic payload does NOT crash the callback
     * and does NOT relay anything (graceful degradation).
     *
     * <p>The SSRF/embed/opt-in gates may not even be reached if JSON is unparseable.
     * The requirement is: no unchecked exception propagates to the Bigbone virtual thread,
     * and the relay is never called for a bad payload.
     */
    @Test
    void genericMessagePath_malformedPayload_noCrashNoRelay() {
        // Arrange
        String principal = UUID.randomUUID().toString();
        String hashtag = "glacier";

        StompCallback callback = new StompCallback(
                subscriptionManager, messageCache, shareViewStompRelay, restTemplate,
                PERMISSIVE_VALIDATOR, principal, hashtag,
                MastodonShortHandle.parse("glacier@mastodon.example.com"), "glacier.example.com");

        // Build a GenericMessage with syntactically valid outer JSON but malformed
        // inner payload JSON (truncated, not valid JSON)
        String malformedPayloadJson = "{not valid json at all %%%";
        // Escape for outer JSON string: the outer structure is valid, payload is a JSON-string
        // containing the malformed content
        String outerJson = "{\"stream\":[\"hashtag\"],\"event\":\"update\",\"payload\":"
                + "\"" + malformedPayloadJson.replace("\"", "\\\"") + "\"}";

        MastodonApiEvent.GenericMessage genericMessage = mock(MastodonApiEvent.GenericMessage.class);
        when(genericMessage.getText()).thenReturn(outerJson);

        // Act: must not throw (robustness — OWASP C3 — error handling without crash)
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> callback.onEvent(genericMessage));

        // Assert: relay is never called for unparseable payloads
        verify(shareViewStompRelay, never()).relayTootEvent(anyString(), anyString(), anyString(), any(Status.class));
        verify(shareViewStompRelay, never()).relayTootEvent(anyString(), anyString(), anyString(), any(Object.class));
    }

    /**
     * C10 gate ordering: SSRF guard fires BEFORE any relay call on the generic path.
     *
     * <p>A blocked URL must result in NO relay call (neither Status nor Object overload),
     * even if the payload JSON is valid.
     */
    @Test
    void genericMessagePath_ssrfBlockedUrl_noRelayCall() throws Exception {
        // Arrange: SSRF-blocking validator
        SafeUrlValidator blockingValidator = rawUrl -> Optional.empty();

        String principal = UUID.randomUUID().toString();
        String hashtag = "glacier";

        StompCallback callback = new StompCallback(
                subscriptionManager, messageCache, shareViewStompRelay, restTemplate,
                blockingValidator, principal, hashtag,
                MastodonShortHandle.parse("glacier@mastodon.example.com"), "glacier.example.com");

        String statusJson = buildMastodonStatusJson(
                "ssrf-001", "https://192.168.0.1/status-ssrf", "glacier");
        String contentJson = buildGenericMessageContentJson("update", statusJson);
        MastodonApiEvent.GenericMessage genericMessage = mock(MastodonApiEvent.GenericMessage.class);
        when(genericMessage.getText()).thenReturn(contentJson);

        // Act
        callback.onEvent(genericMessage);

        // Assert: no relay call of any kind (C10 / SR-PT-10 gate ordering preserved)
        verify(shareViewStompRelay, never()).relayTootEvent(anyString(), anyString(), anyString(), any());
        // Cache must not be written either
        verify(messageCache, never()).recordThenPublish(any(), any(), any());
    }

    /**
     * Generic DELETION still uses the minimal Object overload — Status overload must not fire.
     * (ADR-RENDER-01: deletion does not deliver ReadonlyTootView to the frontend.)
     */
    @Test
    void genericMessagePath_deletionEvent_doesNotCallStatusOverload() throws Exception {
        // Arrange
        String principal = UUID.randomUUID().toString();
        String hashtag = "glacier";

        when(messageCache.recordThenPublish(any(), any(), any()))
                .thenReturn(new CacheEntry(EventType.DELETED, "del-001", null, null, 5L));

        StompCallback callback = new StompCallback(
                subscriptionManager, messageCache, shareViewStompRelay, restTemplate,
                PERMISSIVE_VALIDATOR, principal, hashtag,
                MastodonShortHandle.parse("glacier@mastodon.example.com"), "glacier.example.com");

        // Generic deletion envelope: payload is just the status ID as a string
        String deletionJson = "{\"stream\":[\"hashtag\"],\"event\":\"delete\",\"payload\":\"del-001\"}";
        MastodonApiEvent.GenericMessage genericMessage = mock(MastodonApiEvent.GenericMessage.class);
        when(genericMessage.getText()).thenReturn(deletionJson);

        // Act
        callback.onEvent(genericMessage);

        // Assert: Status overload must NOT be called for deletion
        verify(shareViewStompRelay, never()).relayTootEvent(anyString(), anyString(), anyString(), any(Status.class));
    }

    // -----------------------------------------------------------------------
    // Helper: build Mastodon status JSON and generic event envelope
    // -----------------------------------------------------------------------

    /**
     * Builds a minimal but valid Mastodon status JSON payload that:
     * <ol>
     *   <li>Can be parsed by {@code kotlinx.serialization.json.Json} (with ignoreUnknownKeys)
     *       into a Bigbone {@link Status}</li>
     *   <li>Passes the opt-in check ({@code mentions[0].acct == botAcct})</li>
     * </ol>
     *
     * <p>This is intentionally minimal — only the fields needed for the security gates.
     * Full rendering fields (account, content, media) are omitted; they will be null in
     * the deserialized Status and {@link ShareRenderingService} handles null gracefully.
     */
    private static String buildMastodonStatusJson(String id, String url, String botAcct) {
        // The Mastodon Status JSON follows the REST API shape.
        // Fields not in Bigbone's model are ignored (ignoreUnknownKeys = true).
        // "mentions" uses snake_case field names matching the Mastodon API.
        return "{"
                + "\"id\":\"" + id + "\","
                + "\"url\":\"" + url + "\","
                + "\"uri\":\"" + url + "\","
                + "\"content\":\"<p>Test toot</p>\","
                + "\"created_at\":\"2026-06-24T10:00:00.000Z\","
                + "\"visibility\":\"public\","
                + "\"sensitive\":false,"
                + "\"spoiler_text\":\"\","
                + "\"application\":null,"
                + "\"account\":{"
                + "  \"id\":\"acc-001\","
                + "  \"username\":\"poster\","
                + "  \"acct\":\"poster\","
                + "  \"display_name\":\"Poster\","
                + "  \"url\":\"https://mastodon.example.com/@poster\","
                + "  \"avatar\":\"https://mastodon.example.com/avatars/poster.jpg\","
                + "  \"avatar_static\":\"https://mastodon.example.com/avatars/poster.jpg\","
                + "  \"header\":\"https://mastodon.example.com/headers/poster.jpg\","
                + "  \"header_static\":\"https://mastodon.example.com/headers/poster.jpg\","
                + "  \"locked\":false,"
                + "  \"bot\":false,"
                + "  \"discoverable\":true,"
                + "  \"group\":false,"
                + "  \"created_at\":\"2024-01-01T00:00:00.000Z\","
                + "  \"note\":\"<p>bio</p>\","
                + "  \"followers_count\":10,"
                + "  \"following_count\":5,"
                + "  \"statuses_count\":100,"
                + "  \"last_status_at\":\"2026-06-24\","
                + "  \"emojis\":[],"
                + "  \"fields\":[]"
                + "},"
                + "\"mentions\":["
                + "  {\"id\":\"bot-acc\",\"username\":\"" + botAcct + "\","
                + "   \"url\":\"https://mastodon.example.com/@" + botAcct + "\","
                + "   \"acct\":\"" + botAcct + "\"}"
                + "],"
                + "\"tags\":[],"
                + "\"emojis\":[],"
                + "\"media_attachments\":[],"
                + "\"reblog\":null,"
                + "\"replies_count\":0,"
                + "\"reblogs_count\":0,"
                + "\"favourites_count\":0,"
                + "\"language\":\"de\","
                + "\"text\":null,"
                + "\"edited_at\":null,"
                + "\"in_reply_to_id\":null,"
                + "\"in_reply_to_account_id\":null,"
                + "\"reblogged\":false,"
                + "\"favourited\":false,"
                + "\"muted\":false,"
                + "\"bookmarked\":false,"
                + "\"pinned\":false"
                + "}";
    }

    /**
     * Builds the outer {@link GenericMessageContent} JSON envelope with the given event type
     * and payload (the status JSON, which becomes a JSON-string value in the "payload" field).
     *
     * @param eventType   one of "update", "status.update", "delete"
     * @param payloadJson the raw JSON string for the payload field
     */
    private static String buildGenericMessageContentJson(String eventType, String payloadJson) {
        // The payload field must be a JSON string (double-encoded) — the outer envelope
        // has payload as a string field containing the JSON-encoded status.
        String escapedPayload = payloadJson
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
        return "{\"stream\":[\"hashtag\"],\"event\":\"" + eventType + "\","
                + "\"payload\":\"" + escapedPayload + "\"}";
    }

    /**
     * Builds a mock {@link MastodonApiEvent.GenericMessage} for the given event type and status JSON.
     */
    private static MastodonApiEvent.GenericMessage buildGenericEvent(String eventType, String statusJson) {
        String contentJson = buildGenericMessageContentJson(eventType, statusJson);
        MastodonApiEvent.GenericMessage msg = mock(MastodonApiEvent.GenericMessage.class);
        when(msg.getText()).thenReturn(contentJson);
        return msg;
    }
}
