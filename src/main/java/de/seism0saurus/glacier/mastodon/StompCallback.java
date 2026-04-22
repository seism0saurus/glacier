package de.seism0saurus.glacier.mastodon;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.seism0saurus.glacier.util.LogScrubber;
import de.seism0saurus.glacier.webservice.cache.CacheEntry;
import de.seism0saurus.glacier.webservice.cache.EventType;
import de.seism0saurus.glacier.webservice.cache.MessageCache;
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

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.stream.Stream;

/**
 * The StompCallback class implements the WebSocketCallback interface and is responsible for processing WebSocket events.
 *
 * <p>On each qualifying Mastodon streaming event, this class delegates to
 * {@link MessageCache#recordThenPublish} which atomically appends the event to the
 * ring buffer and fans it out over STOMP (D-03).  The {@link MessageCache}
 * implementation owns the {@code SimpMessagingTemplate}; this class no longer holds it.
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
     * Represents a callback for handling WebSocket events related to subscriptions.
     * This class is used in conjunction with SubscriptionManager to manage hashtag subscriptions on Mastodon.
     */
    private final SubscriptionManager subscriptionManager;

    /**
     * The message cache. Atomically records events and fans them out over STOMP (D-03).
     */
    private final MessageCache messageCache;

    /**
     * The REST template is needed to check the headers of the URLs of the toots for X-FRAME headers.
     */
    private final RestTemplate restTemplate;

    /**
     * The principal aka wallId of the subscription this callback.
     */
    private final String principal;

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
     * @param subscriptionManager the manager to call when a stream failure requires restart
     * @param messageCache        the cache that records events and publishes them over STOMP
     * @param restTemplate        the template used to inspect embed headers of toot URLs
     * @param principal           the wallId associated with the subscription
     * @param hashtag             the hashtag to subscribe to
     * @param handle              the bot's full Mastodon handle, used for the opt-in check
     * @param glacierDomain       the glacier domain for iframe-loadability checks
     */
    public StompCallback(final SubscriptionManager subscriptionManager,
                         final MessageCache messageCache,
                         final RestTemplate restTemplate,
                         final String principal,
                         final String hashtag,
                         final String handle,
                         final String glacierDomain) {
        this.subscriptionManager = subscriptionManager;
        this.messageCache = messageCache;
        this.restTemplate = restTemplate;
        this.principal = principal;
        this.hashtag = hashtag;
        this.shortHandle = getShortHandle(handle);
        this.glacierDomain = glacierDomain;
        // D-13/SR-8: never log raw principal — use hashed 8-char prefix
        LOGGER.info("StompCallback for principal-hash={} with hashtag={} created",
                LogScrubber.hash8(principal), hashtag);
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
        LOGGER.info(event.toString());
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
                LOGGER.warn("Not an update event for the subscribed hashtag: {}", genericMessageContent);
            }
        } catch (JsonProcessingException e) {
            LOGGER.error("Could not parse GenericMessage", e);
        }
    }

    private void sendMessage(ObjectMapper mapper, Class<? extends StatusMessage> statusMessageClass, GenericMessageContent genericMessageContent, String destination) throws JsonProcessingException {
        GenericMessageContentPayload payload = mapper.readValue(genericMessageContent.getPayload().textValue(), GenericMessageContentPayload.class);

        // C-03: timeouts on embed HEAD are treated as not-loadable to avoid stalling
        // the Bigbone virtual thread.  See processStatusCreatedEvent for rationale.
        HttpHeaders httpHeaders;
        try {
            httpHeaders = this.restTemplate.headForHeaders(payload.getUrl() + "/embed");
        } catch (RestClientException ex) {
            LOGGER.debug("HEAD request to {}/embed failed — treating as not-loadable (C-03): {}", payload.getUrl(), ex.getMessage());
            return;
        }
        if (isLoadable(httpHeaders, glacierDomain)) {
            if (payload.getMentions().stream().map(Mention::getAcct).anyMatch(shortHandle::equals)) {
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
                messageCache.recordThenPublish(principal, hashtag, partial);
                LOGGER.info("Sending message to {}", destination);
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
     * If a content security policy with a frame-ancestore direvtive exists. That value is used, since it overrules the X-Frame-Options.
     * Otherwise, the X-Frame-Options are used.
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
                        // This is not perfect, but if the site of the too, does not explicitly allow glacier, or all http(s) sites as ancestors, we will most likely not be able to load it.
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
     * Processes a StatusCreated event by sending a creation notification to the specified destination.
     *
     * @param status      The newly created status.
     * @param destination The destination to send the status event. /creation will be appended to it as a suffix.
     */
    private void processStatusCreatedEvent(final Status status, final String destination) {
        logEvent("got a StatusCreated event");
        // C-03: timeouts on embed HEAD are treated as not-loadable to avoid stalling
        // the Bigbone virtual thread.  The RestTemplate bean is configured with a hard
        // connect + read timeout (glacier.embed.*); ResourceAccessException is the
        // runtime wrapper Spring uses for both connect and read timeouts.
        HttpHeaders httpHeaders;
        try {
            httpHeaders = this.restTemplate.headForHeaders(status.getUrl() + "/embed");
        } catch (RestClientException ex) {
            LOGGER.debug("HEAD request to {}/embed failed — treating as not-loadable (C-03): {}", status.getUrl(), ex.getMessage());
            return;
        }
        if (isLoadable(httpHeaders, glacierDomain)) {
            CacheEntry partial = new CacheEntry(EventType.CREATED, status.getId(), status.getUrl() + "/embed", null, 0L);
            messageCache.recordThenPublish(principal, hashtag, partial);
        }
    }

    /**
     * Process a StatusEdited event by sending a modification notification to the specified destination.
     *
     * @param status      The edited status.
     * @param destination The destination to send the status event. /modification will be appended to it as a suffix.
     */
    private void processStatusEditedEvent(final Status status, final String destination) {
        logEvent("got a StatusEdited event");
        CacheEntry partial = new CacheEntry(EventType.UPDATED, status.getId(), status.getUrl() + "/embed", null, 0L);
        messageCache.recordThenPublish(principal, hashtag, partial);
    }

    /**
     * Processes the StatusDeleted event by sending a deletion notification to the specified destination.
     *
     * @param statusId    The ID of the deleted status.
     * @param destination The destination to send the deletion notification to. /deletion will be appended to it as a suffix.
     */
    private void procesStatusDeletedEvent(final String statusId, final String destination) {
        logEvent("got a StatusDeleted event");
        CacheEntry partial = new CacheEntry(EventType.DELETED, statusId, null, null, 0L);
        messageCache.recordThenPublish(principal, hashtag, partial);
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
     * Logs an event.
     *
     * <p>D-13/SR-8: uses hashed principal prefix — never the raw wallId UUID.
     *
     * @param msg The message to be logged.
     */
    private void logEvent(final String msg) {
        LOGGER.info("Subscription principal-hash={} {}", LogScrubber.hash8(principal), msg);
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
}
