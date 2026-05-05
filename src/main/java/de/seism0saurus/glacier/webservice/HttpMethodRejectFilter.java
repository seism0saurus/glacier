package de.seism0saurus.glacier.webservice;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Locale;
import java.util.Set;

/**
 * Servlet filter that rejects HTTP methods that must not be served by this application.
 *
 * <p><b>Security controls (SR-NEW-10, C8, C1)</b>:
 * <ul>
 *   <li>{@code TRACE} — responds with 405. TRACE echoes request headers back to the caller
 *       (RFC 9110 §9.3.8), which would expose the {@code wallId} identity cookie and any
 *       {@code Authorization} header over-the-wire. Even without Spring Security, TRACE
 *       must be blocked at the application layer as a defence-in-depth control (CWE-16).
 *       Note: the reverse proxy (Traefik) also blocks TRACE at the edge, but application-layer
 *       rejection ensures the control holds even when the proxy is misconfigured or bypassed.</li>
 *   <li>{@code TRACK} — responds with 405. TRACK is a Microsoft-specific variant of TRACE
 *       (rejected on the same grounds).</li>
 * </ul>
 *
 * <p>This filter does NOT block other methods (PUT, PATCH, DELETE etc.) on GET-only endpoints —
 * Spring MVC already returns 405 for those via the {@code RequestMappingHandlerMapping}
 * registered routes. This filter handles only methods that would otherwise be processed
 * positively (TRACE) or passed through to a generic handler.
 *
 * <p>The filter runs at {@link Ordered#HIGHEST_PRECEDENCE} so that TRACE/TRACK requests are
 * rejected before CORS, auth, or any other filter logic executes — preventing any potential
 * information leakage from those layers.
 *
 * <p>References:
 * <ul>
 *   <li>ASVS V14.5.1 (L1): only required HTTP methods must be enabled</li>
 *   <li>WSTG-CONF-06: HTTP methods testing</li>
 *   <li>OWASP Proactive Control C8: Implement Digital Identity (Browser Security)</li>
 *   <li>CWE-16: Configuration</li>
 * </ul>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class HttpMethodRejectFilter implements Filter {

    /**
     * HTTP methods that must never be served by this application.
     *
     * <p>TRACE and TRACK are rejected because they echo request headers (including cookies)
     * back to the caller, which constitutes information disclosure.
     */
    private static final Set<String> REJECTED_METHODS = Set.of("TRACE", "TRACK");

    @Override
    public void doFilter(
            final ServletRequest request,
            final ServletResponse response,
            final FilterChain chain) throws IOException, ServletException {

        if (request instanceof HttpServletRequest httpRequest
                && response instanceof HttpServletResponse httpResponse) {

            String method = httpRequest.getMethod();
            // C8/CWE-178: Locale.ROOT ensures method token comparison is locale-independent.
            // Turkish locale's dotless-i does not affect TRACE/TRACK (no 'i') but
            // Locale.ROOT makes the intent explicit and guards against future method tokens.
            if (method != null && REJECTED_METHODS.contains(method.toUpperCase(Locale.ROOT))) {
                // C8 / ASVS V14.5.1: reject TRACE and TRACK with 405 Method Not Allowed.
                // Do not call chain.doFilter — return immediately to prevent any handler
                // or filter downstream from processing the request.
                httpResponse.setStatus(HttpServletResponse.SC_METHOD_NOT_ALLOWED);
                httpResponse.setHeader("Allow", "GET, POST, DELETE, OPTIONS, HEAD");
                return;
            }
        }

        chain.doFilter(request, response);
    }
}
