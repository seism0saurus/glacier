package de.seism0saurus.glacier.webservice.cache;

import de.seism0saurus.glacier.util.LogScrubber;
import de.seism0saurus.glacier.webservice.messaging.PrincipalKey;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Scope;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Default {@link MessageCache} implementation.
 *
 * <p>Storage layout: {@code outer[principal] → inner[hashtag] → PerTagRing}.
 * Both maps are {@link ConcurrentHashMap}; all mutation inside a ring is serialised
 * by {@link PerTagRing}'s own {@code ReentrantLock}.
 *
 * <p>STOMP publish destination is
 * {@code /topic/hashtags/{principal}/{hashtag}/{suffix}}, where suffix is:
 * {@code creation} / {@code modification} / {@code deletion} (matches the
 * existing fan-out in the legacy {@code StompCallback}).
 *
 * <p>Log content contract (D-13): only {@code {principal-hash, hashtag, sequence,
 * statusId, eventType}} are logged.  The full wallId, toot URL, {@code editedAt},
 * and toot body are never emitted.
 */
@Service
@Scope(value = ConfigurableBeanFactory.SCOPE_SINGLETON)
public class MessageCacheImpl implements MessageCache {

    /**
     * The {@link Logger} for this class.
     *
     * @see "src/main/ressources/logback.xml"
     */
    private static final Logger LOGGER = LoggerFactory.getLogger(MessageCacheImpl.class);

    private final SimpMessagingTemplate simpMessagingTemplate;
    private final int ringCapacity;
    private final int maxHashtagsPerPrincipal;
    private final int maxPrincipals;

    /**
     * Kill-switch flag (D-11).
     *
     * <p>When {@code false} ({@code glacier.fallback.enabled=false}), both
     * {@link #provisionHashtag} and the ring-write path of {@link #recordThenPublish}
     * become no-ops. STOMP fan-out inside {@code recordThenPublish} is deliberately
     * preserved so that live WebSocket clients continue to receive toots — only the
     * cache write is suppressed.
     */
    private final boolean fallbackEnabled;

    /** Outer map keyed by PrincipalKey — prevents cross-namespace collision (ADR-SHARE-05). */
    private final ConcurrentHashMap<PrincipalKey, ConcurrentHashMap<String, PerTagRing>> store;

    private final Counter publishFailureCounter;

    /**
     * Constructs the cache service with all required collaborators.
     *
     * <p>Three Micrometer instruments are registered on construction (D-11):
     * <ul>
     *   <li>{@code glacier.fallback.publish.failures} — counter incremented on every
     *       {@code convertAndSend} failure (D-03); the cache entry is never rolled back.</li>
     *   <li>{@code glacier.cache.principals.count} — gauge reflecting the current number
     *       of provisioned principals; approaches {@code glacier.cache.maxPrincipals}
     *       as the cap is reached.</li>
     *   <li>{@code glacier.cache.entries.total} — gauge summing all ring occupancies across
     *       every {@code (principal, hashtag)} pair; bounded by
     *       {@code maxPrincipals × maxHashtagsPerPrincipal × ringCapacity}.</li>
     * </ul>
     *
     * @param simpMessagingTemplate     the Spring STOMP template used for fan-out
     * @param meterRegistry             the Micrometer registry for counter and gauge registration
     * @param ringCapacity              {@code glacier.cache.size} — max entries per ring (default 20)
     * @param maxHashtagsPerPrincipal   {@code glacier.cache.maxHashtagsPerPrincipal} (default 10)
     * @param maxPrincipals             {@code glacier.cache.maxPrincipals} (default 10 000)
     * @param fallbackEnabled           {@code glacier.fallback.enabled} kill-switch (default {@code true});
     *                                  when {@code false}, cache writes are suppressed but STOMP fan-out
     *                                  is preserved for live WebSocket clients (D-11)
     */
    public MessageCacheImpl(
            final SimpMessagingTemplate simpMessagingTemplate,
            final MeterRegistry meterRegistry,
            @Value("${glacier.cache.size:20}") final int ringCapacity,
            @Value("${glacier.cache.maxHashtagsPerPrincipal:10}") final int maxHashtagsPerPrincipal,
            @Value("${glacier.cache.maxPrincipals:10000}") final int maxPrincipals,
            @Value("${glacier.fallback.enabled:true}") final boolean fallbackEnabled) {
        this.simpMessagingTemplate = simpMessagingTemplate;
        this.ringCapacity = ringCapacity;
        this.maxHashtagsPerPrincipal = maxHashtagsPerPrincipal;
        this.maxPrincipals = maxPrincipals;
        this.fallbackEnabled = fallbackEnabled;
        this.store = new ConcurrentHashMap<>();
        // store is ConcurrentHashMap<PrincipalKey, ...> — prevents cross-namespace collision (ADR-SHARE-05)
        this.publishFailureCounter = meterRegistry.counter("glacier.fallback.publish.failures");

        // D-11 — operational visibility for the principal and entry-count caps.
        // These gauges are consumed by a container-level scraping agent (e.g. Prometheus
        // sidecar), not via the actuator HTTP endpoint (which exposes health,info only —
        // see application.properties and D-A7).
        Gauge.builder("glacier.cache.principals.count", this.store, java.util.Map::size)
                .description("Current number of provisioned principals in the message cache")
                .register(meterRegistry);
        Gauge.builder("glacier.cache.entries.total", this.store,
                        s -> s.values().stream()
                              .mapToLong(rings -> rings.values().stream()
                                                       .mapToLong(PerTagRing::size)
                                                       .sum())
                              .sum())
                .description("Total number of cached entries across all principals and hashtags")
                .register(meterRegistry);
    }

