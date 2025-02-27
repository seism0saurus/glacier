package de.seism0saurus.glacier.webservice;

import de.seism0saurus.glacier.mastodon.SubscriptionManager;
import de.seism0saurus.glacier.webservice.messaging.messages.SubscriptionAckMessage;
import de.seism0saurus.glacier.webservice.messaging.messages.SubscriptionMessage;
import de.seism0saurus.glacier.webservice.messaging.messages.TerminationAckMessage;
import de.seism0saurus.glacier.webservice.messaging.messages.TerminationMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.annotation.SendToUser;
import org.springframework.stereotype.Controller;

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
     */
    public SubscriptionController(
            SubscriptionManager subscriptionManager) {
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
    public SubscriptionAckMessage subscribe(SimpMessageHeaderAccessor headerAccessor, SubscriptionMessage event) {
        if (headerAccessor.getUser() == null) {
            LOGGER.error("Someone tried to subscribe without a principal. This is not supported. HeaderAccessor: {}", headerAccessor);
            return SubscriptionAckMessage.builder()
                    .hashtag(event.getHashtag())
                    .principal(null)
                    .isSubscribed(false)
                    .build();
        }
        String principal = headerAccessor.getUser().getName();
        LOGGER.info("Subscription event for principal {} and hashtag {} received", principal, event.getHashtag());
        this.subscriptionManager.subscribeToHashtag(principal, event.getHashtag());
        LOGGER.info("Subscription event for principal {} and hashtag {} handled. Sending response to user...", principal, event.getHashtag());
        return SubscriptionAckMessage.builder()
                .hashtag(event.getHashtag())
                .principal(principal)
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
    public TerminationAckMessage unsubscribe(SimpMessageHeaderAccessor headerAccessor, TerminationMessage event) {
        if (headerAccessor.getUser() == null) {
            LOGGER.error("Someone tried to unsubscribe without a principal. This is not supported. HeaderAccessor: {}", headerAccessor);
            return getMessage(null, event.getHashtag(), false, "Could not unsubscibe due to missing principal. Sending response to user...");
        }
        String principal = headerAccessor.getUser().getName();
        LOGGER.info("Termination event for principal {} and hashtag {} received", principal, event.getHashtag());
        try {
            this.subscriptionManager.terminateSubscription(principal, event.getHashtag());
            return getMessage(principal, event.getHashtag(), true, "Subscription for principal " + principal + " and hashtag " + event.getHashtag() + " terminated. Sending response to user...");
        } catch (IllegalArgumentException | NullPointerException e) {
            return getMessage(principal, event.getHashtag(), false, "The subscriptionId " + event.getHashtag() + " is invalid. Sending response to user...");
        }
    }

    /**
     * Constructs a TerminationAckMessage with the given parameters.
     *
     * @param principal    The subscription ID.
     * @param isTerminated Indicates if the subscription is terminated.
     * @param logMessage   The log message.
     * @return The TerminationAckMessage object.
     */
    private static TerminationAckMessage getMessage(final String principal, final String hashtag, boolean isTerminated, final String logMessage) {
        LOGGER.info(logMessage);
        return TerminationAckMessage.builder()
                .principal(principal)
                .hashtag(hashtag)
                .isTerminated(isTerminated)
                .build();
    }
}
