package de.seism0saurus.glacier.share.web;

import de.seism0saurus.glacier.share.application.ShareLinkService;
import de.seism0saurus.glacier.share.domain.ShareLink;
import de.seism0saurus.glacier.share.domain.ShareLinkId;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifies that the sharer's wallId never leaks into any response visible to viewers.
 *
 * <p>Security requirement: SR-SHARE-02 (sharerWallId never transmitted to viewer).
 * References: ADR-SHARE-02, architecture invariant.
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
        "glacier.operatorName=Test",
        "glacier.operatorStreetAndNumber=Test 1",
        "glacier.operatorZipcode=12345",
        "glacier.operatorCity=Test",
        "glacier.operatorCountry=Test",
        "glacier.operatorPhone=+1",
        "glacier.operatorMail=test@test.com",
        "glacier.operatorWebsite=test.com"
})
class WallIdLeakageIT {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ShareLinkService shareLinkService;

    /**
     * The secret sharer wallId that must NEVER appear in any viewer-facing response.
     */
    private static final String SECRET_SHARER_WALL_ID = "SECRET_SHARER_WALL_ID_MUST_NOT_LEAK_IN_RESPONSE";

    @Test
    void catalogEndpointDoesNotLeakSharerWallId() throws Exception {
        // Share IDs must be >= 43 chars (sv_ + 40 URL-safe base64 chars)
        String shareId = "sv_aAbBcCdDeEfFgGhHiIjJkKlLmMnNoOpPqQrRssSS";
        ShareLink link = mockLinkWithSharer(shareId, SECRET_SHARER_WALL_ID);
        when(shareLinkService.resolve(any(ShareLinkId.class), any(Instant.class)))
                .thenReturn(Optional.of(link));

        MvcResult result = mockMvc.perform(get("/rest/share/{id}/catalog", shareId)
                        .cookie(new jakarta.servlet.http.Cookie("shareViewerId",
                                "sv_" + "V".repeat(43))))
                .andExpect(status().isOk())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        String headers = result.getResponse().getHeaderNames().stream()
                .map(h -> h + "=" + result.getResponse().getHeader(h))
                .reduce("", String::concat);

        assertThat(body).doesNotContain(SECRET_SHARER_WALL_ID);
        assertThat(headers).doesNotContain(SECRET_SHARER_WALL_ID);
    }

    @Test
    void notFoundResponseDoesNotLeakSharerWallId() throws Exception {
        when(shareLinkService.resolve(any(ShareLinkId.class), any(Instant.class)))
                .thenReturn(Optional.empty());

        MvcResult result = mockMvc.perform(get("/rest/share/{id}/catalog",
                        "sv_unknownId1234567890abcdefghijklmnopqrstu"))
                .andExpect(status().isNotFound())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        assertThat(body).doesNotContain(SECRET_SHARER_WALL_ID);
    }

    // -----------------------------------------------------------------------
    // Helper
    // -----------------------------------------------------------------------

    private ShareLink mockLinkWithSharer(String shareLinkIdStr, String sharerWallId) {
        ShareLink link = mock(ShareLink.class);
        when(link.getId()).thenReturn(new ShareLinkId(shareLinkIdStr));
        when(link.getSharerWallId()).thenReturn(sharerWallId);
        when(link.getExpiresAt()).thenReturn(Instant.now().plusSeconds(86400 * 7));
        when(link.getCreatedAt()).thenReturn(Instant.now());
        return link;
    }
}
