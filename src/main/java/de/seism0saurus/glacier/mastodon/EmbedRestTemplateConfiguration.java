package de.seism0saurus.glacier.mastodon;

import org.apache.hc.client5.http.classic.HttpClient;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.core5.util.TimeValue;
import org.apache.hc.core5.util.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

/**
 * Spring configuration for the {@link RestTemplate} used by {@link StompCallback} to
 * issue HEAD requests to toot embed URLs.
 *
 * <p>This configuration replaces the former {@code SimpleClientHttpRequestFactory}-backed
 * bean in {@code GlacierApplication} with an Apache HttpClient 5 connection pool (P2-01),
 * providing:
 * <ul>
 *   <li>Connection pool: max 50 total connections, max 20 per route.</li>
 *   <li>Connection TTL: 30 s — releases stale connections to unreachable Mastodon instances.</li>
 *   <li>Socket / connect timeouts: configurable (default 5 000 ms / 3 000 ms).</li>
 *   <li>Redirect-following disabled: H-3 SSRF guard — an attacker-controlled Mastodon
 *       instance cannot 302-redirect a HEAD /embed request to an internal address
 *       (OWASP A10, NIST SP 800-53 SI-3).  The Apache 5 client is configured with
 *       {@code redirectsEnabled(false)} in the per-request config.</li>
 * </ul>
 *
 * <p>Configurable via application properties (all have safe defaults):
 * <ul>
 *   <li>{@code glacier.embed.connectTimeoutMs:3000} — TCP connection establishment timeout</li>
 *   <li>{@code glacier.embed.readTimeoutMs:5000} — socket read / response timeout</li>
 *   <li>{@code glacier.embed.connectionPool.maxTotal:50} — connection pool max-total</li>
 *   <li>{@code glacier.embed.connectionPool.maxPerRoute:20} — connection pool max-per-route</li>
 *   <li>{@code glacier.embed.connectionPool.ttlSeconds:30} — connection TTL in seconds</li>
 * </ul>
 *
 * @see StompCallback#isLoadable
 * @see "ADR C-03 — embed HEAD request security controls"
 */
@Configuration
public class EmbedRestTemplateConfiguration {

    /**
     * The {@link Logger} for this class.
     *
     * @see "src/main/resources/logback.xml"
     */
    private static final Logger LOGGER = LoggerFactory.getLogger(EmbedRestTemplateConfiguration.class);

    /**
     * Creates a pooled {@link RestTemplate} for embed HEAD checks.
     *
     * <p>Security note (H-3): redirect-following is disabled so that a 302 from a
     * toot URL cannot redirect to an internal address, bypassing the SSRF allowlist
     * in {@link StompCallback#isLoadable}.
     *
     * @param connectMs      TCP connect timeout in milliseconds
     * @param readMs         socket read timeout in milliseconds
     * @param maxTotal       maximum total pooled connections
     * @param maxPerRoute    maximum connections per target host
     * @param ttlSeconds     maximum lifetime of a pooled connection in seconds
     * @return a {@link RestTemplate} backed by a pooled Apache HttpClient 5 instance
     */
    @Bean
    public RestTemplate restTemplate(
            @Value("${glacier.embed.connectTimeoutMs:3000}") int connectMs,
            @Value("${glacier.embed.readTimeoutMs:5000}") int readMs,
            @Value("${glacier.embed.connectionPool.maxTotal:50}") int maxTotal,
            @Value("${glacier.embed.connectionPool.maxPerRoute:20}") int maxPerRoute,
            @Value("${glacier.embed.connectionPool.ttlSeconds:30}") int ttlSeconds) {

        // Connection pool — bounded by maxTotal / maxPerRoute with TTL-based eviction
        PoolingHttpClientConnectionManager connectionManager = new PoolingHttpClientConnectionManager();
        connectionManager.setMaxTotal(maxTotal);
        connectionManager.setDefaultMaxPerRoute(maxPerRoute);
        connectionManager.setDefaultConnectionConfig(
                org.apache.hc.client5.http.config.ConnectionConfig.custom()
                        .setConnectTimeout(Timeout.ofMilliseconds(connectMs))
                        .setSocketTimeout(Timeout.ofMilliseconds(readMs))
                        .setTimeToLive(TimeValue.ofSeconds(ttlSeconds))
                        .build()
        );

        // H-3 SSRF guard: disable automatic redirect-following on all requests.
        // Per-request config overrides the client-level default.
        RequestConfig requestConfig = RequestConfig.custom()
                .setRedirectsEnabled(false)
                .setResponseTimeout(Timeout.ofMilliseconds(readMs))
                .setConnectionRequestTimeout(Timeout.ofMilliseconds(connectMs))
                .build();

        HttpClient httpClient = HttpClients.custom()
                .setConnectionManager(connectionManager)
                .setDefaultRequestConfig(requestConfig)
                .disableRedirectHandling()
                .build();

        LOGGER.info("embed.resttemplate.configured maxTotal={} maxPerRoute={} ttlSeconds={} " +
                        "connectMs={} readMs={} redirects=disabled",
                maxTotal, maxPerRoute, ttlSeconds, connectMs, readMs);

        return new RestTemplate(new HttpComponentsClientHttpRequestFactory(httpClient));
    }
}
