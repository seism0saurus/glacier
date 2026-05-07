package de.seism0saurus.glacier.share.web;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Turkish-locale regression guard for {@link ShareHostRouter#extractHost}.
 *
 * <p>The Turkish locale maps uppercase 'I' (U+0049) to dotless lowercase 'ı' (U+0131)
 * rather than 'i' (U+0069). A bare {@code serverName.toLowerCase()} call made while
 * the JVM default locale is Turkish would silently mis-map any server name containing
 * an uppercase 'I', causing valid host comparisons inside {@link ShareHostRouter}
 * to fail — routing decisions would then fall through to the localhost pass-through
 * branch instead of enforcing origin separation.
 *
 * <p>This test verifies that {@link ShareHostRouter} correctly routes hosts containing
 * uppercase 'I' regardless of the JVM default locale (SR-LR-06).
 */
class ShareHostRouterLocaleTest {

    /**
     * The JVM default locale at the start of this test class; restored in {@link #restoreLocale()}.
     * Saved as an instance field so each test starts with a fresh save.
     */
    private Locale savedLocale;

    @BeforeEach
    void saveAndSetTurkishLocale() {
        savedLocale = Locale.getDefault();
        Locale.setDefault(Locale.of("tr", "TR"));
    }

    @AfterEach
    void restoreLocale() {
        Locale.setDefault(savedLocale);
    }

    /**
     * Verifies that a server name containing uppercase 'I' (present in "INVITE.EXAMPLE.COM")
     * is correctly matched against the configured share host under Turkish locale.
     *
     * <p>Arrange: JVM default locale is Turkish; ShareHostRouter configured with a share host
     * that, when correctly lowercased with Locale.ROOT, equals "invite.example.com".
     * <p>Act: request arrives with server name "INVITE.EXAMPLE.COM" on a share path.
     * <p>Assert: the filter chain passes through (host recognized as share host on share path),
     * proving that host comparison uses {@code Locale.ROOT} not the Turkish default locale.
     */
    @Test
    void serverNameWithUppercaseI_matchesShareHost_underTurkishLocale() throws Exception {
        // Arrange: configured share host in lowercase; request comes in with uppercase 'I'
        String configuredShareHost = "invite.example.com";
        String mainHost = "glacier.example.com";
        ShareHostRouter router = new ShareHostRouter(mainHost, configuredShareHost);

        // Server sends "INVITE.EXAMPLE.COM" — uppercase 'I' must not become dotless 'ı'
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setServerName("INVITE.EXAMPLE.COM");
        request.setRequestURI("/share/sv_test123");

        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        // Act
        router.doFilterInternal(request, response, chain);

        // Assert: chain invoked (share host allowed on share path), status not 404
        assertThat(chain.getRequest())
                .as("Share host with uppercase-I server name should pass through on share path")
                .isNotNull();
        assertThat(response.getStatus())
                .as("Status must not be 404 — origin separation should recognize the host correctly")
                .isNotEqualTo(404);
    }

    /**
     * Verifies that a main-wall request on the share host is rejected under Turkish locale.
     *
     * <p>The rejection must occur even when the server name contains uppercase 'I',
     * confirming that the routing comparison for the share host uses Locale.ROOT.
     */
    @Test
    void serverNameWithUppercaseI_onMainWallPath_returns404_underTurkishLocale() throws Exception {
        // Arrange
        String configuredShareHost = "invite.example.com";
        String mainHost = "glacier.example.com";
        ShareHostRouter router = new ShareHostRouter(mainHost, configuredShareHost);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setServerName("INVITE.EXAMPLE.COM");
        request.setRequestURI("/rest/wall-id");

        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        // Act
        router.doFilterInternal(request, response, chain);

        // Assert: share host on main-wall path must be rejected
        assertThat(response.getStatus())
                .as("Share host accessing main-wall path must return 404 under Turkish locale")
                .isEqualTo(404);
        assertThat(chain.getRequest())
                .as("Filter chain must NOT be invoked for rejected request")
                .isNull();
    }
}
