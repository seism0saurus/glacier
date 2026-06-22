package de.seism0saurus.glacier.mastodon;

import de.seism0saurus.glacier.share.application.SafeUrlValidator;
import de.seism0saurus.glacier.share.application.ShareViewStompRelay;
import de.seism0saurus.glacier.util.LogScrubber;
import de.seism0saurus.glacier.webservice.cache.MessageCache;
import de.seism0saurus.glacier.webservice.messaging.PrincipalKey;
import de.seism0saurus.glacier.webservice.messaging.PrincipalKind;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import social.bigbone.MastodonClient;
import social.bigbone.api.method.StreamingMethods;

import java.io.Closeable;
import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * The SubscriptionManagerImpl class is responsible for managing subscriptions for hashtags on Mastodon.
 * It implements the SubscriptionManager interface.
 *
 * @see SubscriptionManager
 */
@Service
@Scope(value = ConfigurableBeanFactory.SCOPE_SINGLETON)
public class SubscriptionManagerImpl implements SubscriptionManager {

    /**
     * The {@link org.slf4j.Logger Logger} for this class.
     * The logger is used for logging as configured for the application.
     *
     * @see "src/main/resources/logback.xml"
     */
    private final static Logger LOGGER = LoggerFactory.getLogger(SubscriptionManagerImpl.class);

    /**
     * An ExecutorService instance that utilizes the virtual thread-per-task executor.
     * It is used to manage and execute tasks asynchronously and efficiently, leveraging virtual threads.
     * This implementation facilitates lightweight concurrency and scalability for handling multiple tasks.
     * The executor provides improved performance and resource utilization for threading operations.
     * It is declared as final to ensure immutability and thread safety in its usage.
     */
    private final ExecutorService executorService = Executors.newVirtualThreadPerTaskExecutor();

    /**
     * The list of subscriptions as map of Futures.
     *
     * <p>Both the outer and inner maps are {@link ConcurrentHashMap} so that concurrent
     * mutations from virtual threads, STOMP event-listener threads, and disconnect-timer
     * threads cannot corrupt the data structure (Q-01).
     */
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, Future<?>>> subscriptions;

    /**
     * The message cache. Provisioned before starting a virtual-thread subscription and
     * evicted on termination so that the cache lifetime tracks the subscription lifetime (ADR-05).
     */
    private final MessageCache messageCache;

    /**
     * The domain for the Glacier service.
     */
    private final String glacierDomain;

    /**
     * The validated Mastodon handle of the bot account.
     *
     * <p>Passed to each {@link StompCallback} so that the opt-in check can compare the
     * bot's local part against mention accounts (ADR-P3A-2, ADR-PT-A04-01).
     */
    private final MastodonShortHandle shortHandle;

    /**
     * The {@link RestTemplate RestTemplate} of this class.
     * The template is passed to the {@link StompCallback StompCallback}, so that the callback can check http headers of urls for iframes.
     */
    private final RestTemplate restTemplate;

    /**
     * Relay that fans toot events to viewer-scoped share topics (ADR-SHARE-04).
     * May be null when the share feature is not active.
     */
    private final ShareViewStompRelay shareViewStompRelay;

    /**
     * The SSRF guard passed through to each {@link StompCallback} instance.
     * Validates toot URLs before any outbound HTTP request or cache write is issued (ADR-PT-01 / SR-PT-10).
     */
    private final SafeUrlValidator safeUrlValidator;

    private final StreamingMethods streaming;

    /**
     * Constructs a SubscriptionManagerImpl instance with the specified configuration values,
     * client, message cache, REST template, share view relay, and SSRF validator.
     *
     * @param instance            the Mastodon instance URL (used for startup log only)
     * @param glacierDomain       the domain for Glacier integration
     * @param shortHandle         the validated Mastodon handle of the bot account (ADR-P3A-2)
     * @param client              the Mastodon client used for API interactions
     * @param messageCache        the ring-buffer cache for event storage and STOMP fan-out
     * @param restTemplate        the REST template for making HTTP requests
     * @param shareViewStompRelay relay for fan-out to share viewer topics (ADR-SHARE-04)
     * @param safeUrlValidator    the SSRF guard passed to each {@link StompCallback} (ADR-PT-01 / SR-PT-10)
     */
    public SubscriptionManagerImpl(
            @Value(value = "${mastodon.instance}") String instance,
            @Value(value = "${glacier.domain}") String glacierDomain,
            MastodonShortHandle shortHandle,
            MastodonClient client,
            MessageCache messageCache,
            RestTemplate restTemplate,
            ShareViewStompRelay shareViewStompRelay,
            SafeUrlValidator safeUrlValidator) {
        this.glacierDomain = glacierDomain;
        this.shortHandle = shortHandle;
        this.restTemplate = restTemplate;
        this.messageCache = messageCache;
        this.shareViewStompRelay = shareViewStompRelay;
        this.safeUrlValidator = safeUrlValidator;
        this.subscriptions = new ConcurrentHashMap<>();
        this.streaming = client.streaming();
        LOGGER.info("StatusInterfaceImpl for mastodon instance {} created", instance);
    }

