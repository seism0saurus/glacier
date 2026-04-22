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
     * Registers a STOMP endpoint for WebSocket communication.
     * <p>
     * The endpoint is registered under "/websocket".
     * A "sessionId" is added to the attributes to enable sending messages to a specific user with a "/user" prefix.
     *
     * @param registry the StompEndpointRegistry object used for registering the endpoint
     */
    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        // Main wall endpoint — sharer context (wallId principal)
        registry.addEndpoint("/websocket")
                .setAllowedOrigins("http://localhost:4200","http://localhost:8080","https://"+glacierDomain) //TODO: Make it better configurable and prevent localhost for prod
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