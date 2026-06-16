package de.seism0saurus.glacier.webservice.messaging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import de.seism0saurus.glacier.share.application.ShareLinkActivityRegistry;
import de.seism0saurus.glacier.share.application.ShareLinkService;
import de.seism0saurus.glacier.share.application.ShareLinkViewerCounter;
import de.seism0saurus.glacier.share.domain.ShareLinkCapPolicy;
import de.seism0saurus.glacier.share.domain.ShareLinkId;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.slf4j.LoggerFactory;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.web.socket.WebSocketHandler;

import java.net.URI;
import java.security.Principal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for SEC-ACC-03: AUDIT rate-limit on the unauthenticated handshake-reject path.
 *
 * <p>The {@code viewer.handshake_rejected} AUDIT line (emitted on the unbound-sentinel and
 * link-not-active paths) must be debounced per (ip, shareLinkId) key so that an attacker
 * cannot amplify AUDIT log volume by looping the reject path.  The handshake outcome (null
 * principal / HTTP 403) is UNCHANGED; only the AUDIT emission is throttled.
 *
 * <p>Security controls:
 * <ul>
 *   <li>OWASP API4:2023 — Unrestricted Resource Consumption (log amplification).</li>
 *   <li>C9 (OWASP Proactive Controls) — security events logged without amplification.</li>
 *   <li>glacier-structured-logging-logback: D-13 / SR-8 — no raw wallId/token/IP in AUDIT log.</li>
 * </ul>
 *
 * <p>Tests proven RED against current code (no rate-limit), GREEN after implementation.
 */
@MockitoSettings(strictness = Strictness.LENIENT)
@ExtendWith(MockitoExtension.class)
class ShareViewPrincipalHandlerAuditRateLimitTest {

    private static final String LINK_ID_STR = "CCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCcc";
    private static final URI URI_WITH_LINK =
            URI.create("ws://localhost/share-view-ws?shareLinkId=" + LINK_ID_STR);
    private static final URI URI_NO_LINK =
            URI.create("ws://localhost/share-view-ws");

    /** Fixed base instant for deterministic debounce control. */
    private static final Instant T0 = Instant.parse("2026-06-01T12:00:00Z");

    @Mock
    private ShareLinkService shareLinkService;

    @Mock
    private ShareLinkActivityRegistry registry;

    @Mock
    private WebSocketHandler wsHandler;

    private ShareLinkViewerCounter viewerCounter;
    private ShareLinkCapPolicy capPolicy;
    private MutableClock mutableClock;
    private ShareViewPrincipalHandler handler;

    private Logger auditLogger;
    private ListAppender<ILoggingEvent> auditAppender;

    @BeforeEach
    void setUp() {
        viewerCounter = new ShareLinkViewerCounter();
        capPolicy = new ShareLinkCapPolicy();
        capPolicy.setMaxViewersPerLink(10);

        mutableClock = new MutableClock(T0);

        // Stub: link is NOT active — every determineUser call hits the reject path
        when(shareLinkService.resolve(any(), any())).thenReturn(Optional.empty());

        handler = new ShareViewPrincipalHandler(
                false, viewerCounter, capPolicy, shareLinkService, registry, mutableClock);

        // Attach AUDIT logger capture
        auditLogger = (Logger) LoggerFactory.getLogger("AUDIT");
        auditAppender = new ListAppender<>();
        auditAppender.start();
        auditLogger.addAppender(auditAppender);
    }

    @AfterEach
    void tearDown() {
        auditLogger.detachAppender(auditAppender);
    }

    // -----------------------------------------------------------------------
    // SEC-ACC-03: link-not-active reject path debounce (per ip:shareLinkId)
    // -----------------------------------------------------------------------

    /**
     * SEC-ACC-03 phase 1 — first reject from IP "1.2.3.4" for LINK_ID emits AUDIT.info.
     *
     * <p>Outcome is unchanged: handshake returns null (HTTP 403 fail-closed).
     * The AUDIT line IS emitted on the very first reject for a fresh key.
     */
    @Test
    void linkNotActive_firstReject_emitsAuditLine() {
        ServerHttpRequest req = requestForIp("1.2.3.4", URI_WITH_LINK);

        Principal result = handler.determineUser(req, wsHandler, new HashMap<>());

        // Handshake outcome unchanged — null = HTTP 403 (C1 fail-closed)
        assertThat(result)
                .as("SEC-ACC-03: handshake outcome must still be null (HTTP 403) — rate-limit only suppresses AUDIT")
                .isNull();

        // First reject must emit the AUDIT line
        List<ILoggingEvent> rejectEvents = auditAppender.list.stream()
                .filter(e -> e.getFormattedMessage().contains("viewer.handshake_rejected"))
                .filter(e -> e.getFormattedMessage().contains("link_not_active"))
                .toList();
        assertThat(rejectEvents)
                .as("SEC-ACC-03 phase 1: first reject must emit AUDIT viewer.handshake_rejected reason=link_not_active")
                .hasSize(1);
        assertThat(rejectEvents.get(0).getLevel())
                .as("AUDIT event must be INFO level")
                .isEqualTo(Level.INFO);
    }

