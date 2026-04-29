package de.seism0saurus.glacier.mastodon;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.seism0saurus.glacier.share.application.SafeUrlValidator;
import de.seism0saurus.glacier.share.application.ShareViewStompRelay;
import de.seism0saurus.glacier.util.LogScrubber;
import de.seism0saurus.glacier.webservice.cache.CacheEntry;
import de.seism0saurus.glacier.webservice.cache.EventType;
import de.seism0saurus.glacier.webservice.cache.MessageCache;
import de.seism0saurus.glacier.webservice.messaging.PrincipalKey;
import de.seism0saurus.glacier.webservice.messaging.PrincipalKind;
import de.seism0saurus.glacier.webservice.messaging.messages.*;
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
import java.util.Optional;
import java.util.stream.Stream;

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
     * @see "src/main/ressources/logback.xml"
     */
    private final static Logger LOGGER = LoggerFactory.getLogger(StompCallback.class);

    /**
     * Dedicated AUDIT logger for security-relevant events (D-13 / SR-8).
     * Messages sent here must use scrubbed values only — never raw URLs, principals, or tokens.
     */
    private static final Logger AUDIT = LoggerFactory.getLogger("AUDIT");

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

    private final String shortHandle;

    /**
     * The glacierDomain variable represents the domain used for this instance of glacier.
     */
    private final String glacierDomain;

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
     * @param handle               the bot's full Mastodon handle, used for the opt-in check
     * @param glacierDomain        the glacier domain for iframe-loadability checks
     */
    public StompCallback(final SubscriptionManager subscriptionManager,
                         final MessageCache messageCache,
                         final ShareViewStompRelay shareViewStompRelay,
                         final RestTemplate restTemplate,
                         final SafeUrlValidator safeUrlValidator,
                         final String principal,
                         final String hashtag,
                         final String handle,
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
        this.shortHandle = getShortHandle(handle);
        this.glacierDomain = glacierDomain;
        // D-13/SR-8: never log raw principal (UUID wallId) or raw hashtag
        LOGGER.info("StompCallback for principal-hash={} with hashtag-len={} created",
                LogScrubber.hash8(principal), LogScrubber.hashtagLen(hashtag));
    }

    private static @NotNull String getShortHandle(String handle) {
        String tmpHandle = handle;
        if (null == tmpHandle) {
            throw new IllegalArgumentException("A mastodon handle is needed");
        }
        if (tmpHandle.startsWith("@")) {
            // remove initial @
            tmpHandle = tmpHandle.substring(1);
        }
        if (!tmpHandle.contains("@")) {
            throw new IllegalArgumentException("The mastodon handle does not contain an @ so either the name or the server is missing");
        }
        return tmpHandle.substring(0, tmpHandle.indexOf('@'));
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
        String baseDestination = "/topic/hashtags/" + principal + "/" + hashtag;
        switch (event) {
            case MastodonApiEvent.StreamEvent streamEvent -> {
                switch (streamEvent.getEvent()) {
                    case ParsedStreamEvent.StatusCreated statusCreatedEvent ->
                            processStatusCreatedEvent(statusCreatedEvent.getCreatedStatus(), baseDestination);
                    case ParsedStreamEvent.StatusEdited statusEditedEvent ->
                            processStatusEditedEvent(statusEditedEvent.getEditedStatus(), baseDestination);
                    case ParsedStreamEvent.StatusDeleted statusDeletedEvent ->
                            procesStatusDeletedEvent(statusDeletedEvent.getDeletedStatusId(), baseDestination);
                    default -> logEvent("got an unknown StreamEvent: %s".formatted(streamEvent.getEvent().getClass()));
                }
            }
            case TechnicalEvent technicalEvent -> processTechnicalEvent(technicalEvent);
            case GenericMessage genericMessage -> processGenericEvent(genericMessage, baseDestination);
            default -> logEvent("got an unknown event: %s".formatted(event.getClass()));
        }
    }

    /**
     * Process a generic event.
     *
     * @param genericMessage The GenericMessage event to process.
     * @param destination    The destination to send the processed event.
     */
    private void processGenericEvent(GenericMessage genericMessage, String destination) {
        logEvent("got a GenericMessage event");
        String text = genericMessage.getText();
        ObjectMapper mapper = new ObjectMapper();
        try {
            GenericMessageContent genericMessageContent = mapper.readValue(text, GenericMessageContent.class);
            if (genericMessageContent.getStream().contains("hashtag") && "update".equals(genericMessageContent.getEvent())) {
                sendMessage(mapper, StatusCreatedMessage.class, genericMessageContent, destination + "/creation");
            } else if (genericMessageContent.getStream().contains("hashtag") && "status.update".equals(genericMessageContent.getEvent())) {
                sendMessage(mapper, StatusUpdatedMessage.class, genericMessageContent, destination + "/modification");
            } else if (genericMessageContent.getStream().contains("hashtag")
                    && ("delete".equals(genericMessageContent.getEvent())
                    || "status.delete".equals(genericMessageContent.getEvent()))) {
                procesStatusDeletedEvent(genericMessageContent.getPayload().textValue(), destination);
            } else {
                // D-13/SR-8 / ADR-F6-03: genericMessageContent full dump contains raw URLs,
                // hashtags, and payload — emit only stream size and allowlisted event name
                LOGGER.warn("stream.generic.unhandled streams-size={} event={}",
                        genericMessageContent.getStream().size(),
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
     *   <li>Relay to share-view topics (ADR-SHARE-04).</li>
     * </ol>
     *
     * @param mapper                Jackson mapper for deserialising the payload
     * @param statusMessageClass    the concrete {@link StatusMessage} subtype to build
     * @param genericMessageContent the envelope containing the raw payload JSON
     * @param destination           the STOMP topic destination (informational only — publishing
     *                              is delegated to {@link MessageCache})
     * @throws JsonProcessingException if the payload JSON cannot be parsed
     */
    private void sendMessage(ObjectMapper mapper, Class<? extends StatusMessage> statusMessageClass,
                             GenericMessageContent genericMessageContent, String destination) throws JsonProcessingException {
        GenericMessageContentPayload payload = mapper.readValue(
                genericMessageContent.getPayload().textValue(), GenericMessageContentPayload.class);

        // 1. SSRF guard: validate the toot URL before issuing any outbound request or cache write
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
            // 4. Bot opt-in gate
            if (payload.getMentions().stream().map(Mention::getAcct).anyMatch(shortHandle::equals)) {
                // 5. Cache write (D-03)
                CacheEntry partial;
                if (StatusCreatedMessage.class.equals(statusMessageClass)) {
                    partial = new CacheEntry(EventType.CREATED, payload.getId(), payload.getUrl() + "/embed", null, 0L);
                } else {
                    // StatusUpdatedMessage — normalise editedAt to UTC (D-07)
                    String editedAt = normaliseEditedAt(payload.getEditedAt());
                    if (editedAt == null && payload.getEditedAt() != null) {
                        // normalisation failed (malformed input) — already warned, drop
                        return;
                    }
                    partial = new CacheEntry(EventType.UPDATED, payload.getId(), payload.getUrl() + "/embed", editedAt, 0L);
                }
                CacheEntry stored = messageCache.recordThenPublish(principalKey, hashtag, partial);
                // 6. ADR-SHARE-04: relay to viewer share topics after successful cache write
                if (shareViewStompRelay != null && stored != null) {
                    String eventType = StatusCreatedMessage.class.equals(statusMessageClass) ? "creation" : "modification";
                    shareViewStompRelay.relayTootEvent(principal, hashtag, eventType, stored);
                }
                // D-13/SR-8 / ADR-F6-05: emit structured triple instead of raw STOMP destination
                // (which embeds principal UUID and raw hashtag)
                String eventType = destination.substring(destination.lastIndexOf('/') + 1);
                LOGGER.info("stomp.message.published principal-hash={} hashtag-len={} event-type={}",
                        LogScrubber.hash8(principal), LogScrubber.hashtagLen(hashtag), eventType);
            } else {
                LOGGER.info("No opt in. Ignoring");
            }
        } else {
            LOGGER.info("Toot not loadable by this glacier instance. Ignoring");
        }
    }

    /**
     * Checks if a webpage is loadable as iframe based on the provided HttpHeaders and the configured glacierDomain.
     * <p>
     * If a content security policy with a frame-ancestors directive exists, that value is used, since it overrules
     * the X-Frame-Options. Otherwise, the X-Frame-Options are used.
     * If none of these is set, the browser default (allow) is used.
     *
     * @param httpHeaders   The HttpHeaders of the webpage.
     * @param glacierDomain The glacier domain.
     * @return true if the webpage is loadable, false otherwise.
     */
    private static boolean isLoadable(final HttpHeaders httpHeaders, final String glacierDomain) {
        List<String> xFrameOptions = httpHeaders.get("X-Frame-Options");
        List<String> csp = httpHeaders.get("Content-Security-Policy");
        boolean xFrameExplicitlyNotAllowed = false;
        boolean xFrameExplicitlyAllowed = false;
        boolean xFrameDefaultAllowed = true;
        boolean frameAncestorsExists = false;
        boolean frameAncestorsContainsServerOrWildcard = false;

        if (csp != null && !csp.isEmpty()) {
            // According to http standard, only the first Content-Security Policy is valid. So we take the first element of the Header list.
            frameAncestorsExists = csp.getFirst().toUpperCase().contains("FRAME-ANCESTORS");
            if (frameAncestorsExists) {
                frameAncestorsContainsServerOrWildcard = Stream.of(csp.getFirst().split(";"))
                        .filter(policy -> policy.toUpperCase().contains("FRAME-ANCESTORS"))
                        .map(String::trim)
                        // This is not perfect, but if the site of the toot does not explicitly allow glacier, or all http(s) sites as ancestors, we will most likely not be able to load it.
                        // So this regex should match either *, http(s):, http(s)://* with or without ports or the glacier domain with or without leading http(s) and with or without ports.
                        .anyMatch(policy -> policy.toUpperCase().matches(
                                "FRAME-ANCESTORS (\\S+ )*((HTTPS?:(//)?)|((HTTPS?://)?\\*(:((\\*)|80|443))?)|((HTTPS?://)?"
                                        + glacierDomain.toUpperCase()
                                        + "(:((\\*)|80|443))?))( \\S+)*")
                        );
            }
        }
        if (xFrameOptions != null) {
            xFrameDefaultAllowed = false;

            xFrameExplicitlyNotAllowed = xFrameOptions.stream()
                    .anyMatch(option -> option.equalsIgnoreCase("DENY") || option.equalsIgnoreCase("SAMEORIGIN"));

            xFrameExplicitlyAllowed = xFrameOptions.stream()
                    .anyMatch(option -> option.equalsIgnoreCase("ALLOWALL"));
        }

        if (frameAncestorsExists) {
            if (frameAncestorsContainsServerOrWildcard) {
                LOGGER.info("FRAME-ANCESTORS header exists and this server or a wildcard is allowed");
            } else {
                LOGGER.warn("FRAME-ANCESTORS header exists but this server is not allowed");
            }
            return frameAncestorsContainsServerOrWildcard;
        } else if (xFrameDefaultAllowed) {
            LOGGER.info("FRAME-ANCESTORS header does not exists. X-Frame-Options is default allowed");
            return true;
        } else {
            if (xFrameExplicitlyNotAllowed) {
                LOGGER.warn("FRAME-ANCESTORS header does not exists. X-Frame-Options explicitly not allowed");
                return false;
            } else if (xFrameExplicitlyAllowed) {
                LOGGER.info("FRAME-ANCESTORS header does not exists. X-Frame-Options explicitly allowed");
                return true;
            } else {
                LOGGER.warn("FRAME-ANCESTORS header does not exists. X-Frame-Options has unknown or invalid value: {}", xFrameOptions);
                return false;
            }
        }
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
     * <p>Note: unlike the generic event path ({@link #sendMessage}), this path does not check
     * the bot opt-in mention — it relies on the Mastodon subscription filter having already
     * narrowed the stream to the configured hashtag.
     *
     * @param status      The newly created status.
     * @param destination The base STOMP destination; /creation suffix is appended (informational).
     */
    private void processStatusCreatedEvent(final Status status, final String destination) {
        logEvent("got a StatusCreated event");

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
            // 5. ADR-SHARE-04: relay to viewer share topics after successful cache write
            if (shareViewStompRelay != null && stored != null) {
                shareViewStompRelay.relayTootEvent(principal, hashtag, "creation", stored);
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
     * @param status      The edited status.
     * @param destination The base STOMP destination; /modification suffix is appended (informational).
     */
    private void processStatusEditedEvent(final Status status, final String destination) {
        logEvent("got a StatusEdited event");

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
        // 4. ADR-SHARE-04: relay to viewer share topics
        if (shareViewStompRelay != null && stored != null) {
            shareViewStompRelay.relayTootEvent(principal, hashtag, "modification", stored);
        }
    }

    /**
     * Processes the StatusDeleted event by recording the deletion in the cache and fanning it out.
     *
     * <p>Deletion events carry only a status ID — no URL — so no SSRF guard is required here.</p>
     *
     * @param statusId    The ID of the deleted status.
     * @param destination The base STOMP destination; /deletion suffix is appended (informational).
     */
    private void procesStatusDeletedEvent(final String statusId, final String destination) {
        logEvent("got a StatusDeleted event");
        CacheEntry partial = new CacheEntry(EventType.DELETED, statusId, null, null, 0L);
        CacheEntry stored = messageCache.recordThenPublish(principalKey, hashtag, partial);
        // ADR-SHARE-04: relay to viewer share topics
        if (shareViewStompRelay != null && stored != null) {
            shareViewStompRelay.relayTootEvent(principal, hashtag, "deletion", stored);
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
    private void processTechnicalEvent(final WebSocketEvent event) {
        switch (event) {
            case TechnicalEvent.Open open ->
                    logEvent("got an Open event: %s".formatted(open));
            case TechnicalEvent.Closing closing ->
                    logEvent("got a Closing event: %s".formatted(closing));
            case TechnicalEvent.Closed closed ->
                    logEvent("got a Closed event: %s".formatted(closed));
            case TechnicalEvent.Failure failure -> {
                logEvent("got a Failure event. Restarting subscription. The error is: %s".formatted(failure.getError().getMessage()));
                this.subscriptionManager.terminateSubscription(principal, hashtag);
                this.subscriptionManager.subscribeToHashtag(principal, hashtag);
            }
            default -> logEvent("got an unknown WebSocketEvent: %s".formatted(event));
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
            return scheme != null ? scheme.toLowerCase() : "unknown";
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
            LOGGER.warn("Could not parse editedAt value '{}' — dropping update event (D-07)", raw);
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
