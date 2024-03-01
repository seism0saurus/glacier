package de.seism0saurus.glacier.webservice;

import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.messaging.simp.config.SimpleBrokerRegistration;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.StompWebSocketEndpointRegistration;
import org.springframework.web.socket.server.support.DefaultHandshakeHandler;

import static org.mockito.Mockito.*;

/*
 * WebSocketConfigurationTest is a test class that tests the methods in WebSocketConfiguration class.
 * The configureMessageBroker method is being tested here.
 */
public class WebSocketConfigurationTest {

    /*
     * This test method tests the configureMessageBroker method of WebSocketConfiguration class.
     * The expected outcome is that the enableSimpleBroker method is called with "/topic",
     * and the setApplicationDestinationPrefixes method is called with "/glacier".
     */
    @Test
    public void testConfigureMessageBroker() {
        // Setup
        MessageBrokerRegistry mockRegistry = mock(MessageBrokerRegistry.class);
        MessageBrokerRegistry simpleBrokerRegistration =
                mock(MessageBrokerRegistry.class);
        SimpleBrokerRegistration brokerRegistration =
                mock(SimpleBrokerRegistration.class);
        when(mockRegistry.enableSimpleBroker("/topic"))
                .thenReturn(brokerRegistration);
        when(mockRegistry.setApplicationDestinationPrefixes("/glacier"))
                .thenReturn(simpleBrokerRegistration);
        WebSocketConfiguration webSocketConfiguration = new WebSocketConfiguration();

        // Execute
        webSocketConfiguration.configureMessageBroker(mockRegistry);

        // Verify
        verify(mockRegistry, times(1)).enableSimpleBroker("/topic");
        verify(mockRegistry, times(1)).setApplicationDestinationPrefixes("/glacier");
    }


    /**
     * Test that a `DefaultHandshakeHandler` is set during the registration of STOMP endpoint
     */
    @Test
    void testSetHandshakeHandler() {
        // Setup
        StompEndpointRegistry registry = mock(StompEndpointRegistry.class);
        StompWebSocketEndpointRegistration registration = mock(StompWebSocketEndpointRegistration.class);
        when(registry.addEndpoint(anyString())).thenReturn(registration);
        when(registration.setAllowedOrigins(anyString())).thenReturn(registration);
        WebSocketConfiguration webSocketConfiguration = new WebSocketConfiguration();

        // Execute
        webSocketConfiguration.registerStompEndpoints(registry);

        // Verify
        verify(registration, times(1)).setHandshakeHandler(any(DefaultHandshakeHandler.class));
    }
}