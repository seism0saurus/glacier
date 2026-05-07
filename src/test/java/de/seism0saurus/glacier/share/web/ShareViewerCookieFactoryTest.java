package de.seism0saurus.glacier.share.web;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link ShareViewerCookieFactory}.
 *
 * <p>Security requirements: SR-SHARE-07 (cookie flags), Finding 8 (__Host- prefix requires Path=/).
 * References: OWASP A05 (Security Misconfiguration), RFC 6265bis §4.1.3.
 */
class ShareViewerCookieFactoryTest {

    private static final Instant EXPIRES_IN_7_DAYS = Instant.now().plusSeconds(7 * 24 * 3600);

    /**
     * In secure mode (glacier.cookie.secure=true), the cookie uses the {@code __Host-} prefix.
     * RFC 6265bis §4.1.3 mandates that {@code __Host-} cookies MUST have {@code Path=/}.
     *
     * <p>Finding 8: the previous implementation set {@code Path=/share} even in secure mode,
     * which violates the {@code __Host-} prefix invariant (browsers reject or ignore such cookies).
     */
    @Test
    void secureCookie_hasRootPath() {
        ShareViewerCookieFactory factory = new ShareViewerCookieFactory(true);
        MockHttpServletResponse response = new MockHttpServletResponse();

        factory.mintAndSet(response, EXPIRES_IN_7_DAYS);

        // The Set-Cookie header must contain Path=/
        String setCookieHeader = response.getHeader("Set-Cookie");
        assertThat(setCookieHeader).isNotNull();
        assertThat(setCookieHeader).contains("Path=/");
        // Must NOT have Path=/share in secure mode (violates __Host- RFC requirement)
        assertThat(setCookieHeader).doesNotContain("Path=/share");
    }

    /**
     * In secure mode the cookie name must use the {@code __Host-} prefix.
     */
    @Test
    void secureCookie_usesHostPrefix() {
        ShareViewerCookieFactory factory = new ShareViewerCookieFactory(true);
        MockHttpServletResponse response = new MockHttpServletResponse();

        factory.mintAndSet(response, EXPIRES_IN_7_DAYS);

        String setCookieHeader = response.getHeader("Set-Cookie");
        assertThat(setCookieHeader).isNotNull();
        assertThat(setCookieHeader).startsWith("__Host-shareViewerId=");
    }

    /**
     * In insecure mode (glacier.cookie.secure=false), the cookie name has no prefix
     * and Path=/share scoping is acceptable.
     */
    @Test
    void insecureCookie_hasSharePath() {
        ShareViewerCookieFactory factory = new ShareViewerCookieFactory(false);
        MockHttpServletResponse response = new MockHttpServletResponse();

        factory.mintAndSet(response, EXPIRES_IN_7_DAYS);

        String setCookieHeader = response.getHeader("Set-Cookie");
        assertThat(setCookieHeader).isNotNull();
        assertThat(setCookieHeader).startsWith("shareViewerId=");
        assertThat(setCookieHeader).contains("Path=/share");
    }

    /**
     * FU-R2: In secure mode exactly one {@code Set-Cookie} header must be emitted for the
     * {@code __Host-shareViewerId} cookie (I-CSRF-1 parity — single-emission invariant).
     *
     * <p>Uses {@code getHeaders(SET_COOKIE)} (plural) + filter-by-name + {@code hasSize(1)},
     * which is stronger than {@code getHeader(SET_COOKIE)} (singular) — the latter silently
     * returns only the first value even when two headers are emitted.
     */
    @Test
    void secureCookie_emitsExactlyOneSetCookieHeader() {
        ShareViewerCookieFactory factory = new ShareViewerCookieFactory(true);
        MockHttpServletResponse response = new MockHttpServletResponse();
        factory.mintAndSet(response, EXPIRES_IN_7_DAYS);

        List<String> viewerHeaders = response.getHeaders(HttpHeaders.SET_COOKIE)
                .stream()
                .filter(h -> h.startsWith("__Host-shareViewerId="))
                .toList();
        assertThat(viewerHeaders)
                .as("FU-R2 / I-CSRF-1: ShareViewerCookieFactory must emit exactly one Set-Cookie "
                        + "header for '__Host-shareViewerId' in secure mode")
                .hasSize(1);
    }

    /**
     * FU-R2: In insecure mode exactly one {@code Set-Cookie} header must be emitted for the
     * {@code shareViewerId} cookie (I-CSRF-1 parity — single-emission invariant).
     */
    @Test
    void insecureCookie_emitsExactlyOneSetCookieHeader() {
        ShareViewerCookieFactory factory = new ShareViewerCookieFactory(false);
        MockHttpServletResponse response = new MockHttpServletResponse();
        factory.mintAndSet(response, EXPIRES_IN_7_DAYS);

        List<String> viewerHeaders = response.getHeaders(HttpHeaders.SET_COOKIE)
                .stream()
                .filter(h -> h.startsWith("shareViewerId="))
                .toList();
        assertThat(viewerHeaders)
                .as("FU-R2 / I-CSRF-1: ShareViewerCookieFactory must emit exactly one Set-Cookie "
                        + "header for 'shareViewerId' in insecure mode")
                .hasSize(1);
    }

    /**
     * In secure mode the cookie must have the Secure flag and HttpOnly flag.
     */
    @Test
    void secureCookie_hasSecureAndHttpOnly() {
        ShareViewerCookieFactory factory = new ShareViewerCookieFactory(true);
        MockHttpServletResponse response = new MockHttpServletResponse();

        factory.mintAndSet(response, EXPIRES_IN_7_DAYS);

        String setCookieHeader = response.getHeader("Set-Cookie");
        assertThat(setCookieHeader).contains("Secure");
        assertThat(setCookieHeader).contains("HttpOnly");
        assertThat(setCookieHeader).contains("SameSite=Lax");
    }
}
