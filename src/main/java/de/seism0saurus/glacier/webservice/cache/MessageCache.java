package de.seism0saurus.glacier.webservice.cache;

/**
 * Central coordination point for the HTTP fallback cache.
 *
 * <p>Every Mastodon streaming event that passes the {@code isLoadable} and opt-in
 * gates in {@code StompCallback} is routed through {@link #recordThenPublish}, which
 * atomically assigns a sequence number, persists the event in the per-{@code (principal,
 * hashtag)} ring, and publishes it on the STOMP broker.  The HTTP fallback endpoint
 * reads the ring via {@link #snapshot}.
 *
 * <p>Lifecycle operations ({@link #provisionHashtag}, {@link #evictHashtag},
 * {@link #evictPrincipal}) are driven by {@code SubscriptionManagerImpl} so that
 * the cache lifetime is always subordinate to the subscription lifetime (ADR-05).
 */
public interface MessageCache {

    /**
     * Appends {@code partial} to the ring for {@code (principal, hashtag)}, assigns
     * the next monotonic sequence number, and publishes the resulting {@link CacheEntry}
     * to the STOMP destination for this event type.
     *
     * <p>If {@code convertAndSend} throws, the failure is logged at WARN with
     * {@code {principal-hash, hashtag, sequence, statusId, eventType}} and the Micrometer
     * counter {@code glacier.fallback.publish.failures} is incremented.  The cache entry
     * is <em>not</em> rolled back — that is the whole point of the fallback (D-03).
     *
     * <p>If the tuple is not provisioned this method is a no-op (SR-2.4 defense-in-depth).
     *
     * @param principal the wallId that owns the subscription
     * @param hashtag   the subscribed hashtag
     * @param partial   a {@link CacheEntry} without a sequence number (sequence is ignored)
     * @return the persisted entry with the assigned sequence number; may be {@code null}
     *         if the tuple was not provisioned
     */
    CacheEntry recordThenPublish(String principal, String hashtag, CacheEntry partial);

    /**
     * Returns a snapshot of all events in the ring for {@code (principal, hashtag)}
     * that are newer than {@code since}.
     *
     * @param principal the wallId that owns the subscription
     * @param hashtag   the subscribed hashtag
     * @param since     the client's last-seen sequence number; {@code null} means
     *                  "return everything"
     * @return a {@link Snapshot} with a defensive copy of matching entries,
     *         the current head sequence, and a gap flag
     * @throws UnknownSubscriptionException when the {@code (principal, hashtag)} tuple
     *                                      has not been provisioned or has been evicted
     */
    Snapshot snapshot(String principal, String hashtag, Long since);

    /**
     * Allocates a ring for {@code (principal, hashtag)} if one does not already exist
     * (idempotent re-provision is a no-op).
     *
     * @param principal the wallId that owns the subscription
     * @param hashtag   the subscribed hashtag
     * @throws CacheCapacityException when adding this tuple would exceed
     *                                {@code glacier.cache.maxHashtagsPerPrincipal}
     *                                or {@code glacier.cache.maxPrincipals} (D-11)
     */
    void provisionHashtag(String principal, String hashtag);

    /**
     * Removes the ring for {@code (principal, hashtag)}.
     *
     * <p>Subsequent calls to {@link #recordThenPublish} for this tuple will be no-ops.
     *
     * @param principal the wallId that owns the subscription
     * @param hashtag   the subscribed hashtag
     */
    void evictHashtag(String principal, String hashtag);

    /**
     * Removes all rings owned by {@code principal}.
     *
     * @param principal the wallId whose subscriptions are all being torn down
     */
    void evictPrincipal(String principal);

    /**
     * Returns {@code true} when the {@code (principal, hashtag)} tuple has an active ring.
     *
     * @param principal the wallId to check
     * @param hashtag   the hashtag to check
     * @return {@code true} iff the tuple is provisioned
     */
    boolean isProvisioned(String principal, String hashtag);
}
