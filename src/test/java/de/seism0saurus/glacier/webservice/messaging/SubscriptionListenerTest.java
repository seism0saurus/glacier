package de.seism0saurus.glacier.webservice.messaging;

import de.seism0saurus.glacier.mastodon.SubscriptionManager;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageHeaders;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.web.socket.messaging.SessionConnectedEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import java.security.Principal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SubscriptionListenerTest {

    private final SubscriptionManager subscriptionManager = mock(SubscriptionManager.class);

    private SubscriptionListener subscriptionListener = new SubscriptionListener(subscriptionManager, 300_000L);

    @Test
    void testOnConnectedEvent_WithoutPreviousDisconnect() {
        // Create a valid Principal object
        Principal principal = () -> "user1";

        // Mock the event
        connect(principal);

        // Validate behavior
        verify(subscriptionManager, never()).terminateAllSubscriptions(anyString());
        assertFalse(subscriptionListener.hasRunningDisconnectTimer());
    }

    @Test
    void testOnConnectedEvent_WithoutPreviousDisconnect_WithoutPrincipal() {
        // Mock the event
        connect(null);

        // Validate behavior
        verify(subscriptionManager, never()).terminateAllSubscriptions(anyString());
        assertFalse(subscriptionListener.hasRunningDisconnectTimers());
    }

    @Test
    void testOnConnectedEvent_WithPreviousDisconnect_WithoutWaitingForTimeout() {
        // Create a valid Principal object
        Principal principal = () -> "user1";
        
        disconnect(principal);

        // Validate state before test
        verify(subscriptionManager, never()).terminateAllSubscriptions(anyString());
        assertTrue(subscriptionListener.hasRunningDisconnectTimer());

        // Mock the event
        connect(principal);

        // Validate behavior
        verify(subscriptionManager, never()).terminateAllSubscriptions(anyString());
        assertFalse(subscriptionListener.hasRunningDisconnectTimer());
    }

    @Test
    void testOnConnectedEvent_WithPreviousDisconnect_WithWaitingForTimeout() throws Exception {
        // Reduce the timeout to one second
        subscriptionListener = new SubscriptionListener(subscriptionManager, 1_000L);

        // Create a valid Principal object
        Principal principal = () -> "user1";

        disconnect(principal);

        // Validate state before timeout
        verify(subscriptionManager, never()).terminateAllSubscriptions(anyString());
        assertTrue(subscriptionListener.hasRunningDisconnectTimer());

        // wait for timeout
        Thread.sleep(3_000L);

        // Validate state before test
        verify(subscriptionManager, times(1)).terminateAllSubscriptions(anyString());
        assertFalse(subscriptionListener.hasRunningDisconnectTimer());

        // Mock the event
        connect(principal);

        // Validate behavior
        verify(subscriptionManager, times(1)).terminateAllSubscriptions(anyString());
        assertFalse(subscriptionListener.hasRunningDisconnectTimer());
    }
    
    @Test
    void testOnDisconnectEvent_WithoutWaitingForTimeout() {
        // Create a valid Principal object
        Principal principal = () -> "user1";

        // Mock subscription
        connect(principal);

        // Valiate assumptions before test
        verify(subscriptionManager, never()).terminateAllSubscriptions(anyString());
        assertFalse(subscriptionListener.hasRunningDisconnectTimer());

        // Mock disconnect
        disconnect(principal);

        // Valiate assumptions after test
        verify(subscriptionManager, never()).terminateAllSubscriptions(anyString());
        assertTrue(subscriptionListener.hasRunningDisconnectTimer());
    }

    @Test
    void testOnDisconnectEvent_WithWaitingForTimeout() throws Exception {
        // Reduce the timeout to one second
        subscriptionListener = new SubscriptionListener(subscriptionManager, 1_000L);

        // Create a valid Principal object
        Principal principal = () -> "user1";

        // Mock subscription
        connect(principal);

        // Valiate assumptions before test
        verify(subscriptionManager, never()).terminateAllSubscriptions(anyString());
        assertFalse(subscriptionListener.hasRunningDisconnectTimer());

        // Mock disconnect
        disconnect(principal);

        // Wait for timeout
        Thread.sleep(3_000L);

        // Valiate assumptions after test
        verify(subscriptionManager, times(1)).terminateAllSubscriptions(anyString());
        assertFalse(subscriptionListener.hasRunningDisconnectTimer());
    }

    @Test
    void testOnDisconnectEvent_WithoutPrincipal() {
        // Create a valid Principal object for connect
        Principal principal = () -> "user1";

        // Mock subscription
        connect(principal);

        // Valiate assumptions before test
        verify(subscriptionManager, never()).terminateAllSubscriptions(anyString());
        assertFalse(subscriptionListener.hasRunningDisconnectTimer());

        // Mock disconnect
        disconnect(null);

        // Valiate assumptions after test
        verify(subscriptionManager, never()).terminateAllSubscriptions(anyString());
        assertFalse(subscriptionListener.hasRunningDisconnectTimers());
    }

    @Test
    void testOnDisconnectEvent_WithoutPreviousConnection() {
        // Create a valid Principal object
        Principal principal = () -> "user1";

        // Valiate assumptions before test
        verify(subscriptionManager, never()).terminateAllSubscriptions(anyString());
        assertFalse(subscriptionListener.hasRunningDisconnectTimers());

        // Mock disconnect
        disconnect(principal);

        // Valiate assumptions after test
        verify(subscriptionManager, never()).terminateAllSubscriptions(anyString());
        assertTrue(subscriptionListener.hasRunningDisconnectTimers());
    }

    private void connect(Principal principal) {
        // Mock the event
        SessionConnectedEvent event = mock(SessionConnectedEvent.class);

        // Create a valid MessageHeaders object
        MessageHeaders headers = new MessageHeaders(null);

        // Mock the SimpMessageHeaderAccessor
        SimpMessageHeaderAccessor headerAccessor = mock(SimpMessageHeaderAccessor.class);
        when(headerAccessor.getSessionId()).thenReturn("session123");

        // Mock the message and header behavior
        //noinspection unchecked
        Message<byte[]> message = mock(Message.class);
        when(message.getHeaders()).thenReturn(headers);
        when(event.getMessage()).thenReturn(message);
        when(event.getUser()).thenReturn(principal);

        // Set up the subscription listener to handle the mock event
        subscriptionListener.onConnectedEvent(event);
    }

    private void disconnect(Principal principal) {
        // Mock the event
        SessionDisconnectEvent event = mock(SessionDisconnectEvent.class);

        // Create a valid MessageHeaders object
        MessageHeaders headers = new MessageHeaders(null);

        // Mock the SimpMessageHeaderAccessor
        SimpMessageHeaderAccessor headerAccessor = mock(SimpMessageHeaderAccessor.class);
        when(headerAccessor.getSessionId()).thenReturn("session123");

        // Mock the message and header behavior
        //noinspection unchecked
        Message<byte[]> message = mock(Message.class);
        when(message.getHeaders()).thenReturn(headers);
        when(event.getMessage()).thenReturn(message);
        when(event.getUser()).thenReturn(principal);

        // Set up the subscription listener to handle the mock event
        subscriptionListener.onDisconnectEvent(event);
    }
}

