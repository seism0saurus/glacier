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
    void mainHost_shareCsrf_returns404() throws Exception {
        MockHttpServletRequest request = request(MAIN_HOST, "/rest/share-csrf");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        router.doFilterInternal(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(404);
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
}
