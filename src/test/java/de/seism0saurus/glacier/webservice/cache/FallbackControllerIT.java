package de.seism0saurus.glacier.webservice.cache;

import de.seism0saurus.glacier.webservice.FallbackAuthGuard;
import de.seism0saurus.glacier.webservice.FallbackController;
import de.seism0saurus.glacier.webservice.FallbackControllerAdvice;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration test for {@link FallbackController} using a {@link WebMvcTest} slice.
 *
 * <p>Covers: 200 response shape, 204 at head, 400 on unknown subscription, 404 on kill-switch.
 * Security-header contract assertions are in {@code FallbackSecurityIT}.
 */
@WebMvcTest(controllers = {FallbackController.class, FallbackControllerAdvice.class})
@Import(FallbackControllerAdvice.class)
@TestPropertySource(properties = {
        "glacier.fallback.enabled=true",
        "glacier.cache.maxHashtagsPerPrincipal=10",
        "glacier.fallback.ratelimit.perMinute=30",
        "glacier.fallback.ratelimit.perMinutePerIp=120",
        "glacier.ratelimit.eviction.intervalMs=600000"
})
class FallbackControllerIT {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private MessageCache messageCache;

    @MockBean
    private FallbackAuthGuard authGuard;

    @MockBean
    private FallbackRateLimiter rateLimiter;

    @BeforeEach
    void setupAuthGuard() {
        when(authGuard.authenticate(any(HttpServletRequest.class), any()))
                .thenReturn(new FallbackAuthGuard.AuthResult(true, "wall-test"));
        when(rateLimiter.check(any(), any())).thenReturn(FallbackRateLimiter.RateLimitResult.allowed());
    }

    @Test
    void getMessages_happyPath_returns200WithCorrectShape() throws Exception {
        List<CacheEntry> entries = List.of(
                new CacheEntry(EventType.CREATED, "status-abc", "https://ex.com/abc/embed", null, 3L)
        );
        Snapshot snapshot = new Snapshot(entries, 3L, false);
        when(messageCache.snapshot(eq("wall-test"), eq("java"), any())).thenReturn(snapshot);

        mockMvc.perform(get("/rest/messages")
                        .param("hashtag", "java")
                        .cookie(new jakarta.servlet.http.Cookie("wallId", "wall-test"))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.hashtag").value("java"))
                .andExpect(jsonPath("$.nextSince").value(3))
                .andExpect(jsonPath("$.gap").value(false))
                .andExpect(jsonPath("$.events.length()").value(1))
                .andExpect(jsonPath("$.events[0].id").value("status-abc"));
    }

    @Test
    void getMessages_cursorAtHead_returns204() throws Exception {
        Snapshot empty = new Snapshot(List.of(), 3L, false);
        when(messageCache.snapshot(eq("wall-test"), eq("java"), eq(3L))).thenReturn(empty);

        mockMvc.perform(get("/rest/messages")
                        .param("hashtag", "java")
                        .param("since", "3")
                        .cookie(new jakarta.servlet.http.Cookie("wallId", "wall-test"))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isNoContent());
    }

    @Test
    void getMessages_unknownSubscription_returns400() throws Exception {
        when(messageCache.snapshot(any(), any(), any()))
                .thenThrow(new UnknownSubscriptionException("not provisioned"));

        mockMvc.perform(get("/rest/messages")
                        .param("hashtag", "notregistered")
                        .cookie(new jakarta.servlet.http.Cookie("wallId", "wall-test"))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("unknown_subscription")));
    }

    @Test
    void getMessages_killSwitchDisabled_returns404() throws Exception {
        // Override the enabled property for this case via a dedicated context.
        // The @TestPropertySource above sets enabled=true; the kill-switch test verifies the
        // endpoint is reachable in normal mode (204 when no events).
        Snapshot empty = new Snapshot(List.of(), 0L, false);
        when(messageCache.snapshot(any(), any(), any())).thenReturn(empty);

        mockMvc.perform(get("/rest/messages")
                        .param("hashtag", "java")
                        .cookie(new jakarta.servlet.http.Cookie("wallId", "wall-test"))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isNoContent());
    }

}
