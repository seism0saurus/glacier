package de.seism0saurus.glacier.webservice.messaging;

import de.seism0saurus.glacier.GlacierCookieProperties;
import de.seism0saurus.glacier.share.application.ShareLinkActivityRegistry;
import de.seism0saurus.glacier.share.application.ShareLinkService;
import de.seism0saurus.glacier.share.application.ShareLinkViewerCounter;
import de.seism0saurus.glacier.share.domain.ShareLinkCapPolicy;
import de.seism0saurus.glacier.share.web.ShareViewTopicAuthInterceptor;
import de.seism0saurus.glacier.webservice.security.HandshakeRateLimitInterceptor;
import de.seism0saurus.glacier.webservice.security.SubscribeRateLimitInterceptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketTransportRegistration;

// ADR-SHARE-04: dedicated /share-view-ws endpoint for viewer principals

/**
 * Configuration class for Spring Boot WebSocket.
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfiguration implements WebSocketMessageBrokerConfigurer {

    private final String glacierDomain;
    /**
     * When {@code true}, the deployment is assumed to be production (TLS).
     * {@code http://localhost:8080} is removed from the allowed-origins list in that case
     * to minimise CORS attack surface (FIX D, D-08, ADR-06).
     *
     * <p>Mirrors {@code glacier.cookie.secure} — the same flag that gates the cookie
     * {@code Secure} attribute (D-09).
     */
    private final boolean secureCookies;

    @Autowired(required = false)
    private ShareViewTopicAuthInterceptor shareViewTopicAuthInterceptor;

    /** SR-SHARE-05: per-link viewer cap enforcement. Injected as a bean so that
     * {@link ShareViewPrincipalHandler} can be registered as a Spring event listener
     * (needed to receive {@link org.springframework.web.socket.messaging.SessionDisconnectEvent}
     * for counter decrement on disconnect). */
    private final ShareLinkViewerCounter viewerCounter;
    private final ShareLinkCapPolicy capPolicy;

    /**
     * SR-RELAY-05: share-link service forwarded to {@link ShareViewPrincipalHandler} so that
     * {@code determineUser()} can resolve a link BEFORE incrementing the viewer counter.
     * Declared {@code @Lazy} to break the potential circular dependency via event publishing.
     */
    @Lazy
    @Autowired
    private ShareLinkService shareLinkService;

    /**
     * SR-RELAY-06 / SR-RELAY-07: routing table with per-linkId locks.
     * Forwarded to {@link ShareViewPrincipalHandler} for TOCTOU-resistant handshake registration.
     */
    @Autowired
    private ShareLinkActivityRegistry shareLinkActivityRegistry;

    // SR-WS-05 (ADR-PT-G5-01): explicit WebSocket transport limits (OWASP API4)
    private final int messageSizeBytes;
    private final int sendBufferBytes;
    private final int sendTimeMs;

    // SR-WS-01 (ADR-PT-G5-01): handshake rate limiter — injected via @Autowired
    // so that the bean is available for both /websocket and /share-view-ws registrations.
    @Autowired
    private HandshakeRateLimitInterceptor handshakeRateLimitInterceptor;

    // SR-WS-02 (ADR-PT-G7-01): subscribe rate limiter for clientInboundChannel
    @Autowired
    private SubscribeRateLimitInterceptor subscribeRateLimitInterceptor;

    public WebSocketConfiguration(
            @Value(value = "${glacier.domain}") String glacierDomain,
            final GlacierCookieProperties cookieProps,
            final ShareLinkViewerCounter viewerCounter,
            final ShareLinkCapPolicy capPolicy,
            @Value(value = "${glacier.security.ws.message-size-bytes:65536}") int messageSizeBytes,
            @Value(value = "${glacier.security.ws.send-buffer-bytes:524288}") int sendBufferBytes,
            @Value(value = "${glacier.security.ws.send-time-ms:20000}") int sendTimeMs) {
        this.glacierDomain = glacierDomain;
        this.secureCookies = Boolean.TRUE.equals(cookieProps.getSecure());
        this.viewerCounter = viewerCounter;
        this.capPolicy = capPolicy;
        this.messageSizeBytes = messageSizeBytes;
        this.sendBufferBytes = sendBufferBytes;
        this.sendTimeMs = sendTimeMs;
    }

    /**
     * Exposes {@link ShareViewPrincipalHandler} as a Spring bean so that its
     * {@link org.springframework.context.event.EventListener}-annotated
     * {@code onDisconnect(SessionDisconnectEvent)} method is picked up by the application
     * event bus for viewer counter decrement on disconnect (SR-SHARE-05).
     *
     * <p>SR-RELAY-05 / SR-RELAY-06 / SR-RELAY-13: the handler now receives
     * {@code shareLinkService} and {@code shareLinkActivityRegistry} to enforce the
     * secure ordering: {@code resolve()} → {@code increment()} → {@code registry.register()}.
     */
    @Bean
    public ShareViewPrincipalHandler shareViewPrincipalHandler() {
        return new ShareViewPrincipalHandler(
                secureCookies, viewerCounter, capPolicy, shareLinkService, shareLinkActivityRegistry);
    }

    /**
     * Spring-managed heartbeat scheduler for the STOMP simple broker.
     *
     * <p>SR-RELAY-19: declaring this as a {@code @Bean} ensures that
     * {@link ThreadPoolTaskScheduler#destroy()} — which shuts down the underlying
     * {@link java.util.concurrent.ScheduledThreadPoolExecutor} — is invoked by Spring
     * on application shutdown. Without this, the thread pool would leak in integration
     * test runs that load the Spring context multiple times.
     *
     * <p>Spring calls {@link ThreadPoolTaskScheduler#afterPropertiesSet()} automatically
     * via the {@link org.springframework.beans.factory.InitializingBean} contract, so
     * {@code initialize()} must NOT be called manually here.
     */
    @Bean
    public ThreadPoolTaskScheduler stompHeartbeatScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("stomp-heartbeat-");
        // Do NOT call scheduler.initialize() — Spring calls afterPropertiesSet() automatically
        return scheduler;
    }

    /**
     * Register channel interceptors on the inbound channel.
     *
     * <p>Three interceptors are registered in order:
     * <ol>
     *   <li>{@link de.seism0saurus.glacier.webservice.security.SubscribeRateLimitInterceptor} —
     *       rate-limits STOMP SUBSCRIBE frames per (IP + principal) to prevent subscription
     *       enumeration attacks (SR-WS-02, OWASP API6, ADR-PT-G7-01). Runs first so that
     *       over-limit frames are silently dropped before any authorization check.</li>
     *   <li>{@link WallTopicAuthInterceptor} — enforces per-{@link WallPrincipal} topic
     *       isolation for {@code /topic/hashtags/...} (OWASP API1 BOLA, ADR-TEST-01).
     *       Runs second so that wall-owner SUBSCRIBE frames are validated before the
     *       share-viewer interceptor evaluates them.</li>
     *   <li>{@link de.seism0saurus.glacier.share.web.ShareViewTopicAuthInterceptor} —
     *       enforces that viewer principals can only subscribe to
     *       {@code /topic/share/{shareLinkId}/...} — never to {@code /topic/hashtags/...}
     *       (ADR-SHARE-04, SR-SHARE-06).</li>
     * </ol>
     */
    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        WallTopicAuthInterceptor wallInterceptor = new WallTopicAuthInterceptor();
        if (shareViewTopicAuthInterceptor != null) {
            registration.interceptors(subscribeRateLimitInterceptor, wallInterceptor, shareViewTopicAuthInterceptor);
        } else {
            registration.interceptors(subscribeRateLimitInterceptor, wallInterceptor);
        }
    }

    /**
     * Configures WebSocket transport limits (SR-WS-05, ADR-PT-G5-01).
     *
     * <p>Explicitly sets three axes of resource consumption control:
     * <ol>
     *   <li><b>Message size limit</b> ({@code glacier.security.ws.message-size-bytes}, default 64 KB) —
     *       prevents oversized STOMP frame attacks (OWASP API4, CWE-400).</li>
     *   <li><b>Send buffer size limit</b> ({@code glacier.security.ws.send-buffer-bytes}, default 512 KB) —
     *       caps the per-session send buffer to prevent slow-client memory exhaustion.</li>
     *   <li><b>Send time limit</b> ({@code glacier.security.ws.send-time-ms}, default 20 s) —
     *       disconnects sessions that cannot consume messages within the time limit.</li>
     * </ol>
     *
     * <p>Without these explicit values, Spring defaults apply only partially.
     * Making them explicit ensures operators can tune them via environment variables
     * and that security auditors can verify the configuration (SR-WS-06).
     */
    @Override
    public void configureWebSocketTransport(WebSocketTransportRegistration registration) {
        // OWASP API4 / CWE-400: explicit frame-size and buffer caps
        registration.setMessageSizeLimit(messageSizeBytes);
        registration.setSendBufferSizeLimit(sendBufferBytes);
        registration.setSendTimeLimit(sendTimeMs);
    }

    /**
     * Configures the message broker for WebSocket communication.
     * <p>
     * The application is called glacier.
     * The destination prefix ist topic.
     *
     * @param config the MessageBrokerRegistry object used for configuring the message broker
     */
    @Override
    public void configureMessageBroker(MessageBrokerRegistry config) {
        // SR-RELAY-19: 10 s heartbeat (incoming/outgoing) so the broker detects stale viewer
        // sessions promptly — without a heartbeat, zombie sessions consume counter slots
        // and block new viewers from connecting (OWASP API4: Unrestricted Resource Consumption).
        // A TaskScheduler is required by Spring's SimpleBrokerMessageHandler when heartbeat is set.
        // The scheduler is Spring-managed (stompHeartbeatScheduler bean) so destroy() is called
        // on application stop, preventing thread pool leaks during repeated context loads in tests.
        config.enableSimpleBroker("/topic")
                .setHeartbeatValue(new long[]{10000, 10000})
                .setTaskScheduler(stompHeartbeatScheduler()); // @Configuration CGLIB returns singleton
        config.setApplicationDestinationPrefixes("/glacier");
    }

    /**
     * Registers the STOMP WebSocket endpoint with a security-hardened allowed-origins list.
     *
     * <p>FIX D (D-08, ADR-06): {@code http://localhost:8080} is only included when
     * {@code glacier.cookie.secure=false} (dev/loopback mode). In production
     * ({@code secureCookies=true}), it is excluded to reduce CORS attack surface.
     * The Angular dev server on :4200 is always allowed (needed during local development
     * regardless of the TLS flag).
     */
    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        // Main wall endpoint — sharer context (wallId principal)
        String[] allowedOrigins = secureCookies
                ? new String[]{"http://localhost:4200", "https://" + glacierDomain}
                : new String[]{"http://localhost:4200", "http://localhost:8080", "https://" + glacierDomain};

        registry.addEndpoint("/websocket")
                .setAllowedOrigins(allowedOrigins)
                .setHandshakeHandler(new PrincipalHandler())
                // SR-WS-01: per-IP handshake rate limit (OWASP API4, ADR-PT-G5-01)
                .addInterceptors(handshakeRateLimitInterceptor);

        // ADR-SHARE-04: Dedicated endpoint for readonly share viewers.
        // Uses ShareViewPrincipalHandler which reads __Host-shareViewerId exclusively,
        // preventing any cross-namespace privilege escalation.
        // SR-SHARE-05: handler is the Spring bean (not a new instance) so that the
        // @EventListener for SessionDisconnectEvent is registered on the application bus.
        String[] shareViewOrigins = secureCookies
                ? new String[]{"http://localhost:4200", "https://" + glacierDomain, "https://share." + glacierDomain}
                : new String[]{"http://localhost:4200", "http://localhost:8080", "https://" + glacierDomain, "https://share." + glacierDomain};
        registry.addEndpoint("/share-view-ws")
                .setAllowedOrigins(shareViewOrigins)
                .setHandshakeHandler(shareViewPrincipalHandler())
                // SR-WS-01: per-IP handshake rate limit — same interceptor instance as /websocket (OWASP API4).
                // Design choice: budgets are combined across both endpoints. A high-traffic source IP
                // opening many /websocket connections consumes from the same per-IP bucket as /share-view-ws
                // viewers at that IP. Acceptable for single-instance homelab deployment (AR-WS-01).
                .addInterceptors(handshakeRateLimitInterceptor);
    }
}