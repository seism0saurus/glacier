package de.seism0saurus.glacier.mastodon;

import java.util.UUID;

/**
 * The manager handles subscriptions for hashtags on Mastodon.
 * <p>
 * You can subscribe to a hashtag or terminate a subscription with an UUID.
 *
 * @author seism0saurus
 */
public interface SubscriptionManager {


    /**
     * Subscribes to a hashtag and returns the UUID of the subscription.
     *
     * @param hashtag The hashtag to subscribe to.
     * @return The UUID of the newly created subscription.
     */
    UUID subscribeToHashtag(final String hashtag);

    /**
     * Terminate a subscription with the given UUID.
     *
     * @param uuid The UUID of the subscription to terminate.
     */
    void terminateSubscription(UUID uuid);
}