    @Override
    public CacheEntry recordThenPublish(final PrincipalKey key, final String hashtag, final CacheEntry partial) {
        // D-11 kill-switch: suppress cache write but preserve STOMP fan-out for live WS clients.
        // The destination and payload can be derived from the parameters without the ring.
        if (!fallbackEnabled) {
            LOGGER.debug("Kill-switch active — skipping cache write for principal-hash={} hashtag={}; " +
                    "STOMP fan-out preserved for live WS clients (D-11)",
                    LogScrubber.hash8(key.name()), hashtag);
            String destination = destinationFor(key.name(), hashtag, partial.type());
            try {
                simpMessagingTemplate.convertAndSend(destination, buildStompPayload(partial));
            } catch (Exception ex) {
                LOGGER.warn("STOMP publish failed (kill-switch path) principal-hash={} hashtag={} statusId={} — " +
                        "no cache entry to preserve (D-11)",
                        LogScrubber.hash8(key.name()), hashtag, partial.statusId());
                publishFailureCounter.increment();
            }
            return null;
        }

        ConcurrentHashMap<String, PerTagRing> principalRings = store.get(key);
        if (principalRings == null) {
            LOGGER.warn("recordThenPublish called for un-provisioned principal-hash={} hashtag={} — dropping (SR-2.4)",
                    LogScrubber.hash8(key.name()), hashtag);
            return null;
        }
        PerTagRing ring = principalRings.get(hashtag);
        if (ring == null) {
            LOGGER.warn("recordThenPublish called for un-provisioned principal-hash={} hashtag={} — dropping (SR-2.4)",
                    LogScrubber.hash8(key.name()), hashtag);
            return null;
        }

        CacheEntry stored = ring.append(partial);

        String destination = destinationFor(key.name(), hashtag, stored.type());
        try {
            simpMessagingTemplate.convertAndSend(destination, buildStompPayload(stored));
            LOGGER.info("Sending message to {} sequence={} statusId={}", destination, stored.sequence(), stored.statusId());
        } catch (Exception ex) {
            LOGGER.warn("STOMP publish failed principal-hash={} hashtag={} sequence={} statusId={} eventType={} — cache entry preserved (D-03)",
                    LogScrubber.hash8(key.name()), hashtag, stored.sequence(), stored.statusId(), stored.type());
            publishFailureCounter.increment();
        }

        return stored;
    }

