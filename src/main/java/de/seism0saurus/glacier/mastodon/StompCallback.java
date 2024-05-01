package de.seism0saurus.glacier.mastodon;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
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

import java.util.List;

/**
 * The StompCallback class implements the WebSocketCallback interface and is responsible for processing WebSocket events.
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
     * The principal aka wallId of the subscription this callback.
     */
    private final String principal;

    /**
     * This variable represents the hashtag of subscription of this callback.
     */
    private final String hashtag;

    private final String handle;
    /**
     * The glacierDomain variable represents the domain used for this instance of glacier.
     */
    private final String glacierDomain;

    /**
     * Initializes a new instance of the StompCallback class.
     * The StompCallback class represents a callback for handling WebSocket events.
     * It is used in conjunction with the SimpMessagingTemplate class to send messages to websocket destinations.
     *
     * @param simpMessagingTemplate The SimpMessagingTemplate instance used for sending WebSocket messages.
     * @param restTemplate          The RestTemplate instance used for making HTTP requests, to check headers of the embedded iframes.
     * @param principal             The principal aka wallId associated with the subscription.
     * @param hashtag               The hashtag to subscribe to.
     * @param glacierDomain         The glacier domain for checking if a webpage is loadable as an iframe.
     */
    public StompCallback(final SubscriptionManager subscriptionManager,
                         final SimpMessagingTemplate simpMessagingTemplate,
                         final RestTemplate restTemplate,
                         final String principal,
                         final String hashtag,
                         final String handle,
                         final String glacierDomain) {
        this.subscriptionManager = subscriptionManager;
        this.simpMessagingTemplate = simpMessagingTemplate;
        this.restTemplate = restTemplate;
        this.principal = principal;
        this.hashtag = hashtag;
        this.handle = handle;
        this.glacierDomain = glacierDomain;
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
                    default ->
                            LOGGER.info("Subscription " + principal + " got an unknown StreamEvent: " + streamEvent.getEvent().getClass());
                }
            }
            case TechnicalEvent technicalEvent -> processTechnicalEvent(technicalEvent);
            case GenericMessage genericMessage -> processGenericEvent(genericMessage, baseDestination);
            default -> LOGGER.info("Subscription " + principal + " got an unknown event " + event.getClass());
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
            if (genericMessageContent.getStream().contains("hashtag")
                    && ("update".equals(genericMessageContent.getEvent())
                    || "status.update".equals(genericMessageContent.getEvent())
            )
            ) {
                GenericMessageContentPayload payload = mapper.readValue(genericMessageContent.getPayload().textValue(), GenericMessageContentPayload.class);

                HttpHeaders httpHeaders = this.restTemplate.headForHeaders(payload.getUrl() + "/embed");
                if (isLoadable(httpHeaders, glacierDomain)) {
                    if (payload.getMentions().stream().map(s -> s.getAcct()).anyMatch( a -> handle.equals(a))) {
                        StatusMessage statusEvent = StatusCreatedMessage.builder().id(payload.getId()).url(payload.getUrl() + "/embed").build();
                        this.simpMessagingTemplate.convertAndSend(destination + "/creation", statusEvent);
                    } else {
                        LOGGER.info("No opt in. Ignoring");
                    }
                }
            } else if (genericMessageContent.getStream().contains("hashtag")
                    && ("delete".equals(genericMessageContent.getEvent())
                    || "status.delete".equals(genericMessageContent.getEvent())
            )
            ) {
                LOGGER.info("Delete toot with id {} id", genericMessageContent.getPayload().textValue());
            } else {
                LOGGER.warn("Not an update event for the subscribed hashtag", genericMessageContent);
            }
        } catch (JsonProcessingException e) {
            LOGGER.error("Could not parse GenericMessage", e);
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

        if (csp != null) {
            frameAncestorsExists = csp.stream()
                    .anyMatch(policy -> policy.toUpperCase().startsWith("FRAME-ANCESTORS"));
            if (frameAncestorsExists) {
                frameAncestorsContainsServerOrWildcard = csp.stream()
                        .filter(policy -> policy.toUpperCase().startsWith("FRAME-ANCESTORS"))
                        .anyMatch(policy -> policy.toUpperCase().matches(
                                "FRAME-ANCESTORS (\\S+ )*(((https?:\\/\\/)?\\*(:((\\*)|80|443))?)|((https?:\\/\\/)?"
                                        + glacierDomain
                                        + "(:((\\*)|80|443))?))( \\S+)*;")
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
                LOGGER.warn("FRAME-ANCESTORS header does not exists. X-Frame-Options has unknown or invalid value" + xFrameOptions);
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
        HttpHeaders httpHeaders = this.restTemplate.headForHeaders(status.getUrl() + "/embed");
        if (status.getMentions().contains(new Status.Mention())){

        }
        if (isLoadable(httpHeaders, glacierDomain)) {
            StatusMessage statusEvent = StatusCreatedMessage.builder().id(status.getId()).author(status.getAccount().getDisplayName()).url(status.getUrl() + "/embed").build();
            this.simpMessagingTemplate.convertAndSend(destination + "/creation", statusEvent);
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
                    LOGGER.info("Subscription {principal} got a Open event: {}", principal, open);
            case TechnicalEvent.Closing closing ->
                    LOGGER.info("Subscription {principal} got a Closing event: {}", principal, closing);
            case TechnicalEvent.Closed closed ->
                    LOGGER.info("Subscription {principal} got a Closed event: {}", principal, closed);
            case TechnicalEvent.Failure failure -> {
                LOGGER.error("Subscription {principal} got a Failure event. Restarting subscription. The error is: {}", principal, failure.getError());
                this.subscriptionManager.terminateSubscription(principal, hashtag);
                this.subscriptionManager.subscribeToHashtag(principal, hashtag);
            }
            default -> LOGGER.info("Subscription {principal} got an unknown WebSocketEvent: {}", principal, event);
        }
    }

    /**
     * Logs an event.
     *
     * @param msg The message to be logged.
     */
    private void logEvent(final String msg) {
        LOGGER.info("Subscription " + principal + " " + msg);
    }
}
