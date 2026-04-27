package de.seism0saurus.glacier.webservice;

import de.seism0saurus.glacier.share.application.ShareLinkViewerCounter;
import de.seism0saurus.glacier.share.domain.ShareLinkCapPolicy;
import de.seism0saurus.glacier.webservice.messaging.WallTopicAuthInterceptor;
import de.seism0saurus.glacier.webservice.messaging.WebSocketConfiguration;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.messaging.simp.config.SimpleBrokerRegistration;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.StompWebSocketEndpointRegistration;
import org.springframework.web.socket.config.annotation.WebMvcStompEndpointRegistry;
import org.springframework.web.socket.server.support.DefaultHandshakeHandler;

import static org.assertj.core.api.Assertions.assertThat;
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
        WebSocketConfiguration webSocketConfiguration = new WebSocketConfiguration(
                "example.com", true, new ShareLinkViewerCounter(), new ShareLinkCapPolicy());

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
        StompEndpointRegistry registry = mock(WebMvcStompEndpointRegistry.class);
        StompWebSocketEndpointRegistration registration = mock(StompWebSocketEndpointRegistration.class);
        when(registry.addEndpoint(anyString())).thenReturn(registration);
        // Use vararg-safe stub: matches any number of origin strings
        // (main endpoint passes 3, share-view endpoint passes 4)
        when(registration.setAllowedOrigins(any(String[].class))).thenReturn(registration);
        WebSocketConfiguration webSocketConfiguration = new WebSocketConfiguration(
                "example.com", true, new ShareLinkViewerCounter(), new ShareLinkCapPolicy());

        // Execute
        webSocketConfiguration.registerStompEndpoints(registry);

        // Verify: both endpoints (/websocket and /share-view-ws) set a DefaultHandshakeHandler.
        // PrincipalHandler and ShareViewPrincipalHandler both extend DefaultHandshakeHandler,
        // so the matcher fires twice — once per endpoint registration.
        verify(registration, times(2)).setHandshakeHandler(any(DefaultHandshakeHandler.class));
    }

    /**
     * F-9: Verifies that {@link WebSocketConfiguration#configureClientInboundChannel}
     * registers at least one {@link WallTopicAuthInterceptor} on the inbound channel.
     *
     * <p>Security: OWASP API1 (BOLA), ADR-TEST-01 — if the interceptor is not wired,
     * any client can subscribe to any other principal's topic by guessing their wallId.
     */
    @Test
    void configureClientInboundChannel_registersWallTopicAuthInterceptor() {
        // Arrange
        ChannelRegistration registration = mock(ChannelRegistration.class);
        when(registration.interceptors(any(ChannelInterceptor[].class))).thenReturn(registration);

        WebSocketConfiguration config = new WebSocketConfiguration(
                "example.com", true, new ShareLinkViewerCounter(), new ShareLinkCapPolicy());

        // Act
        config.configureClientInboundChannel(registration);

        // Assert — at least one WallTopicAuthInterceptor must be registered
        ArgumentCaptor<ChannelInterceptor[]> captor =
                ArgumentCaptor.forClass(ChannelInterceptor[].class);
        verify(registration).interceptors(captor.capture());

        assertThat(captor.getValue())
                .as("WallTopicAuthInterceptor must be registered on the inbound channel (F-9, OWASP API1)")
                .anyMatch(i -> i instanceof WallTopicAuthInterceptor);
    }

    /**
     * Test that configureMessageBroker can handle different prefixes being set.
     */
    @Test
    public void testConfigureMessageBrokerWithDifferentPrefixes() {
        // Setup
        MessageBrokerRegistry mockRegistry = mock(MessageBrokerRegistry.class);
        SimpleBrokerRegistration brokerRegistration = mock(SimpleBrokerRegistration.class);
        when(mockRegistry.enableSimpleBroker("/anotherTopic")).thenReturn(brokerRegistration);

        WebSocketConfiguration webSocketConfiguration = new WebSocketConfiguration(
                "example.com", true, new ShareLinkViewerCounter(), new ShareLinkCapPolicy());

        // Execute
        webSocketConfiguration.configureMessageBroker(mockRegistry);

        // Verify
        verify(mockRegistry, times(1)).enableSimpleBroker("/topic");
        verify(mockRegistry, times(1)).setApplicationDestinationPrefixes("/glacier");
    }
}