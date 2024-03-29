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
     * The UUID of the subscription.
     */
    private final String principal;
    private final String hashtag;
    private final String glacierDomain;

    /**
     * The StompCallback class represents a callback for handling WebSocket events.
     * It is used in conjunction with the SimpMessagingTemplate class to send messages to websocket destinations.
     */
    public StompCallback(final SimpMessagingTemplate simpMessagingTemplate,
                         final RestTemplate restTemplate,
                         final String principal,
                         final String hashtag,
                         final String glacierDomain) {
        this.simpMessagingTemplate = simpMessagingTemplate;
        this.restTemplate = restTemplate;
        this.principal = principal;
        this.hashtag = hashtag;
        this.glacierDomain = glacierDomain;
    }

    /**
     * Handles WebSocket events.
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

    private void processGenericEvent(GenericMessage genericMessage, String destination) {
        logEvent("got a GenericMessage event");
        String text = genericMessage.getText();
        ObjectMapper mapper = new ObjectMapper();
        try {
            GenericMessageContent genericMessageContent = mapper.readValue(text, GenericMessageContent.class);
            if (genericMessageContent.getStream().contains("hashtag")) {
                if ("update".equals(genericMessageContent.getEvent())) {
                    GenericMessageContentPayload payload = mapper.readValue(genericMessageContent.getPayload().textValue(), GenericMessageContentPayload.class);
                    HttpHeaders httpHeaders = this.restTemplate.headForHeaders(payload.getUrl() + "/embed");
                    if (isLoadable(httpHeaders, glacierDomain)) {
                        StatusMessage statusEvent = StatusCreatedMessage.builder().id(payload.getId()).url(payload.getUrl() + "/embed").build();
                        this.simpMessagingTemplate.convertAndSend(destination + "/creation", statusEvent);
                    }
                }
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
                    .anyMatch(option -> option.toUpperCase().equals("DENY") || option.toUpperCase().equals("SAMEORIGIN"));

            xFrameExplicitlyAllowed = xFrameOptions.stream()
                    .anyMatch(option -> option.toUpperCase().equals("ALLOWALL"));
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
        if (event instanceof TechnicalEvent.Failure) {
            logError(((TechnicalEvent.Failure) event).getError().getMessage());
        } else {
            String eventName = ((TechnicalEvent) event).getClass().getSimpleName();
            logEvent("got an event for " + eventName);
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

    /**
     * Logs an error message.
     *
     * @param msg The error message to be logged.
     */
    private void logError(final String msg) {
        LOGGER.error("An error occurred in handler for subscription " + principal + " " + msg);
    }
}
