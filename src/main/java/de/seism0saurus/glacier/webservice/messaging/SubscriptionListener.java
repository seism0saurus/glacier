package de.seism0saurus.glacier.webservice.messaging;

import de.seism0saurus.glacier.mastodon.SubscriptionManager;
import de.seism0saurus.glacier.util.LogScrubber;
import de.seism0saurus.glacier.webservice.cache.MessageCache;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.messaging.SessionConnectedEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * SubscriptionListener is responsible for handling WebSocket-related events
 * such as connection, disconnection, and subscription changes.
 *
 * <p>It utilizes the SubscriptionManager to manage subscriptions in scenarios
 * like disconnection and reconnection, ensuring subscription integrity.
 * Additionally, it implements a timeout mechanism to handle scenarios where
 * a client does not reconnect within a specified time frame.
 *
 * <p>Exponential back-off (P2-02): each successive disconnect without a successful
 * reconnect doubles the wait before subscriptions are terminated, bounded by
 * {@code glacier.timeouts.reconnect_max_ms} (default 5 minutes).  A successful
 * {@link SessionConnectedEvent} resets the back-off counter for that principal.
 *
 * <p>This class leverages Spring's event handling framework and listens
 * to WebSocket events such as {@link SessionConnectedEvent}, {@link SessionDisconnectEvent}.
 *
 * <p>The primary responsibilities of SubscriptionListener include:
 * <ul>
 *   <li>Managing subscription cleanup in disconnect scenarios.</li>
 *   <li>Monitoring and logging client connection and disconnection events.</li>
 * </ul>
 */
@Service
public class SubscriptionListener {

    /**
     * The {@link Logger Logger} for this class.
     * The logger is used for logging as configured for the application.
     *
     * @see "src/main/resources/logback.xml"
     */
    private final static Logger LOGGER = LoggerFactory.getLogger(SubscriptionListener.class);

    /**
     * Represents the initial time duration, in milliseconds, to wait before an operation times out.
     * This is the first delay used in the exponential back-off sequence (P2-02).
     * This value is immutable and must be set during initialization.
     */
    private final long timeout;

    /**
     * Maximum delay cap for the exponential back-off in milliseconds (P2-02).
     * Back-off delays are capped at this value. Defaults to 300 000 ms (5 minutes).
     */
    private final long reconnectMaxMs;

    /**
     * Back-off multiplier applied to successive disconnect delays (P2-02).
     * Defaults to 2.0 — each reconnect window doubles until the cap is reached.
     */
    private final double reconnectMultiplier;

    /**
     * An ExecutorService instance that utilizes the virtual thread-per-task executor.
     * It is used to manage and execute tasks asynchronously and efficiently, leveraging virtual threads.
     * This implementation facilitates lightweight concurrency and scalability for handling multiple tasks.
     * The executor provides improved performance and resource utilization for threading operations.
     * It is declared as final to ensure immutability and thread safety in its usage.
     */
    private final ExecutorService executorService = Executors.newVirtualThreadPerTaskExecutor();

    /**
     * The private final variable subscriptionManager is an instance of the SubscriptionManager interface.
     *
     * <p>It is used to cancel all subscriptions in case of a permanent connection loss.
     *
     * <p>The SubscriptionManager interface defines methods to handle subscriptions for hashtags on Mastodon, such as
     * subscribing to a hashtag and terminating a subscription.
     * By using this variable, you can access the functionality provided by the SubscriptionManager interface to
     * manage and manipulate subscriptions.
     *
     * <p>Example usage:
     * <pre>{@code
     * subscriptionManager.subscribeToHashtag(principal, hashtag);
     * subscriptionManager.terminateSubscription(principal, hashtag);
     * subscriptionManager.terminateAllSubscriptions(principal);
     * }</pre>
     *
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
     *
     * <p>Keys are principal names (wallId strings); values are the timer {@link Future} instances
     * scheduled when a client disconnects. Uses {@link ConcurrentHashMap} because this map is
     * mutated from STOMP event-listener threads and virtual-thread timer closures concurrently (Q-02).
     */
    private final Map<String, Future<?>> disconnectTimer = new ConcurrentHashMap<>();

