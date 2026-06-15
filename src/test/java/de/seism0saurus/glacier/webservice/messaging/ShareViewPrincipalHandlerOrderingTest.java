package de.seism0saurus.glacier.webservice.messaging;

import de.seism0saurus.glacier.share.application.ShareLinkActivityRegistry;
import de.seism0saurus.glacier.share.application.ShareLinkService;
import de.seism0saurus.glacier.share.application.ShareLinkViewerCounter;
import de.seism0saurus.glacier.share.domain.ShareLink;
import de.seism0saurus.glacier.share.domain.ShareLinkCapPolicy;
import de.seism0saurus.glacier.share.domain.ShareLinkId;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.web.socket.WebSocketHandler;

import java.net.URI;
import java.security.Principal;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.mockito.quality.Strictness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests verifying the security-critical ordering in {@link ShareViewPrincipalHandler#determineUser}.
 *
 * <p>Security requirements verified:
 * <ul>
 *   <li>SR-RELAY-05 — {@code resolve()} MUST precede {@code increment()}; a revoked-link
 *       attacker must never consume counter slots.</li>
 *   <li>SR-RELAY-06 — {@code registry.register()} re-resolves under lock; returns {@code false}
 *       on concurrent revocation → handshake rejects with HTTP 403.</li>
 *   <li>SR-RELAY-13 — if {@code registry.register()} returns {@code false}, counter is
 *       decremented AND handshake is rejected (fail-closed).</li>
 * </ul>
 *
 * <p>ASVS V4.1.1 (L1) — access-control check applied before any resource allocation.
 * WSTG-SESS-03 — session fixation / hijacking: verifies that a revoked link cannot
 * exhaust the per-link counter pool.
 *
 * <p>{@code LENIENT} strictness: the {@link #requestWithLinkId} helper stubs cookie/session
 * access on the mocked servlet request. Tests that return early (revoked link, sentinel) never
 * reach those stubs. Rather than fragmenting the helper into per-test variants, lenient mode is
 * used here so tests remain readable.
 */
@MockitoSettings(strictness = Strictness.LENIENT)
@ExtendWith(MockitoExtension.class)
class ShareViewPrincipalHandlerOrderingTest {

    private static final int TEST_CAP = 5;

    /** A well-formed shareLinkId used in upgrade URIs. */
    private static final String LINK_ID_STR = "BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBbb";

    /** URI with valid shareLinkId query parameter. */
    private static final URI URI_WITH_LINK =
            URI.create("ws://localhost/share-view-ws?shareLinkId=" + LINK_ID_STR);

    @Mock
    private ShareLinkService shareLinkService;

    @Mock
    private ShareLinkActivityRegistry registry;

    @Mock
    private WebSocketHandler wsHandler;

    private ShareLinkViewerCounter viewerCounter;
    private ShareLinkCapPolicy capPolicy;
    private ShareViewPrincipalHandler handler;

    @BeforeEach
    void setUp() {
        viewerCounter = new ShareLinkViewerCounter();
        capPolicy = new ShareLinkCapPolicy();
        capPolicy.setMaxViewersPerLink(TEST_CAP);
        handler = new ShareViewPrincipalHandler(
                false, viewerCounter, capPolicy, shareLinkService, registry);
    }

    // -----------------------------------------------------------------------
    // SR-RELAY-05: increment MUST come AFTER resolve
    // -----------------------------------------------------------------------

    /**
     * SR-RELAY-05: when {@code shareLinkService.resolve()} returns empty (link revoked/expired),
     * the handshake must be rejected BEFORE {@code viewerCounter.increment()} is ever called.
     *
     * <p>Rationale: an attacker with a revoked-but-known link must not be able to exhaust the
     * counter slots, denying service to legitimate viewers (OWASP API4).
     */
    @Test
    void determineUser_revokedLink_rejectsBeforeIncrement() {
        // GIVEN: link is not active
        when(shareLinkService.resolve(any(), any())).thenReturn(Optional.empty());
        ServerHttpRequest request = requestWithLinkId(URI_WITH_LINK);

        // WHEN
        Principal result = handler.determineUser(request, wsHandler, new HashMap<>());

        // THEN: rejected
        assertThat(result).isNull();

        // SR-RELAY-05: counter must NEVER have been incremented
        ShareLinkId linkId = ShareLinkId.fromUrlPath(LINK_ID_STR);
        assertThat(viewerCounter.get(linkId)).isZero();
    }

    /**
     * SR-RELAY-05: when {@code resolve()} returns an active link, increment is called exactly once
     * and the handshake succeeds.
     */
    @Test
    void determineUser_activeLink_incrementsAfterResolve() {
        // GIVEN: link is active
        ShareLink activeLink = buildActiveLink(LINK_ID_STR, "sharer-wall-id-1234");
        when(shareLinkService.resolve(any(), any())).thenReturn(Optional.of(activeLink));
        when(registry.register(any(), any(), any(), any())).thenReturn(true);
        ServerHttpRequest request = requestWithLinkId(URI_WITH_LINK);

        // WHEN
        Principal result = handler.determineUser(request, wsHandler, new HashMap<>());

        // THEN: accepted
        assertThat(result).isNotNull();
        assertThat(result).isInstanceOf(ShareViewerPrincipal.class);

        // SR-RELAY-05: counter was incremented (exactly once)
        ShareLinkId linkId = ShareLinkId.fromUrlPath(LINK_ID_STR);
        assertThat(viewerCounter.get(linkId)).isEqualTo(1);
    }

    // -----------------------------------------------------------------------
    // SR-RELAY-06 / SR-RELAY-13: concurrent revocation during handshake
    // -----------------------------------------------------------------------

    /**
     * SR-RELAY-13: when {@code registry.register()} returns {@code false} (concurrent revocation
     * won the race), the handler must:
     * <ol>
     *   <li>Decrement the counter (refund the slot it had already incremented).</li>
     *   <li>Return {@code null} to reject the handshake (fail-closed).</li>
     * </ol>
     */
    @Test
    void determineUser_concurrentRevoke_registryReturnsFalse_rejectsAndDecrementsCounter() {
        // GIVEN: initial resolve succeeds, but registry.register() detects concurrent revocation
        ShareLink activeLink = buildActiveLink(LINK_ID_STR, "sharer-wall-id-revoke");
        when(shareLinkService.resolve(any(), any())).thenReturn(Optional.of(activeLink));
        when(registry.register(any(), any(), any(), any())).thenReturn(false);
        ServerHttpRequest request = requestWithLinkId(URI_WITH_LINK);

        // WHEN
        Principal result = handler.determineUser(request, wsHandler, new HashMap<>());

        // THEN: rejected
        assertThat(result).isNull();

        // SR-RELAY-13: counter must have been incremented then decremented (net = 0)
        ShareLinkId linkId = ShareLinkId.fromUrlPath(LINK_ID_STR);
        assertThat(viewerCounter.get(linkId)).isZero();
    }

    // -----------------------------------------------------------------------
    // SEC-ACC-01: counter slot must be refunded when registry.register() throws
    // -----------------------------------------------------------------------

    /**
     * SEC-ACC-01 / SR-RELAY-13 (exception path): when {@code registry.register()} throws a
     * {@link DataAccessException} (e.g. SQLite adapter flapping), the counter slot that was
     * already incremented MUST be refunded and {@code determineUser} MUST return {@code null}
     * so the handshake is rejected with HTTP 403 (fail-closed, C1 — access control at every
     * entry point).
     *
     * <p>Without a try/finally guard, the slot leaks permanently: repeated flapping drives
     * {@code viewerCount → maxViewers}, causing a self-inflicted DoS for future legitimate
     * viewers of the same share link (OWASP API4 — Unrestricted Resource Consumption).
     *
     * <p>ASVS V4.1.1 (L1) — access-control enforced server-side on every code path.
     * WSTG-SESS-03 — token/slot exhaustion via exception-path counter leak.
     */
    @Test
    void determineUser_registryRegisterThrows_counterRefunded() {
        // GIVEN: initial resolve succeeds
        ShareLink activeLink = buildActiveLink(LINK_ID_STR, "sharer-wall-id-throws");
        when(shareLinkService.resolve(any(), any())).thenReturn(Optional.of(activeLink));
        // registry.register() simulates a SQLite DataAccessException (flapping DB)
        DataAccessException dbError = new TransientDataAccessResourceException("SQLite locked");
        when(registry.register(any(), any(), any(), any())).thenThrow(dbError);
        ServerHttpRequest request = requestWithLinkId(URI_WITH_LINK);

        // WHEN
        Principal result = handler.determineUser(request, wsHandler, new HashMap<>());

        // THEN: fail-closed — handshake rejected
        assertThat(result)
                .as("SEC-ACC-01: determineUser must return null (fail-closed) when register() throws")
                .isNull();

        // SEC-ACC-01: counter slot must be refunded (net count = 0)
        ShareLinkId linkId = ShareLinkId.fromUrlPath(LINK_ID_STR);
        assertThat(viewerCounter.get(linkId))
                .as("SEC-ACC-01: counter slot must be refunded when register() throws (SR-RELAY-13 exception path)")
                .isZero();
    }

    // -----------------------------------------------------------------------
    // Cap check must occur before registry.register()
    // -----------------------------------------------------------------------

    /**
     * When the viewer cap is exceeded, {@code registry.register()} must never be called —
     * the cap is a cheaper guard and must fire first.
     */
    @Test
    void determineUser_capExceeded_doesNotRegisterInRegistry() {
        // GIVEN: cap = 5; fill it up first
        ShareLink activeLink = buildActiveLink(LINK_ID_STR, "sharer-wall-id-cap");
        when(shareLinkService.resolve(any(), any())).thenReturn(Optional.of(activeLink));
        when(registry.register(any(), any(), any(), any())).thenReturn(true);

        // Fill the cap completely
        for (int i = 0; i < TEST_CAP; i++) {
            Principal p = handler.determineUser(requestWithLinkId(URI_WITH_LINK), wsHandler, new HashMap<>());
            assertThat(p).as("viewer %d should be accepted", i + 1).isNotNull();
        }

        // WHEN: one more attempt — cap exceeded
        Principal rejected = handler.determineUser(requestWithLinkId(URI_WITH_LINK), wsHandler, new HashMap<>());

        // THEN: rejected at cap check
        assertThat(rejected).isNull();

        // The registry.register() should only have been called for the TEST_CAP successful handshakes
        // (not for the rejected one — the cap check fires before the registry call)
        org.mockito.Mockito.verify(registry, org.mockito.Mockito.times(TEST_CAP))
                .register(any(), any(), any(), any());
    }

    // -----------------------------------------------------------------------
    // No shareLinkId in URI — sentinel path
    // -----------------------------------------------------------------------

    /**
     * When the upgrade URI carries no {@code shareLinkId} query parameter, the handler must
     * return the sentinel-bound {@link ShareViewerPrincipal} immediately without calling
     * {@code shareLinkService.resolve()} at all.
     *
     * <p>Note: the sentinel principal is still returned (not null) so the downstream
     * interceptor (ShareViewTopicAuthInterceptor) can enforce topic restrictions. The resolve
     * call is skipped because an unbound link cannot match any real link.
     */
    @Test
    void determineUser_unboundShareLinkId_doesNotCallResolve() {
        // GIVEN: no shareLinkId param in the URI
        URI uriWithoutParam = URI.create("ws://localhost/share-view-ws");
        ServerHttpRequest request = requestWithLinkId(uriWithoutParam);

        // WHEN
        handler.determineUser(request, wsHandler, new HashMap<>());

        // THEN: resolve() was never called (sentinel short-circuits the flow)
        verify(shareLinkService, never()).resolve(any(), any());
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /**
     * Builds a mock {@link ServerHttpRequest} whose {@code getURI()} returns the given URI,
     * with no cookies and a fresh session — minimal setup for ordering tests.
     */
    private static ServerHttpRequest requestWithLinkId(final URI uri) {
        HttpServletRequest servletRequest = mock(HttpServletRequest.class);
        when(servletRequest.getCookies()).thenReturn(null);
        when(servletRequest.getSession()).thenReturn(mock(HttpSession.class));
        ServletServerHttpRequest serverHttpRequest = mock(ServletServerHttpRequest.class);
        when(serverHttpRequest.getServletRequest()).thenReturn(servletRequest);
        when(serverHttpRequest.getURI()).thenReturn(uri);
        return serverHttpRequest;
    }

    /**
     * Constructs a minimal active {@link ShareLink} for use in {@code when()} stubs.
     */
    private static ShareLink buildActiveLink(final String linkIdStr, final String sharerWallId) {
        ShareLinkId id = ShareLinkId.fromUrlPath(linkIdStr);
        return ShareLink.create(id, sharerWallId, Instant.now(), Duration.ofDays(7));
    }
}
