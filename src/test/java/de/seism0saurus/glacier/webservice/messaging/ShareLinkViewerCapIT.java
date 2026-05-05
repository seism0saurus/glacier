package de.seism0saurus.glacier.webservice.messaging;

import de.seism0saurus.glacier.share.application.ShareLinkViewerCounter;
import de.seism0saurus.glacier.share.domain.ShareLinkId;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;
import social.bigbone.MastodonClient;

import java.net.URI;
import java.security.Principal;
import java.util.HashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Integration test verifying that {@link ShareViewPrincipalHandler} enforces the
 * {@code glacier.share.maxViewersPerLink} cap (SR-SHARE-05, OWASP API4).
 *
 * <p>Strategy: start the full Spring context, retrieve the real
 * {@link ShareViewPrincipalHandler} and {@link ShareLinkViewerCounter} beans, and drive the
 * handler's {@code determineUser()} method (same package — protected access OK) with mocked
 * STOMP-upgrade requests.  This tests the enforcement logic without requiring a real WebSocket
 * client — the handshake behaviour (null = reject) is the contract under test, not the HTTP
 * upgrade wire format.
 *
 * <p>Scenarios:
 * <ul>
 *   <li>Two viewer sessions are admitted when {@code maxViewersPerLink=2}.</li>
 *   <li>Third session is rejected (null principal — WebSocket upgrade refused).</li>
 *   <li>Counter is refunded on rejection (stays at cap, not cap+1).</li>
 *   <li>After a Spring disconnect event, one slot is freed and a new session is admitted.</li>
 *   <li>WallPrincipal disconnect does NOT free a viewer slot (namespace isolation).</li>
 * </ul>
 *
 * <p>Security: SR-SHARE-05 (per-link cap), OWASP API4 (Unrestricted Resource Consumption),
 * glacier-fallback-mode-discipline (WallPrincipal / ShareViewerPrincipal isolation).
 *
 * <h2>Why this class does NOT use {@code @DirtiesContext}</h2>
 * <p>Unlike {@code SubscribeRateLimitProductionPathIT}, {@code ShareViewRemoteAddrProductionPathIT},
 * and {@code HandshakeForwardedForRespectedIT} — which all use
 * {@code @DirtiesContext(classMode = ClassMode.AFTER_EACH_TEST_METHOD)} to reset Bucket4j
 * token-bucket singletons between tests — this class deliberately avoids that annotation.
 * {@code @DirtiesContext} restarts the full Spring context after every test method, which
 * incurs a ~5-second overhead per test.  The viewer counter is instead reset explicitly in
 * {@link #resetCounter()} (the {@code @BeforeEach} method) by draining
 * {@link de.seism0saurus.glacier.share.application.ShareLinkViewerCounter} until it returns zero.
 *
 * <p><strong>Obligation for future {@code @Test} authors in this class</strong>:
 * every new test method added here <em>must</em> rely on the {@code @BeforeEach} drain to
 * ensure a clean counter state, or must set up and tear down its own counter state explicitly.
 * Do NOT introduce test state that cannot be reset by decrementing the counter to zero
 * (e.g. do not store shared mutable state in static fields or rely on Bucket4j token buckets)
 * without first adding a corresponding cleanup step to {@link #resetCounter()}.
 * If this invariant cannot be maintained, replace the drain pattern with
 * {@code @DirtiesContext(classMode = ClassMode.AFTER_EACH_TEST_METHOD)} as the safe fallback,
 * accepting the ~5s-per-test cost.
 */
@SpringBootTest
@TestPropertySource(properties = {
        // Set the cap to 2 so rejection is triggered cheaply in the test
        "glacier.share.maxViewersPerLink=2"
})
class ShareLinkViewerCapIT {

    @SuppressWarnings("unused")
    @MockitoBean
    private MastodonClient mastodonClient;

    @Autowired
    private ShareViewPrincipalHandler shareViewPrincipalHandler;

    @Autowired
    private ShareLinkViewerCounter viewerCounter;

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    private WebSocketHandler wsHandler;

    /**
     * The sentinel share link ID produced by the handler when no {@code shareLinkId}
     * query parameter is present.  It is a 43-char base64url string of all zeros.
     * All test requests use this same sentinel so they contend on the same counter bucket.
     */
    private static final ShareLinkId UNBOUND_ID =
            ShareLinkId.fromUrlPath("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA");

    @BeforeEach
    void resetCounter() {
        // Drain the counter for the unbound sentinel link between tests.
        // The counter never goes below zero, so decrementing until zero is safe.
        while (viewerCounter.get(UNBOUND_ID) > 0) {
            viewerCounter.decrement(UNBOUND_ID);
        }
        wsHandler = mock(WebSocketHandler.class);
    }

    // -----------------------------------------------------------------------
    // Cap enforcement at handshake
    // -----------------------------------------------------------------------

    /**
     * Two viewer sessions are admitted when the cap is 2.
     */
    @Test
    void twoSessionsAdmittedAtCap() {
        Principal p1 = shareViewPrincipalHandler.determineUser(
                requestWithNoCookies(), wsHandler, new HashMap<>());
        Principal p2 = shareViewPrincipalHandler.determineUser(
                requestWithNoCookies(), wsHandler, new HashMap<>());

        assertThat(p1).isNotNull();
        assertThat(p2).isNotNull();
    }

    /**
     * The third session is rejected (returns null) when the cap is already reached.
     * This is the primary SR-SHARE-05 acceptance criterion.
     */
    @Test
    void thirdSessionRejectedWhenCapReached() {
        // Fill the cap
        shareViewPrincipalHandler.determineUser(requestWithNoCookies(), wsHandler, new HashMap<>());
        shareViewPrincipalHandler.determineUser(requestWithNoCookies(), wsHandler, new HashMap<>());

        // The third attempt must be rejected (null = WebSocket upgrade refused)
        Principal p3 = shareViewPrincipalHandler.determineUser(
                requestWithNoCookies(), wsHandler, new HashMap<>());

        assertThat(p3)
                .as("Third viewer session must be rejected when maxViewersPerLink=2 is reached")
                .isNull();
    }

    /**
     * The counter is not over-incremented on rejection — the refund after cap detection
     * keeps the count at the cap value, not cap+1.
     */
    @Test
    void counterRefundedOnRejection() {
        shareViewPrincipalHandler.determineUser(requestWithNoCookies(), wsHandler, new HashMap<>());
        shareViewPrincipalHandler.determineUser(requestWithNoCookies(), wsHandler, new HashMap<>());
        shareViewPrincipalHandler.determineUser(requestWithNoCookies(), wsHandler, new HashMap<>()); // rejected

        // Counter must be exactly 2 (the cap), not 3
        assertThat(viewerCounter.get(UNBOUND_ID))
                .as("Counter must be refunded after rejection so it stays at the cap, not cap+1")
                .isEqualTo(2);
    }

    // -----------------------------------------------------------------------
    // Disconnect frees the slot
    // -----------------------------------------------------------------------

    /**
     * After a viewer disconnects via the Spring event bus, one slot is freed and a new
     * session is accepted.
     *
     * <p>This tests the full admit → Spring-event-disconnect → admit cycle,
     * confirming that the {@code @EventListener} in {@link ShareViewPrincipalHandler} is
     * registered on the application bus when the handler is wired as a Spring bean.
     */
    @Test
    void disconnectEventFreesSlotForNewViewer() {
        // Fill the cap
        Principal p1 = shareViewPrincipalHandler.determineUser(
                requestWithNoCookies(), wsHandler, new HashMap<>());
        shareViewPrincipalHandler.determineUser(requestWithNoCookies(), wsHandler, new HashMap<>());

        // Third attempt rejected
        assertThat(shareViewPrincipalHandler.determineUser(
                requestWithNoCookies(), wsHandler, new HashMap<>())).isNull();

        // Simulate p1 disconnect via the real Spring event bus
        assertThat(p1).isInstanceOf(ShareViewerPrincipal.class);
        eventPublisher.publishEvent(disconnectEventFor(p1));

        // Slot freed — new session now accepted
        Principal p3 = shareViewPrincipalHandler.determineUser(
                requestWithNoCookies(), wsHandler, new HashMap<>());

        assertThat(p3)
                .as("A new viewer session must be admitted after a prior session disconnects")
                .isNotNull();
    }

    // -----------------------------------------------------------------------
    // glacier-fallback-mode-discipline: WallPrincipal namespace isolation
    // -----------------------------------------------------------------------

    /**
     * A WallPrincipal disconnect must NOT free a viewer slot.
     *
     * <p>glacier-fallback-mode-discipline: sharer and viewer namespaces must be fully
     * isolated.  The viewer counter must only be decremented for {@link ShareViewerPrincipal}
     * disconnects, never for {@link WallPrincipal} disconnects.
     */
    @Test
    void wallPrincipalDisconnectDoesNotFreeViewerSlot() {
        // Fill the cap
        shareViewPrincipalHandler.determineUser(requestWithNoCookies(), wsHandler, new HashMap<>());
        shareViewPrincipalHandler.determineUser(requestWithNoCookies(), wsHandler, new HashMap<>());

        // WallPrincipal disconnects via the Spring event bus — must NOT affect the viewer counter
        WallPrincipal wallPrincipal = new WallPrincipal("wall-principal-uuid-1234567890abcdef");
        eventPublisher.publishEvent(disconnectEventFor(wallPrincipal));

        // Third viewer session must still be rejected (counter was not decremented)
        Principal p3 = shareViewPrincipalHandler.determineUser(
                requestWithNoCookies(), wsHandler, new HashMap<>());

        assertThat(p3)
                .as("WallPrincipal disconnect must not free a viewer slot — third session must still be rejected")
                .isNull();
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static ServerHttpRequest requestWithNoCookies() {
        HttpServletRequest servletRequest = mock(HttpServletRequest.class);
        when(servletRequest.getCookies()).thenReturn(null);
        HttpSession session = mock(HttpSession.class);
        when(session.getId()).thenReturn("test-session-" + System.nanoTime());
        when(servletRequest.getSession()).thenReturn(session);
        ServletServerHttpRequest serverHttpRequest = mock(ServletServerHttpRequest.class);
        when(serverHttpRequest.getServletRequest()).thenReturn(servletRequest);
        when(serverHttpRequest.getURI()).thenReturn(
                URI.create("ws://localhost/share-view-ws"));
        return serverHttpRequest;
    }

    private static SessionDisconnectEvent disconnectEventFor(final Principal principal) {
        org.springframework.messaging.Message<byte[]> msg =
                MessageBuilder.withPayload(new byte[0])
                        .setHeader(SimpMessageHeaderAccessor.SESSION_ID_HEADER, "test-session")
                        .build();
        return new SessionDisconnectEvent(new Object(), msg, "test-session", null, principal);
    }
}
