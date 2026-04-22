package de.seism0saurus.glacier.webservice.messaging;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

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
    private final boolean cookieSecure;

    public WebSocketConfiguration(
            @Value(value = "${glacier.domain}") String glacierDomain,
            @Value(value = "${glacier.cookie.secure:true}") boolean cookieSecure) {
        this.glacierDomain = glacierDomain;
        this.cookieSecure = cookieSecure;
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
     * Registers a STOMP endpoint for WebSocket communication.
     * <p>
     * The endpoint is registered under "/websocket".
     * A "sessionId" is added to the attributes to enable sending messages to a specific user with a "/user" prefix.
     *
     * @param registry the StompEndpointRegistry object used for registering the endpoint
     */
    /**
     * Registers the STOMP WebSocket endpoint with a security-hardened allowed-origins list.
     *
     * <p>FIX D (D-08, ADR-06): {@code http://localhost:8080} is only included when
     * {@code glacier.cookie.secure=false} (dev/loopback mode). In production
     * ({@code cookieSecure=true}), it is excluded to reduce CORS attack surface.
     * The Angular dev server on :4200 is always allowed (needed during local development
     * regardless of the TLS flag).
     */
    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        // Production origins: Angular dev server + HTTPS production domain
        // Dev-only addition: bare-jar run on :8080 (only when TLS/Secure mode is off)
        String[] allowedOrigins = cookieSecure
                ? new String[]{"http://localhost:4200", "https://" + glacierDomain}
                : new String[]{"http://localhost:4200", "http://localhost:8080", "https://" + glacierDomain};

        registry.addEndpoint("/websocket")
                .setAllowedOrigins(allowedOrigins)
                .setHandshakeHandler(new PrincipalHandler());
    }
}