package de.seism0saurus.glacier.webservice.messaging;

import de.seism0saurus.glacier.mastodon.SubscriptionManager;
import de.seism0saurus.glacier.webservice.cache.MessageCache;
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
    private final MessageCache messageCache = mock(MessageCache.class);

    private SubscriptionListener subscriptionListener = new SubscriptionListener(
            subscriptionManager, messageCache, 300_000L, 300_000L, 2.0);

    @Test
    void testOnConnectedEvent_WithoutPreviousDisconnect() throws Exception {
        // Create a valid Principal object
        Principal principal = () -> "user1";

        // Mock the event
        connect(principal);

        // Validate behavior
        verify(subscriptionManager, never()).terminateAllSubscriptions(anyString());
        assertFalse(subscriptionListener.hasRunningDisconnectTimer("user1"));
    }

    @Test
    void testOnConnectedEvent_WithoutPreviousDisconnect_WithoutPrincipal() throws Exception {
        // Mock the event
        connect(null);

        // Validate behavior
        verify(subscriptionManager, never()).terminateAllSubscriptions(anyString());
        assertFalse(subscriptionListener.hasRunningDisconnectTimers());
    }

    @Test
    void testOnConnectedEvent_WithPreviousDisconnect_WithoutWaitingForTimeout() throws Exception {
        // Create a valid Principal object
        Principal principal = () -> "user1";
        
        disconnect(principal);

        // Validate state before test
        verify(subscriptionManager, never()).terminateAllSubscriptions(anyString());
        assertTrue(subscriptionListener.hasRunningDisconnectTimer("user1"));

        // Mock the event
        connect(principal);

        // Validate behavior
        verify(subscriptionManager, never()).terminateAllSubscriptions(anyString());
        assertFalse(subscriptionListener.hasRunningDisconnectTimer("user1"));
    }

    @Test
    void testOnConnectedEvent_WithPreviousDisconnect_WithWaitingForTimeout() throws Exception {
        // Reduce the timeout to one second
        subscriptionListener = new SubscriptionListener(
                subscriptionManager, messageCache, 1_000L, 300_000L, 2.0);

        // Create a valid Principal object
        Principal principal = () -> "user1";

        disconnect(principal);

        // Validate state before timeout
        verify(subscriptionManager, never()).terminateAllSubscriptions(anyString());
        assertTrue(subscriptionListener.hasRunningDisconnectTimer("user1"));

        // wait for timeout
        Thread.sleep(3_000L);

        // Validate state before test
        verify(subscriptionManager, times(1)).terminateAllSubscriptions(anyString());
        assertFalse(subscriptionListener.hasRunningDisconnectTimer("user1"));

        // Mock the event
        connect(principal);

        // Validate behavior
        verify(subscriptionManager, times(1)).terminateAllSubscriptions(anyString());
        assertFalse(subscriptionListener.hasRunningDisconnectTimer("user1"));
    }
    
    @Test
    void testOnDisconnectEvent_WithoutWaitingForTimeout() throws Exception {
        // Create a valid Principal object
        Principal principal = () -> "user1";

        // Mock subscription
        connect(principal);

        // Valiate assumptions before test
        verify(subscriptionManager, never()).terminateAllSubscriptions(anyString());
        assertFalse(subscriptionListener.hasRunningDisconnectTimer("user1"));

        // Mock disconnect
        disconnect(principal);

        // Valiate assumptions after test
        verify(subscriptionManager, never()).terminateAllSubscriptions(anyString());
        assertTrue(subscriptionListener.hasRunningDisconnectTimer("user1"));
    }

    @Test
    void testOnDisconnectEvent_WithWaitingForTimeout() throws Exception {
        // Reduce the timeout to one second
        subscriptionListener = new SubscriptionListener(
                subscriptionManager, messageCache, 1_000L, 300_000L, 2.0);

        // Create a valid Principal object
        Principal principal = () -> "user1";

        // Mock subscription
        connect(principal);

        // Valiate assumptions before test
        verify(subscriptionManager, never()).terminateAllSubscriptions(anyString());
        assertFalse(subscriptionListener.hasRunningDisconnectTimer("user1"));

        // Mock disconnect
        disconnect(principal);

        // Wait for timeout
        Thread.sleep(3_000L);

        // Valiate assumptions after test
        verify(subscriptionManager, times(1)).terminateAllSubscriptions(anyString());
        assertFalse(subscriptionListener.hasRunningDisconnectTimer("user1"));
    }

    @Test
    void testOnDisconnectEvent_WithoutPrincipal() throws Exception {
        // Create a valid Principal object for connect
        Principal principal = () -> "user1";

        // Mock subscription
        connect(principal);

        // Valiate assumptions before test
        verify(subscriptionManager, never()).terminateAllSubscriptions(anyString());
        assertFalse(subscriptionListener.hasRunningDisconnectTimer("user1"));

        // Mock disconnect
        disconnect(null);

        // Valiate assumptions after test
        verify(subscriptionManager, never()).terminateAllSubscriptions(anyString());
        assertFalse(subscriptionListener.hasRunningDisconnectTimers());
    }

    @Test
    void testOnDisconnectEvent_WithoutPreviousConnection() throws Exception {
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

    // -----------------------------------------------------------------------
    // P2-02: Exponential back-off unit tests
    // -----------------------------------------------------------------------

    /**
     * First disconnect returns the initial timeout as the delay.
     *
     * <p>Arrange: fresh listener, timeout = 5 000 ms, multiplier = 2.0, max = 60 000 ms.<br>
     * Act: call nextBackoffDelay once.<br>
     * Assert: returns 5 000 ms (the initial delay, not yet multiplied).
     */
    @Test
    void backoffDelay_firstDisconnect_returnsInitialTimeout() {
        SubscriptionListener listener = new SubscriptionListener(
                subscriptionManager, messageCache, 5_000L, 60_000L, 2.0);

        long delay = listener.nextBackoffDelay("principal-a");

        assertEquals(5_000L, delay);
    }

    /**
     * Second disconnect returns the initial timeout multiplied by 2.
     *
     * <p>Arrange: fresh listener, timeout = 5 000 ms, multiplier = 2.0, max = 60 000 ms.<br>
     * Act: call nextBackoffDelay twice for the same principal.<br>
     * Assert: second call returns 10 000 ms.
     */
    @Test
    void backoffDelay_secondDisconnect_returnsDoubledDelay() {
        SubscriptionListener listener = new SubscriptionListener(
                subscriptionManager, messageCache, 5_000L, 60_000L, 2.0);

        listener.nextBackoffDelay("principal-b");
        long delay = listener.nextBackoffDelay("principal-b");

        assertEquals(10_000L, delay);
    }

    /**
     * Back-off delay is capped at the configured maximum.
     *
     * <p>Arrange: timeout = 5 000 ms, multiplier = 2.0, max = 12 000 ms.<br>
     * Act: advance back-off until it would exceed max (5 000 → 10 000 → would be 20 000, capped).<br>
     * Assert: third call returns max (12 000 ms).
     */
    @Test
    void backoffDelay_exceedingMax_isCappedAtMaxDelay() {
        SubscriptionListener listener = new SubscriptionListener(
                subscriptionManager, messageCache, 5_000L, 12_000L, 2.0);

        listener.nextBackoffDelay("principal-c"); // returns 5 000, next = 10 000
        listener.nextBackoffDelay("principal-c"); // returns 10 000, next = min(20 000, 12 000) = 12 000
        long delay = listener.nextBackoffDelay("principal-c"); // returns 12 000

        assertEquals(12_000L, delay);
    }

    /**
     * Back-off state is reset when the principal reconnects successfully.
     *
     * <p>Arrange: advance back-off for principal by calling nextBackoffDelay twice,
     * then simulate a connected event for the principal.<br>
     * Act: call nextBackoffDelay again after reconnect.<br>
     * Assert: delay resets to the initial timeout value.
     */
    @Test
    void backoffDelay_afterSuccessfulReconnect_resetsToInitialTimeout() {
        SubscriptionListener listener = new SubscriptionListener(
                subscriptionManager, messageCache, 5_000L, 60_000L, 2.0);
        String principalName = "principal-d";
        Principal principal = () -> principalName;

        listener.nextBackoffDelay(principalName); // returns 5 000, next = 10 000
        listener.nextBackoffDelay(principalName); // returns 10 000, next = 20 000

        // Simulate successful reconnect — should reset back-off state
        connect(principal, listener);

        long delayAfterReset = listener.nextBackoffDelay(principalName);

        assertEquals(5_000L, delayAfterReset,
                "Back-off must reset to initial timeout after a successful reconnect (P2-02)");
    }

    /**
     * Different principals have independent back-off state.
     *
     * <p>Arrange: advance back-off for principalA twice.<br>
     * Act: get back-off delay for principalB.<br>
     * Assert: principalB delay is the initial timeout, not affected by principalA's state.
     */
    @Test
    void backoffDelay_differentPrincipals_haveIndependentBackoffState() {
        SubscriptionListener listener = new SubscriptionListener(
                subscriptionManager, messageCache, 5_000L, 60_000L, 2.0);

        listener.nextBackoffDelay("principal-e"); // 5000 → next 10000
        listener.nextBackoffDelay("principal-e"); // 10000 → next 20000

        long delayForOtherPrincipal = listener.nextBackoffDelay("principal-f");

        assertEquals(5_000L, delayForOtherPrincipal,
                "Back-off state must be independent per principal (P2-02)");
    }

    private void connect(Principal principal) {
        connect(principal, subscriptionListener);
    }

    private void connect(Principal principal, SubscriptionListener listener) {
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

        // Set up the given listener to handle the mock event
        listener.onConnectedEvent(event);
    }

    // -----------------------------------------------------------------------
    // F11: the timer helper must report per the actual principal, not a
    //      hardcoded "user1" key.
    // -----------------------------------------------------------------------
    @Test
    void hasRunningDisconnectTimer_reflectsTheActualPrincipal_notAHardcodedKey() {
        Principal alice = () -> "alice";
        disconnect(alice);

        assertTrue(subscriptionListener.hasRunningDisconnectTimer("alice"),
                "a running timer must be reported for the principal that disconnected");
        assertFalse(subscriptionListener.hasRunningDisconnectTimer("user1"),
                "no timer must be reported for an unrelated principal");
    }

    // -----------------------------------------------------------------------
    // F3: a second disconnect for the same principal must cancel the prior
    //     timer, so that a reconnect cannot be undone by an orphaned timer.
    // -----------------------------------------------------------------------
    @Test
    void doubleDisconnectThenReconnect_doesNotTerminateSubscriptions() throws Exception {
        // Short base delay so the (orphaned) first timer would fire quickly if not cancelled.
        subscriptionListener = new SubscriptionListener(
                subscriptionManager, messageCache, 500L, 300_000L, 2.0);
        Principal principal = () -> "user1";

        connect(principal);
        disconnect(principal);  // timer1 (~500 ms)
        disconnect(principal);  // timer2 (~1000 ms); must cancel timer1
        connect(principal);     // cancels timer2

        // Wait well past timer1's delay — with the bug, the orphaned timer1 fires here.
        Thread.sleep(2_000L);

        verify(subscriptionManager, never()).terminateAllSubscriptions(anyString());
        assertFalse(subscriptionListener.hasRunningDisconnectTimers());
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

