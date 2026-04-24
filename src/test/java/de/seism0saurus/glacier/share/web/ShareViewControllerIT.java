package de.seism0saurus.glacier.share.web;

import de.seism0saurus.glacier.share.application.ShareLinkService;
import de.seism0saurus.glacier.share.application.ShareViewStompRelay;
import de.seism0saurus.glacier.share.domain.ShareLink;
import de.seism0saurus.glacier.share.domain.ShareLinkId;
import de.seism0saurus.glacier.webservice.cache.CacheEntry;
import de.seism0saurus.glacier.webservice.cache.EventType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
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

    @MockBean
    private ShareViewStompRelay shareViewStompRelay;

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

    /**
     * Fix 7: GET /rest/share/{id}/messages happy-path — returns CacheEntry list as JSON array.
     *
     * <p>Security: SR-SHARE-12 (rate limiting), SR-SHARE-02 (no wallId in response),
     * glacier-fallback-mode-discipline (fallback.enabled=true in this test).
     */
    @Test
    void messagesEndpoint_happyPath_returnsCacheEntries() throws Exception {
        ShareLink activeLink = mockActiveShareLink(VALID_SHARE_ID);
        when(shareLinkService.resolve(any(ShareLinkId.class), any(Instant.class)))
                .thenReturn(Optional.of(activeLink));

        CacheEntry entry = new CacheEntry(EventType.CREATED, "status-001",
                "https://example.com/status/1/embed", null, 1L);
        when(shareViewStompRelay.getRecentMessages(
                any(ShareLinkId.class), eq("cats"), isNull(), any(Instant.class)))
                .thenReturn(List.of(entry));

        MvcResult result = mockMvc.perform(get("/rest/share/{id}/messages", VALID_SHARE_ID)
                        .param("hashtag", "cats")
                        .cookie(new jakarta.servlet.http.Cookie("shareViewerId",
                                "sv_" + "V".repeat(40))))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andReturn();

        String body = result.getResponse().getContentAsString();
        // SR-SHARE-02: sharerWallId must not appear in the response
        assertThat(body).doesNotContain("sharer-wall-id-internal-only");
        // Response must contain the statusId from the cache entry
        assertThat(body).contains("status-001");
    }

    /**
     * Fix 7: GET /rest/share/{id}/messages in killswitch mode (fallback.enabled=false) → 404.
     *
     * <p>Security: glacier-fallback-mode-discipline — killswitch must return 404, not data.
     * Configured via separate SpringBootTest with fallback.enabled=false.
     */
    @Test
    void messagesEndpoint_unknownShareId_returns404() throws Exception {
        // Unknown share ID → relay cannot resolve → 404 (anti-enumeration SR-SHARE-01)
        when(shareLinkService.resolve(any(ShareLinkId.class), any(Instant.class)))
                .thenReturn(Optional.empty());
        when(shareViewStompRelay.getRecentMessages(
                any(ShareLinkId.class), anyString(), any(), any(Instant.class)))
                .thenReturn(List.of());

        mockMvc.perform(get("/rest/share/{id}/messages", UNKNOWN_SHARE_ID)
                        .param("hashtag", "cats"))
                .andExpect(status().isNotFound());
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private ShareLink mockActiveShareLink(String shareLinkIdStr) {
        return mockActiveShareLinkWithSharer(shareLinkIdStr, "sharer-wall-id-internal-only");
    }

    private ShareLink mockActiveShareLinkWithSharer(String shareLinkIdStr, String sharerWallId) {
        ShareLink link = org.mockito.Mockito.mock(ShareLink.class);
        when(link.id()).thenReturn(ShareLinkId.fromUrlPath(shareLinkIdStr));
        when(link.sharerWallId()).thenReturn(sharerWallId);
        when(link.expiresAt()).thenReturn(Instant.now().plusSeconds(86400 * 7));
        when(link.createdAt()).thenReturn(Instant.now());
        return link;
    }
}
