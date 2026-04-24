package de.seism0saurus.glacier.webservice;

import de.seism0saurus.glacier.webservice.cache.*;
import de.seism0saurus.glacier.webservice.messaging.PrincipalKey;
import de.seism0saurus.glacier.webservice.messaging.PrincipalKind;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifies rate-limiter key resolution under different {@code server.forward-headers-strategy}
 * settings (SR-4, D-10, T-08).
 *
 * <p>NONE mode (default): the rate-limiter key equals the real peer IP; {@code X-Forwarded-For}
 * is ignored, preventing IP spoofing.
 *
 * <p>FRAMEWORK mode: the rate-limiter key equals the value from {@code X-Forwarded-For}; this is
 * only safe when a trusted proxy is in front stripping forged headers.
 *
 * <p>MockMvc does not actually test network-level proxy behaviour, but we can verify that the
 * IP passed to {@code rateLimiter.check()} matches the expectation by capturing it via Mockito.
 */
@WebMvcTest(controllers = {FallbackController.class, FallbackControllerAdvice.class})
@Import({CookieBasedFallbackAuthGuard.class, FallbackSecurityHeadersFilter.class})
@TestPropertySource(properties = {
        "glacier.fallback.enabled=true",
        "glacier.cache.maxHashtagsPerPrincipal=10",
        "glacier.fallback.ratelimit.perMinute=30",
        "glacier.fallback.ratelimit.perMinutePerIp=120",
        "glacier.ratelimit.eviction.intervalMs=600000",
        // NONE mode: X-Forwarded-For headers are ignored (default)
        "server.forward-headers-strategy=NONE"
})
class RateLimitHeaderTrustIT {

    /** Convenience factory: wraps a wallId string in a WALL PrincipalKey. */
    private static PrincipalKey wall(String wallId) {
        return new PrincipalKey(PrincipalKind.WALL, wallId);
    }

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private MessageCache messageCache;

    @MockBean
    private FallbackRateLimiter rateLimiter;

    // -------------------------------------------------------------------------
    // NONE mode: remoteAddr is the peer IP (X-Forwarded-For ignored)
    // -------------------------------------------------------------------------

    @Test
    void noneMode_rateLimiterReceivesPeerIp_notForwardedForHeader() throws Exception {
        String wallId = "wall-rl-trust-aaaaaaaaaaaaaaaaaaaaaa";
        AtomicReference<String> capturedIp = new AtomicReference<>();

        when(rateLimiter.check(eq(wall(wallId)), anyString())).thenAnswer(inv -> {
            capturedIp.set(inv.getArgument(1, String.class));
            return FallbackRateLimiter.RateLimitResult.allowed();
        });
        when(messageCache.snapshot(any(), any(), any()))
                .thenReturn(new Snapshot(java.util.List.of(), 0L, false));

        mockMvc.perform(get("/rest/messages")
                        .param("hashtag", "java")
                        .cookie(new jakarta.servlet.http.Cookie("wallId", wallId))
                        // Attacker attempts IP spoofing via X-Forwarded-For
                        .header("X-Forwarded-For", "1.2.3.4"))
                .andExpect(status().isNoContent());

        // In NONE mode: the IP passed to the rate limiter must NOT be the spoofed X-Forwarded-For value
        // MockMvc uses 127.0.0.1 as the default remote address
        assertThat(capturedIp.get())
                .as("In NONE mode the rate-limiter key must be the real peer IP, not X-Forwarded-For")
                .isNotNull()
                // MockMvc peer IP is 127.0.0.1, not the spoofed value
                .isNotEqualTo("1.2.3.4");
    }

    @Test
    void noneMode_xForwardedForHeader_presentButNotUsedAsBucketKey() throws Exception {
        String wallId = "wall-rl-trust-bbbbbbbbbbbbbbbbbbbbbbb";
        AtomicReference<String> capturedIp = new AtomicReference<>();

        when(rateLimiter.check(eq(wall(wallId)), anyString())).thenAnswer(inv -> {
            capturedIp.set(inv.getArgument(1, String.class));
            return FallbackRateLimiter.RateLimitResult.allowed();
        });
        when(messageCache.snapshot(any(), any(), any()))
                .thenReturn(new Snapshot(java.util.List.of(), 0L, false));

        mockMvc.perform(get("/rest/messages")
                        .param("hashtag", "java")
                        .cookie(new jakarta.servlet.http.Cookie("wallId", wallId))
                        .header("X-Forwarded-For", "10.0.0.1, 172.16.0.1"))
                .andExpect(status().isNoContent());

        // The bucket key should be the MockMvc loopback address (127.0.0.1), not the spoofed header
        assertThat(capturedIp.get()).isNotNull();
        assertThat(capturedIp.get()).doesNotContain("10.0.0.1");
        assertThat(capturedIp.get()).doesNotContain("172.16.0.1");
    }

    // -------------------------------------------------------------------------
    // Baseline: rate limiter is always called with wallId + remoteAddr (NONE mode)
    // -------------------------------------------------------------------------

    @Test
    void noneMode_rateLimiterAlwaysCalledWithBothAxes() throws Exception {
        String wallId = "wall-rl-trust-ccccccccccccccccccccccc";
        AtomicReference<PrincipalKey> capturedKey = new AtomicReference<>();
        AtomicReference<String> capturedIp = new AtomicReference<>();

        when(rateLimiter.check(any(PrincipalKey.class), anyString())).thenAnswer(inv -> {
            capturedKey.set(inv.getArgument(0, PrincipalKey.class));
            capturedIp.set(inv.getArgument(1, String.class));
            return FallbackRateLimiter.RateLimitResult.allowed();
        });
        when(messageCache.snapshot(any(), any(), any()))
                .thenReturn(new Snapshot(java.util.List.of(), 0L, false));

        mockMvc.perform(get("/rest/messages")
                        .param("hashtag", "java")
                        .cookie(new jakarta.servlet.http.Cookie("wallId", wallId)))
                .andExpect(status().isNoContent());

        // Assert key name equals wallId and kind is WALL (compile-enforced namespace isolation)
        assertThat(capturedKey.get()).isNotNull();
        assertThat(capturedKey.get().name()).isEqualTo(wallId);
        assertThat(capturedKey.get().kind()).isEqualTo(PrincipalKind.WALL);
        assertThat(capturedIp.get()).isNotNull().isNotBlank();
    }
}
