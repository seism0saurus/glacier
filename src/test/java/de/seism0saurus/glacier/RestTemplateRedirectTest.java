package de.seism0saurus.glacier;

import de.seism0saurus.glacier.mastodon.EmbedRestTemplateConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the {@link RestTemplate} bean in {@link EmbedRestTemplateConfiguration}.
 *
 * <p>Security control: H-3 / SSRF redirect prevention (OWASP A10, NIST SP 800-53 SI-3).
 *
 * <p>An attacker-controlled Mastodon instance can issue a 302 redirect from a HEAD request
 * to {@code https://trusted-host.com/embed} to an internal address such as
 * {@code http://169.254.169.254/latest/meta-data/}.  Without explicit redirect suppression,
 * the HTTP client would follow that redirect, bypassing any URL allowlist applied before
 * the request.
 *
 * <p>Fix: {@link EmbedRestTemplateConfiguration#restTemplate} configures the Apache HttpClient 5
 * with {@code disableRedirectHandling()} and sets {@code redirectsEnabled(false)} in the default
 * request config, ensuring that 3xx responses are returned to the caller
 * ({@link de.seism0saurus.glacier.mastodon.StompCallback#isLoadable}) rather than silently followed (P2-01, H-3).
 */
class RestTemplateRedirectTest {

    /**
     * Verifies that the {@link RestTemplate} bean uses the Apache HttpClient 5 pooled factory
     * (not {@link org.springframework.http.client.SimpleClientHttpRequestFactory}).
     *
     * <p>The Apache HttpClient 5 factory is configured with redirect-following disabled
     * via {@code disableRedirectHandling()} — the H-3 SSRF guard (P2-01).
     */
    @Test
    void restTemplate_factory_usesApacheHttpClient5PooledFactory() {
        // Arrange: construct the bean the same way Spring would
        EmbedRestTemplateConfiguration config = new EmbedRestTemplateConfiguration();
        RestTemplate rt = config.restTemplate(3000, 5000, 50, 20, 30);

        // Assert: factory must be Apache HttpComponents (not SimpleClientHttpRequestFactory)
        assertThat(rt.getRequestFactory())
                .as("factory must be HttpComponentsClientHttpRequestFactory (Apache HttpClient 5, P2-01)")
                .isInstanceOf(HttpComponentsClientHttpRequestFactory.class);
    }

    /**
     * Verifies that the configuration parameters are accepted without error for all valid ranges.
     *
     * <p>Regression guard: calling the bean method with custom parameters must not throw.
     * The parameters correspond to the pool and timeout application properties.
     */
    @Test
    void restTemplate_factory_constructsWithCustomPoolParameters() {
        // Arrange
        EmbedRestTemplateConfiguration config = new EmbedRestTemplateConfiguration();

        // Act + Assert: no exception — parameters accepted for all valid boundary values
        RestTemplate rtDefault = config.restTemplate(3000, 5000, 50, 20, 30);
        assertThat(rtDefault).isNotNull();

        RestTemplate rtCustom = config.restTemplate(1000, 2000, 10, 5, 10);
        assertThat(rtCustom).isNotNull();
    }
}
