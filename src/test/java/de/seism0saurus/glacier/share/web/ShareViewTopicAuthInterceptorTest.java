package de.seism0saurus.glacier.share.web;

import de.seism0saurus.glacier.share.application.ShareLinkService;
import de.seism0saurus.glacier.share.domain.ShareLink;
import de.seism0saurus.glacier.share.domain.ShareLinkId;
import de.seism0saurus.glacier.webservice.messaging.ShareViewerPrincipal;
import de.seism0saurus.glacier.webservice.messaging.WallPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;

import java.security.Principal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ShareViewTopicAuthInterceptor}.
 *
 * <p>Security requirements covered:
 * <ul>
 *   <li>SR-SHARE-06 (viewer topic isolation): viewers may only subscribe to
 *       {@code /topic/share/{boundShareLinkId}/...} matching their own bound link.</li>
 *   <li>SR-SHARE-02 (wallId non-disclosure): viewers must never access
 *       {@code /topic/hashtags/...}.</li>
 *   <li>OWASP API1 (BOLA): destination shareLinkId must match the session's
 *       boundShareLinkId — checked before ACTIVE status (ADR-SHARE-05 revised).</li>
 *   <li>glacier-fallback-mode-discipline: {@link WallPrincipal} sessions pass through
 *       unchanged — only {@link ShareViewerPrincipal} sessions are subject to this cap.</li>
 * </ul>
 *
 * <p>Naming: {@code *Test.java} → Surefire (unit). No Spring context loaded.
 */
@ExtendWith(MockitoExtension.class)
class ShareViewTopicAuthInterceptorTest {

    /**
     * A valid, well-formed share link ID token (43 URL-safe base64 chars = 256 bits entropy).
     * Used as the "own" link that the test viewer is bound to.
     */
    private static final String OWN_LINK_TOKEN =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopq"; // 43 chars

    /**
     * A second valid share link ID token, distinct from {@link #OWN_LINK_TOKEN}.
     * Used to simulate a foreign link that the viewer must NOT subscribe to.
     */
    private static final String FOREIGN_LINK_TOKEN =
            "ZYXWVUTSRQPONMLKJIHGFEDCBAzyxwvutsrqponmlkj"; // 43 chars

    /**
     * A valid share viewer ID in {@code sv_} + 43-char base64url format.
     */
    private static final String VIEWER_ID =
            "sv_" + "A".repeat(43);

    @Mock
    private ShareLinkService shareLinkService;

    @Mock
    private MessageChannel channel;

    private ShareViewTopicAuthInterceptor interceptor;

    private ShareLinkId ownLinkId;

    @BeforeEach
    void setUp() {
        interceptor = new ShareViewTopicAuthInterceptor(shareLinkService);
        ownLinkId = ShareLinkId.fromUrlPath(OWN_LINK_TOKEN);
    }

    // -----------------------------------------------------------------------
    // Case 1: valid viewer, ACTIVE link, correct own shareLinkId → allowed
    // -----------------------------------------------------------------------

    /**
     * SR-SHARE-06: a viewer subscribed to their own ACTIVE share link is admitted.
     */
    @Test
    void validViewerSubscribeToOwnActiveLinkPasses() {
        ShareLink activeLink = mock(ShareLink.class);
        when(shareLinkService.resolve(eq(ownLinkId), any())).thenReturn(Optional.of(activeLink));

        Message<?> msg = subscribeMessage(
                viewerPrincipal(VIEWER_ID, ownLinkId),
                "/topic/share/" + OWN_LINK_TOKEN + "/hashtags/test/creation");

        Message<?> result = interceptor.preSend(msg, channel);

        assertThat(result).isSameAs(msg);
        verify(shareLinkService).resolve(eq(ownLinkId), any());
    }

    // -----------------------------------------------------------------------
    // Case 2: viewer subscribes to a foreign shareLinkId (BOLA guard)
    // -----------------------------------------------------------------------

    /**
     * OWASP API1 (BOLA): a viewer bound to link A must not subscribe to link B's topics,
     * even if link B is ACTIVE. The bound-link check runs before the ACTIVE check.
     */
    @Test
    void viewerSubscribingToForeignShareLinkIdIsRejected() {
        // shareLinkService.resolve is never called when the BOLA guard triggers first
        Message<?> msg = subscribeMessage(
                viewerPrincipal(VIEWER_ID, ownLinkId),
                "/topic/share/" + FOREIGN_LINK_TOKEN + "/hashtags/test/creation");

        Message<?> result = interceptor.preSend(msg, channel);

        assertThat(result).isNull();
        // BOLA guard fires before service call — no ACTIVE check needed
        verify(shareLinkService, never()).resolve(any(), any());
    }

    // -----------------------------------------------------------------------
    // Case 3: viewer subscribes to sharer topic /topic/hashtags/... → rejected
    // -----------------------------------------------------------------------

