package de.seism0saurus.glacier.webservice.messaging;

import de.seism0saurus.glacier.mastodon.SubscriptionManager;

import static org.mockito.Mockito.mock;

class SubscriptionListenerTest {

    private final SubscriptionManager subscriptionManager = mock(SubscriptionManager.class);

    private final SubscriptionListener subscriptionListener = new SubscriptionListener(subscriptionManager);

//
//    @Test
//    void testOnConnectedEvent() {
//        // Mock event, user, session ID, and message
//        SessionConnectedEvent event = mock(SessionConnectedEvent.class);
//        SimpMessageHeaderAccessor headerAccessor = mock(SimpMessageHeaderAccessor.class);
//        when(headerAccessor.getSessionId()).thenReturn("session123");
//        when(event.getUser()).thenReturn(() -> "user123");
//        when(event.getMessage()).thenReturn(mock(Message.class));
//        when(SimpMessageHeaderAccessor.wrap(event.getMessage())).thenReturn(headerAccessor);
//
//        // Prepare mock future and set it in the disconnectTimer map
//        Future<?> mockFuture = mock(Future.class);
//        HashMap<String, Future<?>> disconnectTimerMock = new HashMap<>();
//        disconnectTimerMock.put("user123", mockFuture);
//
//        // Set the private disconnectTimer field in SubscriptionListener
//        var field = SubscriptionListener.class.getDeclaredField("disconnectTimer");
//        field.setAccessible(true);
//        field.set(subscriptionListener, disconnectTimerMock);
//
//        // Execute
//        subscriptionListener.onConnectedEvent(event);
//
//        // Verify the existing future was canceled
//        verify(mockFuture).cancel(true);
//
//        // Assert no entry remains in disconnectTimer
//        assertNull(disconnectTimerMock.get("user123"));
//    }
//
//    @Test
//    void testOnDisconnectEventWithReconnection() throws Exception {
//        // Mock event, user, session ID, and message
//        SessionDisconnectEvent event = mock(SessionDisconnectEvent.class);
//        SimpMessageHeaderAccessor headerAccessor = mock(SimpMessageHeaderAccessor.class);
//        when(headerAccessor.getSessionId()).thenReturn("session123");
//        when(event.getUser()).thenReturn(() -> "user123");
//        when(event.getMessage()).thenReturn(mock(Message.class));
//        when(SimpMessageHeaderAccessor.wrap(event.getMessage())).thenReturn(headerAccessor);
//
//        // Mock executor service and future
//        ExecutorService executorServiceMock = mock(ExecutorService.class);
//        Future<?> mockFuture = mock(Future.class);
//        when(executorServiceMock.submit(any(Runnable.class))).thenReturn(mockFuture);
//
//        // Set the private disconnectTimer field in SubscriptionListener
//        Map<String, Future<?>> disconnectTimerMock = new HashMap<>();
//        var field = SubscriptionListener.class.getDeclaredField("disconnectTimer");
//        field.setAccessible(true);
//        field.set(subscriptionListener, disconnectTimerMock);
//
//        // Execute
//        subscriptionListener.onDisconnectEvent(event);
//
//        // Timer should be set in disconnectTimer
//        assertTrue(disconnectTimerMock.containsKey("user123"));
//
//        // Simulate reconnection and cancel timer
//        disconnectTimerMock.get("user123").cancel(true);
//        assertTrue(mockFuture.isCancelled());
//    }
//
//    @Test
//    void testOnDisconnectEventWithTimeout() throws Exception {
//        // Mock event, user, session ID, and message
//        SessionDisconnectEvent event = mock(SessionDisconnectEvent.class);
//        SimpMessageHeaderAccessor headerAccessor = mock(SimpMessageHeaderAccessor.class);
//        when(headerAccessor.getSessionId()).thenReturn("session123");
//        when(event.getUser()).thenReturn(() -> "user123");
//        when(event.getMessage()).thenReturn(mock(Message.class));
//        when(SimpMessageHeaderAccessor.wrap(event.getMessage())).thenReturn(headerAccessor);
//
//        // Mock executor service
//        ExecutorService executorServiceMock = Executors.newSingleThreadExecutor();
//        Future<?>[] futureHolder = new Future[1]; // Holder to store future inside lambda
//
//        // Set the private disconnectTimer field in SubscriptionListener
//        Map<String, Future<?>> disconnectTimerMock = new HashMap<>();
//        var field = SubscriptionListener.class.getDeclaredField("disconnectTimer");
//        field.setAccessible(true);
//        field.set(subscriptionListener, disconnectTimerMock);
//
//        // Replace executorService inside SubscriptionListener
//        field = SubscriptionListener.class.getDeclaredField("executorService");
//        field.setAccessible(true);
//        field.set(subscriptionListener, executorServiceMock);
//
//        // Execute
//        subscriptionListener.onDisconnectEvent(event);
//
//        // Let the timeout operation execute
//        Thread.sleep(100);
//        assertTrue(disconnectTimerMock.containsKey("user123"));
//
//        // Assert subscription termination after timeout
//        verify(subscriptionManager, timeout(310_000)).terminateAllSubscriptions("user123");
//    }
}

