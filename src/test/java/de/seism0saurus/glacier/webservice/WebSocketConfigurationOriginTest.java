package de.seism0saurus.glacier.webservice;

import de.seism0saurus.glacier.webservice.messaging.WebSocketConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.StompWebSocketEndpointRegistration;
import org.springframework.web.socket.config.annotation.WebMvcStompEndpointRegistry;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Tests for CORS origin hardening in {@link WebSocketConfiguration} (FIX D, D-08, ADR-06).
 *
 * <p>When {@code glacier.cookie.secure=true} (production), {@code http://localhost:8080} must
 * NOT be in the allowed-origins list — it is a plaintext loopback origin that serves no
 * legitimate production use and expands the CORS attack surface.
 *
 * <p>When {@code glacier.cookie.secure=false} (dev), {@code http://localhost:8080} MAY be
 * present so that developers can run the Spring Boot jar directly alongside the Angular
 * dev server on :4200.
 */
class WebSocketConfigurationOriginTest {

    private static final String GLACIER_DOMAIN = "glacier.example.com";

    /**
     * Production profile (cookieSecure=true): localhost:8080 must NOT be in the origin list.
     */
    @Test
    void registerStompEndpoints_secureModeTrue_doesNotAllowLocalhostPort8080() {
        List<String> origins = captureAllowedOrigins(GLACIER_DOMAIN, true);

        assertThat(origins).isNotNull();
        assertThat(origins).doesNotContain("http://localhost:8080");
    }

    /**
     * Production profile (cookieSecure=true): the required origins ARE present.
     */
    @Test
    void registerStompEndpoints_secureModeTrue_containsDevAndProdOrigins() {
        List<String> origins = captureAllowedOrigins(GLACIER_DOMAIN, true);

        assertThat(origins).contains("http://localhost:4200");
        assertThat(origins).contains("https://" + GLACIER_DOMAIN);
    }

    /**
     * Dev profile (cookieSecure=false): localhost:8080 IS allowed (for bare-jar dev runs).
     */
    @Test
    void registerStompEndpoints_secureModeOff_allowsLocalhostPort8080() {
        List<String> origins = captureAllowedOrigins(GLACIER_DOMAIN, false);

        assertThat(origins).contains("http://localhost:8080");
    }

    // -------------------------------------------------------------------------
    // Helper
    // -------------------------------------------------------------------------

    /**
     * Invokes {@link WebSocketConfiguration#registerStompEndpoints} and captures the vararg
     * passed to {@code StompWebSocketEndpointRegistration#setAllowedOrigins}.
     */
    private List<String> captureAllowedOrigins(String glacierDomain, boolean cookieSecure) {
        WebSocketConfiguration config = new WebSocketConfiguration(glacierDomain, cookieSecure);

        StompEndpointRegistry registry = mock(WebMvcStompEndpointRegistry.class);
        StompWebSocketEndpointRegistration registration = mock(StompWebSocketEndpointRegistration.class);
        when(registry.addEndpoint(anyString())).thenReturn(registration);
        when(registration.setHandshakeHandler(any())).thenReturn(registration);

        AtomicReference<List<String>> captured = new AtomicReference<>();
        doAnswer(inv -> {
            // setAllowedOrigins is declared as setAllowedOrigins(String... origins)
            // Mockito passes the vararg as individual parameters; getArguments() gives them
            Object[] args = inv.getArguments();
            // When called as setAllowedOrigins(String[]) Mockito may wrap them differently.
            // Handle both: single String[] arg and spread individual String args.
            if (args.length == 1 && args[0] instanceof String[]) {
                captured.set(Arrays.asList((String[]) args[0]));
            } else {
                String[] strs = Arrays.copyOf(args, args.length, String[].class);
                captured.set(Arrays.asList(strs));
            }
            return registration;
        }).when(registration).setAllowedOrigins(any(String[].class));

        config.registerStompEndpoints(registry);

        return captured.get();
    }
}
