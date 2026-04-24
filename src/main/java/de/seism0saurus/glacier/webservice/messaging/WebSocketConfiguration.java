package de.seism0saurus.glacier.webservice.messaging;

import de.seism0saurus.glacier.share.application.ShareLinkViewerCounter;
import de.seism0saurus.glacier.share.domain.ShareLinkCapPolicy;
import de.seism0saurus.glacier.share.web.ShareViewTopicAuthInterceptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
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

    /** SR-SHARE-05: per-link viewer cap enforcement. Injected as a bean so that
     * {@link ShareViewPrincipalHandler} can be registered as a Spring event listener
     * (needed to receive {@link org.springframework.web.socket.messaging.SessionDisconnectEvent}
     * for counter decrement on disconnect). */
    private final ShareLinkViewerCounter viewerCounter;
    private final ShareLinkCapPolicy capPolicy;

    public WebSocketConfiguration(
            @Value(value = "${glacier.domain}") String glacierDomain,
            @Value(value = "${glacier.cookie.secure:true}") boolean secureCookies,
            final ShareLinkViewerCounter viewerCounter,
            final ShareLinkCapPolicy capPolicy) {
        this.glacierDomain = glacierDomain;
        this.secureCookies = secureCookies;
        this.viewerCounter = viewerCounter;
        this.capPolicy = capPolicy;
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
        // SR-SHARE-05: handler is the Spring bean (not a new instance) so that the
        // @EventListener for SessionDisconnectEvent is registered on the application bus.
        String[] shareViewOrigins = secureCookies
                ? new String[]{"http://localhost:4200", "https://" + glacierDomain, "https://share." + glacierDomain}
                : new String[]{"http://localhost:4200", "http://localhost:8080", "https://" + glacierDomain, "https://share." + glacierDomain};
        registry.addEndpoint("/share-view-ws")
                .setAllowedOrigins(shareViewOrigins)
                .setHandshakeHandler(shareViewPrincipalHandler());
    }
}