    /**
     * SR-SHARE-02: viewers must never access {@code /topic/hashtags/...} because
     * those paths expose the sharer's wallId.
     */
    @Test
    void viewerSubscribingToHashtagTopicIsRejected() {
        Message<?> msg = subscribeMessage(
                viewerPrincipal(VIEWER_ID, ownLinkId),
                "/topic/hashtags/some-wall-id/glacier/creation");

        Message<?> result = interceptor.preSend(msg, channel);

        assertThat(result).isNull();
        verify(shareLinkService, never()).resolve(any(), any());
    }

    // -----------------------------------------------------------------------
    // Case 4: viewer subscribes to REVOKED (non-ACTIVE) share link → rejected
    // -----------------------------------------------------------------------

    /**
     * SR-SHARE-06: a revoked link (service returns empty) must not allow subscription.
     */
    @Test
    void viewerSubscribingToRevokedShareLinkIsRejected() {
        when(shareLinkService.resolve(eq(ownLinkId), any())).thenReturn(Optional.empty());

        Message<?> msg = subscribeMessage(
                viewerPrincipal(VIEWER_ID, ownLinkId),
                "/topic/share/" + OWN_LINK_TOKEN + "/hashtags/test/creation");

        Message<?> result = interceptor.preSend(msg, channel);

        assertThat(result).isNull();
        verify(shareLinkService).resolve(eq(ownLinkId), any());
    }

    // -----------------------------------------------------------------------
    // Case 5: viewer subscribes to an EXPIRED share link → rejected
    // -----------------------------------------------------------------------

    /**
     * SR-SHARE-06: an expired link returns empty from the service (same as revoked).
     * Both revoked and expired are indistinguishable to the caller — both return empty.
     */
    @Test
    void viewerSubscribingToExpiredShareLinkIsRejected() {
        // shareLinkService.resolve returns empty for expired links (same contract as revoked)
        when(shareLinkService.resolve(eq(ownLinkId), any())).thenReturn(Optional.empty());

        Message<?> msg = subscribeMessage(
                viewerPrincipal(VIEWER_ID, ownLinkId),
                "/topic/share/" + OWN_LINK_TOKEN + "/sublevel");

        Message<?> result = interceptor.preSend(msg, channel);

        assertThat(result).isNull();
    }

    // -----------------------------------------------------------------------
    // Case 6: non-SUBSCRIBE frame (e.g. SEND) → passes through without checks
    // -----------------------------------------------------------------------

    /**
     * The interceptor only polices SUBSCRIBE frames. SEND and other commands pass through.
     */
    @Test
    void sendFrameFromViewerPassesThroughWithoutChecks() {
        Message<?> msg = commandMessage(
                StompCommand.SEND,
                viewerPrincipal(VIEWER_ID, ownLinkId),
                "/topic/share/" + OWN_LINK_TOKEN + "/something");

        Message<?> result = interceptor.preSend(msg, channel);

        assertThat(result).isSameAs(msg);
        verify(shareLinkService, never()).resolve(any(), any());
    }

    /**
     * CONNECT frames pass through unchanged — interceptor only concerns SUBSCRIBE.
     */
    @Test
    void connectFramePassesThroughWithoutChecks() {
        Message<?> msg = commandMessage(
                StompCommand.CONNECT,
                viewerPrincipal(VIEWER_ID, ownLinkId),
                null);

        Message<?> result = interceptor.preSend(msg, channel);

        assertThat(result).isSameAs(msg);
        verify(shareLinkService, never()).resolve(any(), any());
    }

    // -----------------------------------------------------------------------
    // Case 7: WallPrincipal (sharer) → passes through unchanged
    // -----------------------------------------------------------------------

    /**
     * glacier-fallback-mode-discipline: WallPrincipal sessions are never subject to the
     * share-topic isolation logic. The interceptor passes them through to the existing
     * sharer authorization chain.
     */
    @Test
    void wallPrincipalSubscribePassesThroughUnchanged() {
        WallPrincipal sharer = new WallPrincipal("wall-uuid-1234");

        Message<?> msg = subscribeMessage(
                sharer,
                "/topic/hashtags/wall-uuid-1234/glacier/creation");

        Message<?> result = interceptor.preSend(msg, channel);

        assertThat(result).isSameAs(msg);
        verify(shareLinkService, never()).resolve(any(), any());
    }

