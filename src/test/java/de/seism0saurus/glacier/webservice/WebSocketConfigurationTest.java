package de.seism0saurus.glacier.webservice;

import de.seism0saurus.glacier.GlacierCookieProperties;
import de.seism0saurus.glacier.share.application.ShareLinkActivityRegistry;
import de.seism0saurus.glacier.share.application.ShareLinkService;
import de.seism0saurus.glacier.share.application.ShareLinkViewerCounter;
import de.seism0saurus.glacier.share.domain.ShareLinkCapPolicy;
import de.seism0saurus.glacier.webservice.messaging.WallTopicAuthInterceptor;
import de.seism0saurus.glacier.webservice.messaging.WebSocketConfiguration;
import de.seism0saurus.glacier.webservice.security.HandshakeRateLimitInterceptor;
import de.seism0saurus.glacier.webservice.security.SubscribeRateLimitInterceptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.messaging.simp.config.SimpleBrokerRegistration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.StompWebSocketEndpointRegistration;
import org.springframework.web.socket.config.annotation.WebMvcStompEndpointRegistry;
import org.springframework.web.socket.server.support.DefaultHandshakeHandler;

import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/*
 * WebSocketConfigurationTest is a test class that tests the methods in WebSocketConfiguration class.
 * The configureMessageBroker method is being tested here.
 */
public class WebSocketConfigurationTest {

    /** Factory helper: creates {@link GlacierCookieProperties} with the given secure flag. */
    private static GlacierCookieProperties cookieProps(boolean secure) {
        GlacierCookieProperties props = new GlacierCookieProperties();
        props.setSecure(secure);
        return props;
    }

    /** Injects the @Autowired fields that Spring would normally inject. */
    private static WebSocketConfiguration createConfig(String domain, boolean secureCookies) {
        WebSocketConfiguration config = new WebSocketConfiguration(
                domain, cookieProps(secureCookies), new ShareLinkViewerCounter(), new ShareLinkCapPolicy(),
                65536, 524288, 20000);
        // Inject @Autowired interceptors and SR-RELAY-05/06 deps via reflection (Spring normally does this)
        try {
            Field handshakeField = WebSocketConfiguration.class.getDeclaredField("handshakeRateLimitInterceptor");
            handshakeField.setAccessible(true);
            handshakeField.set(config, mock(HandshakeRateLimitInterceptor.class));

            Field subscribeField = WebSocketConfiguration.class.getDeclaredField("subscribeRateLimitInterceptor");
            subscribeField.setAccessible(true);
            subscribeField.set(config, mock(SubscribeRateLimitInterceptor.class));

            // SR-RELAY-05: inject shareLinkService mock (new constructor dep)
            Field shareLinkServiceField = WebSocketConfiguration.class.getDeclaredField("shareLinkService");
            shareLinkServiceField.setAccessible(true);
            shareLinkServiceField.set(config, mock(ShareLinkService.class));

            // SR-RELAY-06: inject registry mock (new constructor dep)
            Field registryField = WebSocketConfiguration.class.getDeclaredField("shareLinkActivityRegistry");
            registryField.setAccessible(true);
            registryField.set(config, mock(ShareLinkActivityRegistry.class));

        } catch (Exception e) {
            throw new RuntimeException("Failed to inject interceptor mocks", e);
        }
        return config;
    }

