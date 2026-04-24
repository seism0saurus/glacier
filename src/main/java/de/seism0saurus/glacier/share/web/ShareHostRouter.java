package de.seism0saurus.glacier.share.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Set;

/**
 * Enforces dedicated-origin routing for the share-link feature (ADR-SHARE-09).
 *
 * <p>Rules:
 * <ul>
 *   <li>Requests to {@code share.${glacier.domain}} may only access share paths
 *       ({@code /share/*}, {@code /rest/share/*}, {@code /share-view-ws},
 *       {@code /rest/share-csrf}). Non-share paths on the share host return 404.</li>
 *   <li>Requests to the main {@code ${glacier.domain}} host may NOT access share paths;
 *       they return 404 (anti-enumeration: not 301/302, which would leak origin).</li>
 * </ul>
 *
 * <p>This filter runs at {@code @Order(1)} — before all other filters including
 * {@link ShareSecurityHeadersFilter} and {@link CspNonceFilter} — so misdirected
 * requests are rejected before any processing occurs.
 *
 * <p>Security: ADR-SHARE-09 (dedicated origin), user resolution 2026-04-22 option A.
 * Browser-enforced isolation via origin separation strictly dominates code-enforced isolation.
 * NIST SP 800-53 SC-7 (boundary protection).
 */
@Component
@Order(1)
public class ShareHostRouter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ShareHostRouter.class);

    /** Path prefixes that belong exclusively to the share-link feature. */
    private static final Set<String> SHARE_PATHS = Set.of(
            "/share", "/rest/share", "/share-view-ws", "/rest/share-csrf"
    );

    private final String mainHost;
    private final String shareHost;

    public ShareHostRouter(
            @Value("${glacier.domain}") final String glacierDomain,
            @Value("${glacier.share.host:#{null}}") final String configuredShareHost) {
        this.mainHost = glacierDomain;
        // Derive share host from config; fall back to share.{glacierDomain} if not set.
        // The devops-infra-engineer wires GLACIER_SHARE_HOST into docker-compose.
        this.shareHost = configuredShareHost != null && !configuredShareHost.isBlank()
                ? configuredShareHost
                : "share." + glacierDomain;
    }

    @Override
    protected void doFilterInternal(
            final HttpServletRequest request,
            final HttpServletResponse response,
            final FilterChain filterChain) throws ServletException, IOException {

        String host = extractHost(request);
        String path = request.getRequestURI();
        boolean isSharePath = isSharePath(path);

        boolean isShareHost = shareHost.equalsIgnoreCase(host);
        boolean isMainHost = mainHost.equalsIgnoreCase(host);

        if (isShareHost && !isSharePath) {
            // Share host trying to access main-wall path — reject (origin isolation)
            log.debug("ShareHostRouter: share-host accessing non-share path; sending 404. host={} path={}", host, path);
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }

        if (isMainHost && isSharePath) {
            // Main host trying to access share path — reject (anti-enumeration: 404, not 302)
            log.debug("ShareHostRouter: main-host accessing share path; sending 404. host={} path={}", host, path);
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }

        // localhost / unknown hosts: allow through (supports dev mode + tests)
        filterChain.doFilter(request, response);
    }

    private boolean isSharePath(String path) {
        if (path == null) return false;
        for (String prefix : SHARE_PATHS) {
            if (path.equals(prefix) || path.startsWith(prefix + "/") || path.startsWith(prefix + "?")) {
                return true;
            }
        }
        return false;
    }

    private String extractHost(HttpServletRequest request) {
        String serverName = request.getServerName();
        return serverName != null ? serverName.toLowerCase() : "";
    }
}
