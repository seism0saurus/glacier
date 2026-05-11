package de.seism0saurus.glacier.share.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration test verifying CSP and security headers are applied on share routes.
 *
 * <p>Asserts that the {@link ShareSecurityHeadersFilter} applies the required headers:
 * <ul>
 *   <li>Content-Security-Policy (SR-SHARE-11)</li>
 *   <li>X-Frame-Options: DENY (legacy)</li>
 *   <li>X-Content-Type-Options: nosniff</li>
 *   <li>Cross-Origin-Opener-Policy: same-origin</li>
 *   <li>Referrer-Policy: no-referrer</li>
 * </ul>
 *
 * <p>Security: SR-SHARE-11, OWASP A05, BSI TSS-WEB 5.2.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "glacier.domain=glacier.example.com",
        "glacier.cookie.secure=false",
        "glacier.fallback.enabled=true",
        "mastodon.instance=mastodon.social",
        "mastodon.accessToken=dummy",
        "mastodon.handle=glacier@mastodon.social",
        "glacier.operator.name=Test",
        "glacier.operator.streetAndNumber=Test 1",
        "glacier.operator.zipcode=12345",
        "glacier.operator.city=Test",
        "glacier.operator.country=Test",
        "glacier.operator.phone=+1",
        "glacier.operator.mail=test@test.com",
        "glacier.operator.website=test.com",
        "glacier.share.imgproxy.hmacSecret=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
})
class CspOnShareRouteIT {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void shareRoute_hasCspHeader() throws Exception {
        mockMvc.perform(get("/rest/share/sv_AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA/catalog"))
                .andExpect(header().exists("Content-Security-Policy"));
    }

    @Test
    void shareRoute_cspContainsDefaultSrcNone() throws Exception {
        mockMvc.perform(get("/rest/share/sv_AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA/catalog"))
                .andExpect(header().string("Content-Security-Policy",
                        org.hamcrest.Matchers.containsString("default-src 'none'")));
    }

    @Test
    void shareRoute_cspContainsFrameAncestorsNone() throws Exception {
        mockMvc.perform(get("/rest/share/sv_AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA/catalog"))
                .andExpect(header().string("Content-Security-Policy",
                        org.hamcrest.Matchers.containsString("frame-ancestors 'none'")));
    }

    @Test
    void shareRoute_hasXFrameOptionsDeny() throws Exception {
        mockMvc.perform(get("/rest/share/sv_AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA/catalog"))
                .andExpect(header().string("X-Frame-Options", "DENY"));
    }

    @Test
    void shareRoute_hasNosniff() throws Exception {
        mockMvc.perform(get("/rest/share/sv_AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA/catalog"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"));
    }

    @Test
    void shareRoute_hasReferrerPolicyNoReferrer() throws Exception {
        mockMvc.perform(get("/rest/share/sv_AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA/catalog"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"));
    }

    @Test
    void shareRoute_hasCrossOriginOpenerPolicy() throws Exception {
        mockMvc.perform(get("/rest/share/sv_AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA/catalog"))
                .andExpect(header().string("Cross-Origin-Opener-Policy", "same-origin"));
    }

    @Test
    void nonShareRoute_doesNotHaveShareCsp() throws Exception {
        // The /rest/wall-id endpoint does NOT go through ShareSecurityHeadersFilter
        // so it should NOT have the share-specific CSP (no default-src 'none')
        mockMvc.perform(get("/rest/wall-id"))
                .andExpect(header().doesNotExist("Cross-Origin-Opener-Policy"));
    }

    @Test
    void csrfEndpoint_hasShareSecurityHeaders() throws Exception {
        mockMvc.perform(get("/rest/share-csrf"))
                .andExpect(header().exists("Content-Security-Policy"))
                .andExpect(header().string("X-Frame-Options", "DENY"));
    }
}