    /**
     * SEC-ACC-03 phase 2 — rapid repeated rejects from same (ip, shareLinkId) within the
     * debounce window are suppressed (AUDIT not emitted again).
     *
     * <p>Outcome is UNCHANGED on every call: each returns null / HTTP 403.
     * Only the AUDIT emission is throttled.
     */
    @Test
    void linkNotActive_rapidRepeats_samKey_auditSuppressed() {
        ServerHttpRequest req = requestForIp("2.3.4.5", URI_WITH_LINK);

        // First call: AUDIT emitted
        Principal first = handler.determineUser(req, wsHandler, new HashMap<>());
        assertThat(first).isNull();

        // All subsequent calls within debounce window (clock NOT advanced)
        for (int i = 0; i < 9; i++) {
            Principal repeated = handler.determineUser(req, wsHandler, new HashMap<>());
            assertThat(repeated)
                    .as("SEC-ACC-03: handshake outcome must still be null on attempt %d", i + 2)
                    .isNull();
        }

        // Only ONE AUDIT event for this (ip, shareLinkId) key — all repeats suppressed
        long linkNotActiveCount = auditAppender.list.stream()
                .filter(e -> e.getFormattedMessage().contains("viewer.handshake_rejected"))
                .filter(e -> e.getFormattedMessage().contains("link_not_active"))
                .count();
        assertThat(linkNotActiveCount)
                .as("SEC-ACC-03 phase 2: 10 rapid rejects from same key must produce only 1 AUDIT event (9 suppressed)")
                .isEqualTo(1L);
    }

    /**
     * SEC-ACC-03 — different source IP uses a separate debounce key and still logs.
     *
     * <p>IP "3.4.5.6" is independent from "2.3.4.5" — both must emit their first reject.
     */
    @Test
    void linkNotActive_differentIp_independentDebounceKey_bothLog() {
        ServerHttpRequest req1 = requestForIp("3.4.5.6", URI_WITH_LINK);
        ServerHttpRequest req2 = requestForIp("5.6.7.8", URI_WITH_LINK);

        handler.determineUser(req1, wsHandler, new HashMap<>());
        handler.determineUser(req2, wsHandler, new HashMap<>());

        // Both IPs have independent keys — each emits its first reject
        long count = auditAppender.list.stream()
                .filter(e -> e.getFormattedMessage().contains("viewer.handshake_rejected"))
                .filter(e -> e.getFormattedMessage().contains("link_not_active"))
                .count();
        assertThat(count)
                .as("SEC-ACC-03: two different IPs must each emit one AUDIT event (independent keys)")
                .isEqualTo(2L);
    }

    /**
     * SEC-ACC-03 — after the debounce window elapses, the AUDIT line is emitted again for the
     * same (ip, shareLinkId) key.
     */
    @Test
    void linkNotActive_afterDebounceWindow_emitsAgain() {
        ServerHttpRequest req = requestForIp("6.7.8.9", URI_WITH_LINK);

        // Phase 1 at T0: first emit
        handler.determineUser(req, wsHandler, new HashMap<>());
        long countAfterFirst = auditAppender.list.stream()
                .filter(e -> e.getFormattedMessage().contains("viewer.handshake_rejected")
                        && e.getFormattedMessage().contains("link_not_active"))
                .count();
        assertThat(countAfterFirst).as("phase 1: must emit once").isEqualTo(1L);

        // Phase 2: advance to inside the window (T0+30s) — suppressed
        mutableClock.advanceTo(T0.plusSeconds(30));
        handler.determineUser(req, wsHandler, new HashMap<>());
        long countInsideWindow = auditAppender.list.stream()
                .filter(e -> e.getFormattedMessage().contains("viewer.handshake_rejected")
                        && e.getFormattedMessage().contains("link_not_active"))
                .count();
        assertThat(countInsideWindow)
                .as("SEC-ACC-03 phase 2 (T0+30s still inside 60s window): must still be 1 — suppressed")
                .isEqualTo(1L);

        // Phase 3: advance past the window (T0+61s) — emitted again
        mutableClock.advanceTo(T0.plusSeconds(61));
        handler.determineUser(req, wsHandler, new HashMap<>());
        long countAfterWindow = auditAppender.list.stream()
                .filter(e -> e.getFormattedMessage().contains("viewer.handshake_rejected")
                        && e.getFormattedMessage().contains("link_not_active"))
                .count();
        assertThat(countAfterWindow)
                .as("SEC-ACC-03 phase 3 (T0+61s past window): must emit again — total 2")
                .isEqualTo(2L);
    }

