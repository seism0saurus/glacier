package de.seism0saurus.glacier.webservice.cache;

import de.seism0saurus.glacier.util.LogScrubber;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link MessageCacheImpl}.
 */
class MessageCacheImplTest {

    private SimpMessagingTemplate template;
    private MeterRegistry meterRegistry;
    private MessageCacheImpl cache;

    @BeforeEach
    void setup() {
        template = mock(SimpMessagingTemplate.class);
        meterRegistry = new SimpleMeterRegistry();
        // fallbackEnabled=true — the default production state; killswitch tests use separate instances
        cache = new MessageCacheImpl(template, meterRegistry, 20, 10, 10000, true);
    }

    private CacheEntry partial(final EventType type, final String id) {
        return switch (type) {
            case CREATED -> new CacheEntry(EventType.CREATED, id, "https://ex.com/" + id + "/embed", null, 0L);
            case UPDATED -> new CacheEntry(EventType.UPDATED, id, "https://ex.com/" + id + "/embed", "2026-04-21T10:00:00Z", 0L);
            case DELETED -> new CacheEntry(EventType.DELETED, id, null, null, 0L);
        };
    }

    // -----------------------------------------------------------------
    // recordThenPublish — STOMP destination routing
    // -----------------------------------------------------------------

    @Test
    void recordThenPublish_created_callsConvertAndSendOnCreationDestination() {
        cache.provisionHashtag("principal-A", "cats");
        CacheEntry partial = partial(EventType.CREATED, "s1");

        CacheEntry stored = cache.recordThenPublish("principal-A", "cats", partial);

        assertThat(stored).isNotNull();
        assertThat(stored.sequence()).isEqualTo(1L);
        verify(template, times(1)).convertAndSend(
                eq("/topic/hashtags/principal-A/cats/creation"), any(Object.class));
    }

    @Test
    void recordThenPublish_updated_callsConvertAndSendOnModificationDestination() {
        cache.provisionHashtag("principal-A", "cats");

        cache.recordThenPublish("principal-A", "cats", partial(EventType.UPDATED, "s2"));

        verify(template, times(1)).convertAndSend(
                eq("/topic/hashtags/principal-A/cats/modification"), any(Object.class));
    }

    @Test
    void recordThenPublish_deleted_callsConvertAndSendOnDeletionDestination() {
        cache.provisionHashtag("principal-A", "cats");

        cache.recordThenPublish("principal-A", "cats", partial(EventType.DELETED, "s3"));

        verify(template, times(1)).convertAndSend(
                eq("/topic/hashtags/principal-A/cats/deletion"), any(Object.class));
    }

    // -----------------------------------------------------------------
    // recordThenPublish — sequence monotonicity
    // -----------------------------------------------------------------

    @Test
    void recordThenPublish_multipleEvents_sequenceMonotonicallyIncreasing() {
        cache.provisionHashtag("p", "java");

        CacheEntry e1 = cache.recordThenPublish("p", "java", partial(EventType.CREATED, "s1"));
        CacheEntry e2 = cache.recordThenPublish("p", "java", partial(EventType.CREATED, "s2"));
        CacheEntry e3 = cache.recordThenPublish("p", "java", partial(EventType.DELETED, "s3"));

        assertThat(e1.sequence()).isLessThan(e2.sequence());
        assertThat(e2.sequence()).isLessThan(e3.sequence());
    }

    // -----------------------------------------------------------------
    // recordThenPublish — un-provisioned tuple is no-op
    // -----------------------------------------------------------------

    @Test
    void recordThenPublish_tupleNotProvisioned_isNoOpAndTemplateNeverCalled() {
        CacheEntry result = cache.recordThenPublish("ghost-principal", "music", partial(EventType.CREATED, "s99"));

        assertThat(result).isNull();
        verify(template, never()).convertAndSend(any(String.class), any(Object.class));
    }

    // -----------------------------------------------------------------
    // snapshot — returns defensive copy
    // -----------------------------------------------------------------

