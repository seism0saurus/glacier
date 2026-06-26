package de.seism0saurus.glacier.share.web;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import jakarta.servlet.ServletException;
import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link ShareHostRouter}.
 *
 * <p>Verifies dedicated-origin routing enforcement (ADR-SHARE-09).
 * Browser-enforced origin isolation requires that:
 * <ul>
 *   <li>Share host rejects main-wall paths with 404</li>
 *   <li>Main host rejects share paths with 404</li>
 *   <li>Localhost passes through (dev / test support)</li>
 * </ul>
 */
class ShareHostRouterTest {

    private static final String MAIN_HOST = "glacier.example.com";
    private static final String SHARE_HOST = "share.glacier.example.com";

    private final ShareHostRouter router = new ShareHostRouter(MAIN_HOST, SHARE_HOST);

    // -----------------------------------------------------------------------
    // Share host — only share paths allowed
    // -----------------------------------------------------------------------

    @Test
    void shareHost_sharePathAllowed() throws Exception {
        MockHttpServletRequest request = request(SHARE_HOST, "/rest/share/sv_AAAA/catalog");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        router.doFilterInternal(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200); // chain passed through
        assertThat(chain.getRequest()).isNotNull(); // filter chain was invoked
    }

    @Test
    void shareHost_shareViewWsAllowed() throws Exception {
        MockHttpServletRequest request = request(SHARE_HOST, "/share-view-ws");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        router.doFilterInternal(request, response, chain);

        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    void shareHost_mainWallPath_returns404() throws Exception {
        MockHttpServletRequest request = request(SHARE_HOST, "/rest/wall-id");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        router.doFilterInternal(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(404);
        assertThat(chain.getRequest()).isNull(); // chain NOT invoked
    }

    @Test
    void shareHost_websocketPath_returns404() throws Exception {
        MockHttpServletRequest request = request(SHARE_HOST, "/websocket");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        router.doFilterInternal(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(404);
    }

    // -----------------------------------------------------------------------
    // Main host — share paths blocked
    // -----------------------------------------------------------------------

    @Test
    void mainHost_sharePath_returns404() throws Exception {
        MockHttpServletRequest request = request(MAIN_HOST, "/rest/share/sv_AAAA/catalog");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        router.doFilterInternal(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(404);
        assertThat(chain.getRequest()).isNull(); // chain NOT invoked
    }

    @Test
    void mainHost_mainWallPath_allowed() throws Exception {
        MockHttpServletRequest request = request(MAIN_HOST, "/rest/wall-id");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        router.doFilterInternal(request, response, chain);

        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    void mainHost_shareViewWs_returns404() throws Exception {
        MockHttpServletRequest request = request(MAIN_HOST, "/share-view-ws");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        router.doFilterInternal(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(404);
    }

    @Test
    void mainHost_shareCsrf_allowed() throws Exception {
        // The CSRF-token endpoint must be reachable on the MAIN host: the sharer needs the
        // __Host-shareCsrf token there to POST/DELETE /rest/share-links (served on the main
        // host), and __Host- cookies are host-scoped so the token must be issued on that
        // same origin. It carries no share-link IDs, so this does not weaken ADR-SHARE-09
        // anti-enumeration. (Previously this 404'd, which made owner share-creation impossible
        // behind a TLS proxy — see ShareHostRouter.CSRF_PATH.)
        MockHttpServletRequest request = request(MAIN_HOST, "/rest/share-csrf");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        router.doFilterInternal(request, response, chain);

        assertThat(chain.getRequest()).isNotNull(); // filter chain proceeded (not blocked)
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void shareHost_staticBundleAssets_allowed() throws Exception {
        // The readonly SPA shell loads its Angular bundle root-relative; those static
        // assets (no wall data) must be servable on the share host or the view can't boot.
        for (String asset : new String[]{"/main.js", "/polyfills.js", "/styles.css",
                "/chunk-ABC123.js", "/media/font.woff2", "/favicon.ico", "/assets/icons/share.svg"}) {
            MockHttpServletRequest request = request(SHARE_HOST, asset);
            MockHttpServletResponse response = new MockHttpServletResponse();
            MockFilterChain chain = new MockFilterChain();

            router.doFilterInternal(request, response, chain);

            assertThat(chain.getRequest())
                    .as("static asset must pass through on the share host: " + asset)
                    .isNotNull();
        }
    }

    @Test
    void shareHost_mainWallDataEndpoints_stillBlocked() throws Exception {
        // Sensitive main-wall endpoints have no file extension, so the static-asset
        // exemption must NOT let them through on the share host (origin isolation).
        for (String path : new String[]{"/rest/wall-id", "/websocket", "/topic/hashtags/x"}) {
            MockHttpServletRequest request = request(SHARE_HOST, path);
            MockHttpServletResponse response = new MockHttpServletResponse();
            MockFilterChain chain = new MockFilterChain();

            router.doFilterInternal(request, response, chain);

            assertThat(response.getStatus())
                    .as("main-wall endpoint must stay blocked on the share host: " + path)
                    .isEqualTo(404);
        }
    }

    @Test
    void shareHost_shareCsrf_stillAllowed() throws Exception {
        // Regression guard: the CSRF endpoint remains reachable on the SHARE host too.
        MockHttpServletRequest request = request(SHARE_HOST, "/rest/share-csrf");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        router.doFilterInternal(request, response, chain);

        assertThat(chain.getRequest()).isNotNull();
    }

    // -----------------------------------------------------------------------
    // Dev / localhost — passes through
    // -----------------------------------------------------------------------

    @Test
    void localhost_sharePath_passesThroughForDev() throws Exception {
        MockHttpServletRequest request = request("localhost", "/rest/share/sv_AAAA/catalog");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        router.doFilterInternal(request, response, chain);

        assertThat(chain.getRequest()).isNotNull(); // allowed in dev
    }

    @Test
    void localhost_mainWallPath_passesThroughForDev() throws Exception {
        MockHttpServletRequest request = request("localhost", "/rest/wall-id");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        router.doFilterInternal(request, response, chain);

        assertThat(chain.getRequest()).isNotNull();
    }

    // -----------------------------------------------------------------------
    // Anti-enumeration: 404 not 302
    // -----------------------------------------------------------------------

    @Test
    void mainHostAccessingSharePath_returns404_notRedirect() throws Exception {
        MockHttpServletRequest request = request(MAIN_HOST, "/share/sv_AAAA");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        router.doFilterInternal(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(404);
        assertThat(response.getStatus()).isNotEqualTo(301);
        assertThat(response.getStatus()).isNotEqualTo(302);
    }

    // -----------------------------------------------------------------------
    // Helper
    // -----------------------------------------------------------------------

    private MockHttpServletRequest request(String host, String path) {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setServerName(host);
        req.setRequestURI(path);
        return req;
    }

    // -----------------------------------------------------------------------
    // Share-host config fallback: when glacier.share.host is unset/blank the router
    // derives "share.{glacier.domain}" (the GLACIER_SHARE_HOST-not-wired default).
    // -----------------------------------------------------------------------

    @Test
    void nullShareHostConfig_derivesShareDotDomain_andRoutesSharePathToIt() throws Exception {
        ShareHostRouter derived = new ShareHostRouter(MAIN_HOST, null);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        // The derived share host "share.glacier.example.com" must accept a share path.
        derived.doFilterInternal(request("share." + MAIN_HOST, "/rest/share/sv_AAAA/catalog"), response, chain);

        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    void blankShareHostConfig_derivesShareDotDomain_andBlocksMainPathOnIt() throws Exception {
        ShareHostRouter derived = new ShareHostRouter(MAIN_HOST, "   ");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        // The derived share host must still enforce origin isolation (main-wall path → 404).
        derived.doFilterInternal(request("share." + MAIN_HOST, "/rest/wall-id"), response, chain);

        assertThat(response.getStatus()).isEqualTo(404);
        assertThat(chain.getRequest()).isNull();
    }
}
