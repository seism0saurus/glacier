package de.seism0saurus.glacier.share.web;

import de.seism0saurus.glacier.share.application.CapacityExceededException;
import de.seism0saurus.glacier.share.application.ShareLinkNotFoundOrNotAuthorisedException;
import de.seism0saurus.glacier.share.application.ShareLinkService;
import de.seism0saurus.glacier.share.domain.ShareLink;
import de.seism0saurus.glacier.share.domain.ShareLinkId;
import de.seism0saurus.glacier.share.domain.ShareLinkStatus;
import de.seism0saurus.glacier.webservice.FallbackAuthGuard;
import de.seism0saurus.glacier.webservice.cache.FallbackRateLimiter;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration test for {@link ShareLinkController} using a {@link WebMvcTest} slice.
 *
 * <p>Scenarios covered:
 * <ul>
 *   <li>POST /rest/share-links — 201 Created with valid body; cap rejection → 429;
 *       rate-limit rejection → 429; missing wallId cookie → 401.</li>
 *   <li>DELETE /rest/share-links/{id} — 204 No Content on success; 404 for not-found;
 *       404 for wrong-wallId (anti-enumeration: both produce same status).</li>
 *   <li>GET /rest/share-links — 200 with list of active links for the sharer.</li>
 * </ul>
 *
 * <p>The {@code sharerWallId} must NEVER appear in any response body — asserted inline.
 */
@WebMvcTest(controllers = {ShareLinkController.class, ShareLinkControllerConfig.class})
@TestPropertySource(properties = {
        "glacier.domain=glacier.example.com",
        "glacier.fallback.ratelimit.perMinute=30",
        "glacier.fallback.ratelimit.perMinutePerIp=120",
        "glacier.ratelimit.eviction.intervalMs=600000",
        "glacier.share.create.perMinutePerWallId=5",
        "glacier.share.create.perMinutePerIp=20",
        "glacier.share.maxActivePerSharer=3",
        "glacier.share.maxActivePerIp=10",
        "glacier.share.maxViewersPerLink=100",
        "glacier.share.globalMax=10000"
})
class ShareLinkControllerIT {

    private static final String WALL_ID = "valid-wall-id-fixture-000000000000000";
    private static final Instant NOW = Instant.parse("2025-01-01T12:00:00Z");
    private static final Instant EXPIRES = NOW.plus(7, ChronoUnit.DAYS);

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ShareLinkService shareLinkService;

    @MockitoBean
    private FallbackAuthGuard authGuard;

    @MockitoBean
    private FallbackRateLimiter rateLimiter;

    // ---------------------------------------------------------------------------
    // POST /rest/share-links — happy path
    // ---------------------------------------------------------------------------

