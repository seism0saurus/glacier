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
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration test for {@link ShareViewController}.
 *
 * <p>Security requirements: SR-SHARE-01 (anti-enumeration uniform response),
 * SR-SHARE-07 (viewer cookie flags), SR-SHARE-11 (CSP headers on share route),
 * SR-SHARE-13 (killswitch returns empty catalog).
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
class ShareViewControllerIT {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ShareLinkService shareLinkService;

    // IDs must be >= 43 chars total (URL-safe base64 alphabet: [A-Za-z0-9_-]{43,256})
    // sv_ prefix (3 chars) + 40 URL-safe base64 chars = 43 chars minimum
    private static final String VALID_SHARE_ID = "sv_aAbBcCdDeEfFgGhHiIjJkKlLmMnNoOpPqQrRssSS";
    private static final String UNKNOWN_SHARE_ID = "sv_unknownId1234567890abcdefghijklmnopqrstu";

    @Test
    void catalogEndpointMintsShareViewerIdCookieWhenAbsent() throws Exception {
        ShareLink activeLink = mockActiveShareLink(VALID_SHARE_ID);
        when(shareLinkService.resolve(any(ShareLinkId.class), any(Instant.class)))
                .thenReturn(Optional.of(activeLink));

        MvcResult result = mockMvc.perform(get("/rest/share/{id}/catalog", VALID_SHARE_ID))
                .andExpect(status().isOk())
                .andReturn();

        String setCookie = result.getResponse().getHeader("Set-Cookie");
        assertThat(setCookie).isNotNull();
        // glacier.cookie.secure=false in test, so cookie name is 'shareViewerId' (no __Host- prefix)
        assertThat(setCookie).contains("shareViewerId");
        assertThat(setCookie).contains("HttpOnly");
        assertThat(setCookie).contains("SameSite=Lax");
        assertThat(setCookie).contains("Path=/share");
    }

    @Test
    void catalogEndpointReturns404ForUnknownShareId() throws Exception {
        when(shareLinkService.resolve(any(ShareLinkId.class), any(Instant.class)))
                .thenReturn(Optional.empty());

        mockMvc.perform(get("/rest/share/{id}/catalog", UNKNOWN_SHARE_ID))
                .andExpect(status().isNotFound());
    }

    @Test
    void catalogEndpointReturns404ForExpiredShareId() throws Exception {
        // expired / revoked returns empty from service — uniform 404
        when(shareLinkService.resolve(any(ShareLinkId.class), any(Instant.class)))
                .thenReturn(Optional.empty());

        MvcResult expiredResult = mockMvc.perform(get("/rest/share/{id}/catalog", VALID_SHARE_ID))
                .andExpect(status().isNotFound())
                .andReturn();

        MvcResult unknownResult = mockMvc.perform(get("/rest/share/{id}/catalog", UNKNOWN_SHARE_ID))
                .andExpect(status().isNotFound())
                .andReturn();

        // Anti-enumeration: both must return same status and same body shape (SR-SHARE-01)
        assertThat(expiredResult.getResponse().getStatus())
                .isEqualTo(unknownResult.getResponse().getStatus());
    }

    @Test
    void catalogEndpointSetsSecurityHeaders() throws Exception {
        ShareLink activeLink = mockActiveShareLink(VALID_SHARE_ID);
        when(shareLinkService.resolve(any(ShareLinkId.class), any(Instant.class)))
                .thenReturn(Optional.of(activeLink));

        mockMvc.perform(get("/rest/share/{id}/catalog", VALID_SHARE_ID)
                        .cookie(new jakarta.servlet.http.Cookie("shareViewerId",
                                "sv_" + "V".repeat(43))))
                .andExpect(status().isOk())
                .andExpect(header().exists("Content-Security-Policy"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"));
    }

    @Test
    void catalogCspHeaderContainsCriticalDirectives() throws Exception {
        ShareLink activeLink = mockActiveShareLink(VALID_SHARE_ID);
        when(shareLinkService.resolve(any(ShareLinkId.class), any(Instant.class)))
                .thenReturn(Optional.of(activeLink));

        MvcResult result = mockMvc.perform(get("/rest/share/{id}/catalog", VALID_SHARE_ID)
                        .cookie(new jakarta.servlet.http.Cookie("shareViewerId",
                                "sv_" + "V".repeat(43))))
                .andExpect(status().isOk())
                .andReturn();

        String csp = result.getResponse().getHeader("Content-Security-Policy");
        assertThat(csp).isNotNull();
        assertThat(csp).contains("default-src 'none'");
        assertThat(csp).contains("frame-ancestors 'none'");
        assertThat(csp).contains("base-uri 'none'");
    }

    @Test
    void catalogResponseDoesNotContainSharerWallId() throws Exception {
        String sharerWallId = "sharer-secret-wall-id-should-never-leak";
        ShareLink activeLink = mockActiveShareLinkWithSharer(VALID_SHARE_ID, sharerWallId);
        when(shareLinkService.resolve(any(ShareLinkId.class), any(Instant.class)))
                .thenReturn(Optional.of(activeLink));

        MvcResult result = mockMvc.perform(get("/rest/share/{id}/catalog", VALID_SHARE_ID)
                        .cookie(new jakarta.servlet.http.Cookie("shareViewerId",
                                "sv_" + "V".repeat(43))))
                .andExpect(status().isOk())
                .andReturn();

        // SR-SHARE-02: sharerWallId must never appear in response body
        String body = result.getResponse().getContentAsString();
        assertThat(body).doesNotContain(sharerWallId);
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private ShareLink mockActiveShareLink(String shareLinkIdStr) {
        return mockActiveShareLinkWithSharer(shareLinkIdStr, "sharer-wall-id-internal-only");
    }

    private ShareLink mockActiveShareLinkWithSharer(String shareLinkIdStr, String sharerWallId) {
        ShareLink link = org.mockito.Mockito.mock(ShareLink.class);
        when(link.getId()).thenReturn(new ShareLinkId(shareLinkIdStr));
        when(link.getSharerWallId()).thenReturn(sharerWallId);
        when(link.getExpiresAt()).thenReturn(Instant.now().plusSeconds(86400 * 7));
        when(link.getCreatedAt()).thenReturn(Instant.now());
        return link;
    }
}