    /**
     * Per-principal back-off delay (P2-02).
     *
     * <p>Tracks the current reconnect wait for each principal as an {@link AtomicLong}
     * (milliseconds).  On each successive disconnect the delay doubles up to
     * {@link #reconnectMaxMs}. On a successful {@link SessionConnectedEvent} the entry
     * is removed, resetting the back-off to the initial value on next disconnect.
     */
    private final Map<String, AtomicLong> backoffDelayMs = new ConcurrentHashMap<>();

    /**
     * Constructs a new instance of SubscriptionListener with the provided SubscriptionManager.
     *
     * @param subscriptionManager  the SubscriptionManager to be used for managing subscriptions
     * @param messageCache         the message cache for direct eviction of fallback-path clients
     * @param timeout              initial reconnect timeout (and base back-off delay) in milliseconds
     * @param reconnectMaxMs       maximum back-off delay cap in milliseconds (default 300 000)
     * @param reconnectMultiplier  back-off multiplier applied on each successive disconnect (default 2.0)
     */
    public SubscriptionListener(final SubscriptionManager subscriptionManager,
                               final MessageCache messageCache,
                               @Value("${glacier.timeouts.client_reconnect}") final long timeout,
                               @Value("${glacier.timeouts.reconnect_max_ms:300000}") final long reconnectMaxMs,
                               @Value("${glacier.timeouts.reconnect_multiplier:2.0}") final double reconnectMultiplier) {
        this.subscriptionManager = subscriptionManager;
        this.messageCache = messageCache;
        this.timeout = timeout;
        this.reconnectMaxMs = reconnectMaxMs;
        this.reconnectMultiplier = reconnectMultiplier;
    }

