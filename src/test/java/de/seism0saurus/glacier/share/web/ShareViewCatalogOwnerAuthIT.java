package de.seism0saurus.glacier.share.web;

import de.seism0saurus.glacier.share.application.ShareLinkService;
import de.seism0saurus.glacier.share.application.ShareViewStompRelay;
import de.seism0saurus.glacier.share.domain.ShareLink;
import de.seism0saurus.glacier.share.domain.ShareLinkId;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration test for the owner-scoped authorization on {@code GET /rest/share/{shareId}/catalog}.
 *
 * <p>Sec-16/P1-04: If a {@code wallId} cookie is present in the request, it must match
 * the share link's owner (sharerWallId). A mismatch returns 401 to avoid link enumeration.
 * Requests without a wallId cookie (viewer access) are not affected.
 *
 * <p>Scenarios:
 * <ul>
 *   <li>Owner with matching wallId → 200</li>
 *   <li>Non-owner with different wallId → 401</li>
 *   <li>Request with no wallId (viewer) → 200</li>
 *   <li>Request with no wallId (unknown link) → 404 (anti-enumeration preserved)</li>
 *   <li>Unauthenticated (no cookies) → 200 (viewer path, cookie minted)</li>
 * </ul>
 *
 * <p>Security: Sec-16/P1-04; OWASP A01:2021 Broken Access Control (BOLA);
 * ASVS V4.1.1 (L1) — authorization check at every entry point.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "glacier.domain=glacier.example.com",
        "glacier.cookie.secure=false",
        "glacier.fallback.enabled=true",
        "mastodon.instance=mastodon.social",
        "mastodon.accessToken=dummy",
        "mastodon.handle=glacier@mastodon.social",
        "glacier.operator.name=Test",
        "glacier.operator.streetAndNumber=Test 1",
        "glacier.operator.zipcode=12345",
        "glacier.operator.city=Test",
        "glacier.operator.country=Test",
        "glacier.operator.phone=+1",
        "glacier.operator.mail=test@test.com",
        "glacier.operator.website=test.com"
})
class ShareViewCatalogOwnerAuthIT {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ShareLinkService shareLinkService;

    @MockBean
    private ShareViewStompRelay shareViewStompRelay;

    /**
     * A valid share ID (43-char URL-safe base64 per SHARE_ID_PATTERN).
     * sv_ prefix (3 chars) + 40 URL-safe base64 chars = 43 chars.
     */
    private static final String VALID_SHARE_ID = "sv_aAbBcCdDeEfFgGhHiIjJkKlLmMnNoOpPqQrRaabb";

    /** The owner's wallId — the value stored in the share link's sharerWallId field. */
    private static final String OWNER_WALL_ID = "owner-wall-id-0000000000000000000000000000";

    /** A different wallId — simulates a request from a different user. */
    private static final String OTHER_WALL_ID = "other-wall-id-1111111111111111111111111111";

    /**
     * Sec-16 (pass): the owner presents their wallId matching the link's sharerWallId → 200.
     *
     * <p>Arrange: link.sharerWallId() = OWNER_WALL_ID, request cookie wallId = OWNER_WALL_ID.
     * Act: GET /rest/share/{id}/catalog with wallId cookie.
     * Assert: 200 OK.
     */
    @Test
    void catalogEndpoint_ownerWallIdMatchesLink_returns200() throws Exception {
        ShareLink link = mockActiveShareLink(OWNER_WALL_ID);
        when(shareLinkService.resolve(any(ShareLinkId.class), any(Instant.class)))
                .thenReturn(Optional.of(link));

        mockMvc.perform(get("/rest/share/{id}/catalog", VALID_SHARE_ID)
                        .cookie(new Cookie("wallId", OWNER_WALL_ID)))
                .andExpect(status().isOk());
    }

