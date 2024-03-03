package de.seism0saurus.glacier.webservice;

import de.seism0saurus.glacier.mastodon.SubscriptionManager;
import de.seism0saurus.glacier.webservice.messages.SubscriptionAckMessage;
import de.seism0saurus.glacier.webservice.messages.SubscriptionMessage;
import de.seism0saurus.glacier.webservice.messages.TerminationAckMessage;
import de.seism0saurus.glacier.webservice.messages.TerminationMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.annotation.SendToUser;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.CrossOrigin;

import java.util.UUID;

/**
 * The SubscriptionController is responsible for the subscription management via WebSockets.
 * <p>
 * You can create or terminate a subscription for hashtags.
 * After the creation of a subscription the caller gets an acknowledgement with a subscription id.
 * With this id they can subscribe to message queues for toots with the given hashtag.
 * <p>>
 * The management of the Mastodon part of the subscriptions is delegated to the {@link SubscriptionManager SubscriptionManager}.
 *
 * @author seism0saurus
 */
@Controller
public class SubscriptionController {

    /**
     * The {@link org.slf4j.Logger Logger} for this class.
     * The logger is used for logging as configured for the application.
     *
     * @see "src/main/ressources/logback.xml"
     */
    private final static Logger LOGGER = LoggerFactory.getLogger(SubscriptionController.class);

    /**
     * The {@link SubscriptionManager SubscriptionManager} of this class.
     * The SubscriptionManager is used to follow hashtags and receive asynchronous events about toots with the hashtag.
     */
    private final SubscriptionManager subscriptionManager;

    /**
     * The sole constructor for this class.
     * The needed classes are {@link org.springframework.beans.factory.annotation.Autowired autowired} by Spring.
     *
     * @param subscriptionManager The {@link SubscriptionManager SubscriptionManager} of this class. Will be stored to {@link de.seism0saurus.glacier.webservice.SubscriptionController#subscriptionManager subscriptionManager}.
     *
     */
    public SubscriptionController(
            @Autowired SubscriptionManager subscriptionManager) {
        this.subscriptionManager = subscriptionManager;
    }

    /**
     * Subscribes to a hashtag and returns a SubscriptionAckMessage.
     *
     * @param event The SubscriptionMessage containing the hashtag to subscribe to.
     * @return The SubscriptionAckMessage indicating the subscription status.
     */
    @MessageMapping("/subscription")
    @SendToUser("/topic/subscriptions")
    public SubscriptionAckMessage subscribe(SubscriptionMessage event) {
        LOGGER.info(event.toString());
        UUID uuid = this.subscriptionManager.subscribeToHashtag(event.getHashtag());
        LOGGER.info("Subscription " + uuid + " created. Sending response to user....");
        return SubscriptionAckMessage.builder()
                .hashtag(event.getHashtag())
                .subscriptionId(uuid.toString())
                .isSubscribed(true)
                .build();
    }

    /**
     * Unsubscribes from a subscription and returns a TerminationAckMessage.
     *
     * @param event The TerminationMessage containing the subscriptionId to unsubscribe from.
     * @return The TerminationAckMessage indicating the termination status.
     */
    @MessageMapping("/termination")
    @SendToUser("/topic/terminations")
    public TerminationAckMessage unsubscribe(TerminationMessage event) {
        LOGGER.info(event.toString());
        try {
            UUID uuid = UUID.fromString(event.getSubscriptionId());
            this.subscriptionManager.terminateSubscription(uuid);
            return getMessage(uuid.toString(), true, "Subscription " + uuid + " terminates. Sending response to user...");
        } catch (IllegalArgumentException | NullPointerException e){
            return getMessage(event.getSubscriptionId(), false, "The subscriptionId " + event.getSubscriptionId() + " is invalid. Sending response to user...");
        }
    }

    /**
     * Constructs a TerminationAckMessage with the given parameters.
     *
     * @param subscriptionId The subscription ID.
     * @param isTerminated   Indicates if the subscription is terminated.
     * @param logMessage     The log message.
     * @return The TerminationAckMessage object.
     */
    private static TerminationAckMessage getMessage(final String subscriptionId, boolean isTerminated, final String logMessage) {
        LOGGER.info(logMessage);
        return TerminationAckMessage.builder()
                .subscriptionId(subscriptionId)
                .isTerminated(isTerminated)
                .build();
    }
}
