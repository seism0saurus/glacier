package de.seism0saurus.glacier.webservice.messaging;

import de.seism0saurus.glacier.share.web.ShareViewTopicAuthInterceptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

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

    public WebSocketConfiguration(
            @Value(value = "${glacier.domain}") String glacierDomain,
            @Value(value = "${glacier.cookie.secure:true}") boolean secureCookies) {
        this.glacierDomain = glacierDomain;
        this.secureCookies = secureCookies;
    }

    /**
     * Register the ShareViewTopicAuthInterceptor on the inbound channel.
     * This enforces that viewer principals (sv_ prefix) can only subscribe to
     * /topic/share/{shareLinkId}/... — never to /topic/hashtags/... (ADR-SHARE-04, SR-SHARE-06).
     */
    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        if (shareViewTopicAuthInterceptor != null) {
            registration.interceptors(shareViewTopicAuthInterceptor);
        }
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
                .setHandshakeHandler(new PrincipalHandler());

        // ADR-SHARE-04: Dedicated endpoint for readonly share viewers.
        // Uses ShareViewPrincipalHandler which reads __Host-shareViewerId exclusively,
        // preventing any cross-namespace privilege escalation.
        registry.addEndpoint("/share-view-ws")
                .setAllowedOrigins(
                        "http://localhost:4200",
                        "http://localhost:8080",
                        "https://" + glacierDomain,
                        "https://share." + glacierDomain)
                .setHandshakeHandler(new ShareViewPrincipalHandler(secureCookies));
    }
}