    /**
     * Sec-16 (fail): a different wallId (non-owner) tries to access the catalog → 401.
     *
     * <p>Unified 401 response avoids leaking whether the link exists (anti-enumeration).
     * A 403 or 404 would leak presence vs. authorization state.
     *
     * <p>Arrange: link.sharerWallId() = OWNER_WALL_ID, request cookie wallId = OTHER_WALL_ID.
     * Act: GET /rest/share/{id}/catalog with OTHER_WALL_ID cookie.
     * Assert: 401 Unauthorized.
     */
    @Test
    void catalogEndpoint_nonOwnerWallId_returns401() throws Exception {
        ShareLink link = mockActiveShareLink(OWNER_WALL_ID);
        when(shareLinkService.resolve(any(ShareLinkId.class), any(Instant.class)))
                .thenReturn(Optional.of(link));

        mockMvc.perform(get("/rest/share/{id}/catalog", VALID_SHARE_ID)
                        .cookie(new Cookie("wallId", OTHER_WALL_ID)))
                .andExpect(status().isUnauthorized());
    }

    /**
     * Sec-16 (viewer path): no wallId cookie present → 200 (viewer flow unaffected).
     *
     * <p>Viewers don't present a wallId cookie; the owner check is only triggered
     * when a wallId cookie IS present.
     *
     * <p>Arrange: link.sharerWallId() = OWNER_WALL_ID, no wallId cookie in request.
     * Act: GET /rest/share/{id}/catalog without wallId cookie.
     * Assert: 200 OK.
     */
    @Test
    void catalogEndpoint_noWallIdCookie_returns200AsViewer() throws Exception {
        ShareLink link = mockActiveShareLink(OWNER_WALL_ID);
        when(shareLinkService.resolve(any(ShareLinkId.class), any(Instant.class)))
                .thenReturn(Optional.of(link));

        mockMvc.perform(get("/rest/share/{id}/catalog", VALID_SHARE_ID))
                .andExpect(status().isOk());
    }

    /**
     * Sec-16 (anti-enumeration preserved): unknown link with any wallId → 404,
     * not 401 (prevents using the owner check as an oracle to detect link existence).
     *
     * <p>Wait — actually we need to confirm: for unknown links, resolve() returns empty,
     * so the check is never reached. The response is 404 before the owner check fires.
     *
     * <p>Arrange: shareLinkService.resolve() returns empty (link not found).
     * Act: GET /rest/share/{id}/catalog with any wallId cookie.
     * Assert: 404 Not Found (anti-enumeration preserved — Sec-16 owner check not reached).
     */
    @Test
    void catalogEndpoint_unknownLinkWithWallId_returns404NotLeaking401() throws Exception {
        when(shareLinkService.resolve(any(ShareLinkId.class), any(Instant.class)))
                .thenReturn(Optional.empty());

        // Even with a valid wallId cookie, unknown links return 404 (not 401)
        // because the 404 fires before the owner check
        mockMvc.perform(get("/rest/share/{id}/catalog", VALID_SHARE_ID)
                        .cookie(new Cookie("wallId", OWNER_WALL_ID)))
                .andExpect(status().isNotFound());
    }

    /**
     * Sec-16 (blank wallId treated as absent): a blank wallId cookie should not trigger
     * the owner check — treat as no-cookie (viewer path).
     *
     * <p>This prevents a blank cookie from causing a mismatch rejection.
     */
    @Test
    void catalogEndpoint_blankWallIdCookie_returns200AsViewer() throws Exception {
        ShareLink link = mockActiveShareLink(OWNER_WALL_ID);
        when(shareLinkService.resolve(any(ShareLinkId.class), any(Instant.class)))
                .thenReturn(Optional.of(link));

        // Blank wallId → treated as absent → viewer path → 200
        mockMvc.perform(get("/rest/share/{id}/catalog", VALID_SHARE_ID)
                        .cookie(new Cookie("wallId", "   ")))
                .andExpect(status().isOk());
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private ShareLink mockActiveShareLink(String sharerWallId) {
        ShareLink link = mock(ShareLink.class);
        when(link.id()).thenReturn(ShareLinkId.fromUrlPath(VALID_SHARE_ID));
        when(link.sharerWallId()).thenReturn(sharerWallId);
        when(link.expiresAt()).thenReturn(Instant.now().plusSeconds(86400 * 7));
        when(link.createdAt()).thenReturn(Instant.now());
        return link;
    }
}
