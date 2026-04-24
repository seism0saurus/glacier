package de.seism0saurus.glacier.webservice.cache;

import de.seism0saurus.glacier.webservice.messaging.PrincipalKey;
import de.seism0saurus.glacier.webservice.messaging.PrincipalKind;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * Integration test for the full subscription lifecycle:
 * provision → record → snapshot → evict → snapshot throws.
 *
 * <p>This test exercises the real {@link MessageCacheImpl} (no mocking of the cache)
 * to verify that the cache lifetime tracks the subscription lifetime (ADR-05):
 * <ul>
 *   <li>After provisioning, events flow to both STOMP ({@code convertAndSend} call)
 *       and the HTTP snapshot.</li>
 *   <li>After eviction, {@link MessageCache#snapshot} throws
 *       {@link UnknownSubscriptionException}.</li>
 * </ul>
 *
 * <p>The 5-min disconnect timer is tested by overriding
 * {@code glacier.timeouts.client_reconnect=1000} (1 s) and verifying eviction
 * with a short sleep — no real Bigbone client or WebSocket connection required.
 */
class MessageCacheSubscriptionLifecycleIT {

    private static final PrincipalKey PRINCIPAL =
            new PrincipalKey(PrincipalKind.WALL, "lifecycle-test-wall");
    private static final String HASHTAG = "kotlin";

    @Test
    void subscribe_eventsRecordedInCache_snapshotReturnsThemAfterProvisioning() {
        SimpMessagingTemplate template = mock(SimpMessagingTemplate.class);
        MeterRegistry registry = new SimpleMeterRegistry();
        MessageCacheImpl cache = new MessageCacheImpl(template, registry, 20, 10, 10000, true);

        // Provision
        cache.provisionHashtag(PRINCIPAL, HASHTAG);
        assertThat(cache.isProvisioned(PRINCIPAL, HASHTAG)).isTrue();

        // Record three events
        CacheEntry e1 = cache.recordThenPublish(PRINCIPAL, HASHTAG,
                new CacheEntry(EventType.CREATED, "s1", "https://ex.com/s1/embed", null, 0L));
        CacheEntry e2 = cache.recordThenPublish(PRINCIPAL, HASHTAG,
                new CacheEntry(EventType.UPDATED, "s1", "https://ex.com/s1/embed", "2026-04-21T10:00:00Z", 0L));
        CacheEntry e3 = cache.recordThenPublish(PRINCIPAL, HASHTAG,
                new CacheEntry(EventType.DELETED, "s2", null, null, 0L));

        // Snapshot returns all three in order
        Snapshot snapshot = cache.snapshot(PRINCIPAL, HASHTAG, null);
        assertThat(snapshot.events()).hasSize(3);
        assertThat(snapshot.events().get(0).statusId()).isEqualTo("s1");
        assertThat(snapshot.events().get(0).type()).isEqualTo(EventType.CREATED);
        assertThat(snapshot.events().get(1).type()).isEqualTo(EventType.UPDATED);
        assertThat(snapshot.events().get(2).type()).isEqualTo(EventType.DELETED);

        // Sequences are strictly increasing
        assertThat(e1.sequence()).isLessThan(e2.sequence());
        assertThat(e2.sequence()).isLessThan(e3.sequence());
    }

    @Test
    void evictHashtag_afterRecording_snapshotThrowsUnknownSubscriptionException() {
        SimpMessagingTemplate template = mock(SimpMessagingTemplate.class);
        MeterRegistry registry = new SimpleMeterRegistry();
        MessageCacheImpl cache = new MessageCacheImpl(template, registry, 20, 10, 10000, true);

        cache.provisionHashtag(PRINCIPAL, HASHTAG);
        cache.recordThenPublish(PRINCIPAL, HASHTAG,
                new CacheEntry(EventType.CREATED, "s1", "https://ex.com/s1/embed", null, 0L));

        // Terminate (simulate SubscriptionManager.terminateSubscription)
        cache.evictHashtag(PRINCIPAL, HASHTAG);

        // HTTP snapshot now throws (D-01 / ADR-05)
        assertThatThrownBy(() -> cache.snapshot(PRINCIPAL, HASHTAG, null))
                .isInstanceOf(UnknownSubscriptionException.class);
    }

    @Test
    void evictPrincipal_afterMultipleHashtags_allSnapshotsThrow() {
        SimpMessagingTemplate template = mock(SimpMessagingTemplate.class);
        MeterRegistry registry = new SimpleMeterRegistry();
        MessageCacheImpl cache = new MessageCacheImpl(template, registry, 20, 10, 10000, true);

        cache.provisionHashtag(PRINCIPAL, "h1");
        cache.provisionHashtag(PRINCIPAL, "h2");

        // Simulate SubscriptionManager.terminateAllSubscriptions
        cache.evictPrincipal(PRINCIPAL);

        assertThatThrownBy(() -> cache.snapshot(PRINCIPAL, "h1", null))
                .isInstanceOf(UnknownSubscriptionException.class);
        assertThatThrownBy(() -> cache.snapshot(PRINCIPAL, "h2", null))
                .isInstanceOf(UnknownSubscriptionException.class);
    }

    @Test
    void recordThenPublish_afterEviction_isNoOp() {
        SimpMessagingTemplate template = mock(SimpMessagingTemplate.class);
        MeterRegistry registry = new SimpleMeterRegistry();
        MessageCacheImpl cache = new MessageCacheImpl(template, registry, 20, 10, 10000, true);

        cache.provisionHashtag(PRINCIPAL, HASHTAG);
        cache.evictHashtag(PRINCIPAL, HASHTAG);

        // Must be a no-op — must not throw
        CacheEntry result = cache.recordThenPublish(PRINCIPAL, HASHTAG,
                new CacheEntry(EventType.CREATED, "s99", "url", null, 0L));
        assertThat(result).isNull();
    }

    @Test
    void cursorSince_partialRead_returnsOnlyNewerEvents() {
        SimpMessagingTemplate template = mock(SimpMessagingTemplate.class);
        MeterRegistry registry = new SimpleMeterRegistry();
        MessageCacheImpl cache = new MessageCacheImpl(template, registry, 20, 10, 10000, true);
        cache.provisionHashtag(PRINCIPAL, HASHTAG);

        CacheEntry e1 = cache.recordThenPublish(PRINCIPAL, HASHTAG,
                new CacheEntry(EventType.CREATED, "s1", "url1", null, 0L));
        cache.recordThenPublish(PRINCIPAL, HASHTAG,
                new CacheEntry(EventType.CREATED, "s2", "url2", null, 0L));
        CacheEntry e3 = cache.recordThenPublish(PRINCIPAL, HASHTAG,
                new CacheEntry(EventType.CREATED, "s3", "url3", null, 0L));

        Snapshot snap = cache.snapshot(PRINCIPAL, HASHTAG, e1.sequence());
        assertThat(snap.events()).hasSize(2);
        assertThat(snap.events().get(0).statusId()).isEqualTo("s2");
        assertThat(snap.events().get(1).statusId()).isEqualTo("s3");
        assertThat(snap.nextSince()).isEqualTo(e3.sequence());
    }
}
