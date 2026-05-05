package de.seism0saurus.glacier.security;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import de.seism0saurus.glacier.util.LogScrubber;
import de.seism0saurus.glacier.webservice.security.HandshakeRateLimitInterceptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.annotation.DirtiesContext.ClassMode;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.socket.WebSocketHandler;
import social.bigbone.MastodonClient;

import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Integration test verifying that the FRAMEWORK forward-headers strategy is wired
 * correctly and that {@link HandshakeRateLimitInterceptor} logs the masked form of
 * a forwarded client IP (IT-sec-FH-01 / SR-WS-03, CWE-290).
 *
 * <h2>What this test covers</h2>
 * <p>When {@code server.forward-headers-strategy=FRAMEWORK} is active, Spring Boot
 * auto-registers {@code ForwardedHeaderFilter} (servlet-stack). The filter unwraps
 * {@code X-Forwarded-For} so that downstream code — including
 * {@link HandshakeRateLimitInterceptor} — sees the real client IP via
 * {@code request.getRemoteAddress()}.
 *
 * <p>This test verifies at the bean/unit boundary (real Spring context, mocked HTTP
 * primitives) that when a request carries a realistic forwarded IP address, the
 * AUDIT log entry produced by the rate-limiter contains the correctly masked form
 * {@code ip-hash=1.2.3.xxx} (via {@link LogScrubber#maskIp}) and NOT the raw value.
 *
 * <p>The test overrides {@code server.forward-headers-strategy=FRAMEWORK} via
 * {@link TestPropertySource} so that the context under test mirrors exactly the
 * property value that production docker-compose.yaml injects via
 * {@code FORWARD_HEADERS_STRATEGY=FRAMEWORK}.
 *
 * <h2>Mode applicability</h2>
 * <p><strong>Live mode only</strong>: WebSocket handshake rate-limiting is active
 * only when the STOMP WebSocket broker is running (live mode). In fallback mode the
 * WebSocket broker is inert; in killswitch mode there is no WebSocket endpoint at all.
 * Insecure mode: the same {@code ForwardedHeaderFilter} and interceptor apply.
 *
 * <p>This is a Failsafe IT ({@code *IT.java}) and runs during {@code mvn verify}.
 *
 * <p>Standard: OWASP API4:2023 / SR-WS-03 / SR-LOG-WS-01 / CWE-290.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        // Mirror production: FRAMEWORK mode auto-registers ForwardedHeaderFilter
        // (servlet-stack). This is exactly the value set via FORWARD_HEADERS_STRATEGY
        // in infrastructure/docker-compose.yaml.
        "server.forward-headers-strategy=FRAMEWORK",
        // Use a capacity of 1 so the second call triggers the AUDIT log event
        "glacier.security.ws.handshake.max-per-minute=1"
})
@DirtiesContext(classMode = ClassMode.AFTER_EACH_TEST_METHOD)
class HandshakeForwardedForRespectedIT {

    /** The simulated real client IP as it would arrive in X-Forwarded-For from Traefik. */
    private static final String FORWARDED_IP = "1.2.3.4";
    /** Expected masked form: last octet replaced with "xxx" (LogScrubber.maskIp). */
    private static final String MASKED_IP_PREFIX = "1.2.3";
    private static final String EXPECTED_MASKED_IP = LogScrubber.maskIp(FORWARDED_IP); // "1.2.3.xxx"

    @Autowired
    private HandshakeRateLimitInterceptor interceptor;

    @MockitoBean
    @SuppressWarnings("unused")
    private MastodonClient mastodonClient;

    private ServerHttpRequest mockRequest;
    private ServerHttpResponse mockResponse;
    private WebSocketHandler mockWsHandler;
    private Map<String, Object> attributes;

    @BeforeEach
    void setUp() {
        mockRequest = mock(ServerHttpRequest.class);
        mockResponse = mock(ServerHttpResponse.class);
        mockWsHandler = mock(WebSocketHandler.class);
        attributes = new HashMap<>();

        // Simulate the IP that ForwardedHeaderFilter would populate after unwrapping
        // X-Forwarded-For: 1.2.3.4 from a Traefik-forwarded connection.
        InetSocketAddress remoteAddress = new InetSocketAddress(FORWARDED_IP, 54321);
        when(mockRequest.getRemoteAddress()).thenReturn(remoteAddress);
    }

    // -------------------------------------------------------------------------
    // IT-sec-FH-01: AUDIT log contains masked forwarded IP — SR-WS-03 + SR-LOG-WS-01
    // -------------------------------------------------------------------------