    @Test
    void postShareLinks_happyPath_returns201WithBody() throws Exception {
        authenticateAs(WALL_ID);
        allowRateLimit();

        ShareLinkId id = ShareLinkId.fromUrlPath("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA");
        ShareLink link = ShareLink.create(id, WALL_ID, NOW, java.time.Duration.ofDays(7));
        when(shareLinkService.create(eq(WALL_ID), any(), any(Instant.class))).thenReturn(link);

        mockMvc.perform(post("/rest/share-links")
                        .cookie(new Cookie("wallId", WALL_ID))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.shareLinkId").value("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"))
                .andExpect(jsonPath("$.expiresAt").isNotEmpty())
                .andExpect(jsonPath("$.readonlyUrl").isNotEmpty())
                // sharerWallId must NEVER appear in the response
                .andExpect(result -> {
                    String body = result.getResponse().getContentAsString();
                    org.assertj.core.api.Assertions.assertThat(body).doesNotContain(WALL_ID);
                });
    }

    // ---------------------------------------------------------------------------
    // POST /rest/share-links — missing wallId cookie → 401
    // ---------------------------------------------------------------------------

    @Test
    void postShareLinks_missingWallIdCookie_returns401() throws Exception {
        when(authGuard.authenticate(any(HttpServletRequest.class), any()))
                .thenReturn(new FallbackAuthGuard.AuthResult(false, null));

        mockMvc.perform(post("/rest/share-links")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());
    }

    // ---------------------------------------------------------------------------
    // POST /rest/share-links — cap exceeded → 429
    // ---------------------------------------------------------------------------

    @Test
    void postShareLinks_capacityExceeded_returns429() throws Exception {
        authenticateAs(WALL_ID);
        allowRateLimit();
        when(shareLinkService.create(any(), any(), any()))
                .thenThrow(new CapacityExceededException("sharer cap exceeded"));

        mockMvc.perform(post("/rest/share-links")
                        .cookie(new Cookie("wallId", WALL_ID))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isTooManyRequests());
    }

    // ---------------------------------------------------------------------------
    // POST /rest/share-links — rate limit exceeded → 429
    // ---------------------------------------------------------------------------

    @Test
    void postShareLinks_rateLimitExceeded_returns429() throws Exception {
        authenticateAs(WALL_ID);
        when(rateLimiter.check(any(), any()))
                .thenReturn(FallbackRateLimiter.RateLimitResult.rejected(30L));

        mockMvc.perform(post("/rest/share-links")
                        .cookie(new Cookie("wallId", WALL_ID))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isTooManyRequests());
    }

    // ---------------------------------------------------------------------------
    // DELETE /rest/share-links/{id} — happy path → 204
    // ---------------------------------------------------------------------------

    @Test
    void deleteShareLink_happyPath_returns204() throws Exception {
        authenticateAs(WALL_ID);
        String linkId = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";

        mockMvc.perform(delete("/rest/share-links/{id}", linkId)
                        .cookie(new Cookie("wallId", WALL_ID)))
                .andExpect(status().isNoContent());
    }

    // ---------------------------------------------------------------------------
    // DELETE /rest/share-links/{id} — not found → 404
    // ---------------------------------------------------------------------------

    @Test
    void deleteShareLink_notFound_returns404() throws Exception {
        authenticateAs(WALL_ID);
        String linkId = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
        doThrow(new ShareLinkNotFoundOrNotAuthorisedException("not found"))
                .when(shareLinkService).revoke(any(ShareLinkId.class), eq(WALL_ID), any(Instant.class));

        mockMvc.perform(delete("/rest/share-links/{id}", linkId)
                        .cookie(new Cookie("wallId", WALL_ID)))
                .andExpect(status().isNotFound());
    }

    // ---------------------------------------------------------------------------
    // DELETE /rest/share-links/{id} — wrong wallId → SAME 404 (anti-enumeration)
    // ---------------------------------------------------------------------------

    @Test
    void deleteShareLink_wrongWallId_returns404SameAsNotFound() throws Exception {
        authenticateAs(WALL_ID);
        String linkId = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
        doThrow(new ShareLinkNotFoundOrNotAuthorisedException("not authorised"))
                .when(shareLinkService).revoke(any(ShareLinkId.class), eq(WALL_ID), any(Instant.class));

        mockMvc.perform(delete("/rest/share-links/{id}", linkId)
                        .cookie(new Cookie("wallId", WALL_ID)))
                .andExpect(status().isNotFound());
    }

    // ---------------------------------------------------------------------------
    // GET /rest/share-links — sharer self-listing
    // ---------------------------------------------------------------------------

    @Test
    void getShareLinks_returnsActiveLinksForSharer() throws Exception {
        authenticateAs(WALL_ID);
        ShareLinkId id1 = ShareLinkId.fromUrlPath("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA");
        ShareLink link1 = ShareLink.create(id1, WALL_ID, NOW, java.time.Duration.ofDays(7));
        when(shareLinkService.listBySharer(eq(WALL_ID), any(Instant.class)))
                .thenReturn(List.of(link1));

        mockMvc.perform(get("/rest/share-links")
                        .cookie(new Cookie("wallId", WALL_ID))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].shareLinkId").value("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"))
                // sharerWallId must NEVER appear in the response
                .andExpect(result -> {
                    String body = result.getResponse().getContentAsString();
                    org.assertj.core.api.Assertions.assertThat(body).doesNotContain(WALL_ID);
                });
    }

    @Test
    void getShareLinks_missingWallIdCookie_returns401() throws Exception {
        when(authGuard.authenticate(any(HttpServletRequest.class), any()))
                .thenReturn(new FallbackAuthGuard.AuthResult(false, null));

        mockMvc.perform(get("/rest/share-links")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());
    }

    // ---------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------

    private void authenticateAs(final String wallId) {
        when(authGuard.authenticate(any(HttpServletRequest.class), any()))
                .thenReturn(new FallbackAuthGuard.AuthResult(true, wallId));
    }

    private void allowRateLimit() {
        when(rateLimiter.check(any(), any())).thenReturn(FallbackRateLimiter.RateLimitResult.allowed());
    }
}