    /**
     * Subscribes to a specified hashtag on Mastodon and starts a virtual thread for asynchronous listening.
     *
     * <p>Calls {@link MessageCache#provisionHashtag} before submitting the virtual thread.
     * If {@code provisionHashtag} throws {@link de.seism0saurus.glacier.webservice.cache.CacheCapacityException},
     * the exception propagates to the caller ({@code SubscriptionController}) without
     * starting the Bigbone streaming thread.
     *
     * @param principal The principal of the user.
     * @param hashtag   The hashtag to subscribe to.
     */
    /**
     * Shuts the subscription executor down on bean destruction (F7).
     *
     * <p>The executor is owned by this singleton bean (no longer a {@code static} field),
     * binding it to the Spring lifecycle so it never outlives the application context —
     * clean shutdown in production, proper isolation across integration-test contexts.
     * {@code shutdownNow()} interrupts the per-subscription keep-alive threads (which sleep
     * in 60 s loops); their {@link InterruptedException} path closes the Bigbone stream.
     */
    @PreDestroy
    void shutdownExecutor() {
        this.executorService.shutdownNow();
    }

    /** Diagnostic accessor (F7): whether the subscription executor has been shut down. */
    protected boolean isExecutorShutdown() {
        return this.executorService.isShutdown();
    }

    @Override
    public void subscribeToHashtag(String principal, String hashtag) {
        LOGGER.info("subscribeToHashtag");
        assert principal != null;
        assert hashtag != null;
        // Atomically obtain the per-principal map, creating it if absent (Q-01).
        // computeIfAbsent is atomic on ConcurrentHashMap — no separate get + put needed.
        ConcurrentHashMap<String, Future<?>> previousSubscriptions =
                subscriptions.computeIfAbsent(principal, k -> new ConcurrentHashMap<>());
        if (previousSubscriptions.get(hashtag) != null) {
            // D-13/SR-8: log only hashed principal and hashtag length — never raw values
            LOGGER.info("A subscription for principal-hash={} with hashtag-len={} already exists",
                    LogScrubber.hash8(principal), LogScrubber.hashtagLen(hashtag));
            return;
        }

        // CacheCapacityException propagates to SubscriptionController — do not catch here (D-11)
        // ADR-SHARE-05 (revised): wrap wallId in PrincipalKey to prevent cross-namespace collision
        messageCache.provisionHashtag(new PrincipalKey(PrincipalKind.WALL, principal), hashtag);

        LOGGER.debug("Submitting asynchronous future task...");
        Future<?> future = executorService.submit(() -> {
            StompCallback stompCallback = new StompCallback(
                    this, messageCache, shareViewStompRelay, restTemplate,
                    safeUrlValidator, principal, hashtag, shortHandle, glacierDomain);
            try (Closeable subscription = streaming.hashtag(hashtag, false, stompCallback)) {
                // D-13/SR-8: log only hashed principal and hashtag length — never raw values
                LOGGER.info("Asynchronous subscription for principal-hash={} with hashtag-len={} started",
                        LogScrubber.hash8(principal), LogScrubber.hashtagLen(hashtag));
                sleepForever(subscription);
            } catch (NullPointerException | IOException e) {
                // D-13/SR-8: log only hashed principal and hashtag length — never raw values
                LOGGER.error("Asynchronous subscription for principal-hash={} with hashtag-len={} had an exception",
                        LogScrubber.hash8(principal), LogScrubber.hashtagLen(hashtag), e);
                throw new RuntimeException(e);
            }
        });
        // putIfAbsent: if a concurrent subscription raced us to the same key, prefer the
        // winner's future and cancel ours — the subscription must be idempotent (Q-01).
        Future<?> existing = previousSubscriptions.putIfAbsent(hashtag, future);
        if (existing != null) {
            future.cancel(true);
        }
    }

