package de.seism0saurus.glacier;

import jakarta.annotation.Generated;
import org.jetbrains.annotations.NotNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.time.Clock;

/**
 * The GlacierApplication class is the main class for running the Glacier application.
 * It is annotated with the @SpringBootApplication annotation to enable Spring Boot features and configuration.
 * The @EnableScheduling annotation is used to enable scheduling support in the application
 * (required for {@code FallbackRateLimiter.evictStaleBuckets()}).
 * It contains a main method that starts the application.
 *
 * <p>CORS (D-08, ADR-06, SR-6):
 * Allowed origins for {@code /rest/*} are exactly:
 * <ul>
 *   <li>{@code http://localhost:4200} — Angular dev server</li>
 *   <li>{@code https://${glacier.domain}} — production origin</li>
 * </ul>
 * {@code allowCredentials} is NOT set (defaults to false — never "*" origins with credentials).
 */
@SpringBootApplication
@EnableScheduling
public class GlacierApplication {

    @Generated(value = "GlacierApplication")
    public static void main(String[] args) {
        SpringApplication.run(GlacierApplication.class, args);
    }

    /**
     * Configures CORS for the {@code /rest/*} path.
     *
     * <p>Security controls (D-08, ADR-06, SR-6, OWASP A05, T-18):
     * <ul>
     *   <li>Exact allowed-origins list — never wildcard {@code *}</li>
     *   <li>{@code allowCredentials} not set (false by default) — prevents cookie exfil
     *       via cross-origin credentialed requests</li>
     *   <li>Production origin ({@code https://${glacier.domain}}) explicitly listed so that
     *       only the operator's domain is permitted, not any subdomain</li>
     * </ul>
     *
     * @param domain the configured {@code glacier.domain} value
     */
    @Bean
    public WebMvcConfigurer corsConfigurer(@Value("${glacier.domain:example.com}") String domain) {
        return new WebMvcConfigurer() {
            @Override
            public void addCorsMappings(@NotNull CorsRegistry registry) {
                // SR-6 / ADR-06: exact origin allowlist, no wildcard, no allowCredentials
                registry.addMapping("/rest/*")
                        .allowedOrigins(
                                "http://localhost:4200",       // Angular dev server
                                "https://" + domain            // production origin
                        );
                // allowCredentials() not called — defaults to false (OWASP A05)
            }
        };
    }

    /**
     * Creates a {@link RestTemplate} bean with explicit connect and read timeouts (C-03)
     * and redirect-following disabled (H-3 SSRF guard).
     *
     * <p>Used by {@link de.seism0saurus.glacier.mastodon.StompCallback} to issue HEAD
     * requests to toot embed URLs.  Without timeouts, a slow or hung remote Mastodon
     * instance would stall the Bigbone virtual thread indefinitely, blocking event
     * delivery for that {@code (principal, hashtag)} subscription.
     *
     * <p>H-3 security control (OWASP A10 SSRF, NIST SP 800-53 SI-3): redirect-following is
     * disabled by overriding {@link SimpleClientHttpRequestFactory#prepareConnection} and
     * calling {@link HttpURLConnection#setInstanceFollowRedirects(false)}.  Without this,
     * an attacker-controlled Mastodon instance can issue a 302 redirect from a HEAD request
     * to {@code https://trusted-host.com/embed} to an internal address such as
     * {@code http://169.254.169.254/latest/meta-data/}, bypassing any URL allowlist that
     * {@code StompCallback.isLoadable} applies before the request.  With redirect-following
     * disabled, 3xx responses are returned directly to {@code isLoadable}, which must treat
     * them as non-embeddable.
     *
     * <p>Defaults (overridable via environment variables):
     * <ul>
     *   <li>Connect timeout: 3 000 ms ({@code GLACIER_EMBED_CONNECT_TIMEOUT_MS})</li>
     *   <li>Read timeout:    5 000 ms ({@code GLACIER_EMBED_READ_TIMEOUT_MS})</li>
     * </ul>
     *
     * @param connectMs connect timeout in milliseconds
     * @param readMs    read timeout in milliseconds
     * @return a {@link RestTemplate} backed by a {@link SimpleClientHttpRequestFactory}
     *         with the configured timeouts and redirect-following disabled
     */
    @Bean
    public RestTemplate restTemplate(
            @Value("${glacier.embed.connectTimeoutMs:3000}") int connectMs,
            @Value("${glacier.embed.readTimeoutMs:5000}") int readMs) {
        // H-3: anonymous subclass overrides prepareConnection to disable redirect-following.
        // This must come BEFORE super.prepareConnection so the flag is set on every connection.
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory() {
            @Override
            protected void prepareConnection(HttpURLConnection connection, String httpMethod)
                    throws IOException {
                super.prepareConnection(connection, httpMethod);
                // H-3 SSRF guard (OWASP A10): never follow 3xx redirects automatically.
                // An attacker-controlled origin can 302-redirect HEAD /embed to an internal
                // address, bypassing the URL allowlist in StompCallback.isLoadable.
                connection.setInstanceFollowRedirects(false);
            }
        };
        factory.setConnectTimeout(connectMs);
        factory.setReadTimeout(readMs);
        return new RestTemplate(factory);
    }

    /**
     * Provides a UTC {@link Clock} bean for injection into {@link de.seism0saurus.glacier.webservice.cache.FallbackRateLimiter}.
     *
     * <p>H-1 fix: externalising the clock makes time-dependent eviction logic fully
     * deterministic in tests — test code can inject a controlled {@link Clock} instance
     * instead of relying on reflection to manipulate {@link java.time.Instant} fields.
     *
     * @return {@link Clock#systemUTC()} for production use
     */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    /**
     * Provides a UTC {@link Clock} bean for injection into rate limiters.
     * Using an injected Clock rather than Instant.now() makes timing deterministic in tests.
     * NIST SP 800-53 AC-3 (rate limiting requires deterministic admission decisions).
     */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
