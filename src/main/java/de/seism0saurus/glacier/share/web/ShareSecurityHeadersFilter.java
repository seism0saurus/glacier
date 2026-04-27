package de.seism0saurus.glacier.share.web;

import jakarta.annotation.PostConstruct;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.regex.Pattern;

/**
 * Security headers filter for all {@code /share/*} and {@code /rest/share/*} routes.
 *
 * <p>Applies an isolation-maximizing CSP, COOP, COEP, CORP, HSTS (in secure mode),
 * and other security headers required by the share-link feature's threat model.
 *
 * <p>The CSP is tighter than the main wall's because the readonly view:
 * <ul>
 *   <li>Uses only Angular interpolation (no {@code [innerHTML]}, no iframes)</li>
 *   <li>Images are served through the signed img-proxy (so {@code img-src 'self' data:} only)</li>
 *   <li>All scripts/styles loaded from {@code 'self'} only (no CDN, no inline)</li>
 * </ul>
 *
 * <p>The nonce for {@code script-src} is injected by {@link CspNonceFilter} (ordered before this).
 *
 * <p>Security: SR-SHARE-11, SR-SHARE-15 (CSP Trusted Types). References:
 * OWASP A05 (Security Misconfiguration), BSI TSS-WEB 5.2,
 * {@code spring-security-hardening} skill CSP section.
 */
@Component
@Order(10) // run before application logic but after nonce filter
public class ShareSecurityHeadersFilter extends OncePerRequestFilter {

    /**
     * Safe hostname pattern — allows hostnames like {@code glacier.events},
     * {@code share.glacier.events}, {@code localhost:8080}.
     *
     * <p>Rejects any value that could inject extra directives into the CSP header
     * (e.g. {@code evil.com; default-src *}).  Validated at startup via
     * {@link #validateDomains()} (F-3, OWASP A05 Security Misconfiguration).
     */
    // OWASP A05 / F-3: reject operator misconfigurations before any request is served
    static final Pattern SAFE_HOSTNAME_PATTERN = Pattern.compile(
            "^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)*(:[0-9]{1,5})?$"
    );

    private static final String SHARE_PATH_PREFIX = "/share";
    private static final String REST_SHARE_PATH_PREFIX = "/rest/share";
    private static final String SHARE_CSRF_PATH = "/rest/share-csrf";

    /**
     * The main glacier domain (e.g. {@code glacier.events}).
     * Used to build the explicit {@code wss://{glacierDomain}} connect-src value (ADR-TEST-03).
     */
    private final String glacierDomain;

    /**
     * The share subdomain host (e.g. {@code share.glacier.events}).
     * Used to build the explicit {@code wss://{shareHost}} connect-src value (ADR-TEST-03).
     */
    private final String shareHost;

    /**
     * Constructs the filter with the domain values needed for the explicit-host connect-src.
     *
     * @param glacierDomain the main glacier domain from {@code glacier.domain}
     * @param shareHost     the share subdomain from {@code glacier.share.host}
     */
    public ShareSecurityHeadersFilter(
            @Value("${glacier.domain}") String glacierDomain,
            @Value("${glacier.share.host}") String shareHost) {
        this.glacierDomain = glacierDomain;
        this.shareHost = shareHost;
    }

    /**
     * Validates {@code glacier.domain} and {@code glacier.share.host} at startup.
     *
     * <p>Rejects any value that does not match the safe-hostname pattern, preventing
     * CSP header injection via operator misconfiguration.  Spring calls this method
     * automatically after dependency injection (before the first request is served).
     *
     * <p>In unit tests, call this method explicitly after constructing the filter,
     * since Spring's lifecycle hooks are not active in plain-unit-test contexts.
     *
     * <p>Security: F-3, OWASP A05 (Security Misconfiguration) — a mis-configured
     * domain could inject extra CSP directives, weakening the policy for all share routes.
     *
     * @throws IllegalStateException if either domain value is not a valid hostname
     */
    @PostConstruct
    void validateDomains() {
        if (!SAFE_HOSTNAME_PATTERN.matcher(glacierDomain).matches()) {
            throw new IllegalStateException(
                    "glacier.domain is not a valid hostname: " + glacierDomain
                            + ". Must match pattern: " + SAFE_HOSTNAME_PATTERN.pattern());
        }
        if (!SAFE_HOSTNAME_PATTERN.matcher(shareHost).matches()) {
            throw new IllegalStateException(
                    "glacier.share.host is not a valid hostname: " + shareHost
                            + ". Must match pattern: " + SAFE_HOSTNAME_PATTERN.pattern());
        }
    }