    /**
     * Checks if there is an active disconnect timer associated with the specified principal.
     *
     * @param principal the principal (wallId) to check for a running disconnect timer
     * @return true if a disconnect timer is currently running for the given principal, false otherwise
     */
    protected boolean hasRunningDisconnectTimer(String principal) {
        return this.disconnectTimer.containsKey(principal);
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
     * Shuts the reconnect-timer executor down on bean destruction (F7).
     *
     * <p>The executor is owned by this bean (no longer a {@code static} field), so it is
     * bound to the Spring lifecycle and never outlives the application context — this
     * gives clean shutdown in production and proper isolation across integration-test
     * contexts. {@code shutdownNow()} interrupts any sleeping reconnect timers; they
     * handle {@link InterruptedException} and exit without terminating subscriptions.
     */
    @PreDestroy
    void shutdownExecutor() {
        this.executorService.shutdownNow();
    }

    /** Diagnostic accessor (F7): whether the timer executor has been shut down. */
    protected boolean isExecutorShutdown() {
        return this.executorService.isShutdown();
    }

    /**
     * Handles the event when a session is connected.
     *
     * <p>When a client connects, the timers are checked.
     * If the client was connected shortly before and lost the connection temporarily,
     * the timer is stopped. The back-off counter is reset so the next disconnect starts
     * fresh from the initial delay (P2-02).
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
        String principalName = event.getUser().getName();
        Future<?> future = this.disconnectTimer.get(principalName);
        if (future != null) {
            future.cancel(true);
        }
        this.disconnectTimer.remove(principalName);
        // Reset back-off counter — successful reconnect means next disconnect starts fresh (P2-02)
        this.backoffDelayMs.remove(principalName);
    }

    /**
     * Handles the event when a session is disconnected.
     *
     * <p>To prevent a loss of subscriptions a timer is started on disconnect.
     * If the client does not come back after the current back-off delay, the subscriptions are
     * terminated. Otherwise, the timer is stopped and the old subscriptions can be accessed
     * through the known endpoints.
     *
     * <p>Exponential back-off (P2-02): the wait delay doubles on each successive disconnect,
     * capped at {@link #reconnectMaxMs}. The back-off resets on a successful
     * {@link SessionConnectedEvent}.
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
        String principalName = event.getUser().getName();

        // Compute and advance the back-off delay for this principal (P2-02)
        long currentDelay = nextBackoffDelay(principalName);

        // D-13/SR-8 / ADR-F6-01: hash both sessionId and principal — never log raw values
        LOGGER.info("Client with session-hash={} and username-hash={} disconnected. " +
                        "Starting timer with backoff-delay-ms={} to wait for reconnection",
                LogScrubber.hash8(headerAccessor.getSessionId()),
                LogScrubber.hash8(principalName),
                currentDelay);

        // Q-02 / F3: holds a reference to *this* timer's Future so the lambda can remove
        // itself from the map identity-safely (only if it is still the current timer).
        final AtomicReference<Future<?>> selfRef = new AtomicReference<>();
        Future<?> future = executorService.submit(() -> {
            // D-13/SR-8: hash the principal in all timer-lambda log lines — capture hash once for closure
            // raw principal stays in the closure only to pass to terminateAllSubscriptions
            String principalHash = LogScrubber.hash8(principalName);
            LOGGER.info("Timer for principal-hash={} started with backoff-delay-ms={}", principalHash, currentDelay);
            try {
                Thread.sleep(currentDelay);
            } catch (InterruptedException e) {
                LOGGER.info("Timeout for principal-hash={} was canceled", principalHash);
                return;
            }
            LOGGER.info("Connection for principal-hash={} timed out. Terminating all subscriptions.", principalHash);
            this.subscriptionManager.terminateAllSubscriptions(principalName);
            // ADR-05 / D-11: also evict the message cache directly so that principals
            // that provisioned the cache without active Bigbone streaming subscriptions
            // (fallback-mode-only clients) are also cleaned up.  Idempotent if already evicted.
            // ADR-SHARE-05 (revised): wrap wallId in PrincipalKey to prevent cross-namespace collision.
            this.messageCache.evictPrincipal(new PrincipalKey(PrincipalKind.WALL, principalName));
            // F3: identity-safe removal — only clear the map entry if it still points at THIS
            // timer, so a newer disconnect's timer (installed concurrently) is never dropped.
            this.disconnectTimer.remove(principalName, selfRef.get());
            // Clear back-off state after termination so a fresh reconnect starts from scratch
            this.backoffDelayMs.remove(principalName);
        });
        selfRef.set(future);
        // F3: atomically install the new timer and cancel any prior (orphaned) timer for this
        // principal. Without this, a second disconnect would overwrite the map entry while the
        // first timer kept running — and could terminate all subscriptions even after the client
        // successfully reconnected (onConnectedEvent only cancels the *currently mapped* future).
        // The cancelled timer is interrupted mid-sleep and returns early without terminating.
        this.disconnectTimer.compute(principalName, (key, previous) -> {
            if (previous != null) {
                previous.cancel(true);
            }
            return future;
        });
    }

    /**
     * Computes the next back-off delay for the given principal and advances the stored
     * counter for the next call (P2-02).
     *
     * <p>The sequence is: {@code timeout, timeout * multiplier, timeout * multiplier², …}
     * capped at {@link #reconnectMaxMs}.
     *
     * <p>Uses an {@link AtomicLong} stored in {@link #backoffDelayMs} so concurrent
     * disconnect events for the same principal are safe (unlikely but possible with
     * multiple sessions sharing one wallId).
     *
     * @param principalName the wallId to compute back-off for
     * @return the delay to use for this disconnect
     */
    long nextBackoffDelay(String principalName) {
        AtomicLong holder = backoffDelayMs.computeIfAbsent(principalName, k -> new AtomicLong(timeout));
        long current = holder.get();
        long next = (long) Math.min(current * reconnectMultiplier, reconnectMaxMs);
        holder.set(next);
        return current;
    }
}
