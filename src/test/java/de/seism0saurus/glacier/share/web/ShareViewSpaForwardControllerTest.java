package de.seism0saurus.glacier.share.web;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Unit tests for {@link ShareViewSpaForwardController}.
 *
 * <p>Verifies that the readonly share-view deep-link routes forward to the Angular SPA
 * shell ({@code /index.html}) so the client router can bootstrap. Host isolation
 * (main host 404 for {@code /share/*}) is enforced separately by {@code ShareHostRouter}
 * and covered by {@code ShareHostRouterTest}; here we use a standalone setup with no
 * filters to assert the forward mapping in isolation.
 */
class ShareViewSpaForwardControllerTest {

    private final MockMvc mvc =
            MockMvcBuilders.standaloneSetup(new ShareViewSpaForwardController()).build();

    @Test
    void shareViewRoute_forwardsToSpaShell() throws Exception {
        mvc.perform(get("/share/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"))
                .andExpect(status().isOk())
                .andExpect(forwardedUrl("/index.html"));
    }

    @Test
    void expiredSubRoute_forwardsToSpaShell() throws Exception {
        mvc.perform(get("/share/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA/expired"))
                .andExpect(status().isOk())
                .andExpect(forwardedUrl("/index.html"));
    }
}
