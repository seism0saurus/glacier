package de.seism0saurus.glacier.webservice.messaging;

import de.seism0saurus.glacier.share.application.ShareLinkActivityRegistry;
import de.seism0saurus.glacier.share.application.ShareLinkService;
import de.seism0saurus.glacier.share.application.ShareLinkViewerCounter;
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
import org.mockito.quality.Strictness;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.web.socket.WebSocketHandler;

import java.net.URI;
import java.util.HashMap;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Verifies that a revoked share link cannot exhaust the per-link viewer counter.
 *
 * <p>Security requirement SR-RELAY-05: {@code shareLinkService.resolve()} is called BEFORE
 * {@code viewerCounter.increment()}, so an attacker with a revoked-but-known link ID cannot
 * fill the viewer counter and deny service to legitimate viewers (OWASP API4).
 *
 * <p>ASVS V13.2.1 (L1) — API input validation / resource-consumption controls.
 * WSTG-SESS-03 — token exhaustion / DoS via repeated revoked-link handshake attempts.
 *
 * <p>{@code LENIENT} strictness: the {@link #requestWithLinkId} helper stubs cookie/session
 * access, but for the revoked-link test path the handler returns before those stubs are consumed
 * (early return at the resolve() gate). Lenient mode avoids fragmenting the helper.
 */
@MockitoSettings(strictness = Strictness.LENIENT)
@ExtendWith(MockitoExtension.class)
class ShareViewPrincipalHandlerCapExhaustionTest {

    private static final int CAP = 2;
    private static final String LINK_ID_STR = "DDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDdd";
    private static final URI URI_WITH_LINK =
            URI.create("ws://localhost/share-view-ws?shareLinkId=" + LINK_ID_STR);

    @Mock
    private ShareLinkService shareLinkService;

    @Mock
    private ShareLinkActivityRegistry registry;

    @Mock
    private WebSocketHandler wsHandler;

    private ShareLinkViewerCounter viewerCounter;
    private ShareViewPrincipalHandler handler;

    @BeforeEach
    void setUp() {
        viewerCounter = new ShareLinkViewerCounter();
        ShareLinkCapPolicy capPolicy = new ShareLinkCapPolicy();
        capPolicy.setMaxViewersPerLink(CAP);
        handler = new ShareViewPrincipalHandler(
                false, viewerCounter, capPolicy, shareLinkService, registry);
    }

    /**
     * SR-RELAY-05: An attacker holding a revoked link ID calls {@code determineUser()} 10 times.
     * Because {@code resolve()} rejects every attempt before {@code increment()} is called,
     * the counter for that link ID must remain at zero after all 10 attempts.
     *
     * <p>Without this ordering fix, an attacker could increment the counter 10 times and
     * prevent legitimate viewers from connecting even after the link was re-activated
     * (OWASP API4: Unrestricted Resource Consumption).
     */
    @Test
    void revokedLink_cannotExhaustViewerCounter() {
        // GIVEN: link is always revoked (resolve always returns empty)
        when(shareLinkService.resolve(any(), any())).thenReturn(Optional.empty());

        // WHEN: 10 handshake attempts with the revoked link ID
        for (int i = 0; i < 10; i++) {
            handler.determineUser(requestWithLinkId(URI_WITH_LINK), wsHandler, new HashMap<>());
        }

        // THEN: counter for this linkId is still 0 — resolve() rejected before increment()
        ShareLinkId linkId = ShareLinkId.fromUrlPath(LINK_ID_STR);
        assertThat(viewerCounter.get(linkId))
                .as("Revoked link must never consume counter slots (SR-RELAY-05)")
                .isZero();
    }

    // -----------------------------------------------------------------------
    // Helper
    // -----------------------------------------------------------------------

    private static ServerHttpRequest requestWithLinkId(final URI uri) {
        HttpServletRequest servletRequest = mock(HttpServletRequest.class);
        when(servletRequest.getCookies()).thenReturn(null);
        when(servletRequest.getSession()).thenReturn(mock(HttpSession.class));
        ServletServerHttpRequest serverHttpRequest = mock(ServletServerHttpRequest.class);
        when(serverHttpRequest.getServletRequest()).thenReturn(servletRequest);
        when(serverHttpRequest.getURI()).thenReturn(uri);
        return serverHttpRequest;
    }
}
