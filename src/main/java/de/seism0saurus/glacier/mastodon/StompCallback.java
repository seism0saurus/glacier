package de.seism0saurus.glacier.mastodon;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.seism0saurus.glacier.eventtype.EventTypeMapping;
import de.seism0saurus.glacier.share.application.SafeUrlValidator;
import de.seism0saurus.glacier.share.application.ShareViewStompRelay;
import de.seism0saurus.glacier.util.LogScrubber;
import de.seism0saurus.glacier.webservice.cache.CacheEntry;
import de.seism0saurus.glacier.webservice.cache.EventType;
import de.seism0saurus.glacier.webservice.cache.MessageCache;
import de.seism0saurus.glacier.webservice.messaging.PrincipalKey;
import de.seism0saurus.glacier.webservice.messaging.PrincipalKind;
import de.seism0saurus.glacier.webservice.messaging.messages.*;
import kotlin.Unit;
import kotlinx.serialization.KSerializer;
import kotlinx.serialization.SerializersKt;
import kotlinx.serialization.json.Json;
import kotlinx.serialization.json.JsonKt;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import social.bigbone.api.entity.Status;
import social.bigbone.api.entity.streaming.*;
import social.bigbone.api.entity.streaming.MastodonApiEvent.GenericMessage;

import java.net.URI;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The StompCallback class implements the WebSocketCallback interface and is responsible for
 * processing WebSocket events received from the Mastodon streaming API.
 *
 * <p>On each qualifying Mastodon streaming event, this class delegates to
 * {@link MessageCache#recordThenPublish} which atomically appends the event to the
 * ring buffer and fans it out over STOMP (D-03). The {@link MessageCache}
 * implementation owns the {@code SimpMessagingTemplate}; this class no longer holds it.</p>
 *
 * <p>SSRF guard (ADR-PT-01 / ADR-PT-02 / SR-PT-10): for every event that carries a toot URL
 * ({@code processStatusCreatedEvent}, {@code processStatusEditedEvent}, {@code sendMessage}),
 * the URL is passed through the injected {@link SafeUrlValidator} <em>before</em> any outbound
 * {@code HEAD} request and <em>before</em> any {@link MessageCache#recordThenPublish} call.
 * If the validator returns empty, the toot is silently dropped and an audit event
 * ({@code stomp.embed.ssrf_blocked}) is emitted with scrubbed URL metadata only
 * (D-13 / SR-8 — raw URL never logged).
 *
 * <p>Deletion events ({@code procesStatusDeletedEvent}) carry only a status ID — no URL —
 * and therefore require no SSRF guard.</p>
 */
public class StompCallback implements WebSocketCallback {

    /**
     * The {@link Logger Logger} for this class.
     * The logger is used for logging as configured for the application.
     *
     * @see "src/main/resources/logback.xml"
     */
    private final static Logger LOGGER = LoggerFactory.getLogger(StompCallback.class);

    /**
     * Dedicated AUDIT logger for security-relevant events (D-13 / SR-8).
     * Messages sent here must use scrubbed values only — never raw URLs, principals, or tokens.
     */
    private static final Logger AUDIT = LoggerFactory.getLogger("AUDIT");

    /**
     * Lenient {@code kotlinx.serialization.json.Json} instance for parsing the raw Mastodon
     * status JSON from the generic WebSocket path (B2 / SR-FLAW2-01 resolution).
     *
     * <p>{@code ignoreUnknownKeys = true}: the Mastodon WebSocket payload contains many fields
     * not modelled in Bigbone's {@link Status} entity (e.g. {@code pinned}, {@code reblogged}).
     * Without this, deserialization would throw on every event.
     *
     * <p>{@code coerceInputValues = true}: matches Bigbone's internal {@code JSON_SERIALIZER}
     * configuration (defined in {@code social.bigbone.JsonSerializer}). Handles nullable fields
     * where a Mastodon fork or version omits a field or sends an unexpected null variant.
     *
     * <p>Bigbone's {@code JSON_SERIALIZER} is declared {@code internal} in Kotlin — it compiles
     * to package-private on the JVM and is NOT accessible from Java. This static field replicates
     * the same configuration for the generic path.
     *
     * <p>This field is package-private for testability (verified by {@code StompCallbackRenderTest}
     * — malformed payload test confirms no crash on bad JSON).
     *
     * <p>ADR-RENDER-01 / SR-FLAW2-01 (B2 resolution).
     */
    static final Json LENIENT_JSON = JsonKt.Json(Json.Default, builder -> {
        // ignoreUnknownKeys: Mastodon status payload has many fields not modelled in Bigbone's Status
        // (e.g. pinned, reblogged, favourited). Without this, deserialization throws SerializationException.
        builder.setIgnoreUnknownKeys(true);
        // coerceInputValues: matches Bigbone's internal JsonSerializer configuration — handles null
        // coercion for nullable fields where a Mastodon fork may omit a field vs. sending explicit null.
        builder.setCoerceInputValues(true);
        return Unit.INSTANCE;
    });

    /**
     * Represents a callback for handling WebSocket events related to subscriptions.
     * This class is used in conjunction with SubscriptionManager to manage hashtag subscriptions on Mastodon.
     */
    private final SubscriptionManager subscriptionManager;

    /**
     * The message cache. Atomically records events and fans them out over STOMP (D-03).
     * Replaces the former {@code SimpMessagingTemplate} — the template is now owned by
     * the cache implementation.
     */
    private final MessageCache messageCache;

    /**
     * Relay that fans toot events to viewer-scoped share topics (ADR-SHARE-04).
     * May be null if no share links are active — null-safe call sites use it only when non-null.
     */
    private final ShareViewStompRelay shareViewStompRelay;

    /**
     * The REST template is needed to check the headers of the URLs of the toots for X-FRAME headers.
     */
    private final RestTemplate restTemplate;

    /**
     * SSRF guard: validates that a toot URL resolves to a public, non-private address
     * and uses an allowed scheme before the callback issues any outbound HTTP request
     * or writes to the cache (ADR-PT-01 / ADR-PT-02 / SR-PT-10).
     */
    private final SafeUrlValidator safeUrlValidator;

    /**
     * The principal aka wallId of the subscription this callback.
     * Stored as raw String for STOMP destination construction only.
     * Never logged directly — always hashed via {@link LogScrubber#hash8} (D-13 / SR-8).
     */
    private final String principal;

    /**
     * PrincipalKey wrapping the wallId — used for MessageCache lookups to prevent
     * cross-namespace collision (ADR-SHARE-05, revised).
     */
    private final PrincipalKey principalKey;

    /**
     * This variable represents the hashtag of subscription of this callback.
     */
    private final String hashtag;

    /**
     * The local part of the bot's Mastodon handle (e.g. {@code "glacier"}).
     *
     * <p>Only the local part is needed for opt-in checks: the bot's short name is compared
     * against {@code mention.getAcct()} in the toot's mentions list. The full handle and
     * server part are not needed here (ADR-P3A-2).
     */
    private final String localPart;

    /**
     * The glacierDomain variable represents the domain used for this instance of glacier.
     */
    private final String glacierDomain;

    /**
     * Set of status IDs that have been successfully published as {@code StatusCreated} events
     * for this callback's {@code (principal, hashtag)} subscription (P2-13).
     *
     * <p>A {@code StatusEdited} event is only published if the corresponding status ID was
     * previously recorded in this set. This prevents delivering edits for toots that were
     * silently dropped during creation (e.g. because {@code isLoadable} returned {@code false}
     * or the SSRF guard blocked the URL at create-time), which would cause the client to display
     * an update for a toot it never received.
     *
     * <p>Uses {@link ConcurrentHashMap#newKeySet()} because {@code StompCallback.onEvent} is
     * called from a Bigbone I/O thread, while {@code TechnicalEvent.Failure} handling calls back
     * into the subscription manager which may access this set concurrently.
     *
     * <p>Memory: bounded by the number of toots that pass all creation gates for this
     * subscription. The callback has a subscription lifetime — it is discarded with the
     * subscription — so the set does not accumulate indefinitely.
     */
    private final Set<String> publishedStatusIds = ConcurrentHashMap.newKeySet();

    /**
     * Initializes a new instance of the StompCallback class.
     *
     * <p>The {@code messageCache} parameter replaces the former
     * {@code SimpMessagingTemplate} parameter; the template is now owned by
     * {@link MessageCache} (D-03).
     *
     * <p>SSRF guard (ADR-PT-01): every toot URL is validated by {@code safeUrlValidator}
     * before any outbound HTTP request or cache write. If the validator returns empty,
     * the toot is silently dropped.
     *
     * @param subscriptionManager  the manager to call when a stream failure requires restart
     * @param messageCache         the cache that records events and publishes them over STOMP (D-03)
     * @param shareViewStompRelay  the relay that fans events to viewer share topics (may be null,
     *                             ADR-SHARE-04)
     * @param restTemplate         the template used to inspect embed headers of toot URLs
     * @param safeUrlValidator     the SSRF guard: validates toot URLs before any outbound request
     *                             or cache write (ADR-PT-01 / SR-PT-10)
     * @param principal            the wallId associated with the subscription
     * @param hashtag              the hashtag to subscribe to
     * @param shortHandle          the bot's validated Mastodon handle; only the local part is
     *                             used for opt-in checks (ADR-P3A-2)
     * @param glacierDomain        the glacier domain for iframe-loadability checks
     */
    public StompCallback(final SubscriptionManager subscriptionManager,
                         final MessageCache messageCache,
                         final ShareViewStompRelay shareViewStompRelay,
                         final RestTemplate restTemplate,
                         final SafeUrlValidator safeUrlValidator,
                         final String principal,
                         final String hashtag,
                         final MastodonShortHandle shortHandle,
                         final String glacierDomain) {
        this.subscriptionManager = subscriptionManager;
        this.messageCache = messageCache;
        this.shareViewStompRelay = shareViewStompRelay;
        this.restTemplate = restTemplate;
        this.safeUrlValidator = safeUrlValidator;
        this.principal = principal;
        // ADR-SHARE-05 (revised): wrap raw wallId in PrincipalKey to prevent cross-namespace
        // collision in MessageCache lookups. StompCallback is always called for a wall principal.
        this.principalKey = new PrincipalKey(PrincipalKind.WALL, principal);
        this.hashtag = hashtag;
        this.localPart = shortHandle.localPart();
        this.glacierDomain = glacierDomain;
        // D-13/SR-8: never log raw principal (UUID wallId) or raw hashtag
        LOGGER.info("StompCallback for principal-hash={} with hashtag-len={} created",
                LogScrubber.hash8(principal), LogScrubber.hashtagLen(hashtag));
    }

    /**
     * Handles a WebSocket event.
     *
     * @param event The WebSocket event to handle.
     */
    @Override
    public void onEvent(@NotNull final WebSocketEvent event) {
        // D-13/SR-8 / ADR-F6-02: event.toString() is uncontrolled output that can include raw URLs,
        // hashtags, account handles, and raw HTML toot content — demoted to DEBUG, type-only rendering
        LOGGER.debug("stream.event type={}", event.getClass().getSimpleName());
        switch (event) {
            case MastodonApiEvent.StreamEvent streamEvent -> {
                switch (streamEvent.getEvent()) {
                    case ParsedStreamEvent.StatusCreated statusCreatedEvent ->
                            processStatusCreatedEvent(statusCreatedEvent.getCreatedStatus());
                    case ParsedStreamEvent.StatusEdited statusEditedEvent ->
                            processStatusEditedEvent(statusEditedEvent.getEditedStatus());
                    case ParsedStreamEvent.StatusDeleted statusDeletedEvent ->
                            procesStatusDeletedEvent(statusDeletedEvent.getDeletedStatusId());
                    // D-13/SR-8/CWE-117 (TD-5-FU-1/SR-TD5-FU-01): use getSimpleName(), not getClass() bare.
                    // Class.toString() produces "class fully.qualified.Name" — peer-controlled package
                    // metadata reaching the log encoder. getSimpleName() is JVM-controlled and bounded.
                    default -> logEvent("got an unknown StreamEvent: %s".formatted(streamEvent.getEvent().getClass().getSimpleName()));
                }
            }
            case TechnicalEvent technicalEvent -> processTechnicalEvent(technicalEvent);
            case GenericMessage genericMessage -> processGenericEvent(genericMessage);
            // D-13/SR-8/CWE-117 (TD-5-FU-1/SR-TD5-FU-02): use getSimpleName(), not getClass() bare.
            // Class.toString() produces "class fully.qualified.Name" — peer-controlled package
            // metadata reaching the log encoder. getSimpleName() is JVM-controlled and bounded.
            default -> logEvent("got an unknown event: %s".formatted(event.getClass().getSimpleName()));
        }
    }

    /**
     * Process a generic event.
     *
     * @param genericMessage The GenericMessage event to process.
     */
    private void processGenericEvent(GenericMessage genericMessage) {
        logEvent("got a GenericMessage event");
        String text = genericMessage.getText();
        ObjectMapper mapper = new ObjectMapper();
        try {
            GenericMessageContent genericMessageContent = mapper.readValue(text, GenericMessageContent.class);
            // Null-safe guard: a hostile Mastodon fork may send a null stream field (UT-sec-09)
            List<String> stream = genericMessageContent.getStream();
            if (stream == null) {
                LOGGER.warn("stream.generic.null_stream event={}",
                        LogScrubber.safeEventName(genericMessageContent.getEvent()));
                return;
            }
            if (stream.contains("hashtag") && "update".equals(genericMessageContent.getEvent())) {
                sendMessage(mapper, StatusCreatedMessage.class, genericMessageContent);
            } else if (stream.contains("hashtag") && "status.update".equals(genericMessageContent.getEvent())) {
                sendMessage(mapper, StatusUpdatedMessage.class, genericMessageContent);
            } else if (stream.contains("hashtag")
                    && ("delete".equals(genericMessageContent.getEvent())
                    || "status.delete".equals(genericMessageContent.getEvent()))) {
                procesStatusDeletedEvent(genericMessageContent.getPayload().textValue());
            } else {
                // D-13/SR-8 / ADR-F6-03: genericMessageContent full dump contains raw URLs,
                // hashtags, and payload — emit only stream size and allowlisted event name
                LOGGER.warn("stream.generic.unhandled streams-size={} event={}",
                        stream.size(),
                        LogScrubber.safeEventName(genericMessageContent.getEvent()));
            }
        } catch (JsonProcessingException e) {
            LOGGER.error("Could not parse GenericMessage — exception={}", e.getClass().getSimpleName());
        }
    }

    /**
     * Sends a status message to the given destination after passing SSRF and frame-ancestor guards.
     *
     * <p>Processing order (SR-PT-10 invariant):
     * <ol>
     *   <li>SSRF guard via {@link SafeUrlValidator} — exits early if blocked, emits AUDIT event.</li>
     *   <li>{@code HEAD} request to inspect embed headers (C-03 — RestClientException treated as
     *       not-loadable to avoid stalling the Bigbone virtual thread).</li>
     *   <li>Frame-ancestor / X-Frame-Options check via {@link #isLoadable}.</li>
     *   <li>Bot opt-in check (toot must mention {@code shortHandle}).</li>
     *   <li>Cache write via {@link MessageCache#recordThenPublish} (D-03).</li>
     *   <li>Relay to share-view topics — Object overload for fallback polling (ADR-SHARE-04).</li>
     *   <li>Parse payload JSON into {@link Status} + Status-overload relay for full
     *       {@link de.seism0saurus.glacier.share.application.ReadonlyTootView} rendering
     *       (ADR-RENDER-01 / SR-FLAW2-01 B2 resolution — live render path for dockerized
     *       Mastodon hashtag WebSocket subscription).</li>
     * </ol>
     *
     * @param mapper                Jackson mapper for deserialising the payload
     * @param statusMessageClass    the concrete {@link StatusMessage} subtype to build;
     *                              determines the {@link StompEventType} via
     *                              {@link EventTypeMapping#stompFor(Class)} (ADR-P3A-4)
     * @param genericMessageContent the envelope containing the raw payload JSON
     * @throws JsonProcessingException if the outer payload JSON cannot be parsed
     */
    private void sendMessage(ObjectMapper mapper, Class<? extends StatusMessage> statusMessageClass,
                             GenericMessageContent genericMessageContent) throws JsonProcessingException {
        // Resolve type from message class via the single-authority translator (ADR-P3A-4, F-6-INFO-2).
        // EventTypeMapping is the ONLY class permitted to map StatusMessage subtypes to StompEventType.
        Optional<StompEventType> typeOpt = EventTypeMapping.stompFor(statusMessageClass);
        if (typeOpt.isEmpty()) {
            LOGGER.error("stomp.message.unknown_status_class class={}", statusMessageClass.getSimpleName());
            return;
        }
        StompEventType type = typeOpt.get();

        // Keep the raw payload JSON string for B2 Status deserialization (SR-FLAW2-01 resolution).
        // The outer payload node is a text-encoded JSON string: its textValue() is the raw status JSON.
        String payloadJson = genericMessageContent.getPayload().textValue();

        GenericMessageContentPayload payload = mapper.readValue(payloadJson, GenericMessageContentPayload.class);

        // 1. SSRF guard: validate the toot URL before issuing any outbound request or cache write
        // C10 / SR-PT-10: this gate MUST precede any network call or relay (B2 relay is step 6b,
        // after all gates — ordering invariant preserved).
        Optional<URI> safeUri = safeUrlValidator.validate(payload.getUrl());
        if (safeUri.isEmpty()) {
            AUDIT.info("stomp.embed.ssrf_blocked url-host-hash={} scheme={}",
                    LogScrubber.urlHostHash(payload.getUrl()),
                    extractScheme(payload.getUrl()));
            return;
        }

        // 2. HEAD request (C-03: timeout / connection failure → not-loadable)
        // D-13/SR-8 / ADR-F6-04: on failure log only url-host-hash and exception class —
        // never payload.getUrl() or ex.getMessage() which embeds the full URL
        HttpHeaders httpHeaders;
        try {
            httpHeaders = this.restTemplate.headForHeaders(payload.getUrl() + "/embed");
        } catch (RestClientException ex) {
            LOGGER.debug("stomp.embed.head_failed url-host-hash={} error={}",
                    LogScrubber.urlHostHash(payload.getUrl()), ex.getClass().getSimpleName());
            return;
        }

        // 3. Frame-ancestor / X-Frame-Options gate
        if (isLoadable(httpHeaders, glacierDomain)) {
            // 4. Bot opt-in gate (ADR-PT-A04-01 — enforced on all paths via isOptedIn helper)
            if (isOptedIn(payload, localPart)) {
                // 5. Cache write (D-03)
                CacheEntry partial;
                if (StatusCreatedMessage.class.equals(statusMessageClass)) {
                    partial = new CacheEntry(EventType.CREATED, payload.getId(), payload.getUrl() + "/embed", null, 0L);
                } else {
                    // StatusUpdatedMessage — P2-13: guard against edits without prior create
                    if (!publishedStatusIds.contains(payload.getId())) {
                        LOGGER.debug("stomp.edit.dropped_no_prior_create hashtag-len={} — generic-path edit silently dropped (P2-13)",
                                LogScrubber.hashtagLen(hashtag));
                        return;
                    }
                    // normalise editedAt to UTC (D-07)
                    String editedAt = normaliseEditedAt(payload.getEditedAt());
                    if (editedAt == null && payload.getEditedAt() != null) {
                        // normalisation failed (malformed input) — already warned, drop
                        return;
                    }
                    partial = new CacheEntry(EventType.UPDATED, payload.getId(), payload.getUrl() + "/embed", editedAt, 0L);
                }
                CacheEntry stored = messageCache.recordThenPublish(principalKey, hashtag, partial);

                // 6a. ADR-SHARE-04 (legacy Object overload): relay CacheEntry to share topics.
                // This path is kept for the fallback polling endpoint (FLAW-3 — deferred).
                if (shareViewStompRelay != null && stored != null) {
                    shareViewStompRelay.relayTootEvent(principal, hashtag, type.suffix(), stored);
                }

                // 6b. ADR-RENDER-01 / SR-FLAW2-01 (B2 resolution): parse the raw payload JSON
                // into a Bigbone Status and call the Status-overload relay for full ReadonlyTootView
                // rendering per share link. This is the live render path for the dockerized Mastodon
                // hashtag WebSocket subscription (streaming().hashtag() → GenericMessage).
                //
                // Security ordering (C10 / SR-PT-10): all gates (SSRF → HEAD → isLoadable → opt-in)
                // have already passed before reaching this point. The cache write (step 5) is also
                // complete. This is intentionally the LAST action in the success path.
                //
                // KNOWN LIMITATION (FLAW-2 — resolved B2): previously this path called the Object
                // overload only (insufficient for ReadonlyTootView rendering). The typed-path instances
                // (Mastodon servers that emit typed StreamEvents) are also served by the Status overload
                // via processStatusCreatedEvent/processStatusEditedEvent — both paths are now consistent.
                if (shareViewStompRelay != null && stored != null) {
                    parseStatusAndRelay(payloadJson, type.suffix());
                }

                // 7. P2-13: record successful StatusCreated publishes for future edit guard
                if (StatusCreatedMessage.class.equals(statusMessageClass) && stored != null) {
                    publishedStatusIds.add(payload.getId());
                }
                // D-13/SR-8 / ADR-F6-05: emit structured triple — type derived from statusMessageClass,
                // never from destination bytes (F-6-INFO-2, SR-F6INFO2-03)
                LOGGER.info("stomp.message.published principal-hash={} hashtag-len={} event-type={}",
                        LogScrubber.hash8(principal), LogScrubber.hashtagLen(hashtag), type.suffix());
            } else {
                LOGGER.info("No opt in. Ignoring");
            }
        } else {
            LOGGER.info("Toot not loadable by this glacier instance. Ignoring");
        }
    }

    /**
     * Parses the raw Mastodon status JSON into a Bigbone {@link Status} using
     * {@link #LENIENT_JSON} and relays it via the Status-overload relay (B2 / SR-FLAW2-01).
     *
     * <p>Separated from {@link #sendMessage} to isolate the deserialization error boundary:
     * a malformed JSON payload is caught here and logged at DEBUG level, allowing the method
     * to return cleanly without crashing the Bigbone virtual thread or blocking other relays.
     *
     * <p>D-13/SR-8: exceptions are logged by class name only — no raw payload content in logs.
     *
     * @param payloadJson   the raw JSON string from the GenericMessage payload field
     * @param eventTypeSuffix the event type suffix for the relay topic (e.g. "creation")
     */
    private void parseStatusAndRelay(final String payloadJson, final String eventTypeSuffix) {
        try {
            // Deserialize Bigbone Status from raw Mastodon JSON (kotlinx.serialization).
            // LENIENT_JSON has ignoreUnknownKeys=true and coerceInputValues=true to handle the full
            // Mastodon status payload which contains many fields not modelled in Bigbone's Status.
            // Status.Companion.serializer() is the @Serializable-generated companion serializer.
            @SuppressWarnings("unchecked")
            KSerializer<Status> statusSerializer =
                    (KSerializer<Status>) Status.Companion.serializer();
            Status status = LENIENT_JSON.decodeFromString(statusSerializer, payloadJson);
            // ADR-RENDER-01 / SR-RENDER-01: relay the parsed Status to ShareViewStompRelay.
            // The relay performs per-link renderForView() inside its loop (never here).
            shareViewStompRelay.relayTootEvent(principal, hashtag, eventTypeSuffix, status);
        } catch (Exception e) {
            // D-13/SR-8: log class name only — payloadJson content must not reach the logger.
            LOGGER.debug("stomp.generic.status_parse_failed event={} error={}",
                    eventTypeSuffix, e.getClass().getSimpleName());
        }
    }

    /**
     * Determines whether a Bigbone {@link Status} has opted in to the glacier wall by mentioning
     * the bot's short handle in its mentions list.
     *
     * <p>Defence-in-depth (ADR-PT-A04-01): this check is enforced at the Glacier layer on all
     * three event paths — typed ({@code processStatusCreatedEvent}, {@code processStatusEditedEvent})
     * and generic ({@code sendMessage}) — so that a future Bigbone API change or subscription
     * misconfiguration cannot bypass the opt-in invariant.
     *
     * <p>Null-safe: if {@code status.getMentions()} returns {@code null}, the method returns
     * {@code false} without throwing an NPE.
     *
     * <p>Exact-match semantics: the comparison uses {@code String#equals}, not
     * {@code String#startsWith} or {@code String#contains}, so a mention of
     * {@code "glacier@otherinstance.social"} does NOT satisfy a {@code shortHandle} of
     * {@code "glacier"}. This is intentional — partial matches would allow impersonation via
     * similarly-named accounts on other instances.
     *
     * <p>D-13/SR-8: this method emits no log output at INFO or above. The caller is responsible
     * for logging the drop decision using hashed identifiers only (never raw hashtag or wallId).
     *
     * @param status       the Bigbone status whose mentions list is to be checked; must not be null
     * @param shortHandle  the bot's short name (local part only, e.g. {@code "glacier"}) —
     *                     derived from the configured mastodon handle at construction time
     * @return {@code true} if any {@link social.bigbone.api.entity.Status.Mention#getAcct()} on
     *         the status exactly equals {@code shortHandle}; {@code false} otherwise (including
     *         the null-mentions case)
     */
    private static boolean isOptedIn(final Status status, final String shortHandle) {
        List<Status.Mention> mentions = status.getMentions();
        if (mentions == null) {
            return false;
        }
        return mentions.stream()
                .anyMatch(mention -> shortHandle.equals(mention.getAcct()));
    }

    /**
     * Determines whether a {@link GenericMessageContentPayload} has opted in to the glacier wall
     * by mentioning the bot's short handle in its mentions list.
     *
     * <p>This overload covers the GenericMessage path in {@link #sendMessage}, where the payload
     * is already deserialized into our own DTO type. The semantics are identical to the
     * {@link #isOptedIn(Status, String)} overload — null-safe and exact-match.
     *
     * <p>Exact-match semantics: uses {@code String#equals}, not {@code startsWith/contains},
     * so {@code "glacier@otherinstance.social"} does NOT satisfy {@code shortHandle = "glacier"}.
     *
     * @param payload      the deserialized payload; must not be null
     * @param shortHandle  the bot's short name (local part only)
     * @return {@code true} if any {@link Mention#getAcct()} exactly equals {@code shortHandle};
     *         {@code false} otherwise (including null-mentions case)
     */
    private static boolean isOptedIn(final GenericMessageContentPayload payload, final String shortHandle) {
        List<Mention> mentions = payload.getMentions();
        if (mentions == null) {
            return false;
        }
        return mentions.stream()
                .anyMatch(mention -> shortHandle.equals(mention.getAcct()));
    }

    /**
     * Checks if a webpage is loadable as iframe based on the provided HttpHeaders and the configured glacierDomain.
     * <p>
     * Delegates all header-precedence logic to {@link IframeEmbedPolicy#isEmbeddable}.
     *
     * @param httpHeaders   The HttpHeaders of the webpage.
     * @param glacierDomain The glacier domain.
     * @return true if the webpage is loadable, false otherwise.
     */
    private static boolean isLoadable(final HttpHeaders httpHeaders, final String glacierDomain) {
        return IframeEmbedPolicy.isEmbeddable(
                httpHeaders.get("X-Frame-Options"),
                httpHeaders.get("Content-Security-Policy"),
                glacierDomain);
    }

    /**
     * Processes a StatusCreated event by recording it in the cache and fanning it out over STOMP.
     *
     * <p>Processing order (SR-PT-10 invariant):
     * <ol>
     *   <li>SSRF guard via {@link SafeUrlValidator} — exits early if blocked, emits AUDIT event.</li>
     *   <li>{@code HEAD} request to inspect embed headers (C-03).</li>
     *   <li>Frame-ancestor / X-Frame-Options check.</li>
     *   <li>Cache write via {@link MessageCache#recordThenPublish} (D-03).</li>
     *   <li>Relay to share-view topics (ADR-SHARE-04).</li>
     * </ol>
     *
     * <p>Bot opt-in check (ADR-PT-A04-01): this typed path now enforces the same opt-in invariant
     * as the generic path — the toot must mention the bot's short handle. Delegation to the
     * Mastodon subscription filter alone is fragile (a future Bigbone change could bypass it);
     * {@link #isOptedIn(Status, String)} provides a Glacier-layer defence-in-depth guard.
     *
     * @param status The newly created status.
     */
    private void processStatusCreatedEvent(final Status status) {
        logEvent("got a StatusCreated event");

        // 0. Bot opt-in gate (ADR-PT-A04-01 — defence-in-depth: checked on ALL typed paths)
        if (!isOptedIn(status, localPart)) {
            LOGGER.debug("opt-in.check.failed hashtag-len={} — dropping StatusCreated",
                    LogScrubber.hashtagLen(hashtag));
            return;
        }

        // 1. SSRF guard: validate the toot URL before issuing any outbound request or cache write
        Optional<URI> safeUri = safeUrlValidator.validate(status.getUrl());
        if (safeUri.isEmpty()) {
            AUDIT.info("stomp.embed.ssrf_blocked url-host-hash={} scheme={}",
                    LogScrubber.urlHostHash(status.getUrl()),
                    extractScheme(status.getUrl()));
            return;
        }

        // 2. HEAD request (C-03: timeout / connection failure → not-loadable)
        // D-13/SR-8 / ADR-F6-04: on failure log only url-host-hash and exception class —
        // never status.getUrl() or ex.getMessage() which embeds the full URL
        HttpHeaders httpHeaders;
        try {
            httpHeaders = this.restTemplate.headForHeaders(status.getUrl() + "/embed");
        } catch (RestClientException ex) {
            LOGGER.debug("stomp.embed.head_failed url-host-hash={} error={}",
                    LogScrubber.urlHostHash(status.getUrl()), ex.getClass().getSimpleName());
            return;
        }

        // 3. Frame-ancestor / X-Frame-Options gate
        if (isLoadable(httpHeaders, glacierDomain)) {
            // 4. Cache write (D-03)
            CacheEntry partial = new CacheEntry(EventType.CREATED, status.getId(), status.getUrl() + "/embed", null, 0L);
            CacheEntry stored = messageCache.recordThenPublish(principalKey, hashtag, partial);
            // 5. ADR-SHARE-04 / ADR-RENDER-01: relay to viewer share topics after successful cache write.
            // Use the Status overload so ShareViewStompRelay can render a per-link ReadonlyTootView.
            // SR-RENDER-01: renderForView(status, shareLinkId) is called INSIDE the relay's per-link
            // loop — never hoisted here.
            if (shareViewStompRelay != null && stored != null) {
                shareViewStompRelay.relayTootEvent(principal, hashtag, StompEventType.CREATION.suffix(), status);
            }
            // P2-13: record that this status was published so future StatusEdited events are allowed
            if (stored != null) {
                publishedStatusIds.add(status.getId());
            }
        }
    }

    /**
     * Process a StatusEdited event by updating the cache and fanning out the modification over STOMP.
     *
     * <p>Processing order (SR-PT-10 invariant):
     * <ol>
     *   <li>SSRF guard via {@link SafeUrlValidator} — exits early if blocked, emits AUDIT event.</li>
     *   <li>{@code editedAt} normalisation to UTC (D-07).</li>
     *   <li>Cache write via {@link MessageCache#recordThenPublish} (D-03).</li>
     *   <li>Relay to share-view topics (ADR-SHARE-04).</li>
     * </ol>
     *
     * <p>Note: no {@code HEAD} request is issued for edited statuses — the toot URL was already
     * validated when the toot was first created.
     *
     * <p>Bot opt-in check (ADR-PT-A04-01): same defence-in-depth guard as
     * {@link #processStatusCreatedEvent} — toot must mention the bot's short handle.
     *
     * @param status The edited status.
     */
    private void processStatusEditedEvent(final Status status) {
        logEvent("got a StatusEdited event");

        // 0. Bot opt-in gate (ADR-PT-A04-01 — defence-in-depth: checked on ALL typed paths)
        if (!isOptedIn(status, localPart)) {
            LOGGER.debug("opt-in.check.failed hashtag-len={} — dropping StatusEdited",
                    LogScrubber.hashtagLen(hashtag));
            return;
        }

        // P2-13: guard — only publish edits for statuses that were previously published
        // as StatusCreated. If the toot was dropped at create-time (SSRF block, not-loadable,
        // opt-in failure), the client never saw it and delivering the edit would be confusing.
        if (!publishedStatusIds.contains(status.getId())) {
            LOGGER.debug("stomp.edit.dropped_no_prior_create hashtag-len={} — edit silently dropped (P2-13)",
                    LogScrubber.hashtagLen(hashtag));
            return;
        }

        // 1. SSRF guard: validate the toot URL before publishing any message to the cache
        Optional<URI> safeUri = safeUrlValidator.validate(status.getUrl());
        if (safeUri.isEmpty()) {
            AUDIT.info("stomp.embed.ssrf_blocked url-host-hash={} scheme={}",
                    LogScrubber.urlHostHash(status.getUrl()),
                    extractScheme(status.getUrl()));
            return;
        }

        // 2. Normalise editedAt to UTC (D-07)
        String editedAt = normaliseEditedAt(status.getEditedAt() != null ? status.getEditedAt().toString() : null);

        // 3. Cache write (D-03)
        CacheEntry partial = new CacheEntry(EventType.UPDATED, status.getId(), status.getUrl() + "/embed", editedAt, 0L);
        CacheEntry stored = messageCache.recordThenPublish(principalKey, hashtag, partial);
        // 4. ADR-SHARE-04 / ADR-RENDER-01: relay to viewer share topics.
        // Use the Status overload so ShareViewStompRelay can render a per-link ReadonlyTootView.
        // P2-13 guard already verified above; Status overload called only on prior-create confirmed.
        if (shareViewStompRelay != null && stored != null) {
            shareViewStompRelay.relayTootEvent(principal, hashtag, StompEventType.MODIFICATION.suffix(), status);
        }
    }

    /**
     * Processes the StatusDeleted event by recording the deletion in the cache and fanning it out.
     *
     * <p>Deletion events carry only a status ID — no URL — so no SSRF guard is required here.</p>
     *
     * @param statusId The ID of the deleted status.
     */
    private void procesStatusDeletedEvent(final String statusId) {
        logEvent("got a StatusDeleted event");
        CacheEntry partial = new CacheEntry(EventType.DELETED, statusId, null, null, 0L);
        CacheEntry stored = messageCache.recordThenPublish(principalKey, hashtag, partial);
        // ADR-SHARE-04: relay to viewer share topics
        if (shareViewStompRelay != null && stored != null) {
            shareViewStompRelay.relayTootEvent(principal, hashtag, StompEventType.DELETION.suffix(), stored);
        }
    }

    /**
     * Processes a technical WebSocket event.
     * <p>
     * The event is logged as information or error, depending on its class.
     * No further actions are taken.
     *
     * @param event The event to process.
     */
    // Package-private for tests: since bigbone 2.0.0 the WebSocketEvent hierarchy is sealed,
    // so no foreign subtype can reach the default branch through onEvent anymore. The branch
    // stays as defence-in-depth for future bigbone versions that add permitted subtypes at
    // runtime; direct invocation keeps its CWE-117 guard testable (StompCallbackTest).
    void processTechnicalEvent(final WebSocketEvent event) {
        switch (event) {
            case TechnicalEvent.Open open ->
                    logEvent("got an Open event (class=%s)".formatted(open.getClass().getSimpleName()));
            case TechnicalEvent.Closing closing ->
                    logEvent("got a Closing event — code=%d".formatted(closing.getCode()));
            case TechnicalEvent.Closed closed ->
                    logEvent("got a Closed event — code=%d".formatted(closed.getCode()));
            case TechnicalEvent.Failure failure -> {
                logEvent("got a Failure event. Restarting subscription. exception=%s".formatted(failure.getError().getClass().getSimpleName()));
                this.subscriptionManager.terminateSubscription(principal, hashtag);
                this.subscriptionManager.subscribeToHashtag(principal, hashtag);
            }
            default -> logEvent("got an unknown WebSocketEvent (class=%s)".formatted(event.getClass().getSimpleName()));
        }
    }

    /**
     * Extracts the URI scheme from a raw URL string for use in audit log fields.
     *
     * <p>Returns {@code "unknown"} for null, blank, or malformed URLs so that
     * the audit event is always emitted even when the URL cannot be parsed.</p>
     *
     * @param rawUrl the raw URL string; may be null or malformed
     * @return the lowercase scheme (e.g. {@code "https"}), or {@code "unknown"}
     */
    private static String extractScheme(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            return "unknown";
        }
        try {
            URI uri = URI.create(rawUrl);
            String scheme = uri.getScheme();
            return scheme != null ? scheme.toLowerCase(Locale.ROOT) : "unknown";
        } catch (IllegalArgumentException e) {
            return "unknown";
        }
    }

    /**
     * Normalises an ISO-8601 timestamp to UTC by parsing it as an {@link Instant} and
     * calling {@code toString()}, which always produces a {@code Z}-suffix form (D-07).
     *
     * <p>Returns {@code null} and logs a WARN when the input cannot be parsed, so that
     * a malformed {@code editedAt} in a Mastodon fork's payload does not crash the
     * ingestion thread.
     *
     * <p>D-13/SR-8/CWE-117: the raw peer-controlled string is never passed to the log
     * encoder. Only {@code raw.length()} and {@code ex.getErrorIndex()} — both {@code int}
     * values — are logged. Integer arguments structurally eliminate CRLF and ANSI injection.
     * {@code raw.length()} is NPE-safe because the null guard above runs first (SR-TD3-07).
     *
     * @param raw the raw {@code editedAt} string from the Mastodon payload; may be {@code null}
     * @return UTC normalised string, or {@code null} if {@code raw} was {@code null} or
     *         unparseable
     */
    static String normaliseEditedAt(final String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return Instant.parse(raw).toString();
        } catch (DateTimeParseException ex) {
            LOGGER.warn("Could not parse editedAt — len={} errorIndex={} — dropping update event (D-07)", raw.length(), ex.getErrorIndex());
            return null;
        }
    }

    /**
     * Logs an event.
     *
     * <p>D-13/SR-8: uses hashed principal prefix — never the raw wallId UUID.
     *
     * @param msg The message to be logged.
     */
    private void logEvent(final String msg) {
        // D-13/SR-8: never log raw principal (UUID wallId) — use hashed 8-char prefix
        LOGGER.info("Subscription principal-hash={} {}", LogScrubber.hash8(principal), msg);
    }
}
