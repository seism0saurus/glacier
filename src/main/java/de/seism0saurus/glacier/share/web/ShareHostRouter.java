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
import java.util.Locale;
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

    /**
     * The CSRF-token endpoint. Unlike the other share paths it is NOT viewer content
     * and must be reachable on BOTH hosts: the sharer (on the main wall host) needs it
     * to obtain the {@code __Host-shareCsrf} token before {@code POST}/{@code DELETE
     * /rest/share-links} (which are served on the main host). Because {@code __Host-}
     * cookies are host-scoped, the token has to be issued on the same origin where the
     * sharer creates/revokes links — so it is exempt from the main-host block below.
     * It carries no share-link identifiers, so allowing it does not weaken the
     * anti-enumeration guarantee of ADR-SHARE-09.
     */
    private static final String CSRF_PATH = "/rest/share-csrf";

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

        if (isShareHost && !isSharePath && !isStaticAsset(path)) {
            // Share host trying to access a main-wall path — reject (origin isolation).
            // Static assets (the Angular bundle: *.js / *.css / fonts / images / *.map,
            // /assets/**) are exempt: the readonly SPA shell loads them root-relative
            // (<base href="/">), and they carry no wall data — blocking them would make
            // the share view unable to boot. The sensitive main-wall endpoints
            // (/rest/wall-id, /rest/messages, /websocket, /topic/*, and the wall HTML
            // routes) have no file extension, so they remain blocked here.
            log.debug("ShareHostRouter: share-host accessing non-share path; sending 404. host={} path={}", host, path);
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }

        if (isMainHost && isSharePath && !isCsrfPath(path)) {
            // Main host trying to access share path — reject (anti-enumeration: 404, not 302).
            // The CSRF-token endpoint is exempt: the sharer needs it on the main origin to
            // create/revoke links (see CSRF_PATH javadoc).
            log.debug("ShareHostRouter: main-host accessing share path; sending 404. host={} path={}", host, path);
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }

        // localhost / unknown hosts: allow through (supports dev mode + tests)
        filterChain.doFilter(request, response);
    }

    private boolean isCsrfPath(String path) {
        return path != null && (path.equals(CSRF_PATH) || path.startsWith(CSRF_PATH + "?"));
    }

    /**
     * Heuristic for static front-end assets (the Angular bundle): the last path segment
     * carries a file extension (contains a {@code .}). Application/data routes and
     * endpoints — {@code /}, {@code /share/{id}}, {@code /rest/wall-id}, {@code /websocket},
     * {@code /topic/...} — have no dotted final segment (share-link ids are base64url, no
     * dots), so this cleanly separates servable bundle assets from sensitive endpoints.
     */
    private boolean isStaticAsset(String path) {
        if (path == null || path.isEmpty()) {
            return false;
        }
        int q = path.indexOf('?');
        String p = (q >= 0) ? path.substring(0, q) : path;
        int lastSlash = p.lastIndexOf('/');
        String lastSegment = (lastSlash >= 0) ? p.substring(lastSlash + 1) : p;
        return lastSegment.indexOf('.') >= 0;
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
        return serverName != null ? serverName.toLowerCase(Locale.ROOT) : "";
    }
}
