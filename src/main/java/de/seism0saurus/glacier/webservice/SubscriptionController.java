package de.seism0saurus.glacier.webservice;

import de.seism0saurus.glacier.mastodon.SubscriptionManager;
import de.seism0saurus.glacier.util.LogScrubber;
import de.seism0saurus.glacier.webservice.cache.CacheCapacityException;
import de.seism0saurus.glacier.webservice.messaging.messages.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.annotation.SendToUser;
import org.springframework.stereotype.Controller;

import java.util.Map;

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
     * Dedicated AUDIT logger for security-relevant events (D-13, SR-8).
     * Routes {@code cache.capacity.exhausted} events to the AUDIT channel.
     */
    private static final Logger AUDIT = LoggerFactory.getLogger("AUDIT");

    /**
     * The {@link SubscriptionManager SubscriptionManager} of this class.
     * The SubscriptionManager is used to follow hashtags and receive asynchronous events about toots with the hashtag.
     */
    private final SubscriptionManager subscriptionManager;

    /**
     * The configured maximum number of hashtags a single principal may subscribe to.
     * Injected from {@code glacier.cache.maxHashtagsPerPrincipal} so that the rejection
     * details map carries the actual configured limit (D-12).
     */
    private final int maxHashtagsPerPrincipal;

    /**
     * The sole constructor for this class.
     * The needed classes are {@link org.springframework.beans.factory.annotation.Autowired autowired} by Spring.
     *
     * @param subscriptionManager      The {@link SubscriptionManager SubscriptionManager} of this class.
     * @param maxHashtagsPerPrincipal  The configured per-principal hashtag cap (D-11).
     */
    public SubscriptionController(
            SubscriptionManager subscriptionManager,
            @Value("${glacier.cache.maxHashtagsPerPrincipal:10}") int maxHashtagsPerPrincipal) {
        this.subscriptionManager = subscriptionManager;
        this.maxHashtagsPerPrincipal = maxHashtagsPerPrincipal;
    }

    /**
     * Subscribes to a hashtag and returns a SubscriptionAckMessage.
     *
     * <p>When the configured per-principal or global principal cap is reached, a negative
     * acknowledgement is returned with {@code rejection.code == CAP_EXCEEDED} and
     * {@code rejection.details.limit} set to the configured per-principal limit (D-11, D-12).
     * The exception is never allowed to escape past {@code @SendToUser} — a thrown exception
     * would corrupt the STOMP frame.
     *
     * @param event The SubscriptionMessage containing the hashtag to subscribe to.
     * @return The SubscriptionAckMessage indicating the subscription status.
     */
    @MessageMapping("/subscription")
    @SendToUser("/topic/subscriptions")
    public SubscriptionAckMessage subscribe(SimpMessageHeaderAccessor headerAccessor, SubscriptionMessage event) {
        if (headerAccessor.getUser() == null) {
            // D-13/SR-8: log sessionId only — not the raw headerAccessor (may contain cookies)
            LOGGER.error("Someone tried to subscribe without a principal. This is not supported. sessionId={}",
                    headerAccessor.getSessionId());
            return SubscriptionAckMessage.builder()
                    .hashtag(event.getHashtag())
                    .principal(null)
                    .isSubscribed(false)
                    .build();
        }
        String principal = headerAccessor.getUser().getName();
        // D-13/SR-8: log only hashed principal — never the raw wallId UUID
        LOGGER.info("Subscription event for principal-hash={} and hashtag-len={} received",
                LogScrubber.hash8(principal), LogScrubber.hashtagLen(event.getHashtag()));
        try {
            this.subscriptionManager.subscribeToHashtag(principal, event.getHashtag());
        } catch (CacheCapacityException e) {
            // D-13/SR-8: log only hashed principal — never the raw wallId UUID
            LOGGER.warn("Subscription rejected for principal-hash={} hashtag-len={} — cap exceeded",
                    LogScrubber.hash8(principal), LogScrubber.hashtagLen(event.getHashtag()));
            // D-13: emit cache.capacity.exhausted to AUDIT logger (planning line 105)
            AUDIT.info("cache.capacity.exhausted principal-hash={} limit={} axis=hashtags-per-principal",
                    LogScrubber.hash8(principal), maxHashtagsPerPrincipal);
            SubscriptionRejection rejection = SubscriptionRejection.builder()
                    .code(RejectionCode.CAP_EXCEEDED)
                    .details(Map.of("limit", maxHashtagsPerPrincipal))
                    .build();
            return SubscriptionAckMessage.builder()
                    .hashtag(event.getHashtag())
                    .principal(principal)
                    .isSubscribed(false)
                    .rejection(rejection)
                    .build();
        }
        // D-13/SR-8: log only hashed principal — never the raw wallId UUID
        LOGGER.info("Subscription event for principal-hash={} handled. Sending response to user...",
                LogScrubber.hash8(principal));
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
            // D-13/SR-8: log sessionId only — not the raw headerAccessor
            LOGGER.error("Someone tried to unsubscribe without a principal. This is not supported. sessionId={}",
                    headerAccessor.getSessionId());
            return getMessage(null, event.getHashtag(), false, "Could not unsubscibe due to missing principal. Sending response to user...");
        }
        String principal = headerAccessor.getUser().getName();
        // D-13/SR-8: log only hashed principal — never the raw wallId UUID
        LOGGER.info("Termination event for principal-hash={} and hashtag-len={} received",
                LogScrubber.hash8(principal), LogScrubber.hashtagLen(event.getHashtag()));
        try {
            this.subscriptionManager.terminateSubscription(principal, event.getHashtag());
            return getMessage(principal, event.getHashtag(), true,
                    "Subscription for principal-hash=" + LogScrubber.hash8(principal) + " and hashtag-len="
                            + LogScrubber.hashtagLen(event.getHashtag()) + " terminated. Sending response to user...");
        } catch (IllegalArgumentException | NullPointerException e) {
            return getMessage(principal, event.getHashtag(), false,
                    "The subscription with hashtag-len=" + LogScrubber.hashtagLen(event.getHashtag()) + " is invalid. Sending response to user...");
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
