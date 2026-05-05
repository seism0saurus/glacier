package de.seism0saurus.glacier.webservice.messaging;

import de.seism0saurus.glacier.share.application.ShareLinkViewerCounter;
import de.seism0saurus.glacier.share.domain.ShareLinkCapPolicy;
import de.seism0saurus.glacier.share.web.ShareViewTopicAuthInterceptor;
import de.seism0saurus.glacier.webservice.security.HandshakeRateLimitInterceptor;
import de.seism0saurus.glacier.webservice.security.SubscribeRateLimitInterceptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
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
            @Value(value = "${glacier.cookie.secure:true}") boolean secureCookies,
            final ShareLinkViewerCounter viewerCounter,
            final ShareLinkCapPolicy capPolicy,
            @Value(value = "${glacier.security.ws.message-size-bytes:65536}") int messageSizeBytes,
            @Value(value = "${glacier.security.ws.send-buffer-bytes:524288}") int sendBufferBytes,
            @Value(value = "${glacier.security.ws.send-time-ms:20000}") int sendTimeMs) {
        this.glacierDomain = glacierDomain;
        this.secureCookies = secureCookies;
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
     */
    @Bean
    public ShareViewPrincipalHandler shareViewPrincipalHandler() {
        return new ShareViewPrincipalHandler(secureCookies, viewerCounter, capPolicy);
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
        config.enableSimpleBroker("/topic");
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