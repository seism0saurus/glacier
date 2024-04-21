package de.seism0saurus.glacier.mastodon;

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
     * @param principal
     * @param hashtag   The hashtag to subscribe to.
     */
    void subscribeToHashtag(final String principal, final String hashtag);

    /**
     * Terminate a subscription with the given UUID.
     */
    void terminateSubscription(final String principal, final String hashtag);

    void terminateAllSubscriptions(final String principal);
}
