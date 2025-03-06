package de.seism0saurus.glacier.mastodon;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Scope;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import social.bigbone.MastodonClient;
import social.bigbone.api.method.StreamingMethods;

import java.io.Closeable;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
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
     * @see "src/main/ressources/logback.xml"
     */
    private final static Logger LOGGER = LoggerFactory.getLogger(SubscriptionManagerImpl.class);

    /**
     * An ExecutorService instance that utilizes the virtual thread-per-task executor.
     * It is used to manage and execute tasks asynchronously and efficiently, leveraging virtual threads.
     * This implementation facilitates lightweight concurrency and scalability for handling multiple tasks.
     * The executor provides improved performance and resource utilization for threading operations.
     * It is declared as final to ensure immutability and thread safety in its usage.
     */
    private final static ExecutorService executorService = Executors.newVirtualThreadPerTaskExecutor();

    /**
     * The list of subscriptions as list of Futures.
     */
    private final Map<String, Map<String, Future<?>>> subscriptions;

    /**
     * The {@link SimpMessagingTemplate SimpMessagingTemplate} of this class.
     * The template is passed to the {@link StompCallback StompCallback}, so that the callback can send asynchronous messages via WebSockets.
     */
    private final SimpMessagingTemplate simpMessagingTemplate;

    /**
     * The domain for the Glacier service.
     */
    private final String glacierDomain;

    /**
     * The mastodon handle of this instance.
     */
    private final String handle;

    /**
     * The {@link RestTemplate RestTemplate} of this class.
     * The template is passed to the {@link StompCallback StompCallback}, so that the callback can check http headers of urls for iframes.
     */
    private final RestTemplate restTemplate;

    private final StreamingMethods streaming;

    /**
     * Constructs a SubscriptionManagerImpl instance with the specified configuration values,
     * client, messaging template, and REST template.
     *
     * @param instance the Mastodon instance URL
     * @param glacierDomain the domain for Glacier integration
     * @param handle the Mastodon user handle
     * @param client the Mastodon client used for API interactions
     * @param simpMessagingTemplate the messaging template for WebSocket communications
     * @param restTemplate the REST template for making HTTP requests
     */
    public SubscriptionManagerImpl(
            @Value(value = "${mastodon.instance}") String instance,
            @Value(value = "${glacier.domain}") String glacierDomain,
            @Value(value = "${mastodon.handle}") String handle,
            MastodonClient client,
            SimpMessagingTemplate simpMessagingTemplate,
            RestTemplate restTemplate) {
        this.glacierDomain = glacierDomain;
        this.handle = handle;
        this.restTemplate = restTemplate;
        this.simpMessagingTemplate = simpMessagingTemplate;
        this.subscriptions = new HashMap<>();
        this.streaming = client.streaming();
        LOGGER.info("StatusInterfaceImpl for mastodon instance {} created", instance);
    }

    /**
     * Subscribes to a specified hashtag on Mastodon and starts a virtual thread for asynchronous listening.
     *
     * @param principal The principal of the user.
     * @param hashtag   The hashtag to subscribe to.
     */
    @Override
    public void subscribeToHashtag(String principal, String hashtag) {
        LOGGER.info("subscribeToHashtag");
        assert principal != null;
        assert hashtag != null;
        subscriptions.computeIfAbsent(principal, k -> new HashMap<>());
        Map<String, Future<?>> previousSubscriptions = subscriptions.get(principal);
        if (previousSubscriptions.get(hashtag) != null) {
            LOGGER.info("A subscription for principal {} with the hashtag {} already exists", principal, hashtag);
            return;
        }
        Future<?> future;
        LOGGER.debug("Submitting asynchronous future task...");
        future = executorService.submit(() -> {
            StompCallback stompCallback = new StompCallback(this, simpMessagingTemplate, restTemplate, principal, hashtag, handle, glacierDomain);
            try (Closeable subscription = streaming.hashtag(hashtag, false, stompCallback)) {
                LOGGER.info("Asynchronous subscription for {} with the hashtag {} started", principal, hashtag);
                sleepForever(subscription);
            } catch (NullPointerException | IOException e) {
                LOGGER.error("Asynchronous subscription for {} with the hashtag {} had an exception", principal, hashtag, e);
                throw new RuntimeException(e);
            }
        });
        previousSubscriptions.put(hashtag, future);
        subscriptions.put(principal, previousSubscriptions);
    }

    /**
     * Terminates a subscription for a given principal and hashtag.
     *
     * @param principal The principal associated with the subscription.
     * @param hashtag   The hashtag of the subscription to be terminated.
     * @throws IllegalArgumentException If the provided principal or hashtag is unknown.
     */
    @Override
    public void terminateSubscription(final String principal, final String hashtag) {
        Map<String, Future<?>> subscriptionsOfPrincipal = this.subscriptions.get(principal);
        if (subscriptionsOfPrincipal == null) {
            throw new IllegalArgumentException("The provided principal " + principal + " is unknown");
        }
        Future<?> subscription = subscriptionsOfPrincipal.get(hashtag);
        if (subscription == null) {
            throw new IllegalArgumentException("The provided hashtag " + hashtag + " for principal " + principal + " is unknown");
        }
        subscriptionsOfPrincipal.remove(hashtag);
        if (subscriptionsOfPrincipal.isEmpty()) {
            this.subscriptions.remove(principal);
        } else {
            this.subscriptions.put(principal, subscriptionsOfPrincipal);
        }
        subscription.cancel(true);
    }

    /**
     * Terminate all subscriptions for the given principal.
     *
     * @param principal The principal for which subscriptions should be terminated.
     */
    @Override
    public void terminateAllSubscriptions(String principal) {
        Map<String, Future<?>> futureMap = this.subscriptions.get(principal);
        if (futureMap == null) {
            return;
        }
        futureMap.forEach((tag, future) -> future.cancel(true));
        this.subscriptions.remove(principal);
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
        return subscriptions.getOrDefault(principal, new HashMap<>()).size();
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
