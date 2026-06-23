package de.seism0saurus.glacier.share.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Serves the Angular SPA shell ({@code index.html}) for the readonly share-view client
 * routes so that deep links resolve.
 *
 * <p>The share view is opened via a deep link — a viewer navigates directly to
 * {@code https://{share host}/share/{shareLinkId}} (and the SPA may route on to
 * {@code /share/{shareLinkId}/expired}). Spring Boot serves {@code index.html} only as the
 * welcome page at {@code /}; there is no generic SPA fallback, so without this forward the
 * deep link returns 404 and the share view is unreachable. Forwarding to
 * {@code /index.html} lets the Angular router bootstrap and resolve the route — the
 * {@code ShareLinkGuard} then validates the id against the catalog endpoint and redirects
 * to the expired page when the link is unknown/inactive.
 *
 * <p>Origin isolation is preserved: {@link ShareHostRouter} (order 1) already returns 404
 * for {@code /share/*} on the main wall host before this controller is reached, so these
 * mappings only ever serve on the dedicated share host. The CSP set by
 * {@link ShareSecurityHeadersFilter} for share routes permits the same-origin bundle
 * ({@code script-src 'self'}), so the forwarded shell loads its scripts normally.
 */
@Controller
public class ShareViewSpaForwardController {

    /** View name that forwards (server-internal dispatch) to the static SPA shell. */
    private static final String SPA_SHELL = "forward:/index.html";

    @GetMapping({"/share/{shareLinkId}", "/share/{shareLinkId}/expired"})
    public String forwardShareViewToSpaShell() {
        return SPA_SHELL;
    }
}
