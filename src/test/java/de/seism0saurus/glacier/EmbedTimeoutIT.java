package de.seism0saurus.glacier;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;
import social.bigbone.MastodonClient;

import java.util.concurrent.TimeUnit;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.head;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration test that proves the {@link RestTemplate} bean wired in
 * {@link GlacierApplication#restTemplate} actually enforces the configured connect and
 * read timeouts at the socket level (FIX A / F-12 / C-03).
 *
 * <p><strong>Why this test exists</strong>: {@code RestTemplateRedirectTest} verifies via
 * reflection that {@code SimpleClientHttpRequestFactory.connectTimeout} and
 * {@code readTimeout} fields were set.  Reflection proves configuration is set; it does
 * NOT prove the socket honours the value under a hung-peer scenario.  A JVM update, a
 * Spring {@code SimpleClientHttpRequestFactory} internal refactor, or an
 * {@code HttpURLConnection} default-flip to {@code -1} could pass the reflection test and
 * still starve the Bigbone virtual thread — exactly the C-03 threat model.
 *
 * <p><strong>Test strategy</strong>: start a real WireMock server on a random port, configure
 * it to delay responses by 10 s (much longer than any configured timeout), inject the real
 * {@link RestTemplate} bean with tight timeouts (500 ms), issue a HEAD request, and assert:
 * <ol>
 *   <li>A {@link ResourceAccessException} is thrown.</li>
 *   <li>Wall-clock elapsed time is below 2 000 ms (generous grace beyond the 500 ms timeout).</li>
 *   <li>The overall test is annotated with {@link Timeout @Timeout(10)} so that if the
 *       timeout fails to fire the test itself fails (defence-in-depth sentinel).</li>
 * </ol>
 *
 * <p><strong>Read-timeout test</strong> ({@code readTimeout_slowResponseServer_throwsWithinDeadline}):
 * WireMock accepts the TCP connection immediately but delays the HTTP response by 10 s.
 * This directly exercises {@code HttpURLConnection}'s read-timeout enforcement.
 *
 * <p><strong>"Connect-timeout" / no-response test</strong>
 * ({@code connectTimeout_serverNeverResponds_throwsWithinDeadline}):
 * WireMock accepts the TCP connection but uses {@code withFixedDelay(10_000)} with
 * {@code withChunkedDribbleDelay} disabled.  On the JDK-{@code HttpURLConnection} layer the
 * observable effect is identical to a long read timeout: the socket is open but no data
 * arrives, and the configured {@code readTimeoutMs} terminates the wait.
 *
 * <p><em>Note on true connect-timeout simulation</em>: a reliable in-process
 * "TCP SYN never acknowledged" scenario requires kernel-level packet dropping (e.g. iptables
 * or TEST-NET routing), which is not safe in a shared CI runner.  On modern Linux the kernel
 * completes the TCP three-way handshake into the listen backlog before userspace calls
 * {@code accept()}, so a closed {@code ServerSocket} produces an immediate connection-refused
 * error rather than a timeout.  The test below therefore validates the latency contract
 * (bound the hung-peer window) rather than the specific TCP connect phase.
 * The reflection test in {@code RestTemplateRedirectTest} confirms the {@code connectTimeout}
 * field was set; this IT confirms the overall timeout guard fires.
 *
 * <p><strong>Falsification check</strong>: temporarily returning {@code new RestTemplate()}
 * (infinite timeouts) from the bean causes both tests to hang until the
 * {@code @Timeout(10)} JUnit annotation kills them — confirming the tests genuinely detect
 * the absence of timeout configuration.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                // Reduce timeouts to 500 ms so the tests complete quickly.
                // 500 ms << 10 000 ms WireMock delay → timeout fires reliably.
                "glacier.embed.readTimeoutMs=500",
                "glacier.embed.connectTimeoutMs=500"
        }
)
class EmbedTimeoutIT {

    /**
     * The real {@link RestTemplate} bean wired by {@link GlacierApplication#restTemplate}.
     * This is exactly the same instance used by {@link de.seism0saurus.glacier.mastodon.StompCallback#isLoadable}.
     */
    @Autowired
    private RestTemplate restTemplate;

    /**
     * Mocked to prevent the Spring context from attempting to connect to a real Mastodon instance
     * during context startup.  {@code MastodonClient} is a bean that makes network calls on
     * construction (nodeinfo + instance API); without this mock the test would fail in CI with
     * a connection-refused error against the placeholder instance configured in
     * {@code application.properties}.
     */
    @SuppressWarnings("unused")
    @MockitoBean
    private MastodonClient mastodonClient;