    /**
     * Terminates a subscription for a given principal and hashtag.
     *
     * <p>Calls {@link MessageCache#evictHashtag} after cancelling the future (ADR-05).
     *
     * @param principal The principal associated with the subscription.
     * @param hashtag   The hashtag of the subscription to be terminated.
     * @throws IllegalArgumentException If the provided principal or hashtag is unknown.
     */
    @Override
    public void terminateSubscription(final String principal, final String hashtag) {
        ConcurrentHashMap<String, Future<?>> subscriptionsOfPrincipal = this.subscriptions.get(principal);
        if (subscriptionsOfPrincipal == null) {
            // D-13/SR-8: never echo raw principal in exception messages (feeds into 4xx responses)
            throw new IllegalArgumentException("The provided principal is unknown");
        }
        Future<?> subscription = subscriptionsOfPrincipal.get(hashtag);
        if (subscription == null) {
            // D-13/SR-8: never echo raw principal in exception messages (feeds into 4xx responses)
            throw new IllegalArgumentException("The provided hashtag is unknown for this principal");
        }
        subscriptionsOfPrincipal.remove(hashtag);
        if (subscriptionsOfPrincipal.isEmpty()) {
            this.subscriptions.remove(principal);
        } else {
            this.subscriptions.put(principal, subscriptionsOfPrincipal);
        }
        subscription.cancel(true);
        messageCache.evictHashtag(new PrincipalKey(PrincipalKind.WALL, principal), hashtag);
    }

    /**
     * Terminate all subscriptions for the given principal.
     *
     * <p>Calls {@link MessageCache#evictPrincipal} after cancelling all futures (ADR-05).
     *
     * @param principal The principal for which subscriptions should be terminated.
     */
    @Override
    public void terminateAllSubscriptions(String principal) {
        // Always evict the cache for this principal — the disconnect timer guarantees
        // eviction regardless of whether subscriptions were already removed individually
        // (e.g., via terminateSubscription). This prevents dormant cache entries from
        // accumulating toward the 10 000-principal cap (ADR-05, D-11 memory-reclamation).
        ConcurrentHashMap<String, Future<?>> futureMap = this.subscriptions.get(principal);
        if (futureMap != null) {
            futureMap.forEach((tag, future) -> future.cancel(true));
            this.subscriptions.remove(principal);
        }
        messageCache.evictPrincipal(new PrincipalKey(PrincipalKind.WALL, principal));
    }

    /**
     * Checks if the specified principal is subscribed.
     *
     * @param principal the identifier of the principal to check for subscription status
     * @return true if the principal is subscribed, otherwise false
     */
    public boolean hasPrincipalSubscriptions(String principal) {
        return subscriptions.containsKey(principal);
    }

    /**
     * Checks if a given hashtag is subscribed by the specified principal.
     *
     * @param principal the unique identifier of the principal (e.g., user or entity).
     * @param hashtag the hashtag to check for subscription.
     * @return true if the principal has subscribed to the specified hashtag, false otherwise.
     */
    public boolean isHashtagSubscribedByPrincipal(String principal, String hashtag) {
        return subscriptions.containsKey(principal) && subscriptions.get(principal).containsKey(hashtag);
    }

    /**
     * Calculates the number of subscriptions associated with the given principal.
     *
     * @param principal the identifier for the user or entity whose subscriptions are being queried
     * @return the total number of subscriptions associated with the specified principal
     */
    public int numberOfSubscriptions(String principal) {
        ConcurrentHashMap<String, Future<?>> principalMap = subscriptions.get(principal);
        return principalMap != null ? principalMap.size() : 0;
    }

    /**
     * Suspends the current thread indefinitely until it is interrupted.
     * <p>
     * This method continuously sleeps the current thread using the {@link Thread#wait()} method
     * until the thread is interrupted. If the sleep is interrupted by an {@link InterruptedException},
     * the method logs the exception and re-interrupts the thread.
     */
    private static void sleepForever(Closeable subscription) {
        try {
            while (!Thread.currentThread().isInterrupted()) {
                // DANGER. The Stream is only kept open if we have this sleep.
                // It closes directly after openening, if this is a wait or other construct. Dont't know why :(
                Thread.sleep(60_000L);
            }
        } catch (InterruptedException e) {
            LOGGER.info("Sleep interrupted by InterruptedException. Most likely because it was interrupted by a subscription termination", e);
            try {
                subscription.close();
            } catch (IOException ex) {
                throw new RuntimeException(ex);
            }
            Thread.currentThread().interrupt();
        }
    }
}
