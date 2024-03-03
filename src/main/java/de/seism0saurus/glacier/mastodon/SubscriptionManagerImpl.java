package de.seism0saurus.glacier.mastodon;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Scope;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import social.bigbone.MastodonClient;

import java.io.Closeable;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
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
    private final Map<UUID, Future<?>> subscriptions;

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
     * The sole constructor for this class.
     * The needed classes are provided by Spring {@link org.springframework.beans.factory.annotation.Value Values}.
     *
     * @param instance    The mastodon instance for this repository. Can be configured in the <code>application.properties</code>.
     * @param accessToken The access token for this repository.
     *                    You get an access token on the instance of your bot at the {@link <a href="https://docs.joinmastodon.org/spec/oauth/#token">Token Endpoint</a>} of your bot's instance or in the GUI.
     *                    Can be configured in the <code>application.properties</code>.
     * @param simpMessagingTemplate The {@link SimpMessagingTemplate SimpMessagingTemplate} of this class. Will be stored to {@link SubscriptionManagerImpl#simpMessagingTemplate simpMessagingTemplate}.
     */
    public SubscriptionManagerImpl(
            @Value(value = "${mastodon.instance}") String instance,
            @Value(value = "${mastodon.accessToken}") String accessToken,
            @Autowired SimpMessagingTemplate simpMessagingTemplate) {
        this.client = new MastodonClient.Builder(instance).accessToken(accessToken).setReadTimeoutSeconds(240).setReadTimeoutSeconds(240).build();
        this.simpMessagingTemplate = simpMessagingTemplate;
        subscriptions = new HashMap<UUID, Future<?>>();
        LOGGER.info("StatusInterfaceImpl for mastodon instance " + instance + " created");
    }

    /**
     * Subscribes to a specified hashtag on Mastodon and starts a virtual thread for asynchronous listening.
     *
     * @param hashtag The hashtag to subscribe to.
     * @return The unique identifier (UUID) associated with the subscription.
     */
    @Override
    public UUID subscribeToHashtag(String hashtag) {
        UUID uuid = UUID.randomUUID();
        var executorService = Executors.newVirtualThreadPerTaskExecutor();
        Future<?> future = executorService.submit(() -> {
            try (Closeable subscription = client.streaming().hashtag(hashtag, false, new StompCallback(simpMessagingTemplate, uuid))) {
                LOGGER.info("Virtual thread for asynchronous listening to Mastodon started");
                sleepForever();
            } catch (IOException e) {
                LOGGER.error("Virtual Thread for asynchronous listening to Mastodon had an exception", e);
                throw new RuntimeException(e);
            }
        });
        subscriptions.put(uuid, future);
        return uuid;
    }

    /**
     * Terminates a subscription with the given UUID.
     *
     * @param uuid The unique identifier (UUID) associated with the subscription
     */
    @Override
    public void terminateSubscription(UUID uuid) {
        Future<?> subscription = this.subscriptions.get(uuid);
        if (subscription != null) {
            this.subscriptions.remove(subscription);
            subscription.cancel(true);
        }
    }

    /**
     * Suspends the current thread indefinitely until it is interrupted.
     * <p>
     * This method continuously sleeps the current thread using the {@link Thread#sleep(long)} method with the maximum
     * possible value of {@link Long#MAX_VALUE} until the thread is interrupted. If the sleep is interrupted by an
     * {@link InterruptedException}, the method logs the exception and re-interrupts the thread.
     */
    private static void sleepForever() {
        try {
            while (!Thread.currentThread().isInterrupted()) {
                Thread.sleep(Long.MAX_VALUE);
            }
        } catch (InterruptedException e) {
            LOGGER.info("Sleep interrupted by exception. Most likely because it was interrupted by a subscription termination", e);
            Thread.currentThread().interrupt();
        }
    }
}