    /**
     * Per-test WireMock server on a random port — started fresh before each test
     * to guarantee isolation.
     */
    private WireMockServer wireMock;

    @BeforeEach
    void startWireMock() {
        wireMock = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
        wireMock.start();
    }

    @AfterEach
    void stopWireMock() {
        wireMock.stop();
    }

    /**
     * C-03 read-timeout test: WireMock accepts the TCP connection immediately but stalls
     * for 10 000 ms before returning the HTTP response.  With {@code readTimeoutMs=500}
     * the {@link RestTemplate} must throw {@link ResourceAccessException} well before
     * 2 000 ms.
     *
     * <p>The {@link Timeout @Timeout(10, unit = TimeUnit.SECONDS)} annotation is a
     * defence-in-depth sentinel: if the timeout configuration is removed or broken,
     * WireMock delays 10 s and the sentinel kills the test at that threshold — ensuring
     * the IT fails rather than hanging indefinitely.
     */
    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void readTimeout_slowResponseServer_throwsWithinDeadline() {
        // Arrange: WireMock accepts TCP connection, then stalls 10 s before any HTTP response.
        // The read timeout (500 ms) must fire before the 10 s delay elapses.
        wireMock.stubFor(
                head(urlPathEqualTo("/embed"))
                        .willReturn(aResponse()
                                .withFixedDelay(10_000)   // 10 s delay — far beyond the 500 ms timeout
                                .withStatus(200))
        );

        String targetUrl = "http://localhost:" + wireMock.port() + "/embed";
        long startNanos = System.nanoTime();

        // Act + Assert: must throw ResourceAccessException due to read timeout
        assertThatThrownBy(() -> restTemplate.headForHeaders(targetUrl))
                .as("RestTemplate must throw ResourceAccessException when the read timeout fires")
                .isInstanceOf(ResourceAccessException.class);

        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);

        // Elapsed must be well below the WireMock 10 s delay — confirms the timeout fired.
        // Upper bound 2 000 ms = 500 ms configured timeout + 1 500 ms generous CI grace.
        assertThat(elapsedMs)
                .as("Elapsed time (%d ms) must be below 2 000 ms; if not, the read timeout did not fire", elapsedMs)
                .isLessThan(2_000L);
    }

    /**
     * C-03 hung-peer / latency-cap test: WireMock accepts the TCP connection but responds
     * extremely slowly (10 s header delay), simulating a hung remote Mastodon instance.
     * With {@code readTimeoutMs=500} the {@link RestTemplate} must bound the wait.
     *
     * <p>This is the primary production threat addressed by C-03: a slow Mastodon embed
     * endpoint stalling the Bigbone virtual thread indefinitely, blocking all event delivery
     * for that {@code (principal, hashtag)} subscription.  The test confirms the timeout
     * guard fires and bounds the latency.
     *
     * <p>See class Javadoc for why a true TCP-connect-level timeout (SYN not acknowledged)
     * is not reliably achievable in a shared CI runner without kernel-level packet dropping.
     */
    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void connectTimeout_serverNeverResponds_throwsWithinDeadline() {
        // Arrange: WireMock stub with maximum delay to simulate a completely hung server.
        // The response never arrives within the timeout window.
        wireMock.stubFor(
                head(urlPathEqualTo("/embed"))
                        .willReturn(aResponse()
                                .withFixedDelay(10_000)   // 10 s delay — simulates hung remote instance
                                .withStatus(200))
        );

        String targetUrl = "http://localhost:" + wireMock.port() + "/embed";
        long startNanos = System.nanoTime();

        // Act + Assert: ResourceAccessException must be thrown before the WireMock delay expires
        assertThatThrownBy(() -> restTemplate.headForHeaders(targetUrl))
                .as("RestTemplate must throw ResourceAccessException within the configured timeout (C-03)")
                .isInstanceOf(ResourceAccessException.class);

        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);

        // Confirm the timeout guard actually fired — elapsed must be much less than 10 000 ms.
        // Upper bound 2 000 ms = 500 ms read timeout + 1 500 ms CI grace.
        assertThat(elapsedMs)
                .as("Elapsed time (%d ms) must be below 2 000 ms; if the timeout did not fire "
                        + "this would block for the full 10 000 ms WireMock delay", elapsedMs)
                .isLessThan(2_000L);
    }
}