    @Override
    protected void doFilterInternal(
            @jakarta.annotation.Nonnull HttpServletRequest request,
            @jakarta.annotation.Nonnull HttpServletResponse response,
            @jakarta.annotation.Nonnull FilterChain filterChain)
            throws ServletException, IOException {

        String path = request.getRequestURI();
        if (isSharePath(path)) {
            applyShareSecurityHeaders(request, response);
        }

        filterChain.doFilter(request, response);
    }

    private boolean isSharePath(String path) {
        return path.startsWith(SHARE_PATH_PREFIX)
                || path.startsWith(REST_SHARE_PATH_PREFIX)
                || path.startsWith(SHARE_CSRF_PATH)
                || "/share-view-ws".equals(path);
    }

    /**
     * Applies the full share-route security header set.
     *
     * <p>CSP breakdown (SR-SHARE-11):
     * <ul>
     *   <li>{@code default-src 'none'} — deny everything by default</li>
     *   <li>{@code script-src 'self' 'nonce-{n}'} — Angular bundle from self + nonce for inline</li>
     *   <li>{@code style-src 'self' 'unsafe-inline'} — Angular Material requires inline styles</li>
     *   <li>{@code img-src 'self' data:} — proxy-signed images from self + data URIs for QR</li>
     *   <li>{@code connect-src 'self' wss://{shareHost} wss://{glacierDomain}} — STOMP WebSocket (explicit hosts, ADR-TEST-03)</li>
     *   <li>{@code font-src 'self'} — Angular Material fonts</li>
     *   <li>{@code frame-ancestors 'none'} — no embedding (XFO: DENY backup)</li>
     *   <li>{@code base-uri 'none'} — prevent base-tag injection</li>
     *   <li>{@code form-action 'none'} — no form submissions from share view</li>
     *   <li>{@code require-trusted-types-for 'script'} — Trusted Types for further XSS hardening (SR-SHARE-15)</li>
     * </ul>
     */
    private void applyShareSecurityHeaders(HttpServletRequest request, HttpServletResponse response) {
        // Retrieve nonce if CspNonceFilter ran before this
        String nonce = (String) request.getAttribute(CspNonceFilter.NONCE_ATTRIBUTE);
        String nonceDirective = (nonce != null) ? " 'nonce-" + nonce + "'" : "";

        // OWASP A05 — tight CSP for share routes
        response.setHeader("Content-Security-Policy",
                "default-src 'none'; "
                        + "script-src 'self'" + nonceDirective + "; "
                        + "style-src 'self' 'unsafe-inline'; "
                        + "img-src 'self' data:; "
                        + "connect-src 'self' wss://" + shareHost + " wss://" + glacierDomain + "; "
                        + "font-src 'self'; "
                        + "frame-ancestors 'none'; "
                        + "base-uri 'none'; "
                        + "form-action 'none'; "
                        + "require-trusted-types-for 'script'"
        );

        // XFO legacy (CSP frame-ancestors is primary)
        response.setHeader("X-Frame-Options", "DENY");

        // Type sniffing prevention (OWASP A05)
        response.setHeader("X-Content-Type-Options", "nosniff");

        // No referrer leakage (SR-SHARE-14)
        response.setHeader("Referrer-Policy", "no-referrer");

        // Permissions Policy — minimal footprint for readonly view
        response.setHeader("Permissions-Policy",
                "camera=(), microphone=(), geolocation=(), payment=(), "
                        + "clipboard-read=(), clipboard-write=(self), "
                        + "display-capture=()");

        // Cross-Origin isolation headers (COOP/COEP/CORP) — SR-SHARE-15
        response.setHeader("Cross-Origin-Opener-Policy", "same-origin");
        response.setHeader("Cross-Origin-Embedder-Policy", "require-corp");
        response.setHeader("Cross-Origin-Resource-Policy", "same-origin");

        // HSTS is applied at the transport level (Traefik in prod).
        // We also set it here for direct-access scenarios.
        // The devops lane owns the Traefik config; this is defence-in-depth.
        // Note: in insecure mode (glacier.cookie.secure=false) HSTS would be ignored by browsers
        // on plain HTTP, but harmless to set.
        response.setHeader("Strict-Transport-Security", "max-age=31536000; includeSubDomains");
    }
}
