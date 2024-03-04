package de.seism0saurus.glacier.mastodon;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.seism0saurus.glacier.webservice.messages.*;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import social.bigbone.api.entity.Status;
import social.bigbone.api.entity.streaming.*;
import social.bigbone.api.entity.streaming.MastodonApiEvent.GenericMessage;

import java.util.UUID;

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
     * The UUID of the subscription.
     */
    private final UUID uuid;

    /**
     * The StompCallback class represents a callback for handling WebSocket events.
     * It is used in conjunction with the SimpMessagingTemplate class to send messages to websocket destinations.
     */
    public StompCallback(final SimpMessagingTemplate simpMessagingTemplate, final UUID uuid) {
        this.simpMessagingTemplate = simpMessagingTemplate;
        this.uuid = uuid;
    }

    /**
     * Handles WebSocket events.
     *
     * @param event The WebSocket event to handle.
     */
    @Override
    public void onEvent(@NotNull final WebSocketEvent event) {
        LOGGER.info(event.toString());
        String baseDestination = "/topic/hashtags/" + uuid.toString();
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
                            LOGGER.info("Subscription " + uuid + " got an unknown StreamEvent: " + streamEvent.getEvent().getClass());
                }
            }
            case TechnicalEvent technicalEvent -> processTechnicalEvent(technicalEvent);
            case GenericMessage genericMessage -> processGenericEvent(genericMessage, baseDestination);
            default -> LOGGER.info("Subscription " + uuid + " got an unknown event " + event.getClass());
        }
    }

    private void processGenericEvent(GenericMessage genericMessage, String baseDestination) {
        String text = genericMessage.getText();
        ObjectMapper mapper = new ObjectMapper();
        try {
            GenericMessageContent genericMessageContent = mapper.readValue(text, GenericMessageContent.class);
            LOGGER.info("Message: " + genericMessageContent);
        } catch (JsonProcessingException e) {
            LOGGER.error("Could not parse GenericMessage", e);
        }
    }

    /**
     * Processes a StatusCreated event by sending a creation notification to the specified destination.
     *
     * @param status The newly created status.
     * @param destination The destination to send the status event. /creation will be appended to it as a suffix.
     */
    private void processStatusCreatedEvent(final Status status, final String destination) {
        logEvent("got a StatusCreated event");
        StatusMessage statusEvent = StatusCreatedMessage.builder().id(status.getId()).author(status.getAccount().getDisplayName()).url(status.getUrl()).build();
        this.simpMessagingTemplate.convertAndSend(destination + "/creation", statusEvent);
    }

    /**
     * Process a StatusEdited event by sending a modification notification to the specified destination.
     *
     * @param status The edited status.
     * @param destination The destination to send the status event. /modification will be appended to it as a suffix.
     */
    private void processStatusEditedEvent(final Status status, final String destination) {
        logEvent("got a StatusEdited event");
        StatusMessage statusEvent = StatusUpdatedMessage.builder().id(status.getId()).text(status.getContent()).build();
        this.simpMessagingTemplate.convertAndSend(destination + "/modification", statusEvent);
    }

    /**
     * Processes the StatusDeleted event by sending a deletion notification to the specified destination.
     *
     * @param statusId The ID of the deleted status.
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
        LOGGER.info("Subscription " + uuid + " " + msg);
    }

    /**
     * Logs an error message.
     *
     * @param msg The error message to be logged.
     */
    private void logError(final String msg) {
        LOGGER.error("An error occurred in handler for subscription " + uuid + " " + msg);
    }
}