    /*
     * This test method tests the configureMessageBroker method of WebSocketConfiguration class.
     * The expected outcome is that the enableSimpleBroker method is called with "/topic",
     * and the setApplicationDestinationPrefixes method is called with "/glacier".
     */
    @Test
    public void testConfigureMessageBroker() {
        // Setup
        MessageBrokerRegistry mockRegistry = mock(MessageBrokerRegistry.class);
        MessageBrokerRegistry simpleBrokerRegistration = mock(MessageBrokerRegistry.class);
        SimpleBrokerRegistration brokerRegistration = mock(SimpleBrokerRegistration.class);
        when(mockRegistry.enableSimpleBroker("/topic")).thenReturn(brokerRegistration);
        when(mockRegistry.setApplicationDestinationPrefixes("/glacier")).thenReturn(simpleBrokerRegistration);
        // SR-RELAY-19: configureMessageBroker now chains .setHeartbeatValue().setTaskScheduler()
        // on the SimpleBrokerRegistration — stub the chainable returns to avoid NPE.
        when(brokerRegistration.setHeartbeatValue(any(long[].class))).thenReturn(brokerRegistration);
        when(brokerRegistration.setTaskScheduler(any(TaskScheduler.class))).thenReturn(brokerRegistration);
        WebSocketConfiguration webSocketConfiguration = createConfig("example.com", true);

        // Execute
        webSocketConfiguration.configureMessageBroker(mockRegistry);

        // Verify
        verify(mockRegistry, times(1)).enableSimpleBroker("/topic");
        verify(mockRegistry, times(1)).setApplicationDestinationPrefixes("/glacier");
        // SR-RELAY-19: verify heartbeat is configured
        verify(brokerRegistration, times(1)).setHeartbeatValue(new long[]{10000, 10000});
        verify(brokerRegistration, times(1)).setTaskScheduler(any(TaskScheduler.class));
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
        when(registration.setHandshakeHandler(any())).thenReturn(registration);
        when(registration.addInterceptors(any())).thenReturn(registration);
        WebSocketConfiguration webSocketConfiguration = createConfig("example.com", true);

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

        WebSocketConfiguration config = createConfig("example.com", true);

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
     * SR-RELAY-19: verifies that the {@code stompHeartbeatScheduler} @Bean method produces a
     * {@link ThreadPoolTaskScheduler} with pool size 1 and the expected thread-name prefix.
     *
     * <p>This guards against thread-pool leaks in repeated-context integration test runs:
     * declaring the scheduler as a bean ensures Spring calls {@code destroy()} on shutdown.
     * The test verifies the factory method configuration before {@code afterPropertiesSet()}
     * (i.e., before {@code initialize()}) so it is independent of the running thread pool.
     */
    @Test
    void stompHeartbeatSchedulerBean_isConfiguredCorrectly() {
        WebSocketConfiguration config = createConfig("example.com", true);

        ThreadPoolTaskScheduler scheduler = config.stompHeartbeatScheduler();

        assertThat(scheduler).as("stompHeartbeatScheduler bean must not be null").isNotNull();
        assertThat(scheduler.getPoolSize())
                .as("SR-RELAY-19: pool size must be 1 (single scheduling thread for heartbeats)")
                .isEqualTo(1);
        assertThat(scheduler.getThreadNamePrefix())
                .as("SR-RELAY-19: thread name prefix must be 'stomp-heartbeat-' for diagnostic traceability")
                .isEqualTo("stomp-heartbeat-");
    }

    /**
     * Test that configureMessageBroker can handle different prefixes being set.
     * SR-RELAY-19: also verifies that heartbeat value is always set to 10s regardless of other prefix config.
     */
    @Test
    public void testConfigureMessageBrokerWithDifferentPrefixes() {
        // Setup
        MessageBrokerRegistry mockRegistry = mock(MessageBrokerRegistry.class);
        SimpleBrokerRegistration brokerRegistration = mock(SimpleBrokerRegistration.class);
        // The config always uses "/topic" — stub that return, not "/anotherTopic"
        when(mockRegistry.enableSimpleBroker("/topic")).thenReturn(brokerRegistration);
        // SR-RELAY-19: chain stubs for heartbeat configuration
        when(brokerRegistration.setHeartbeatValue(any(long[].class))).thenReturn(brokerRegistration);
        when(brokerRegistration.setTaskScheduler(any(TaskScheduler.class))).thenReturn(brokerRegistration);

        WebSocketConfiguration webSocketConfiguration = createConfig("example.com", true);

        // Execute
        webSocketConfiguration.configureMessageBroker(mockRegistry);

        // Verify
        verify(mockRegistry, times(1)).enableSimpleBroker("/topic");
        verify(mockRegistry, times(1)).setApplicationDestinationPrefixes("/glacier");
        // SR-RELAY-19: heartbeat must be configured
        verify(brokerRegistration, times(1)).setHeartbeatValue(new long[]{10000, 10000});
        verify(brokerRegistration, times(1)).setTaskScheduler(any(TaskScheduler.class));
    }
}