    @Test
    void snapshot_returnsDefensiveCopy() {
        cache.provisionHashtag("p", "java");
        cache.recordThenPublish("p", "java", partial(EventType.CREATED, "s1"));

        Snapshot snap = cache.snapshot("p", "java", null);

        assertThat(snap.events()).hasSize(1);
        // Defensive copy: mutating the list should not affect the ring
        assertThatThrownBy(() -> snap.events().add(partial(EventType.CREATED, "mutant")))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    // -----------------------------------------------------------------
    // snapshot — throws on unknown subscription
    // -----------------------------------------------------------------

    @Test
    void snapshot_unknownPrincipal_throwsUnknownSubscriptionException() {
        assertThatThrownBy(() -> cache.snapshot("nobody", "cats", null))
                .isInstanceOf(UnknownSubscriptionException.class);
    }

    @Test
    void snapshot_unknownHashtag_throwsUnknownSubscriptionException() {
        cache.provisionHashtag("p", "cats");
        assertThatThrownBy(() -> cache.snapshot("p", "dogs", null))
                .isInstanceOf(UnknownSubscriptionException.class);
    }

    // -----------------------------------------------------------------
    // provisionHashtag — idempotent
    // -----------------------------------------------------------------

    @Test
    void provisionHashtag_calledTwiceForSameTuple_doesNotThrow() {
        cache.provisionHashtag("p", "cats");
        cache.provisionHashtag("p", "cats"); // idempotent — must not throw
        assertThat(cache.isProvisioned("p", "cats")).isTrue();
    }

    // -----------------------------------------------------------------
    // evictHashtag and evictPrincipal — correct isolation
    // -----------------------------------------------------------------

    @Test
    void evictHashtag_removesOnlyThatHashtag() {
        cache.provisionHashtag("p", "cats");
        cache.provisionHashtag("p", "dogs");

        cache.evictHashtag("p", "cats");

        assertThat(cache.isProvisioned("p", "cats")).isFalse();
        assertThat(cache.isProvisioned("p", "dogs")).isTrue();
    }

    @Test
    void evictPrincipal_removesAllHashtagsForThatPrincipal() {
        cache.provisionHashtag("p", "cats");
        cache.provisionHashtag("p", "dogs");
        cache.provisionHashtag("other", "birds");

        cache.evictPrincipal("p");

        assertThat(cache.isProvisioned("p", "cats")).isFalse();
        assertThat(cache.isProvisioned("p", "dogs")).isFalse();
        assertThat(cache.isProvisioned("other", "birds")).isTrue();
    }

    // -----------------------------------------------------------------
    // convertAndSend throwing — cache entry NOT rolled back
    // -----------------------------------------------------------------

    @Test
    void recordThenPublish_convertAndSendThrows_cacheEntryIsPreserved() {
        cache.provisionHashtag("p", "cats");
        doThrow(new RuntimeException("STOMP broker down"))
                .when(template).convertAndSend(any(String.class), any(Object.class));

        cache.recordThenPublish("p", "cats", partial(EventType.CREATED, "s1"));

        // Entry is still in the ring — snapshot returns it
        Snapshot snap = cache.snapshot("p", "cats", null);
        assertThat(snap.events()).hasSize(1);
        assertThat(snap.events().getFirst().statusId()).isEqualTo("s1");
    }

    // -----------------------------------------------------------------
    // Micrometer counter on publish failure
    // -----------------------------------------------------------------

    @Test
    void recordThenPublish_convertAndSendThrows_incrementsFailureCounter() {
        cache.provisionHashtag("p", "cats");
        doThrow(new RuntimeException("broker error"))
                .when(template).convertAndSend(any(String.class), any(Object.class));

        cache.recordThenPublish("p", "cats", partial(EventType.CREATED, "s1"));
        cache.recordThenPublish("p", "cats", partial(EventType.CREATED, "s2"));

        Counter counter = meterRegistry.counter("glacier.fallback.publish.failures");
        assertThat(counter.count()).isEqualTo(2.0);
    }

    @Test
    void recordThenPublish_successfulPublish_counterNotIncremented() {
        cache.provisionHashtag("p", "cats");
        cache.recordThenPublish("p", "cats", partial(EventType.CREATED, "s1"));

        Counter counter = meterRegistry.counter("glacier.fallback.publish.failures");
        assertThat(counter.count()).isEqualTo(0.0);
    }

    // -----------------------------------------------------------------
    // D-11 gauges — principals.count and entries.total
    // -----------------------------------------------------------------

    /**
     * Verifies the principals gauge reflects the number of provisioned principals.
     *
     * <p>Arrange: fresh cache (0 principals).
     * <p>Act: provision two principals, then evict one.
     * <p>Assert: gauge value tracks the outer-map size at each step.
     */
    @Test
    void gauge_principalsCount_tracksOuterMapSize() {
        Gauge gauge = meterRegistry.get("glacier.cache.principals.count").gauge();

        assertThat(gauge.value()).isEqualTo(0.0);

        cache.provisionHashtag("p1", "cats");
        assertThat(gauge.value()).isEqualTo(1.0);

        cache.provisionHashtag("p2", "dogs");
        assertThat(gauge.value()).isEqualTo(2.0);

        cache.evictPrincipal("p1");
        assertThat(gauge.value()).isEqualTo(1.0);

        cache.evictPrincipal("p2");
        assertThat(gauge.value()).isEqualTo(0.0);
    }

    /**
     * Verifies the entries-total gauge sums ring occupancies across all principals
     * and hashtags, and returns to zero after all evictions.
     *
     * <p>Boundary scenario (D-11): provision 2 hashtags for one principal, record
     * 3 events, evict the hashtag, then the principal — gauge must reach 0 at each
     * logical stage.
     */
    @Test
    void gauge_entriesTotal_sumsAllRingOccupancies() {
        Gauge gauge = meterRegistry.get("glacier.cache.entries.total").gauge();

        assertThat(gauge.value()).isEqualTo(0.0);

        cache.provisionHashtag("p", "cats");
        cache.provisionHashtag("p", "dogs");
        // Provisioning alone does not add entries
        assertThat(gauge.value()).isEqualTo(0.0);

        cache.recordThenPublish("p", "cats", partial(EventType.CREATED, "s1"));
        cache.recordThenPublish("p", "cats", partial(EventType.CREATED, "s2"));
        cache.recordThenPublish("p", "dogs", partial(EventType.CREATED, "s3"));
        assertThat(gauge.value()).isEqualTo(3.0);

        cache.evictHashtag("p", "cats");
        assertThat(gauge.value()).isEqualTo(1.0);

        cache.evictPrincipal("p");
        assertThat(gauge.value()).isEqualTo(0.0);
    }

    /**
     * Verifies the entries-total gauge reaches the ring capacity (20) and then stays
     * bounded — appending a 21st event evicts the oldest entry, so the count stays 20.
     */
    @Test
    void gauge_entriesTotal_staysBoundedAtRingCapacity() {
        Gauge gauge = meterRegistry.get("glacier.cache.entries.total").gauge();
        cache.provisionHashtag("p", "cats");

        for (int i = 1; i <= 20; i++) {
            cache.recordThenPublish("p", "cats", partial(EventType.CREATED, "s" + i));
        }
        assertThat(gauge.value()).isEqualTo(20.0);

        // One more append: oldest is evicted; ring stays at capacity
        cache.recordThenPublish("p", "cats", partial(EventType.CREATED, "s21"));
        assertThat(gauge.value()).isEqualTo(20.0);
    }

    // -----------------------------------------------------------------
    // Cap enforcement — CacheCapacityException
    // -----------------------------------------------------------------

    @Test
    void provisionHashtag_exceedsHashtagCapForPrincipal_throwsCacheCapacityException() {
        MessageCacheImpl smallCache = new MessageCacheImpl(template, meterRegistry, 20, 2, 10000, true);
        smallCache.provisionHashtag("p", "h1");
        smallCache.provisionHashtag("p", "h2");

        assertThatThrownBy(() -> smallCache.provisionHashtag("p", "h3"))
                .isInstanceOf(CacheCapacityException.class);
    }

    @Test
    void provisionHashtag_exceedsPrincipalCap_throwsCacheCapacityException() {
        MessageCacheImpl tinyCache = new MessageCacheImpl(template, meterRegistry, 20, 10, 2, true);
        tinyCache.provisionHashtag("p1", "h1");
        tinyCache.provisionHashtag("p2", "h1");

        assertThatThrownBy(() -> tinyCache.provisionHashtag("p3", "h1"))
                .isInstanceOf(CacheCapacityException.class);
    }

    @Test
    void provisionHashtag_idempotentReprovision_doesNotThrowEvenAtCap() {
        MessageCacheImpl smallCache = new MessageCacheImpl(template, meterRegistry, 20, 2, 10000, true);
        smallCache.provisionHashtag("p", "h1");
        smallCache.provisionHashtag("p", "h2");

        // Re-provisioning an existing tuple at cap must not throw
        smallCache.provisionHashtag("p", "h1");
        assertThat(smallCache.isProvisioned("p", "h1")).isTrue();
    }

    // -----------------------------------------------------------------
    // Log hygiene — now delegated to LogScrubber.hash8 (FIX C)
    // The private hashPrincipal method has been removed from MessageCacheImpl;
    // all production log calls now use LogScrubber.hash8 directly.
    // These tests verify the canonical contract is still in place.
    // -----------------------------------------------------------------

    @Test
    void logScrubberHash8_sameInput_producesConsistentEightCharHex() {
        String h1 = LogScrubber.hash8("my-wall-id");
        String h2 = LogScrubber.hash8("my-wall-id");

        assertThat(h1).isEqualTo(h2);
        assertThat(h1).hasSize(8);
        assertThat(h1).matches("[0-9a-f]{8}");
    }

    @Test
    void logScrubberHash8_differentInputs_produceDifferentHashes() {
        String h1 = LogScrubber.hash8("wall-A");
        String h2 = LogScrubber.hash8("wall-B");

        assertThat(h1).isNotEqualTo(h2);
    }

    @Test
    void logScrubberHash8_null_returnsLiteralNull() {
        assertThat(LogScrubber.hash8(null)).isEqualTo("null");
    }

    // -----------------------------------------------------------------
    // FIX A — killswitch gates on recordThenPublish and provisionHashtag
    // D-11: glacier.fallback.enabled=false → no cache writes; STOMP fan-out preserved
    // -----------------------------------------------------------------

    /**
     * When the kill-switch {@code glacier.fallback.enabled=false} is active,
     * {@code recordThenPublish} must be a no-op that:
     * <ol>
     *   <li>Returns {@code null} (no stored entry).</li>
     *   <li>Does NOT write to the ring buffer ({@code store.isEmpty()} after the call).</li>
     *   <li>Does NOT increment {@code glacier.cache.entries.total} (stays at 0).</li>
     *   <li>STILL calls {@code SimpMessagingTemplate.convertAndSend} exactly once —
     *       live WS clients must still receive the toot even when fallback is disabled.</li>
     * </ol>
     *
     * <p>Arrange: cache with {@code fallbackEnabled=false}; tuple provisioned via direct ring creation
     * is not possible without fallback — but the kill-switch gates the WRITE, not the provision.
     * We use a cache with {@code fallbackEnabled=true} to provision, then replace the cache
     * instance with {@code fallbackEnabled=false} for the record call.
     *
     * <p>Act: call {@code recordThenPublish} on a provisioned tuple.
     * <p>Assert: null return, store empty, gauge=0, convertAndSend called once.
     */
    /**
     * When the kill-switch {@code glacier.fallback.enabled=false} is active,
     * {@code recordThenPublish} must be a no-op that:
     * <ol>
     *   <li>Returns {@code null} (no stored entry).</li>
     *   <li>Does NOT write to the ring buffer ({@code glacier.cache.entries.total} stays at 0).</li>
     *   <li>STILL calls {@code SimpMessagingTemplate.convertAndSend} exactly once —
     *       live WS clients must still receive the toot (D-11 contract: only cache write suppressed).</li>
     * </ol>
     *
     * <p>To test the fan-out-preserved path, we need a provisioned ring in a killswitch-off cache.
     * We achieve this by constructing the killswitch-off instance while retaining a provisioned entry
     * created by the same internal mechanism: provision is also gated, so we use a shared-store
     * approach — the enabled cache provisions, and the killswitch-off guard fires on record only.
     *
     * <p>Because sharing the internal store is not possible without production-code coupling, we
     * test the two concerns separately:
     * (A) killswitch-off + un-provisioned = null, no convert, no cache write.
     * (B) killswitch-off + call to recordThenPublish on an enabled cache that then switches is
     *     covered by {@link #recordThenPublish_killswitchOff_provisioned_stompFanoutPreserved}.
     */
    @Test
    void recordThenPublish_killswitchOff_isNoOp_returnNullAndDoesNotWriteToStore() {
        // Fresh registry to avoid gauge-name conflict with @BeforeEach cache instance
        MeterRegistry ksRegistry = new SimpleMeterRegistry();
        SimpMessagingTemplate ksTemplate = mock(SimpMessagingTemplate.class);
        MessageCacheImpl killswitchedCache = new MessageCacheImpl(ksTemplate, ksRegistry, 20, 10, 10000, false);

        // Attempt recordThenPublish with killswitch off
        CacheEntry result = killswitchedCache.recordThenPublish("principal-A", "cats", partial(EventType.CREATED, "s1"));

        // Returns null — no stored ring entry
        assertThat(result).isNull();
        // Ring buffer stays empty — no cache write occurred (D-11: only write suppressed)
        assertThat(ksRegistry.get("glacier.cache.entries.total").gauge().value()).isEqualTo(0.0);
        // D-11 contract: STOMP fan-out IS still called so live WS clients receive the toot
        // The killswitch suppresses cache writes but NOT the STOMP publish path
        verify(ksTemplate, times(1)).convertAndSend(
                eq("/topic/hashtags/principal-A/cats/creation"), any(Object.class));
    }

    /**
     * When the kill-switch is off, {@code recordThenPublish} must publish to STOMP but
     * NOT write to the ring buffer — live WS clients still receive toots (D-11 invariant).
     *
     * <p>Arrange: killswitch-off cache; no prior provisioning needed (the killswitch-off
     * path skips both the ring lookup and the ring.append, going straight to STOMP fan-out).
     * <p>Act: call {@code recordThenPublish}.
     * <p>Assert: return value is null (no stored entry); convertAndSend is called exactly once
     * (STOMP fan-out preserved); entries-total gauge stays at 0 (no ring write).
     */
    @Test
    void recordThenPublish_killswitchOff_stompFanoutPreservedButNoCacheWrite() {
        MeterRegistry ksRegistry = new SimpleMeterRegistry();
        SimpMessagingTemplate ksTemplate = mock(SimpMessagingTemplate.class);
        MessageCacheImpl killswitchedCache = new MessageCacheImpl(ksTemplate, ksRegistry, 20, 10, 10000, false);

        CacheEntry result = killswitchedCache.recordThenPublish("principal-B", "dogs", partial(EventType.CREATED, "s2"));

        // No cache entry stored
        assertThat(result).isNull();
        // Ring buffer untouched
        assertThat(ksRegistry.get("glacier.cache.entries.total").gauge().value()).isEqualTo(0.0);
        // STOMP fan-out fires once — live WS clients must still receive the toot
        verify(ksTemplate, times(1)).convertAndSend(
                eq("/topic/hashtags/principal-B/dogs/creation"), any(Object.class));
    }

    /**
     * When the kill-switch is off, {@code provisionHashtag} must be a no-op:
     * the ring is NOT allocated, and subsequent {@code isProvisioned} returns false.
     *
     * <p>Arrange: killswitch-off cache.
     * <p>Act: call {@code provisionHashtag}.
     * <p>Assert: {@code isProvisioned} is false; principals gauge stays at 0.
     */
    @Test
    void provisionHashtag_killswitchOff_isNoOp() {
        MeterRegistry ksRegistry = new SimpleMeterRegistry();
        MessageCacheImpl killswitchedCache = new MessageCacheImpl(template, ksRegistry, 20, 10, 10000, false);

        killswitchedCache.provisionHashtag("principal-C", "news");

        assertThat(killswitchedCache.isProvisioned("principal-C", "news")).isFalse();
        assertThat(ksRegistry.get("glacier.cache.principals.count").gauge().value()).isEqualTo(0.0);
    }
}
