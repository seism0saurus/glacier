package de.seism0saurus.glacier.webservice.messaging;

import de.seism0saurus.glacier.mastodon.SubscriptionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.messaging.SessionConnectedEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * SubscriptionListener is responsible for handling WebSocket-related events
 * such as connection, disconnection, and subscription changes.
 *
 * It utilizes the SubscriptionManager to manage subscriptions in scenarios
 * like disconnection and reconnection, ensuring subscription integrity.
 * Additionally, it implements a timeout mechanism to handle scenarios where
 * a client does not reconnect within a specified time frame.
 *
 * This class leverages Spring's event handling framework and listens
 * to WebSocket events such as `SessionConnectedEvent`, `SessionDisconnectEvent`.
 *
 * The primary responsibilities of SubscriptionListener include:
 * - Managing subscription cleanup in disconnect scenarios.
 * - Monitoring and logging client connection and disconnection events.
 */
@Service
public class SubscriptionListener {

    /**
     * The {@link Logger Logger} for this class.
     * The logger is used for logging as configured for the application.
     *
     * @see "src/main/ressources/logback.xml"
     */
    private final static Logger LOGGER = LoggerFactory.getLogger(SubscriptionListener.class);

    /**
     * The private final variable subscriptionManager is an instance of the SubscriptionManager interface.
     * <p>
     * It is used to cancel all subscriptions in case of a permanent connection loss.
     * <p>
     * The SubscriptionManager interface defines methods to handle subscriptions for hashtags on Mastodon, such as
     * subscribing to a hashtag and terminating a subscription.
     * By using this variable, you can access the functionality provided by the SubscriptionManager interface to
     * manage and manipulate subscriptions.
     * <p>
     * Example usage:
     * subscriptionManager.subscribeToHashtag(principal, hashtag);
     * subscriptionManager.terminateSubscription(principal, hashtag);
     * subscriptionManager.terminateAllSubscriptions(principal);
     * <p>
     * For more details, refer to the documentation of the SubscriptionManager interface.
     */
    private final SubscriptionManager subscriptionManager;

    /**
     * A map used to store timers for disconnect events.
     * <p>
     * The keys are String values representing the unique identifiers for the disconnect events,
     * and the values are Future objects that represent the timers associated with the events.
     */
    private final Map<String, Future<?>> disconnectTimer = new HashMap<>();

    /**
     * Constructs a new instance of SubscriptionListener with the provided SubscriptionManager.
     *
     * @param subscriptionManager the SubscriptionManager to be used for managing subscriptions
     */
    public SubscriptionListener(final SubscriptionManager subscriptionManager) {
        this.subscriptionManager = subscriptionManager;
    }

    /**
     * Handles the event when a session is connected.
     * <p>
     * When a client connects, the timers are checked.
     * If the client was connected shortly before and lost the connection temporarily,
     * the timer is stopped.
     *
     * @param event The SessionConnectedEvent object containing the event details.
     */
    @EventListener
    public void onConnectedEvent(SessionConnectedEvent event) {
        SimpMessageHeaderAccessor headerAccessor = SimpMessageHeaderAccessor.wrap(event.getMessage());
        if (event.getUser() == null) {
            LOGGER.warn("Client with session {} connected but has no user associated with it", headerAccessor.getSessionId());
            return;
        }
        LOGGER.info("Client with session {} and username {} connected", headerAccessor.getSessionId(), event.getUser().getName());
        Future<?> future = this.disconnectTimer.get(event.getUser().getName());
        if (future != null) {
            future.cancel(true);
        }
    }

    /**
     * Handles the event when a session is disconnected.
     * <p>
     * To prevent a loss of subscriptions a timer is started on disconnect.
     * If the client does not come back after the defined time (5 minutes) the subscriptions are terminated.
     * Otherwise, the timer is stopped and the old subscriptions can be accessed through the known endpoints.
     *
     * @param event The SessionDisconnectEvent object containing the event details.
     */
    @EventListener
    public void onDisconnectEvent(SessionDisconnectEvent event) {
        SimpMessageHeaderAccessor headerAccessor = SimpMessageHeaderAccessor.wrap(event.getMessage());
        if (event.getUser() == null) {
            LOGGER.warn("Client with session {} disconnected but has no user associated with it", headerAccessor.getSessionId());
            return;
        }
        LOGGER.info("Client with session {} and username {} disconnected. Starting timer to wait for reconnection", headerAccessor.getSessionId(), event.getUser().getName());
        Future<?> future;
        try (var executorService = Executors.newVirtualThreadPerTaskExecutor()) {
            future = executorService.submit(() -> {
                LOGGER.info("Timer for principal {} started", event.getUser().getName());
                try {
                    Thread.sleep(300_000L);
                } catch (InterruptedException e) {
                    LOGGER.info("Timeout for principal {} was canceled", event.getUser().getName());
                    return;
                }
                LOGGER.info("Connection for principal {} timed out. Terminating all subscriptions.", event.getUser().getName());
                this.subscriptionManager.terminateAllSubscriptions(event.getUser().getName());
            });
        }
        this.disconnectTimer.put(event.getUser().getName(), future);
    }


}
