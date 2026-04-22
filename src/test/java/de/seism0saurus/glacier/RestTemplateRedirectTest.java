package de.seism0saurus.glacier;

import org.junit.jupiter.api.Test;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URL;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Unit tests for the {@link RestTemplate} bean in {@link GlacierApplication}.
 *
 * <p>Security control: H-3 / SSRF redirect prevention (OWASP A10, NIST SP 800-53 SI-3).
 *
 * <p>An attacker-controlled Mastodon instance can issue a 302 redirect from a HEAD request
 * to {@code https://trusted-host.com/embed} to an internal address such as
 * {@code http://169.254.169.254/latest/meta-data/}.  Without explicit redirect suppression,
 * the default {@link HttpURLConnection#setInstanceFollowRedirects(true)} would follow that
 * redirect, bypassing any URL allowlist applied before the request.
 *
 * <p>Fix: {@link GlacierApplication#restTemplate} overrides
 * {@link SimpleClientHttpRequestFactory#prepareConnection} and calls
 * {@link HttpURLConnection#setInstanceFollowRedirects(false)}, ensuring that 3xx responses
 * are returned to the caller ({@link de.seism0saurus.glacier.mastodon.StompCallback#isLoadable})
 * rather than silently followed.
 */
class RestTemplateRedirectTest {

    /**
     * Verifies that the {@link HttpURLConnection} created by the factory has
     * {@code instanceFollowRedirects} set to {@code false}.
     *
     * <p>Strategy: obtain the {@link SimpleClientHttpRequestFactory} subclass via reflection
     * on the {@link RestTemplate} backing factory, then call {@code prepareConnection()} on a
     * mock {@link HttpURLConnection} and assert {@code setInstanceFollowRedirects(false)} was
     * invoked.
     */
    @Test
    void restTemplate_factory_doesNotFollowRedirects() throws Exception {
        // Arrange: construct the bean the same way Spring would
        GlacierApplication app = new GlacierApplication();
        RestTemplate rt = app.restTemplate(3000, 5000);

        // Extract the request factory and verify it is our custom subclass
        Object factory = rt.getRequestFactory();
        assertThat(factory)
                .as("factory must be a SimpleClientHttpRequestFactory subclass (anonymous override)")
                .isInstanceOf(SimpleClientHttpRequestFactory.class);

        // Invoke prepareConnection on a mock HttpURLConnection to observe the setter call
        HttpURLConnection mockConn = mock(HttpURLConnection.class);
        // prepareConnection is protected — access via reflection
        Method prepareConnection = SimpleClientHttpRequestFactory.class
                .getDeclaredMethod("prepareConnection", HttpURLConnection.class, String.class);
        prepareConnection.setAccessible(true);
        prepareConnection.invoke(factory, mockConn, "HEAD");

        // Assert: setInstanceFollowRedirects(false) must have been called at least once (H-3 SSRF guard).
        // super.prepareConnection may also invoke the setter (Spring's default sets the class-level flag),
        // so we use atLeastOnce() to confirm our explicit override fires without being fragile to
        // the super-call count. The important invariant is that no call sets it to true AFTER our override.
        verify(mockConn, atLeastOnce()).setInstanceFollowRedirects(false);
        // Confirm redirect-following was never re-enabled after our explicit disable
        verify(mockConn, never()).setInstanceFollowRedirects(true);
    }

    /**
     * Verifies that the timeouts configured in {@code application.properties} are honoured
     * (regression guard for existing C-03 control — timeouts must not be lost by the H-3 change).
     */
    @Test
    void restTemplate_factory_retainsConfiguredTimeouts() throws Exception {
        GlacierApplication app = new GlacierApplication();
        RestTemplate rt = app.restTemplate(1234, 5678);

        SimpleClientHttpRequestFactory factory =
                (SimpleClientHttpRequestFactory) rt.getRequestFactory();

        // Verify connect and read timeouts are preserved — read via reflection since
        // SimpleClientHttpRequestFactory does not expose getters
        Field connectTimeoutField = SimpleClientHttpRequestFactory.class
                .getDeclaredField("connectTimeout");
        connectTimeoutField.setAccessible(true);
        int connectTimeout = (int) connectTimeoutField.get(factory);

        Field readTimeoutField = SimpleClientHttpRequestFactory.class
                .getDeclaredField("readTimeout");
        readTimeoutField.setAccessible(true);
        int readTimeout = (int) readTimeoutField.get(factory);

        assertThat(connectTimeout).isEqualTo(1234);
        assertThat(readTimeout).isEqualTo(5678);
    }
}
