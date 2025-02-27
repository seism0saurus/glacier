package de.seism0saurus.glacier.mastodon;

import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.web.client.RestTemplate;
import social.bigbone.MastodonClient;

import static org.junit.jupiter.api.Assertions.*;

class SubscriptionManagerImplTest {

    @Mock
    private MastodonClient mastodonClient;

    @Mock
    private SimpMessagingTemplate simpMessagingTemplate;

    @Mock
    private RestTemplate restTemplate;

    @Value("${mastodon.instance}")
    private final String instance = "test-instance";

    @Value("${glacier.domain}")
    private final String glacierDomain = "test-domain";

    @Value("${mastodon.handle}")
    private final String handle = "test-handle";

    @InjectMocks
    private SubscriptionManagerImpl subscriptionManager;

    public SubscriptionManagerImplTest() {
        MockitoAnnotations.openMocks(this);
        subscriptionManager = new SubscriptionManagerImpl(instance, glacierDomain, handle, mastodonClient, simpMessagingTemplate, restTemplate);
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
    void testTerminateSubscription_UnknownPrincipal() {
        String principal = "unknownUser";
        String hashtag = "TestHashtag";

        Exception exception = assertThrows(IllegalArgumentException.class, () ->
                subscriptionManager.terminateSubscription(principal, hashtag)
        );
        assertEquals("The provided principal " + principal + " is unknown", exception.getMessage());
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
        assertEquals("The provided hashtag " + unknownHashtag + " for principal " + principal + " is unknown", exception.getMessage());
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
}