    // -----------------------------------------------------------------------
    // SEC-ACC-03: sentinel (missing shareLinkId) reject path debounce
    // -----------------------------------------------------------------------

    /**
     * SEC-ACC-03 — the unbound-sentinel path (no shareLinkId in URI) also debounces
     * its AUDIT emission per source IP.
     *
     * <p>The sentinel path emits {@code "viewer.handshake_rejected reason=missing_share_link_id"}.
     * Rapid repeats from the same IP must suppress the AUDIT line (but still return the
     * sentinel principal — the outcome is NOT changed).
     */
    @Test
    void sentinel_rapidRepeats_sameIp_auditSuppressed() {
        ServerHttpRequest req = requestForIp("10.0.0.1", URI_NO_LINK);

        // First call at T0
        Principal first = handler.determineUser(req, wsHandler, new HashMap<>());
        assertThat(first)
                .as("sentinel path still returns a principal (not null) — outcome unchanged")
                .isNotNull();
        assertThat(first).isInstanceOf(ShareViewerPrincipal.class);

        // Rapid repeats
        for (int i = 0; i < 9; i++) {
            Principal p = handler.determineUser(req, wsHandler, new HashMap<>());
            assertThat(p).isNotNull();
        }

        long missingIdCount = auditAppender.list.stream()
                .filter(e -> e.getFormattedMessage().contains("viewer.handshake_rejected"))
                .filter(e -> e.getFormattedMessage().contains("missing_share_link_id"))
                .count();
        assertThat(missingIdCount)
                .as("SEC-ACC-03 sentinel: 10 rapid calls from same IP must produce only 1 AUDIT event (9 suppressed)")
                .isEqualTo(1L);
    }

    /**
     * SEC-ACC-03 — log hygiene: AUDIT events must use hash8 / masked IP — raw values must not appear.
     *
     * <p>glacier-structured-logging-logback D-13 / SR-8: raw wallId, IP, or token must NEVER
     * appear in AUDIT logger output.
     */
    @Test
    void linkNotActive_auditEvent_noRawIpOrLinkId() {
        String rawIp = "11.22.33.44";
        // A link ID that does not look like the sentinel
        String rawLinkId = LINK_ID_STR;
        ServerHttpRequest req = requestForIp(rawIp, URI_WITH_LINK);

        handler.determineUser(req, wsHandler, new HashMap<>());

        List<ILoggingEvent> rejectEvents = auditAppender.list.stream()
                .filter(e -> e.getFormattedMessage().contains("viewer.handshake_rejected"))
                .toList();
        assertThat(rejectEvents).isNotEmpty();

        for (ILoggingEvent event : rejectEvents) {
            String msg = event.getFormattedMessage();
            assertThat(msg)
                    .as("SEC-ACC-03 / D-13 / SR-8: raw IP must not appear in AUDIT output")
                    .doesNotContain(rawIp);
            assertThat(msg)
                    .as("SEC-ACC-03 / D-13 / SR-8: raw shareLinkId must not appear in AUDIT output")
                    .doesNotContain(rawLinkId);
        }
    }

    // -----------------------------------------------------------------------
    // Helper: MutableClock for deterministic debounce testing
    // -----------------------------------------------------------------------

    /** Clock whose instant can be advanced by the test. Not thread-safe. */
    private static final class MutableClock extends Clock {
        private volatile Instant current;

        MutableClock(Instant initial) {
            this.current = initial;
        }

        void advanceTo(Instant next) {
            this.current = next;
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return current;
        }
    }

    // -----------------------------------------------------------------------
    // Helper: build a mocked request with a specific remote IP
    // -----------------------------------------------------------------------

    /**
     * Builds a mocked {@link ServerHttpRequest} whose servlet request reports
     * the given {@code remoteAddr} and the given URI — minimum setup for the
     * handshake-reject path tests.
     */
    private static ServerHttpRequest requestForIp(final String remoteAddr, final URI uri) {
        HttpServletRequest servletRequest = mock(HttpServletRequest.class);
        when(servletRequest.getRemoteAddr()).thenReturn(remoteAddr);
        when(servletRequest.getCookies()).thenReturn(null);
        when(servletRequest.getSession()).thenReturn(mock(HttpSession.class));
        ServletServerHttpRequest serverHttpRequest = mock(ServletServerHttpRequest.class);
        when(serverHttpRequest.getServletRequest()).thenReturn(servletRequest);
        when(serverHttpRequest.getURI()).thenReturn(uri);
        return serverHttpRequest;
    }
}
