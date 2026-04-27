package de.seism0saurus.glacier.mastodon;

import de.seism0saurus.glacier.webservice.cache.CacheCapacityException;
import de.seism0saurus.glacier.webservice.cache.MessageCache;
import de.seism0saurus.glacier.webservice.messaging.PrincipalKey;
import de.seism0saurus.glacier.webservice.messaging.PrincipalKind;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.web.client.RestTemplate;
import social.bigbone.MastodonClient;
import social.bigbone.api.method.StreamingMethods;

import java.io.Closeable;
import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class SubscriptionManagerImplTest {

    /** Convenience factory: wraps a wallId string in a WALL PrincipalKey. */
    private static PrincipalKey wall(String wallId) {
        return new PrincipalKey(PrincipalKind.WALL, wallId);
    }

    @Mock
    private MastodonClient mastodonClient;

    @Mock
    private MessageCache messageCache;

    @Mock
    private RestTemplate restTemplate;

    private final StreamingMethods methods;

    @InjectMocks
    private SubscriptionManagerImpl subscriptionManager;

    public SubscriptionManagerImplTest() {
        MockitoAnnotations.openMocks(this);
        methods = mock(StreamingMethods.class);
        when(mastodonClient.streaming()).thenReturn(methods);
        String instance = "test-instance";
        String glacierDomain = "test-domain";
        String handle = "test-handle@test-instance";
        subscriptionManager = new SubscriptionManagerImpl(instance, glacierDomain, handle, mastodonClient, messageCache, restTemplate, null);
    }

    @Test
    void testSubscribeToHashtag_NewPrincipalAndHashtag() {
        String principal = "user123";
        String hashtag = "TestHashtag";

        subscriptionManager.subscribeToHashtag(principal, hashtag);

        assertTrue(subscriptionManager.hasPrincipalSubscriptions(principal));
        assertTrue(subscriptionManager.isHashtagSubscribedByPrincipal(principal, hashtag));
    }

    @Test
    void testSubscribeToHashtag_WithStreaming() throws InterruptedException {
        String principal = "user123";
        String hashtag = "TestHashtag";
        Closeable subscription = mock(Closeable.class);
        when(methods.hashtag(eq(hashtag), anyBoolean(), any(StompCallback.class))).thenReturn(subscription);

        subscriptionManager.subscribeToHashtag(principal, hashtag);

        Thread.sleep(3000L);

        assertTrue(subscriptionManager.hasPrincipalSubscriptions(principal));
        assertTrue(subscriptionManager.isHashtagSubscribedByPrincipal(principal, hashtag));
        verify(methods).hashtag(eq(hashtag), anyBoolean(), any(StompCallback.class));
    }

    @Test
    void testSubscribeToHashtag_NullPrincipal() {
        String principal = null;
        String hashtag = "TestHashtag";

        assertThrows(AssertionError.class, () ->
                subscriptionManager.subscribeToHashtag(principal, hashtag)
        );
    }

    @Test
    void testSubscribeToHashtag_NullHashtag() {
        String principal = "user123";
        String hashtag = null;

        assertThrows(AssertionError.class, () ->
                subscriptionManager.subscribeToHashtag(principal, hashtag)
        );
    }

    @Test
    void testSubscribeToHashtag_ExistingPrincipalNewHashtag() {
        String principal = "user123";
        String hashtag1 = "Hashtag1";
        String hashtag2 = "Hashtag2";

        subscriptionManager.subscribeToHashtag(principal, hashtag1);
        subscriptionManager.subscribeToHashtag(principal, hashtag2);

        assertEquals(2, subscriptionManager.numberOfSubscriptions(principal));
        assertTrue(subscriptionManager.isHashtagSubscribedByPrincipal(principal, hashtag1));
        assertTrue(subscriptionManager.isHashtagSubscribedByPrincipal(principal, hashtag2));
    }

    @Test
    void testSubscribeToHashtag_ExistingSubscription() {
        String principal = "user123";
        String hashtag = "TestHashtag";

        subscriptionManager.subscribeToHashtag(principal, hashtag);
        subscriptionManager.subscribeToHashtag(principal, hashtag);

        assertEquals(1, subscriptionManager.numberOfSubscriptions(principal));
    }

    @Test
    void testSubscribeToHashtag_MultiplePrincipals() {
        String principal1 = "user123";
        String principal2 = "user456";
        String hashtag1 = "Hashtag123";
        String hashtag2 = "Hashtag456";

        subscriptionManager.subscribeToHashtag(principal1, hashtag1);
        subscriptionManager.subscribeToHashtag(principal2, hashtag2);

        assertTrue(subscriptionManager.hasPrincipalSubscriptions(principal1));
        assertTrue(subscriptionManager.hasPrincipalSubscriptions(principal2));
        assertEquals(1, subscriptionManager.numberOfSubscriptions(principal1));
        assertEquals(1, subscriptionManager.numberOfSubscriptions(principal2));
    }

    @Test
    void testTerminateSubscription_Valid() {
        String principal = "user123";
        String hashtag = "TestHashtag";

        subscriptionManager.subscribeToHashtag(principal, hashtag);
        assertTrue(subscriptionManager.hasPrincipalSubscriptions(principal));
        assertTrue(subscriptionManager.isHashtagSubscribedByPrincipal(principal, hashtag));

        subscriptionManager.terminateSubscription(principal, hashtag);
        assertEquals(0, subscriptionManager.numberOfSubscriptions(principal));
        assertFalse(subscriptionManager.hasPrincipalSubscriptions(principal));
    }

    @Test
    void testTerminateSubscription_MultipleSubscriptions() {
        String principal = "user123";
        String hashtag1 = "TestHashtag1";
        String hashtag2 = "TestHashtag2";

        subscriptionManager.subscribeToHashtag(principal, hashtag1);
        subscriptionManager.subscribeToHashtag(principal, hashtag2);
        assertTrue(subscriptionManager.hasPrincipalSubscriptions(principal));
        assertTrue(subscriptionManager.isHashtagSubscribedByPrincipal(principal, hashtag1));
        assertTrue(subscriptionManager.isHashtagSubscribedByPrincipal(principal, hashtag2));

        subscriptionManager.terminateSubscription(principal, hashtag1);
        assertEquals(1, subscriptionManager.numberOfSubscriptions(principal));
        assertTrue(subscriptionManager.hasPrincipalSubscriptions(principal));
    }

    @Test
    void testTerminateSubscription_UnknownPrincipal() {
        String principal = "unknownUser";
        String hashtag = "TestHashtag";

        Exception exception = assertThrows(IllegalArgumentException.class, () ->
                subscriptionManager.terminateSubscription(principal, hashtag)
        );
        // FIX A (D-13/SR-8): exception message must NOT echo the raw principal value
        assertThat(exception.getMessage()).doesNotContain(principal);
        assertThat(exception.getMessage()).contains("principal");
    }

    @Test
    void testTerminateSubscription_UnknownHashtag() {
        String principal = "user123";
        String hashtag = "TestHashtag";
        String unknownHashtag = "UnknownHashtag";

        subscriptionManager.subscribeToHashtag(principal, hashtag);
        assertTrue(subscriptionManager.isHashtagSubscribedByPrincipal(principal, hashtag));

        Exception exception = assertThrows(IllegalArgumentException.class, () ->
                subscriptionManager.terminateSubscription(principal, unknownHashtag)
        );
        // FIX A (D-13/SR-8): exception message must NOT echo raw principal or hashtag values
        assertThat(exception.getMessage()).doesNotContain(principal);
        assertThat(exception.getMessage()).contains("hashtag");
    }

    @Test
    void testTerminateAllSubscriptions_Valid() {
        String principal = "user123";
        String hashtag1 = "Hashtag1";
        String hashtag2 = "Hashtag2";

        subscriptionManager.subscribeToHashtag(principal, hashtag1);
        subscriptionManager.subscribeToHashtag(principal, hashtag2);

        assertTrue(subscriptionManager.hasPrincipalSubscriptions(principal));
        assertEquals(2, subscriptionManager.numberOfSubscriptions(principal));

        subscriptionManager.terminateAllSubscriptions(principal);

        assertEquals(0, subscriptionManager.numberOfSubscriptions(principal));
    }

    @Test
    void testTerminateAllSubscriptions_UnknownPrincipal() {
        String unknownPrincipal = "unknownPrincipal";

        subscriptionManager.terminateAllSubscriptions(unknownPrincipal);

        assertFalse(subscriptionManager.hasPrincipalSubscriptions(unknownPrincipal));
    }

    @Test
    void testHasPrincipalSubscribed_KnownPrincipal() {
        String principal = "user123";
        String hashtag = "TestHashtag";

        subscriptionManager.subscribeToHashtag(principal, hashtag);

        assertTrue(subscriptionManager.hasPrincipalSubscriptions(principal));
    }

    @Test
    void testIsPrincipalSubscribed_UnknownPrincipal() {
        String unknownPrincipal = "unknownUser";

        assertFalse(subscriptionManager.hasPrincipalSubscriptions(unknownPrincipal));
    }

    @Test
    void testIsHashtagSubscribedByPrincipal_True() {
        String principal = "user123";
        String hashtag = "TestHashtag";

        subscriptionManager.subscribeToHashtag(principal, hashtag);

        assertTrue(subscriptionManager.isHashtagSubscribedByPrincipal(principal, hashtag));
    }

    @Test
    void testIsHashtagSubscribedByPrincipal_FalseUnknownPrincipal() {
        String unknownPrincipal = "unknownUser";
        String hashtag = "TestHashtag";

        assertFalse(subscriptionManager.isHashtagSubscribedByPrincipal(unknownPrincipal, hashtag));
    }

    @Test
    void testIsHashtagSubscribedByPrincipal_FalseUnknownHashtag() {
        String principal = "user123";
        String subscribedHashtag = "SubscribedHashtag";
        String unknownHashtag = "UnknownHashtag";

        subscriptionManager.subscribeToHashtag(principal, subscribedHashtag);

        assertFalse(subscriptionManager.isHashtagSubscribedByPrincipal(principal, unknownHashtag));
    }

    @Test
    void testNumberOfSubscriptions_NoSubscriptions() {
        String principal = "user123";

        assertEquals(0, subscriptionManager.numberOfSubscriptions(principal));
    }

    @Test
    void testNumberOfSubscriptions_SingleSubscription() {
        String principal = "user123";
        String hashtag = "TestHashtag";

        subscriptionManager.subscribeToHashtag(principal, hashtag);

        assertEquals(1, subscriptionManager.numberOfSubscriptions(principal));
    }

    @Test
    void testNumberOfSubscriptions_MultipleSubscriptions() {
        String principal = "user123";
        String hashtag1 = "Hashtag1";
        String hashtag2 = "Hashtag2";

        subscriptionManager.subscribeToHashtag(principal, hashtag1);
        subscriptionManager.subscribeToHashtag(principal, hashtag2);

        assertEquals(2, subscriptionManager.numberOfSubscriptions(principal));
    }

    // -----------------------------------------------------------------
    // New tests for Phase 1: MessageCache integration
    // -----------------------------------------------------------------

    /**
     * subscribeToHashtag calls provisionHashtag before starting the virtual thread (D-11).
     */
    @Test
    void subscribeToHashtag_callsProvisionHashtagBeforeStartingThread() {
        String principal = "user123";
        String hashtag = "TestHashtag";

        subscriptionManager.subscribeToHashtag(principal, hashtag);

        verify(messageCache, times(1)).provisionHashtag(wall(principal), hashtag);
    }

    /**
     * CacheCapacityException from provisionHashtag propagates to the caller (D-11).
     */
    @Test
    void subscribeToHashtag_cacheCapacityExceptionFromProvision_propagatesToCaller() {
        String principal = "user123";
        String hashtag = "TooMany";
        doThrow(new CacheCapacityException("cap exceeded"))
                .when(messageCache).provisionHashtag(wall(principal), hashtag);

        assertThrows(CacheCapacityException.class, () ->
                subscriptionManager.subscribeToHashtag(principal, hashtag)
        );
    }

    /**
     * terminateSubscription calls evictHashtag after cancelling the future (ADR-05).
     */
    @Test
    void terminateSubscription_callsEvictHashtag() {
        String principal = "user123";
        String hashtag = "TestHashtag";
        subscriptionManager.subscribeToHashtag(principal, hashtag);

        subscriptionManager.terminateSubscription(principal, hashtag);

        verify(messageCache, times(1)).evictHashtag(wall(principal), hashtag);
    }

    /**
     * terminateAllSubscriptions calls evictPrincipal after cancelling all futures (ADR-05).
     */
    @Test
    void terminateAllSubscriptions_callsEvictPrincipal() {
        String principal = "user123";
        subscriptionManager.subscribeToHashtag(principal, "h1");
        subscriptionManager.subscribeToHashtag(principal, "h2");

        subscriptionManager.terminateAllSubscriptions(principal);

        verify(messageCache, times(1)).evictPrincipal(wall(principal));
    }

    /**
     * terminateAllSubscriptions always calls evictPrincipal even when the principal
     * has no active subscriptions in the subscription map.
     *
     * <p>Arrange: no subscriptions have been registered for the principal.
     * <p>Act: call terminateAllSubscriptions (e.g. from a disconnect timer).
     * <p>Assert: evictPrincipal is still called — the disconnect timer must
     *   always reclaim cache entries regardless of subscription-map state (ADR-05, D-11).
     */
    @Test
    void terminateAllSubscriptions_withNoActiveSubscriptions_stillCallsEvictPrincipal() {
        String principal = "principal-with-cache-but-no-subscriptions";

        // Act: timer fires; no subscriptions in the map (e.g. already terminated individually)
        subscriptionManager.terminateAllSubscriptions(principal);

        // Assert: cache eviction is unconditional (ADR-05 memory-reclamation contract)
        verify(messageCache, times(1)).evictPrincipal(wall(principal));
    }

    // -----------------------------------------------------------------------
    // Priority 4 additions — subscription lifecycle gaps
    // -----------------------------------------------------------------------

    /**
     * Two principals with overlapping subscriptions to the same hashtag must not interfere
     * with each other: terminating one principal's subscription must leave the other's intact.
     *
     * <p>This test guards against a hypothetical concurrent-mutation bug where the internal
     * {@code Map<principal, Map<hashtag, Future<?>>>} is shared across principals.
     *
     * <p>Arrange: both principals subscribe to the same hashtag.
     * <p>Act:     terminate principalA's subscription.
     * <p>Assert:  principalB's subscription remains active; principalA's is gone.
     */
    @Test
    void subscribeAndTerminate_byDifferentPrincipals_doNotInterfere() {
        String principalA = "principal-A-00000000";
        String principalB = "principal-B-11111111";
        String sharedHashtag = "sharedHashtag";

        // Arrange — both principals subscribe to the same hashtag
        subscriptionManager.subscribeToHashtag(principalA, sharedHashtag);
        subscriptionManager.subscribeToHashtag(principalB, sharedHashtag);

        assertTrue(subscriptionManager.isHashtagSubscribedByPrincipal(principalA, sharedHashtag));
        assertTrue(subscriptionManager.isHashtagSubscribedByPrincipal(principalB, sharedHashtag));

        // Act — terminate only principalA's subscription
        subscriptionManager.terminateSubscription(principalA, sharedHashtag);

        // Assert — principalA's subscription is gone, principalB's is intact
        assertFalse(subscriptionManager.isHashtagSubscribedByPrincipal(principalA, sharedHashtag));
        assertTrue(subscriptionManager.isHashtagSubscribedByPrincipal(principalB, sharedHashtag));
    }
}