    /**
     * IT-sec-FH-01: when the interceptor processes a request whose
     * {@code getRemoteAddress()} returns the forwarded client IP (as populated by
     * {@code ForwardedHeaderFilter} under {@code FRAMEWORK} strategy), the AUDIT log
     * entry for a rate-limited connection must contain the correctly masked form of
     * that IP via {@link LogScrubber#maskIp}.
     *
     * <p>Specifically:
     * <ul>
     *   <li>The AUDIT event must contain {@code ip-hash=} followed by the masked prefix
     *       ({@code 1.2.3.xxx}) — confirming LogScrubber.maskIp was called.</li>
     *   <li>The AUDIT event must NOT contain the raw IP string {@code 1.2.3.4}.</li>
     *   <li>{@code beforeHandshake} must return {@code false} and set HTTP 429 on the
     *       second call (capacity=1 bucket).</li>
     * </ul>
     *
     * <p>Security: SR-WS-03 (source IP correctly extracted after Traefik unwrap),
     * SR-LOG-WS-01 (raw IP never in AUDIT log, D-13/SR-8), CWE-290.
     */
    @Test
    void auditLog_containsMaskedForwardedIp_notRawIp() throws Exception {
        // Attach a ListAppender to the AUDIT logger to capture log output in-process
        Logger auditLogger = (Logger) LoggerFactory.getLogger("AUDIT");
        ListAppender<ILoggingEvent> listAppender = new ListAppender<>();
        listAppender.start();
        auditLogger.addAppender(listAppender);

        try {
            // First call: consumes the only token in the capacity-1 bucket — allowed
            boolean firstResult = interceptor.beforeHandshake(
                    mockRequest, mockResponse, mockWsHandler, attributes);
            assertThat(firstResult)
                    .as("IT-sec-FH-01: first call within bucket capacity must be allowed")
                    .isTrue();

            // Second call: bucket exhausted — must be rejected with HTTP 429
            ServerHttpResponse secondResponse = mock(ServerHttpResponse.class);
            boolean secondResult = interceptor.beforeHandshake(
                    mockRequest, secondResponse, mockWsHandler, attributes);
            assertThat(secondResult)
                    .as("IT-sec-FH-01: second call after bucket exhausted must be rejected")
                    .isFalse();
            verify(secondResponse).setStatusCode(HttpStatus.TOO_MANY_REQUESTS);

            // Verify AUDIT log was written for the rate-limited event
            assertThat(listAppender.list)
                    .as("IT-sec-FH-01: AUDIT log must contain at least one entry for the rate-limited event")
                    .isNotEmpty();

            // The AUDIT event must contain ip-hash= followed by the masked IP form
            AtomicBoolean hasIpHash = new AtomicBoolean(false);
            AtomicBoolean hasRawIp = new AtomicBoolean(false);
            AtomicBoolean hasMaskedPrefix = new AtomicBoolean(false);
            AtomicBoolean hasExpectedMaskedIp = new AtomicBoolean(false);

            for (ILoggingEvent event : listAppender.list) {
                String msg = event.getFormattedMessage();
                if (msg.contains("ip-hash=")) {
                    hasIpHash.set(true);
                }
                if (msg.contains(FORWARDED_IP)) {
                    hasRawIp.set(true);
                }
                if (msg.contains(MASKED_IP_PREFIX)) {
                    hasMaskedPrefix.set(true);
                }
                if (msg.contains(EXPECTED_MASKED_IP)) {
                    hasExpectedMaskedIp.set(true);
                }
            }

            assertThat(hasIpHash.get())
                    .as("IT-sec-FH-01: AUDIT event must contain ip-hash= (SR-LOG-WS-01)")
                    .isTrue();

            assertThat(hasRawIp.get())
                    .as("IT-sec-FH-01: AUDIT event must NOT contain the raw IP %s (SR-LOG-WS-01, D-13/SR-8)",
                            FORWARDED_IP)
                    .isFalse();

            assertThat(hasMaskedPrefix.get())
                    .as("IT-sec-FH-01: AUDIT event must contain the masked IP prefix %s (LogScrubber.maskIp)",
                            MASKED_IP_PREFIX)
                    .isTrue();

            assertThat(hasExpectedMaskedIp.get())
                    .as("IT-sec-FH-01: AUDIT event must contain the exact masked form %s "
                                    + "(LogScrubber.maskIp(\"%s\") = \"%s\")",
                            EXPECTED_MASKED_IP, FORWARDED_IP, EXPECTED_MASKED_IP)
                    .isTrue();

        } finally {
            auditLogger.detachAppender(listAppender);
        }
    }
}
