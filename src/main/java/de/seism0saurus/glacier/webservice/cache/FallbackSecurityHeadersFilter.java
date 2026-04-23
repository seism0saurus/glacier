package de.seism0saurus.glacier.webservice.cache;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Servlet filter that adds security headers to every response from {@code /rest/messages}.
 *
 * <p>Security controls (D-08, ADR-06, OWASP A05 Security Misconfiguration, TSS-WEB):
 * <ul>
 *   <li>{@code Cache-Control: no-store} — prevents shared-proxy caching of user-specific data (T-17)</li>
 *   <li>{@code Pragma: no-cache}        — HTTP/1.0 compat; belt-and-suspenders for legacy proxies</li>
 *   <li>{@code Vary: Cookie}            — tells caches this response varies by cookie (T-17)</li>
 *   <li>{@code X-Content-Type-Options: nosniff} — prevents MIME sniffing (OWASP A05)</li>
 *   <li>{@code Content-Security-Policy: default-src 'none'} — defense-in-depth against content
 *       injection; the API endpoint returns JSON, not HTML, so no resources are needed (T-18)</li>
 * </ul>
 *
 * <p>Scope: this filter is path-guarded to {@code /rest/messages} only.  It intentionally does
 * NOT decorate other {@code /rest/*} paths such as {@code /rest/wall-id} (which has its own
 * cookie-emission semantics) or the WebSocket upgrade path.
 *
 * <p>Placement: {@link Order#HIGHEST_PRECEDENCE} ensures these headers are set before any
 * exception handler, error page, or other filter has a chance to alter the response — including
 * CORS preflight 204 and Spring's 405 MethodNotAllowed.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class FallbackSecurityHeadersFilter implements Filter {

    /** The single path this filter covers (ADR-06). */
    private static final String GUARDED_PATH = "/rest/messages";

    @Override
    public void doFilter(
            final ServletRequest request,
            final ServletResponse response,
            final FilterChain chain) throws IOException, ServletException {

        // Path guard: only decorate /rest/messages responses
        if (request instanceof jakarta.servlet.http.HttpServletRequest httpRequest
                && GUARDED_PATH.equals(httpRequest.getRequestURI())
                && response instanceof HttpServletResponse httpResponse) {

            // OWASP A05 / BSI TSS-WEB: no caching of session-sensitive data
            httpResponse.setHeader("Cache-Control", "no-store");
            httpResponse.setHeader("Pragma", "no-cache");

            // T-17: force downstream caches to vary by Cookie header
            httpResponse.setHeader("Vary", "Cookie");

            // OWASP A05: prevent MIME sniffing
            httpResponse.setHeader("X-Content-Type-Options", "nosniff");

            // Defense-in-depth against content injection (T-18, ADR-06)
            httpResponse.setHeader("Content-Security-Policy", "default-src 'none'");
        }

        chain.doFilter(request, response);
    }
}
