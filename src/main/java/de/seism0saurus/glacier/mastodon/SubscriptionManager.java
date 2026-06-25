package de.seism0saurus.glacier.mastodon;

import java.util.Set;

/**
 * The manager handles subscriptions for hashtags on Mastodon.
 * <p>
 * You can subscribe to a hashtag or terminate a subscription with a UUID.
 *
 * @author seism0saurus
 */
public interface SubscriptionManager {


    /**
     * Subscribes to a hashtag and returns the UUID of the subscription.
     *
     * @param principal The principal fo the user.
     * @param hashtag   The hashtag to subscribe to.
     */
    void subscribeToHashtag(final String principal, final String hashtag);

    /**
     * Terminate a subscription with the given UUID.
     */
    void terminateSubscription(final String principal, final String hashtag);

    void terminateAllSubscriptions(final String principal);

    /**
     * Returns a snapshot of the hashtags currently subscribed by the given principal.
     *
     * <p>Copy-on-read contract (SR-SUB-01/SR-SUB-02): the returned {@link Set} is a
     * defensive copy — never the live internal view. Mutating the returned set has no
     * effect on the subscription state. Returns an empty set (never {@code null}) when
     * the principal has no active subscriptions or is unknown.
     *
     * <p>Thread-safety: both map levels are {@link java.util.concurrent.ConcurrentHashMap},
     * so the key-set read is thread-safe; the snapshot isolates callers from concurrent
     * mutations. Momentary staleness (a subscription added between the read and the copy)
     * is benign — the catalog consumer subscribes to live STOMP topics, so missed hashtags
     * are recovered on the next live toot.
     *
     * @param principal the wallId or other principal identifier; may be unknown
     * @return a non-null, immutable snapshot of subscribed hashtags (empty when unknown)
     */
    Set<String> getSubscribedHashtags(String principal);
}