    @Override
    public Snapshot snapshot(final PrincipalKey key, final String hashtag, final Long since) {
        ConcurrentHashMap<String, PerTagRing> principalRings = store.get(key);
        if (principalRings == null) {
            throw new UnknownSubscriptionException(
                    "No subscription for principal-hash=" + LogScrubber.hash8(key.name()) + " hashtag=" + hashtag);
        }
        PerTagRing ring = principalRings.get(hashtag);
        if (ring == null) {
            throw new UnknownSubscriptionException(
                    "No subscription for principal-hash=" + LogScrubber.hash8(key.name()) + " hashtag=" + hashtag);
        }
        return ring.snapshotSince(since);
    }

    @Override
    public void provisionHashtag(final PrincipalKey key, final String hashtag) {
        // D-11 kill-switch: when fallback is disabled the cache write path is suppressed.
        // No ring is allocated, so no memory is consumed and gauges stay at zero.
        if (!fallbackEnabled) {
            LOGGER.debug("Kill-switch active — skipping provisionHashtag for principal-hash={} hashtag={} (D-11)",
                    LogScrubber.hash8(key.name()), hashtag);
            return;
        }

        // Idempotent: if already provisioned, return immediately
        ConcurrentHashMap<String, PerTagRing> existing = store.get(key);
        if (existing != null && existing.containsKey(hashtag)) {
            return;
        }

        // computeIfAbsent is atomic for the outer map
        store.compute(key, (k, rings) -> {
            if (rings == null) {
                // New principal — check principal cap first
                if (store.size() >= maxPrincipals) {
                    throw new CacheCapacityException(
                            "Maximum number of principals (" + maxPrincipals + ") reached");
                }
                ConcurrentHashMap<String, PerTagRing> newRings = new ConcurrentHashMap<>();
                newRings.put(hashtag, new PerTagRing(ringCapacity));
                return newRings;
            }
            // Existing principal — check hashtag cap (idempotent re-provision is skipped above)
            if (!rings.containsKey(hashtag) && rings.size() >= maxHashtagsPerPrincipal) {
                throw new CacheCapacityException(
                        "Principal-hash=" + LogScrubber.hash8(key.name())
                                + " has reached the maximum of " + maxHashtagsPerPrincipal + " hashtags");
            }
            rings.putIfAbsent(hashtag, new PerTagRing(ringCapacity));
            return rings;
        });
    }

    @Override
    public void evictHashtag(final PrincipalKey key, final String hashtag) {
        ConcurrentHashMap<String, PerTagRing> principalRings = store.get(key);
        if (principalRings == null) {
            return;
        }
        principalRings.remove(hashtag);
        if (principalRings.isEmpty()) {
            store.remove(key, principalRings);
        }
    }

    @Override
    public void evictPrincipal(final PrincipalKey key) {
        store.remove(key);
    }

    @Override
    public boolean isProvisioned(final PrincipalKey key, final String hashtag) {
        ConcurrentHashMap<String, PerTagRing> principalRings = store.get(key);
        return principalRings != null && principalRings.containsKey(hashtag);
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Maps an {@link EventType} to the STOMP destination suffix that matches the
     * existing fan-out in {@code StompCallback}.
     */
    private static String suffixFor(final EventType type) {
        return switch (type) {
            case CREATED -> "creation";
            case UPDATED -> "modification";
            case DELETED -> "deletion";
        };
    }

    private static String destinationFor(final String principal, final String hashtag, final EventType type) {
        return "/topic/hashtags/" + principal + "/" + hashtag + "/" + suffixFor(type);
    }

    /**
     * Builds the STOMP payload DTO corresponding to the given cache entry.
     * Returns one of the existing {@code Status*Message} wire shapes so the
     * Angular subscriber does not need to change.
     */
    private static Object buildStompPayload(final CacheEntry entry) {
        return switch (entry.type()) {
            case CREATED -> de.seism0saurus.glacier.webservice.messaging.messages.StatusCreatedMessage.builder()
                    .id(entry.statusId())
                    .url(entry.url())
                    .build();
            case UPDATED -> de.seism0saurus.glacier.webservice.messaging.messages.StatusUpdatedMessage.builder()
                    .id(entry.statusId())
                    .url(entry.url())
                    .editedAt(entry.editedAt())
                    .build();
            case DELETED -> de.seism0saurus.glacier.webservice.messaging.messages.StatusDeletedMessage.builder()
                    .id(entry.statusId())
                    .build();
        };
    }

}
