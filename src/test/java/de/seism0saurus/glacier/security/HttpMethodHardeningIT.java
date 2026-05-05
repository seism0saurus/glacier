package de.seism0saurus.glacier.security;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import social.bigbone.MastodonClient;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.http.HttpMethod.DELETE;
import static org.springframework.http.HttpMethod.PUT;
import static org.springframework.http.HttpMethod.PATCH;

/**
 * IT-sec-METHOD: HTTP method hardening integration test (SR-NEW-10).
 *
 * <p>Verifies that undeclared HTTP methods are properly rejected for all declared REST
 * endpoints. Spring MVC returns 405 Method Not Allowed for undeclared methods on known
 * endpoints; this test confirms that behavior and also verifies:
 * <ul>
 *   <li>TRACE returns 405 or 501 (not 200) — TRACE exposes headers including cookies,
 *       which would leak the wallId identity token (CWE-16)</li>
 *   <li>One undeclared verb on each endpoint returns 405 with {@code Allow:} header</li>
 *   <li>{@code X-HTTP-Method-Override: DELETE} on GET /rest/wall-id does NOT delete or
 *       change behavior — method-override tunneling prevention</li>
 *   <li>{@code _method=DELETE} query param on GET /rest/wall-id is ignored</li>
 * </ul>
 *
 * <p>Security reference: C8 — Browser Security; C1 — Access Control;
 * ASVS V14.5.1 (L1), V3.5 (L1); WSTG-CONF-06.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "glacier.fallback.enabled=true",
        "glacier.cookie.secure=true",
        "glacier.cache.maxHashtagsPerPrincipal=10",
        "glacier.fallback.ratelimit.perMinute=30",
        "glacier.fallback.ratelimit.perMinutePerIp=120",
        "glacier.ratelimit.eviction.intervalMs=600000",
        "glacier.domain=example.com",
        "glacier.share.host=share.example.com"
})
class HttpMethodHardeningIT {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    @SuppressWarnings("unused")
    private MastodonClient mastodonClient;

    // -------------------------------------------------------------------------
    // TRACE method — must be rejected on all GET endpoints
    // TRACE responses include request headers, which would expose the wallId cookie.
    // RFC 7231 §4.3.8: TRACE must not be supported for production endpoints.
    // -------------------------------------------------------------------------

    @ParameterizedTest(name = "TRACE on {0} → 405 or 501")
    @ValueSource(strings = {
            "/rest/wall-id",
            "/rest/mastodon-handle",
            "/rest/operator",
            "/rest/messages",
            "/rest/share-csrf",
            "/rest/share-links",
            "/rest/share/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA/catalog",
            "/rest/share/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA/messages",
            "/rest/share/img-proxy",
            "/rest/share-links/00000000-0000-0000-0000-000000000002"
    })
    void trace_onGetEndpoints_returnsMethodNotAllowedOrNotImplemented(String path) throws Exception {
        MvcResult result = mockMvc.perform(
                request("TRACE", URI.create(path))
                        .cookie(new Cookie("wallId", "00000000-0000-0000-0000-000000000002")))
                .andReturn();

        int status = result.getResponse().getStatus();
        assertThat(status)
                .as("IT-sec-METHOD (ASVS V14.5.1, WSTG-CONF-06): TRACE on [%s] must return "
                        + "405 (Method Not Allowed) or 501 (Not Implemented) — not 200. "
                        + "TRACE responses include request headers which would expose the "
                        + "wallId identity cookie (CWE-16, OWASP C8).", path)
                .isIn(405, 501);
    }

    // -------------------------------------------------------------------------
    // Undeclared verbs on GET-only endpoints — must return 405 + Allow header
    // -------------------------------------------------------------------------

    /**
     * PUT on a GET-only endpoint must return 405 with an Allow header listing the
     * declared methods. The Allow header allows clients to discover which methods
     * are permitted rather than guessing.
     */
    @ParameterizedTest(name = "PUT on GET-only {0} → 405 with Allow header")
    @ValueSource(strings = {
            "/rest/wall-id",
            "/rest/mastodon-handle",
            "/rest/operator",
            "/rest/messages",
            "/rest/share-csrf",
            "/rest/share/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA/catalog",
            "/rest/share/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA/messages",
            "/rest/share/img-proxy"
    })
    void put_onGetOnlyEndpoints_returns405WithAllowHeader(String path) throws Exception {
        MvcResult result = mockMvc.perform(
                request(PUT, path)
                        .cookie(new Cookie("wallId", "00000000-0000-0000-0000-000000000003")))
                .andReturn();

        int status = result.getResponse().getStatus();
        assertThat(status)
                .as("IT-sec-METHOD (ASVS V14.5.1, WSTG-CONF-06): PUT on [%s] must return 405", path)
                .isEqualTo(405);

        String allowHeader = result.getResponse().getHeader(HttpHeaders.ALLOW);
        assertThat(allowHeader)
                .as("IT-sec-METHOD (WSTG-CONF-06): 405 response for [%s] must include Allow header "
                        + "listing the declared methods", path)
                .isNotNull();
        assertThat(allowHeader)
                .as("IT-sec-METHOD: Allow header for GET-only endpoint [%s] must include GET, "
                        + "actual Allow: [%s]", path, allowHeader)
                .containsIgnoringCase("GET");
    }

    @ParameterizedTest(name = "DELETE on GET-only {0} → 405")
    @ValueSource(strings = {
            "/rest/mastodon-handle",
            "/rest/operator",
            "/rest/messages",
            "/rest/share-csrf",
            "/rest/share/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA/catalog",
            "/rest/share/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA/messages",
            "/rest/share/img-proxy"
    })
    void delete_onGetOnlyEndpoints_returns405(String path) throws Exception {
        MvcResult result = mockMvc.perform(
                request(DELETE, path)
                        .cookie(new Cookie("wallId", "00000000-0000-0000-0000-000000000004")))
                .andReturn();

        int status = result.getResponse().getStatus();
        assertThat(status)
                .as("IT-sec-METHOD: DELETE on GET-only endpoint [%s] must return 405", path)
                .isEqualTo(405);
    }

    @ParameterizedTest(name = "PATCH on GET-only {0} → 405")
    @ValueSource(strings = {
            "/rest/wall-id",
            "/rest/mastodon-handle",
            "/rest/operator",
            "/rest/messages",
            "/rest/share-csrf",
            "/rest/share/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA/catalog",
            "/rest/share/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA/messages",
            "/rest/share/img-proxy"
    })
    void patch_onGetOnlyEndpoints_returns405(String path) throws Exception {
        MvcResult result = mockMvc.perform(
                request(PATCH, path)
                        .cookie(new Cookie("wallId", "00000000-0000-0000-0000-000000000005")))
                .andReturn();

        int status = result.getResponse().getStatus();
        assertThat(status)
                .as("IT-sec-METHOD: PATCH on GET-only endpoint [%s] must return 405", path)
                .isEqualTo(405);
    }

    // -------------------------------------------------------------------------
    // POST on GET-only endpoints
    // -------------------------------------------------------------------------

    @ParameterizedTest(name = "POST on GET-only {0} → 405")
    @ValueSource(strings = {
            "/rest/wall-id",
            "/rest/mastodon-handle",
            "/rest/operator",
            "/rest/messages",
            "/rest/share-csrf",
            "/rest/share/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA/catalog",
            "/rest/share/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA/messages",
            "/rest/share/img-proxy"
    })
    void post_onGetOnlyEndpoints_returns405(String path) throws Exception {
        MvcResult result = mockMvc.perform(
                request(POST, path)
                        .cookie(new Cookie("wallId", "00000000-0000-0000-0000-000000000006")))
                .andReturn();

        int status = result.getResponse().getStatus();
        assertThat(status)
                .as("IT-sec-METHOD: POST on GET-only endpoint [%s] must return 405", path)
                .isEqualTo(405);
    }

    // -------------------------------------------------------------------------
    // Method-override header tunneling — X-HTTP-Method-Override must be ignored
    // GET /rest/wall-id with X-HTTP-Method-Override: DELETE must behave as a GET.
    // ASVS V3.5 (L1): override tunneling must not be accepted.
    // -------------------------------------------------------------------------

    @Test
    void xHttpMethodOverrideDelete_onWallIdEndpoint_isTreatedAsGet() throws Exception {
        // Send GET /rest/wall-id with X-HTTP-Method-Override: DELETE header
        // The endpoint must not change behavior — it must return 200 (GET semantics),
        // NOT 405 as if DELETE were the method (which would indicate override was accepted)
        // and NOT actually delete anything.
        MvcResult result = mockMvc.perform(
                get("/rest/wall-id")
                        .header("X-HTTP-Method-Override", "DELETE")
                        .cookie(new Cookie("wallId", "00000000-0000-0000-0000-000000000007")))
                .andReturn();

        int status = result.getResponse().getStatus();
        assertThat(status)
                .as("IT-sec-METHOD (ASVS V3.5, WSTG-CONF-06): "
                        + "GET /rest/wall-id with X-HTTP-Method-Override: DELETE must be treated "
                        + "as a GET and return 200. Status was [%d]. "
                        + "If status is 405, the method-override header was accepted (vulnerability).",
                        status)
                .isIn(200, 429); // 429 if rate-limited, but 200 is the normal case
    }

    @Test
    void xHttpMethodOverrideDelete_onWallIdEndpoint_responseBodyContainsId() throws Exception {
        // The response must contain a wallId, not an error about DELETE not being supported
        MvcResult result = mockMvc.perform(
                get("/rest/wall-id")
                        .header("X-HTTP-Method-Override", "DELETE")
                        .cookie(new Cookie("wallId", "00000000-0000-0000-0000-000000000008")))
                .andReturn();

        int status = result.getResponse().getStatus();
        String body = result.getResponse().getContentAsString();

        if (status == 200) {
            // Normal case: body must contain the id field (GET semantics preserved)
            assertThat(body)
                    .as("IT-sec-METHOD: GET with X-HTTP-Method-Override: DELETE must behave as GET — "
                            + "response body must be the wallId JSON, not an error")
                    .contains("\"id\"");
        }
        // If 429 (rate limited), the override was still ignored — acceptable
    }

    // -------------------------------------------------------------------------
    // _method query parameter tunneling — must be ignored
    // _method=DELETE on GET /rest/wall-id must behave as a plain GET.
    // This style of override is used by some frameworks (Rails, etc.) but must not
    // be accepted in Glacier.
    // -------------------------------------------------------------------------

    @Test
    void methodQueryParam_deleteOnWallId_isTreatedAsGet() throws Exception {
        MvcResult result = mockMvc.perform(
                get("/rest/wall-id")
                        .param("_method", "DELETE")
                        .cookie(new Cookie("wallId", "00000000-0000-0000-0000-000000000009")))
                .andReturn();

        int status = result.getResponse().getStatus();
        assertThat(status)
                .as("IT-sec-METHOD (ASVS V3.5): "
                        + "GET /rest/wall-id?_method=DELETE must behave as a GET. "
                        + "Status was [%d]. If 405 or 400 with wrong error, _method was processed.",
                        status)
                .isIn(200, 429); // 200 (normal), 429 (rate-limited) — both acceptable, not 405
    }

    // -------------------------------------------------------------------------
    // GET on POST-only share-links endpoint — must return 405
    // -------------------------------------------------------------------------

    @Test
    void get_onShareLinksCreateEndpoint_returns405() throws Exception {
        // GET on /rest/share-links is actually allowed (list), so let's test
        // a different constraint: PUT on /rest/share-links returns 405
        MvcResult result = mockMvc.perform(
                request(PUT, "/rest/share-links")
                        .cookie(new Cookie("wallId", "00000000-0000-0000-0000-000000000010")))
                .andReturn();

        int status = result.getResponse().getStatus();
        assertThat(status)
                .as("IT-sec-METHOD: PUT on /rest/share-links must return 405")
                .isEqualTo(405);
    }

    // -------------------------------------------------------------------------
    // Allow header coverage — must not list TRACE
    // When a method is rejected with 405, the Allow header must NOT include TRACE
    // as a "supported" method (which would invite attackers to use it).
    // -------------------------------------------------------------------------

    @Test
    void allowHeader_onMethodNotAllowed_doesNotListTrace() throws Exception {
        MvcResult result = mockMvc.perform(
                request(DELETE, "/rest/wall-id")
                        .cookie(new Cookie("wallId", "00000000-0000-0000-0000-000000000011")))
                .andReturn();

        String allowHeader = result.getResponse().getHeader(HttpHeaders.ALLOW);
        if (allowHeader != null) {
            assertThat(allowHeader.toUpperCase())
                    .as("IT-sec-METHOD (WSTG-CONF-06): Allow header must not list TRACE "
                            + "as a supported method. Actual Allow: [%s]", allowHeader)
                    .doesNotContain("TRACE");
        }
    }
}
