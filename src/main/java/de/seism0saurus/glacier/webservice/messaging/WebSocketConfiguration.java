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

    public WebSocketConfiguration(@Value(value = "${glacier.domain}") String glacierDomain){
        this.glacierDomain = glacierDomain;
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
        registry.addEndpoint("/websocket")
                .setAllowedOrigins("http://localhost:4200","http://localhost:8080","https://"+glacierDomain) //TODO: Make it better configurable and prevent localhost for prod
                .setHandshakeHandler(new PrincipalHandler());
    }
}