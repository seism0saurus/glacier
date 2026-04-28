package de.seism0saurus.glacier.webservice.messaging;

import de.seism0saurus.glacier.mastodon.SubscriptionManager;
import de.seism0saurus.glacier.util.LogScrubber;
import de.seism0saurus.glacier.webservice.cache.MessageCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.messaging.SessionConnectedEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * SubscriptionListener is responsible for handling WebSocket-related events
 * such as connection, disconnection, and subscription changes.
 * <p>
 * It utilizes the SubscriptionManager to manage subscriptions in scenarios
 * like disconnection and reconnection, ensuring subscription integrity.
 * Additionally, it implements a timeout mechanism to handle scenarios where
 * a client does not reconnect within a specified time frame.
 * <p>
 * This class leverages Spring's event handling framework and listens
 * to WebSocket events such as `SessionConnectedEvent`, `SessionDisconnectEvent`.
 * <p>
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
     * Represents the time duration, in milliseconds, to wait before an operation times out.
     * This value is immutable and must be set during initialization.
     */
    private final long timeout;

    /**
     * An ExecutorService instance that utilizes the virtual thread-per-task executor.
     * It is used to manage and execute tasks asynchronously and efficiently, leveraging virtual threads.
     * This implementation facilitates lightweight concurrency and scalability for handling multiple tasks.
     * The executor provides improved performance and resource utilization for threading operations.
     * It is declared as final to ensure immutability and thread safety in its usage.
     */
    private final static ExecutorService executorService = Executors.newVirtualThreadPerTaskExecutor();

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
     * The message cache — evicted directly on disconnect-timer expiry so that
     * cache entries are cleaned up even when the principal has no active Bigbone
     * streaming subscriptions (ADR-05, D-11).
     *
     * <p>The {@link SubscriptionManager#terminateAllSubscriptions} call also triggers
     * cache eviction via {@link MessageCache#evictPrincipal}, but only when the principal
     * has entries in the subscription map.  Clients that provisioned the cache through the
     * HTTP fallback path but never established a WebSocket stream would otherwise leak memory.
     * Calling {@link MessageCache#evictPrincipal} here is idempotent — a no-op if the
     * principal was already evicted by the subscription manager.
     */
    private final MessageCache messageCache;

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
    public SubscriptionListener(final SubscriptionManager subscriptionManager,
                               final MessageCache messageCache,
                               @Value("${glacier.timeouts.client_reconnect}") final long timeout) {
        this.subscriptionManager = subscriptionManager;
        this.messageCache = messageCache;
        this.timeout = timeout;
    }

    /**
     * Checks if there is an active disconnect timer associated with the specified principal.
     *
     * @return true if a disconnect timer is currently running for the specified principal, false otherwise
     */
    protected boolean hasRunningDisconnectTimer() {
        return this.disconnectTimer.containsKey("user1");
    }

    /**
     * Checks if there are any currently running disconnect timers.
     *
     * @return {@code true} if there are active disconnect timers, {@code false} otherwise.
     */
    protected boolean hasRunningDisconnectTimers() {
        return !this.disconnectTimer.isEmpty();
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
            // Fix #7a (ADR-F6-01): simpSessionId is a GDPR personal-data correlator — hash it
            LOGGER.warn("Client with session-hash={} connected but has no user associated with it",
                    LogScrubber.hash8(headerAccessor.getSessionId()));
            return;
        }
        // D-13/SR-8 / ADR-F6-01: hash both sessionId and principal — never log raw values
        LOGGER.info("Client with session-hash={} and username-hash={} connected",
                LogScrubber.hash8(headerAccessor.getSessionId()), LogScrubber.hash8(event.getUser().getName()));
        Future<?> future = this.disconnectTimer.get(event.getUser().getName());
        if (future != null) {
            future.cancel(true);
        }
        this.disconnectTimer.remove(event.getUser().getName());
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
            // Fix #7c (ADR-F6-01): simpSessionId is a GDPR personal-data correlator — hash it
            LOGGER.warn("Client with session-hash={} disconnected but has no user associated with it",
                    LogScrubber.hash8(headerAccessor.getSessionId()));
            return;
        }
        // D-13/SR-8 / ADR-F6-01: hash both sessionId and principal — never log raw values
        LOGGER.info("Client with session-hash={} and username-hash={} disconnected. Starting timer to wait for reconnection",
                LogScrubber.hash8(headerAccessor.getSessionId()), LogScrubber.hash8(event.getUser().getName()));
        Future<?> future = executorService.submit(() -> {
            // D-13/SR-8: hash the principal in all timer-lambda log lines — capture hash once for closure
            // raw principal stays in the closure only to pass to terminateAllSubscriptions
            String principalHash = LogScrubber.hash8(event.getUser().getName());
            LOGGER.info("Timer for principal-hash={} started", principalHash);
            try {
                Thread.sleep(timeout);
            } catch (InterruptedException e) {
                LOGGER.info("Timeout for principal-hash={} was canceled", principalHash);
                return;
            }
            LOGGER.info("Connection for principal-hash={} timed out. Terminating all subscriptions.", principalHash);
            String principalName = event.getUser().getName();
            this.subscriptionManager.terminateAllSubscriptions(principalName);
            // ADR-05 / D-11: also evict the message cache directly so that principals
            // that provisioned the cache without active Bigbone streaming subscriptions
            // (fallback-mode-only clients) are also cleaned up.  Idempotent if already evicted.
            // ADR-SHARE-05 (revised): wrap wallId in PrincipalKey to prevent cross-namespace collision.
            this.messageCache.evictPrincipal(new PrincipalKey(PrincipalKind.WALL, principalName));
            this.disconnectTimer.remove(event.getUser().getName());
        });
        this.disconnectTimer.put(event.getUser().getName(), future);
    }
}
