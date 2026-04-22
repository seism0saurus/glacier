package de.seism0saurus.glacier.share.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Generates a per-response CSP nonce and stores it in the request attribute.
 *
 * <p>The nonce is consumed by {@link ShareSecurityHeadersFilter} to inject into
 * {@code script-src 'nonce-{n}'} and by SPA index transformers to inject into
 * {@code <meta name="csp-nonce">}.
 *
 * <p>Security: SR-SHARE-11 (nonce-based CSP). References: OWASP CSP cheat sheet.
 */
@Component
@Order(5) // run before ShareSecurityHeadersFilter (order=10)
public class CspNonceFilter extends OncePerRequestFilter {

    /** Request attribute key where the nonce is stored. */
    public static final String NONCE_ATTRIBUTE = "csp-nonce";

    private static final int NONCE_BYTES = 16; // 128 bits is sufficient for a nonce
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

    @Override
    protected void doFilterInternal(
            @jakarta.annotation.Nonnull HttpServletRequest request,
            @jakarta.annotation.Nonnull HttpServletResponse response,
            @jakarta.annotation.Nonnull FilterChain filterChain)
            throws ServletException, IOException {

        byte[] bytes = new byte[NONCE_BYTES];
        SECURE_RANDOM.nextBytes(bytes);
        String nonce = ENCODER.encodeToString(bytes);
        request.setAttribute(NONCE_ATTRIBUTE, nonce);

        filterChain.doFilter(request, response);
    }
}
