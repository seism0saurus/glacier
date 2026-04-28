package de.seism0saurus.glacier.mastodon;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.seism0saurus.glacier.share.application.SafeUrlValidator;
import de.seism0saurus.glacier.util.LogScrubber;
import de.seism0saurus.glacier.webservice.messaging.messages.*;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.web.client.RestTemplate;
import social.bigbone.api.entity.Status;
import social.bigbone.api.entity.streaming.*;
import social.bigbone.api.entity.streaming.MastodonApiEvent.GenericMessage;

import java.net.URI;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * The StompCallback class implements the WebSocketCallback interface and is responsible for processing WebSocket events.
 *
 * <p>SSRF guard (ADR-PT-01 / ADR-PT-02): before issuing any outbound {@code HEAD} request
 * against a toot URL, the URL is passed through the injected {@link SafeUrlValidator}.
 * If the validator returns empty, the toot is silently dropped and an audit event
 * ({@code stomp.embed.ssrf_blocked}) is emitted without logging the raw URL.</p>
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
     * The simpMessagingTemplate variable is an instance of the SimpMessagingTemplate class. It is used to send messages to WebSocket destinations.
     * The SimpMessagingTemplate class provides methods such as convertAndSend() to convert and send messages to specified destinations.
     * This variable is marked as private and final, indicating that it cannot be modified after initialization and can only be accessed within the containing class.
     */
    private final SimpMessagingTemplate simpMessagingTemplate;

    /**
     * The REST template is needed to check the headers of the URLs of the toots for X-FRAME headers.
     */
    private final RestTemplate restTemplate;

    /**
     * SSRF guard: validates that a toot URL resolves to a public, non-private address
     * and uses an allowed scheme before the callback issues any outbound HTTP request.
     */
    private final SafeUrlValidator safeUrlValidator;

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
     * The StompCallback class represents a callback for handling WebSocket events.
     * It is used in conjunction with the SimpMessagingTemplate class to send messages to websocket destinations.
     *
     * @param subscriptionManager   The SubscriptionManager used for restart-on-failure behaviour.
     * @param simpMessagingTemplate The SimpMessagingTemplate instance used for sending WebSocket messages.
     * @param restTemplate          The RestTemplate instance used for making HTTP requests, to check headers of the embedded iframes.
     * @param safeUrlValidator      The SSRF guard: validates toot URLs before any outbound HTTP request is issued.
     * @param principal             The principal aka wallId associated with the subscription.
     * @param hashtag               The hashtag to subscribe to.
     * @param handle                The full Mastodon handle of the Glacier bot (e.g. {@code @glacier@glacier.events}).
     * @param glacierDomain         The glacier domain for checking if a webpage is loadable as an iframe.
     */
    public StompCallback(final SubscriptionManager subscriptionManager,
                         final SimpMessagingTemplate simpMessagingTemplate,
                         final RestTemplate restTemplate,
                         final SafeUrlValidator safeUrlValidator,
                         final String principal,
                         final String hashtag,
                         final String handle,
                         final String glacierDomain) {
        this.subscriptionManager = subscriptionManager;
        this.simpMessagingTemplate = simpMessagingTemplate;
        this.restTemplate = restTemplate;
        this.safeUrlValidator = safeUrlValidator;
        this.principal = principal;
        this.hashtag = hashtag;
        this.shortHandle = getShortHandle(handle);
        this.glacierDomain = glacierDomain;
        LOGGER.info("StompCallback for {} with hashtag {} created", principal, hashtag);
    }

    private static @NotNull String getShortHandle(String handle) {
        String tmpHandle = handle;
        if (null == tmpHandle){
            throw new IllegalArgumentException("A mastodon handle is needed");
        }
        if (tmpHandle.startsWith("@")){
            // remove initial @
            tmpHandle = tmpHandle.substring(1);
        }
        if (!tmpHandle.contains("@")){
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
            if (genericMessageContent.getStream().contains("hashtag") && "update".equals(genericMessageContent.getEvent())){
                sendMessage(mapper, StatusCreatedMessage.class, genericMessageContent, destination + "/creation");
            } else if (genericMessageContent.getStream().contains("hashtag") && "status.update".equals(genericMessageContent.getEvent())) {
                sendMessage(mapper, StatusUpdatedMessage.class, genericMessageContent, destination + "/modification");
            } else if (genericMessageContent.getStream().contains("hashtag")
                    && ("delete".equals(genericMessageContent.getEvent())
                        || "status.delete".equals(genericMessageContent.getEvent())
                       )
            ) {
                procesStatusDeletedEvent(genericMessageContent.getPayload().textValue(), destination);
            } else {
                LOGGER.warn("Not an update event for the subscribed hashtag: {}", genericMessageContent);
            }
        } catch (JsonProcessingException e) {
            LOGGER.error("Could not parse GenericMessage", e);
        }
    }

    /**
     * Sends a status message to the given destination after passing SSRF and frame-ancestor guards.
     *
     * <p>SSRF guard (ADR-PT-02): the payload URL is validated via {@link SafeUrlValidator}
     * before issuing the outbound {@code HEAD} request. If validation fails, the toot is
     * silently dropped and an audit event is emitted.</p>
     *
     * @param mapper              Jackson mapper for deserialising the payload
     * @param statusMessageClass  the concrete {@link StatusMessage} subtype to build
     * @param genericMessageContent the envelope containing the raw payload JSON
     * @param destination         the STOMP topic destination to publish the message to
     * @throws JsonProcessingException if the payload JSON cannot be parsed
     */
    private void sendMessage(ObjectMapper mapper, Class<? extends StatusMessage> statusMessageClass, GenericMessageContent genericMessageContent, String destination) throws JsonProcessingException {
        GenericMessageContentPayload payload = mapper.readValue(genericMessageContent.getPayload().textValue(), GenericMessageContentPayload.class);

        // SSRF guard: validate the toot URL before issuing any outbound request
        Optional<URI> safeUri = safeUrlValidator.validate(payload.getUrl());
        if (safeUri.isEmpty()) {
            AUDIT.info("stomp.embed.ssrf_blocked url-host-hash={} scheme={}",
                    LogScrubber.urlHostHash(payload.getUrl()),
                    extractScheme(payload.getUrl()));
            return;
        }

        HttpHeaders httpHeaders = this.restTemplate.headForHeaders(payload.getUrl() + "/embed");
        if (isLoadable(httpHeaders, glacierDomain)) {
            if (payload.getMentions().stream().map(Mention::getAcct).anyMatch(shortHandle::equals)) {
                StatusMessage statusEvent = null;
                if (StatusCreatedMessage.class.equals(statusMessageClass)){
                    statusEvent = StatusCreatedMessage.builder().id(payload.getId()).url(payload.getUrl() + "/embed").build();
                } else if (StatusUpdatedMessage.class.equals(statusMessageClass)) {
                    statusEvent = StatusUpdatedMessage.builder().id(payload.getId()).url(payload.getUrl() + "/embed").editedAt(payload.getEditedAt()).build();
                }
                assert statusEvent != null;
                this.simpMessagingTemplate.convertAndSend(destination, statusEvent);
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
     * <p>SSRF guard (ADR-PT-02): the status URL is validated via {@link SafeUrlValidator}
     * before issuing the outbound {@code HEAD} request. If validation fails, the toot is
     * silently dropped and an audit event is emitted — no message is forwarded to the wall.</p>
     *
     * @param status      The newly created status.
     * @param destination The destination to send the status event. /creation will be appended to it as a suffix.
     */
    private void processStatusCreatedEvent(final Status status, final String destination) {
        logEvent("got a StatusCreated event");

        // SSRF guard: validate the toot URL before issuing any outbound request
        Optional<URI> safeUri = safeUrlValidator.validate(status.getUrl());
        if (safeUri.isEmpty()) {
            AUDIT.info("stomp.embed.ssrf_blocked url-host-hash={} scheme={}",
                    LogScrubber.urlHostHash(status.getUrl()),
                    extractScheme(status.getUrl()));
            return;
        }

        HttpHeaders httpHeaders = this.restTemplate.headForHeaders(status.getUrl() + "/embed");
        if (isLoadable(httpHeaders, glacierDomain)) {
            StatusMessage statusEvent = StatusCreatedMessage.builder().id(status.getId()).url(status.getUrl() + "/embed").build();
            this.simpMessagingTemplate.convertAndSend(destination + "/creation", statusEvent);
        }
    }

    /**
     * Process a StatusEdited event by sending a modification notification to the specified destination.
     *
     * <p>SSRF guard (ADR-PT-01 / SR-PT-04): the status URL is validated via {@link SafeUrlValidator}
     * before any message is published to the wall. If the validator returns empty, the edited toot
     * is silently dropped and an audit event ({@code stomp.embed.ssrf_blocked}) is emitted with
     * scrubbed URL metadata — the raw URL is never logged (D-13 / SR-8).</p>
     *
     * @param status      The edited status.
     * @param destination The destination to send the status event. /modification will be appended to it as a suffix.
     */
    private void processStatusEditedEvent(final Status status, final String destination) {
        logEvent("got a StatusEdited event");

        // SSRF guard: validate the toot URL before publishing any message to the wall
        Optional<URI> safeUri = safeUrlValidator.validate(status.getUrl());
        if (safeUri.isEmpty()) {
            AUDIT.info("stomp.embed.ssrf_blocked url-host-hash={} scheme={}",
                    LogScrubber.urlHostHash(status.getUrl()),
                    extractScheme(status.getUrl()));
            return;
        }

        StatusMessage statusEvent = StatusUpdatedMessage.builder().id(status.getId()).url(status.getUrl() + "/embed").build();
        this.simpMessagingTemplate.convertAndSend(destination + "/modification", statusEvent);
    }

    /**
     * Processes the StatusDeleted event by sending a deletion notification to the specified destination.
     *
     * @param statusId    The ID of the deleted status.
     * @param destination The destination to send the deletion notification to. /deletion will be appended to it as a suffix.
     */
    private void procesStatusDeletedEvent(final String statusId, final String destination) {
        logEvent("got a StatusDeleted event");
        StatusMessage statusEvent = StatusDeletedMessage.builder().id(statusId).build();
        this.simpMessagingTemplate.convertAndSend(destination + "/deletion", statusEvent);
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
     * Logs an event.
     *
     * @param msg The message to be logged.
     */
    private void logEvent(final String msg) {
        LOGGER.info("Subscription {} {}", principal, msg);
    }
}
