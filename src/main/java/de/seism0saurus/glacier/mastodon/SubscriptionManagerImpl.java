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

import java.io.Closeable;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
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
     * The list of subscriptions as list of Futures.
     */
    private final Map<String, Map<String, Future<?>>> subscriptions;

    /**
     * The mastodon client is required to communicate with the configured mastodon instance.
     */
    private final MastodonClient client;

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

    /**
     * The sole constructor for this class.
     * The needed classes are provided by Spring {@link org.springframework.beans.factory.annotation.Value Values}.
     *
     * @param instance              The mastodon instance for this repository. Can be configured in the <code>application.properties</code>.
     * @param accessToken           The access token for this repository.
     *                              You get an access token on the instance of your bot at the {@link <a href="https://docs.joinmastodon.org/spec/oauth/#token">Token Endpoint</a>} of your bot's instance or in the GUI.
     *                              Can be configured in the <code>application.properties</code>.
     * @param simpMessagingTemplate The {@link SimpMessagingTemplate SimpMessagingTemplate} of this class. Will be stored to {@link SubscriptionManagerImpl#simpMessagingTemplate simpMessagingTemplate}.
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
        this.client = client;
        this.simpMessagingTemplate = simpMessagingTemplate;
        subscriptions = new HashMap<String, Map<String, Future<?>>>();
        LOGGER.info("StatusInterfaceImpl for mastodon instance " + instance + " created");
    }

    /**
     * Subscribes to a specified hashtag on Mastodon and starts a virtual thread for asynchronous listening.
     *
     * @param principal
     * @param hashtag   The hashtag to subscribe to.
     */
    @Override
    public void subscribeToHashtag(String principal, String hashtag) {
        if (subscriptions.get(principal) == null) {
            subscriptions.put(principal, new HashMap<>());
        }
        Map<String, Future<?>> previousSubscriptions = subscriptions.get(principal);
        if (previousSubscriptions.get(hashtag) != null) {
            LOGGER.info("A subscription for principal {} with the hastag {} already exists", principal, hashtag);
            return;
        }

        var executorService = Executors.newVirtualThreadPerTaskExecutor();
        Future<?> future = executorService.submit(() -> {
            try (Closeable subscription = client.streaming().hashtag(hashtag, false, new StompCallback(this, simpMessagingTemplate, restTemplate, principal, hashtag, handle, glacierDomain))) {
                LOGGER.info("Asynchronous subscription for {} with the hashtag {} started", principal, hashtag);
                sleepForever(subscription);
            } catch (IOException e) {
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
     * Suspends the current thread indefinitely until it is interrupted.
     * <p>
     * This method continuously sleeps the current thread using the {@link Thread#sleep(long)} method with the maximum
     * possible value of {@link Long#MAX_VALUE} until the thread is interrupted. If the sleep is interrupted by an
     * {@link InterruptedException}, the method logs the exception and re-interrupts the thread.
     */
    private static void sleepForever(Closeable subscription) {
        try {
            while (!Thread.currentThread().isInterrupted()) {
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