    /**
     * glacier-fallback-mode-discipline: even a WallPrincipal whose name happens to start with
     * "sv_" is treated as a sharer — authorization is enforced by TYPE, not prefix convention.
     * (ADR-SHARE-05 revised)
     */
    @Test
    void wallPrincipalWithSvPrefixNamePassesThroughUnchanged() {
        // Simulate an adversarial WallPrincipal whose name looks like a viewer ID.
        // The type check (instanceof ShareViewerPrincipal) must reject it from viewer checks.
        WallPrincipal adversarialSharer = new WallPrincipal("sv_" + "A".repeat(43));

        Message<?> msg = subscribeMessage(
                adversarialSharer,
                "/topic/hashtags/some-wall-id/glacier/creation");

        Message<?> result = interceptor.preSend(msg, channel);

        // WallPrincipal is not a ShareViewerPrincipal — interceptor must not reject
        assertThat(result).isSameAs(msg);
        verify(shareLinkService, never()).resolve(any(), any());
    }

    // -----------------------------------------------------------------------
    // Case 8: null principal → rejected (fail-closed, OWASP A07)
    // -----------------------------------------------------------------------

    /**
     * OWASP A07 / fail-closed: a SUBSCRIBE with no authenticated principal is rejected.
     * No unauthenticated session may subscribe to any topic.
     */
    @Test
    void nullPrincipalSubscribeIsRejected() {
        Message<?> msg = subscribeMessage(
                null,
                "/topic/share/" + OWN_LINK_TOKEN + "/hashtags/test/creation");

        Message<?> result = interceptor.preSend(msg, channel);

        assertThat(result).isNull();
        verify(shareLinkService, never()).resolve(any(), any());
    }

    // -----------------------------------------------------------------------
    // Null destination branch
    // -----------------------------------------------------------------------

    /**
     * When a viewer SUBSCRIBE frame has no destination header, the subscription is rejected.
     * Null destination is treated as a misconfigured client (fail-closed).
     */
    @Test
    void viewerSubscribeWithNullDestinationIsRejected() {
        Message<?> msg = subscribeMessage(viewerPrincipal(VIEWER_ID, ownLinkId), null);

        Message<?> result = interceptor.preSend(msg, channel);

        assertThat(result).isNull();
        verify(shareLinkService, never()).resolve(any(), any());
    }

    // -----------------------------------------------------------------------
    // Additional branch coverage: invalid share link ID format in destination
    // -----------------------------------------------------------------------

    /**
     * When the destination contains an invalid (e.g. too-short) share link ID, the
     * interceptor rejects the subscription gracefully without propagating the exception.
     */
    @Test
    void viewerSubscribingToInvalidShareLinkIdFormatIsRejected() {
        Message<?> msg = subscribeMessage(
                viewerPrincipal(VIEWER_ID, ownLinkId),
                "/topic/share/short/hashtags/test"); // "short" is < 43 chars — invalid

        Message<?> result = interceptor.preSend(msg, channel);

        assertThat(result).isNull();
        verify(shareLinkService, never()).resolve(any(), any());
    }

    /**
     * A destination with only the share prefix and a valid ID (no trailing slash) is still
     * checked for ACTIVE status and the bound link match.
     */
    @Test
    void viewerSubscribingToSharePrefixWithNoSubpathPassesWhenActive() {
        ShareLink activeLink = mock(ShareLink.class);
        when(shareLinkService.resolve(eq(ownLinkId), any())).thenReturn(Optional.of(activeLink));

        // Destination ends immediately after the shareLinkId — no trailing slash
        Message<?> msg = subscribeMessage(
                viewerPrincipal(VIEWER_ID, ownLinkId),
                "/topic/share/" + OWN_LINK_TOKEN);

        Message<?> result = interceptor.preSend(msg, channel);

        assertThat(result).isSameAs(msg);
    }

    /**
     * A destination that does not start with either share or hashtag prefix is rejected.
     */
    @Test
    void viewerSubscribingToArbitraryTopicIsRejected() {
        Message<?> msg = subscribeMessage(
                viewerPrincipal(VIEWER_ID, ownLinkId),
                "/user/queue/subscriptions");

        Message<?> result = interceptor.preSend(msg, channel);

        assertThat(result).isNull();
        verify(shareLinkService, never()).resolve(any(), any());
    }

    // -----------------------------------------------------------------------
    // Helper methods
    // -----------------------------------------------------------------------

    /**
     * Builds a STOMP SUBSCRIBE {@link Message} with the given principal and destination.
     */
    private static Message<?> subscribeMessage(Principal principal, String destination) {
        return commandMessage(StompCommand.SUBSCRIBE, principal, destination);
    }

    /**
     * Builds a STOMP {@link Message} for the given {@link StompCommand},
     * with the supplied principal and optional destination.
     */
    private static Message<?> commandMessage(StompCommand command, Principal principal, String destination) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(command);
        if (destination != null) {
            accessor.setDestination(destination);
        }
        if (principal != null) {
            accessor.setUser(principal);
        }
        accessor.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    /**
     * Creates a {@link ShareViewerPrincipal} bound to the given link ID.
     */
    private static ShareViewerPrincipal viewerPrincipal(String viewerId, ShareLinkId boundLinkId) {
        return new ShareViewerPrincipal(viewerId, boundLinkId);
    }
}
