package de.seism0saurus.glacier.security;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import de.seism0saurus.glacier.share.domain.ShareLink;
import de.seism0saurus.glacier.share.domain.ShareLinkId;
import de.seism0saurus.glacier.share.infrastructure.InMemoryShareLinkRepository;
import de.seism0saurus.glacier.webservice.security.SubscribeRateLimitInterceptor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.messaging.converter.StringMessageConverter;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.annotation.DirtiesContext.ClassMode;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import social.bigbone.MastodonClient;

import java.lang.reflect.Type;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test proving that {@link de.seism0saurus.glacier.webservice.messaging.ShareViewPrincipalHandler#determineUser}
 * populates the {@link SubscribeRateLimitInterceptor#REMOTE_ADDR} session attribute,
 * which the interceptor then uses for per-IP rate limiting on the {@code /share-view-ws}
 * endpoint (OBS-1, IT-sec-SV-RL-01).
 *
 * <p>Without this attribute, the IP hash in AUDIT events is {@code null} and per-IP
 * isolation on the share-viewer path is completely inert.
 *
 * <p>Setup:
 * <ol>
 *   <li>A share link is seeded directly via {@link InMemoryShareLinkRepository} (NOT via
 *       {@code POST /rest/share-links} which is rate-limited by {@code ShareRateLimiter} —
 *       ADR-5 from Phase 1 decision doc 2026-05-01-planning-td-backlog-bundle.md).</li>
 *   <li>The test connects to {@code /share-view-ws?shareLinkId=<seededId>} via a real
 *       {@link WebSocketStompClient} — no manual session-attribute injection (SR-OBS1-03).</li>
 *   <li>Five SUBSCRIBE frames to {@code /topic/share/<seededId>/creation} are sent;
 *       the threshold is configured to 3, so frames 4 and 5 trigger AUDIT events.</li>
 * </ol>
 *
 * <p>Assertions:
 * <ol>
 *   <li>At least one {@code ws.subscribe.rate_limited} AUDIT event is emitted (SR-OBS1-02).</li>
 *   <li>The AUDIT event contains {@code ip-hash=} (SR-OBS1-01).</li>
 *   <li>The AUDIT event does NOT contain {@code ip-hash=null} — confirming that
 *       {@code ShareViewPrincipalHandler.determineUser()} correctly populated
 *       {@link SubscribeRateLimitInterceptor#REMOTE_ADDR} during the WebSocket handshake.</li>
 * </ol>
 *
 * <p><b>Mode applicability</b>: <strong>live</strong> (real {@code /share-view-ws} endpoint).
 * Killswitch mode: N/A. Fallback mode: N/A. Insecure mode: N/A (share-view uses
 * {@code __Host-} cookies requiring a secure context in production; this test runs with
 * {@code glacier.cookie.secure=false} to use the plain {@code shareViewerId} cookie name
 * compatible with the plain HTTP test server).
 *
 * <p>This is a Failsafe IT ({@code *IT.java}) — it runs during {@code mvn verify}.
 *
 * <p>Standard: OWASP API6:2023 / API4:2023 / SR-WS-02 / SR-WS-03 / SR-OBS1-01..03.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        // Very low threshold to trigger rate limiting quickly (ADR-5, OBS-1)
        "glacier.security.ws.subscribe.max-per-minute=3",
        // Use plain 'shareViewerId' cookie (no __Host- prefix) compatible with non-TLS test server
        "glacier.cookie.secure=false"
})
@DirtiesContext(classMode = ClassMode.AFTER_EACH_TEST_METHOD)
class ShareViewRemoteAddrProductionPathIT {

    /**
     * A valid share link ID: 43 URL-safe base64 characters (≥ 256 bits of entropy floor,
     * all uppercase A for determinism in the test). Must be an exact 43-char base64url
     * string matching {@code [A-Za-z0-9_-]{43,}}.
     */
    private static final String SHARE_LINK_ID_STR = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";

    /**
     * A valid shareViewerId cookie value: {@code sv_} prefix + 43 URL-safe base64 chars = 46 chars.
     * Satisfies {@link de.seism0saurus.glacier.webservice.messaging.ShareViewPrincipalHandler#isValidShareViewerId}.
     */
    private static final String VIEWER_ID_COOKIE_VALUE = "sv_AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";

    /** Owner wallId for the seeded share link — not used beyond seeding. */
    private static final String SHARER_WALL_ID = "ffffffff-ffff-ffff-ffff-000000000099";

    @LocalServerPort
    private int port;

    @MockitoBean
    @SuppressWarnings("unused")
    private MastodonClient mastodonClient;

    /** Inject the repository directly to seed share links without rate-limiter coupling (ADR-5). */
    @Autowired
    private InMemoryShareLinkRepository shareLinkRepository;

    private WebSocketStompClient stompClient;

    /** AUDIT logger to capture rate-limit events. */
    private Logger auditLogger;
    private ListAppender<ILoggingEvent> auditAppender;

    @BeforeEach
    void setUp() {
        stompClient = new WebSocketStompClient(new StandardWebSocketClient());
        stompClient.setMessageConverter(new StringMessageConverter());

        auditLogger = (Logger) LoggerFactory.getLogger("AUDIT");
        auditAppender = new ListAppender<>();
        auditAppender.start();
        auditLogger.addAppender(auditAppender);

        // Seed an ACTIVE share link via direct repository injection (ADR-5, SR-OBS1-01).
        // Duration of 7 days ensures the link is ACTIVE during the test.
        // OWASP A09:2021 — test setup must not couple to rate-limited production paths.
        ShareLinkId shareLinkId = ShareLinkId.fromUrlPath(SHARE_LINK_ID_STR);
        ShareLink link = ShareLink.create(
                shareLinkId,
                SHARER_WALL_ID,
                Instant.now(),
                Duration.ofDays(7));
        shareLinkRepository.save(link);
    }

    @AfterEach
    void tearDown() {
        auditLogger.detachAppender(auditAppender);
        auditAppender.stop();
        stompClient.stop();
    }

    /**
     * IT-sec-SV-RL-01: the per-IP rate-limit axis is live through the real
     * {@code /share-view-ws} WebSocket handshake path.
     *
     * <p>Connects WITHOUT manually injecting {@code REMOTE_ADDR} session attributes —
     * {@link de.seism0saurus.glacier.webservice.messaging.ShareViewPrincipalHandler#determineUser}
     * must populate it itself.
     *
     * <p>Sends 5 SUBSCRIBE frames (threshold is 3). Asserts:
     * <ul>
     *   <li>At least one AUDIT event is emitted for {@code ws.subscribe.rate_limited}.</li>
     *   <li>The AUDIT log line contains {@code ip-hash=} (SR-OBS1-01).</li>
     *   <li>The AUDIT log line does NOT contain {@code ip-hash=null} — a null value would
     *       mean {@code ShareViewPrincipalHandler} did not write {@code REMOTE_ADDR} to
     *       the session attributes during the WebSocket upgrade (SR-OBS1-03).</li>
     * </ul>
     *
     * <p>Standard: OWASP API6:2023, SR-OBS1-01, SR-OBS1-02, SR-OBS1-03.
     */
    @Test
    void shareViewSubscribeRateLimit_throughRealHandshake_populatesIpInAuditLog() throws Exception {
        AtomicBoolean connected = new AtomicBoolean(false);

        // Pass shareViewerId cookie (plain name — glacier.cookie.secure=false in this test).
        // The handler validates isValidShareViewerId(): sv_ prefix + ≥43 base64url chars.
        WebSocketHttpHeaders handshakeHeaders = new WebSocketHttpHeaders();
        handshakeHeaders.add("Cookie", "shareViewerId=" + VIEWER_ID_COOKIE_VALUE);

        // Connect to /share-view-ws with the seeded shareLinkId as query parameter.
        // ShareViewPrincipalHandler.extractShareLinkId() parses the ?shareLinkId= param.
        String wsUrl = "ws://localhost:" + port + "/share-view-ws?shareLinkId=" + SHARE_LINK_ID_STR;

        StompSession session;
        try {
            session = stompClient.connectAsync(
                    wsUrl,
                    handshakeHeaders,
                    new StompHeaders(),
                    new StompSessionHandlerAdapter() {
                        @Override
                        public void afterConnected(StompSession sess, StompHeaders connectedHeaders) {
                            connected.set(true);
                        }
                    }
            ).get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new AssertionError("WebSocket connection to /share-view-ws failed: " + e.getMessage(), e);
        }

        // Wait for STOMP CONNECTED frame
        long deadline = System.currentTimeMillis() + 3_000;
        while (!connected.get() && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        assertThat(connected.get())
                .as("IT-sec-SV-RL-01 precondition: STOMP session must connect to /share-view-ws")
                .isTrue();

        // Send 5 SUBSCRIBE frames to /topic/share/{shareLinkId}/... — threshold is 3.
        // The SubscribeRateLimitInterceptor runs first (before ShareViewTopicAuthInterceptor),
        // so frames 4 and 5 are silently dropped and trigger AUDIT events before auth checks.
        // Frames 1–3 pass the rate limiter and are validated by ShareViewTopicAuthInterceptor.
        for (int i = 0; i < 5; i++) {
            try {
                StompHeaders subHeaders = new StompHeaders();
                // Destination format required by ShareViewTopicAuthInterceptor:
                // /topic/share/{boundShareLinkId}/...
                // The bound share link ID is SHARE_LINK_ID_STR (set via query param at handshake).
                subHeaders.setDestination("/topic/share/" + SHARE_LINK_ID_STR + "/creation" + i);
                session.subscribe(subHeaders, new StompFrameHandler() {
                    @Override
                    public Type getPayloadType(StompHeaders headers) {
                        return String.class;
                    }

                    @Override
                    public void handleFrame(StompHeaders headers, Object payload) {
                        // no-op — we only care about rate-limit AUDIT events
                    }
                });
            } catch (Exception ignored) {
                // A rate-limited SUBSCRIBE may cause a client-side exception; expected
            }
        }

        // Allow time for async AUDIT events to be written
        Thread.sleep(300);

        // Collect all ws.subscribe.rate_limited AUDIT events
        List<ILoggingEvent> rateLimitEvents = auditAppender.list.stream()
                .filter(e -> e.getFormattedMessage().contains("ws.subscribe.rate_limited"))
                .toList();

        assertThat(rateLimitEvents)
                .as("IT-sec-SV-RL-01: at least one ws.subscribe.rate_limited AUDIT event must be emitted "
                        + "after exceeding the /share-view-ws threshold of 3 per minute. "
                        + "All AUDIT events: %s",
                        auditAppender.list.stream()
                                .map(ILoggingEvent::getFormattedMessage).toList())
                .isNotEmpty();

        // SR-OBS1-01 / SR-OBS1-02: all rate-limit AUDIT events must carry a real ip-hash value.
        for (ILoggingEvent event : rateLimitEvents) {
            String msg = event.getFormattedMessage();

            // SR-OBS1-02: literal event token must be present (F-7 parity)
            assertThat(msg)
                    .as("IT-sec-SV-RL-01: AUDIT event must contain literal token 'ws.subscribe.rate_limited' (SR-OBS1-02)")
                    .contains("ws.subscribe.rate_limited");

            // SR-OBS1-01: ip-hash= prefix must be present
            assertThat(msg)
                    .as("IT-sec-SV-RL-01: AUDIT event must contain 'ip-hash=' (SR-OBS1-01, SR-WS-02, SR-LOG-WS-01)")
                    .contains("ip-hash=");

            // SR-OBS1-03: ip-hash=null means REMOTE_ADDR was NOT written during handshake
            assertThat(msg)
                    .as("IT-sec-SV-RL-01: AUDIT event must NOT contain 'ip-hash=null' — "
                            + "that would mean ShareViewPrincipalHandler did not populate REMOTE_ADDR "
                            + "during the /share-view-ws WebSocket handshake (OBS-1, SR-OBS1-03)")
                    .doesNotContain("ip-hash=null");
        }

        // Cleanup
        try {
            if (session.isConnected()) {
                session.disconnect();
            }
        } catch (Exception ignored) {
            // best-effort
        }
    }
}
