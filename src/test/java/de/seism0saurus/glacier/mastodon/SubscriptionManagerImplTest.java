package de.seism0saurus.glacier.mastodon;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.web.client.RestTemplate;
import social.bigbone.MastodonClient;
import social.bigbone.api.method.StreamingMethods;

import java.io.Closeable;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.*;

class SubscriptionManagerImplTest {

    @Mock
    private MastodonClient mastodonClient;

    @Mock
    private SimpMessagingTemplate simpMessagingTemplate;

    @Mock
    private RestTemplate restTemplate;

    private final String instance = "test-instance";

    private final String glacierDomain = "test-domain";

    private final String handle = "test-handle@test-instance";

    private final StreamingMethods methods;

    @InjectMocks
    private SubscriptionManagerImpl subscriptionManager;

    public SubscriptionManagerImplTest() {
        MockitoAnnotations.openMocks(this);
        methods = mock(StreamingMethods.class);
        when(mastodonClient.streaming()).thenReturn(methods);
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
    void testSubscribeToHashtag_WithStreaming() throws InterruptedException, IOException {
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

//    @Test
//    void testSubscribeToHashtag_WithExceptionDuringStreaming() throws InterruptedException, IOException {
//        String principal = "user123";
//        String hashtag = "TestHashtag";
//        Closeable subscription = mock(Closeable.class);
//        doThrow(new IOException("Test IOException")).when(subscription).close();
//        StreamingMethods methods = mock(StreamingMethods.class);
//        when(methods.hashtag(eq(hashtag), anyBoolean(), any(StompCallback.class))).thenReturn(subscription);
//        when(mastodonClient.streaming()).thenReturn(methods);
//
//        RuntimeException exception = assertThrows(RuntimeException.class, () -> {
//            subscriptionManager.subscribeToHashtag(principal, hashtag);
//            Thread.sleep(3000L);
//            subscriptionManager.terminateSubscription(principal, hashtag);
//            Thread.sleep(30000L);
//        });
//
//        assertEquals("java.io.IOException: Test IOException", exception.getMessage());
//        verify(methods).hashtag(eq(hashtag), anyBoolean(), any(StompCallback.class));
//    }

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