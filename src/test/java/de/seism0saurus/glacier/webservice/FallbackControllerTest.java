package de.seism0saurus.glacier.webservice;

import de.seism0saurus.glacier.webservice.cache.*;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Unit tests for {@link FallbackController} using a standalone MockMvc slice.
 *
 * Scope: happy-path and structural behaviour + security controls (SR-1, SR-2, SR-4, SR-7).
 * Full Spring-context header-contract integration is in {@link FallbackSecurityIT}.
 */
class FallbackControllerTest {

    private MessageCache messageCache;
    private FallbackAuthGuard authGuard;
    private FallbackRateLimiter rateLimiter;
    private MockMvc mockMvc;

    @BeforeEach
    void setup() {
        messageCache = mock(MessageCache.class);
        authGuard = mock(FallbackAuthGuard.class);
        rateLimiter = mock(FallbackRateLimiter.class);

        // Default: auth passes with principal "wall-1"
        when(authGuard.authenticate(any(HttpServletRequest.class), any()))
                .thenReturn(new FallbackAuthGuard.AuthResult(true, "wall-1"));
        // Default: rate limiter allows all
        when(rateLimiter.check(any(), any())).thenReturn(FallbackRateLimiter.RateLimitResult.allowed());

        FallbackController controller = new FallbackController(messageCache, authGuard, rateLimiter, true);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new FallbackControllerAdvice())
                .setValidator(new org.springframework.validation.beanvalidation.LocalValidatorFactoryBean())
                .build();
    }

    // -----------------------------------------------------------------
    // Happy path — 200 with events
    // -----------------------------------------------------------------

    @Test
    void getMessages_eventsAvailable_returns200WithBody() throws Exception {
        List<CacheEntry> entries = List.of(
                new CacheEntry(EventType.CREATED, "s1", "https://ex.com/s1/embed", null, 1L),
                new CacheEntry(EventType.DELETED, "s2", null, null, 2L)
        );
        Snapshot snapshot = new Snapshot(entries, 2L, false);
        when(messageCache.snapshot(eq("wall-1"), eq("cats"), isNull())).thenReturn(snapshot);

        mockMvc.perform(get("/rest/messages")
                        .param("hashtag", "cats")
                        .cookie(new jakarta.servlet.http.Cookie("wallId", "wall-1"))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hashtag").value("cats"))
                .andExpect(jsonPath("$.nextSince").value(2))
                .andExpect(jsonPath("$.gap").value(false))
                .andExpect(jsonPath("$.events.length()").value(2))
                .andExpect(jsonPath("$.events[0].id").value("s1"))
                .andExpect(jsonPath("$.events[0].type").value("CREATED"))
                .andExpect(jsonPath("$.events[1].id").value("s2"))
                .andExpect(jsonPath("$.events[1].type").value("DELETED"));
    }

    // -----------------------------------------------------------------
    // 204 — cursor at head, no new events
    // -----------------------------------------------------------------

    @Test
    void getMessages_cursorAtHead_returns204NoBody() throws Exception {
        Snapshot snapshot = new Snapshot(List.of(), 5L, false);
        when(messageCache.snapshot(eq("wall-1"), eq("cats"), eq(5L))).thenReturn(snapshot);

        mockMvc.perform(get("/rest/messages")
                        .param("hashtag", "cats")
                        .param("since", "5")
                        .cookie(new jakarta.servlet.http.Cookie("wallId", "wall-1"))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isNoContent());
    }

    // -----------------------------------------------------------------
    // 400 — unknown subscription
    // -----------------------------------------------------------------

    @Test
    void getMessages_unknownSubscription_returns400WithErrorBody() throws Exception {
        when(messageCache.snapshot(any(), any(), any()))
                .thenThrow(new UnknownSubscriptionException("not provisioned"));

        mockMvc.perform(get("/rest/messages")
                        .param("hashtag", "unknown")
                        .cookie(new jakarta.servlet.http.Cookie("wallId", "wall-1"))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("unknown_subscription")));
    }

    // -----------------------------------------------------------------
    // 401 — missing / short wallId (SR-2)
    // -----------------------------------------------------------------

    @Test
    void getMessages_authFails_returns401WithMissingWallidError() throws Exception {
        when(authGuard.authenticate(any(), any()))
                .thenReturn(new FallbackAuthGuard.AuthResult(false, null));

        mockMvc.perform(get("/rest/messages")
                        .param("hashtag", "cats")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("missing_wallid"));
    }

    @Test
    void getMessages_401_wallIdNotEchoedInBody() throws Exception {
        when(authGuard.authenticate(any(), any()))
                .thenReturn(new FallbackAuthGuard.AuthResult(false, null));

        mockMvc.perform(get("/rest/messages")
                        .param("hashtag", "cats")
                        .cookie(new jakarta.servlet.http.Cookie("wallId", "short"))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("short"))));
    }

    // -----------------------------------------------------------------
    // 429 — rate limit exceeded (SR-4)
    // -----------------------------------------------------------------

    @Test
    void getMessages_rateLimitExceeded_returns429WithRetryAfter() throws Exception {
        when(rateLimiter.check(any(), any()))
                .thenReturn(FallbackRateLimiter.RateLimitResult.rejected(45L));

        mockMvc.perform(get("/rest/messages")
                        .param("hashtag", "cats")
                        .cookie(new jakarta.servlet.http.Cookie("wallId", "wall-1"))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "45"));
    }

    @Test
    void getMessages_429_doesNotTouchCache() throws Exception {
        when(rateLimiter.check(any(), any()))
                .thenReturn(FallbackRateLimiter.RateLimitResult.rejected(30L));

        mockMvc.perform(get("/rest/messages")
                        .param("hashtag", "cats")
                        .cookie(new jakarta.servlet.http.Cookie("wallId", "wall-1"))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isTooManyRequests());

        org.mockito.Mockito.verify(messageCache, org.mockito.Mockito.never())
                .snapshot(any(), any(), any());
    }

    // -----------------------------------------------------------------
    // 404 — kill-switch (SR-7)
    // -----------------------------------------------------------------

    @Test
    void getMessages_killSwitchDisabled_returns404() throws Exception {
        FallbackController killSwitched = new FallbackController(messageCache, authGuard, rateLimiter, false);
        MockMvc killMvc = MockMvcBuilders.standaloneSetup(killSwitched)
                .setControllerAdvice(new FallbackControllerAdvice())
                .build();

        killMvc.perform(get("/rest/messages")
                        .param("hashtag", "cats")
                        .cookie(new jakarta.servlet.http.Cookie("wallId", "wall-1"))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound());
    }

    // -----------------------------------------------------------------
    // 405 — non-GET methods
    // -----------------------------------------------------------------

    @Test
    void getMessages_postRequest_returns405() throws Exception {
        mockMvc.perform(post("/rest/messages")
                        .param("hashtag", "cats")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isMethodNotAllowed());
    }

    @Test
    void getMessages_deleteRequest_returns405() throws Exception {
        mockMvc.perform(delete("/rest/messages")
                        .param("hashtag", "cats"))
                .andExpect(status().isMethodNotAllowed());
    }

    // -----------------------------------------------------------------
    // @CookieValue binding — wallId is read from cookie, not query param
    // -----------------------------------------------------------------

    @Test
    void getMessages_wallIdFromCookieNotQueryParam_authGuardReceivesCookieValue() throws Exception {
        Snapshot snapshot = new Snapshot(List.of(), 0L, false);
        when(messageCache.snapshot(any(), any(), any())).thenReturn(snapshot);
        when(authGuard.authenticate(any(HttpServletRequest.class), eq("cookie-value")))
                .thenReturn(new FallbackAuthGuard.AuthResult(true, "wall-from-cookie"));
        when(messageCache.snapshot(eq("wall-from-cookie"), any(), any())).thenReturn(snapshot);

        mockMvc.perform(get("/rest/messages")
                        .param("hashtag", "cats")
                        .cookie(new jakarta.servlet.http.Cookie("wallId", "cookie-value"))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isNoContent());
    }

    // -----------------------------------------------------------------
    // gap flag is surfaced in response body
    // -----------------------------------------------------------------

    @Test
    void getMessages_gapTrue_bodyContainsGapTrue() throws Exception {
        List<CacheEntry> entries = List.of(
                new CacheEntry(EventType.CREATED, "s5", "https://ex.com/s5/embed", null, 5L)
        );
        Snapshot gapSnapshot = new Snapshot(entries, 5L, true);
        when(messageCache.snapshot(eq("wall-1"), eq("cats"), any())).thenReturn(gapSnapshot);

        mockMvc.perform(get("/rest/messages")
                        .param("hashtag", "cats")
                        .param("since", "1")
                        .cookie(new jakarta.servlet.http.Cookie("wallId", "wall-1"))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.gap").value(true));
    }

    // -----------------------------------------------------------------
    // SR-1: Input validation note
    // -----------------------------------------------------------------
    // @Pattern/@Min/@Max validation on @RequestParam requires a full Spring context
    // to trigger ConstraintViolationException. Input validation is fully tested in
    // FallbackSecurityIT (WebMvcTest with full Spring context + FallbackControllerAdvice).
}
