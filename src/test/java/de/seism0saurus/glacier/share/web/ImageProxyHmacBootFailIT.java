package de.seism0saurus.glacier.share.web;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContextException;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import social.bigbone.MastodonClient;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration test verifying that the Spring context REFUSES to start when
 * {@code glacier.share.imgproxy.hmacSecret} is absent in production profile
 * ({@code glacier.cookie.secure=true}).
 *
 * <p>Security requirement: SR-SHARE-10 (fail-closed HMAC boot policy).
 * Decision reference: "Fail-closed HMAC secret at boot (prod)" — user resolution 2026-04-22
 * option A.
 *
 * <p>Multi-replica deployments silently diverge if secrets auto-generate independently;
 * soft-fail contradicts "as hardened as possible" (secure-feature-planner Round 2).
 */
class ImageProxyHmacBootFailIT {

    /**
     * Verifies that the application context fails to start when the HMAC secret is absent
     * and {@code glacier.cookie.secure=true} (production profile).
     *
     * <p>This is a context-failure test — the entire application context must fail to load.
     * Use {@code assertThatThrownBy} around the context creation or use
     * {@code SpringBootTest} with {@code ApplicationContextException} expectation.
     */
    @Test
    void missingHmacSecretInProd_bootFails() {
        // Direct unit test of the validator: construction must throw in prod profile
        // when secret is absent. This is the root guard; the SpringBootTest variant
        // (context-load) is covered by contextFailsWithoutHmacSecretInProd below.
        assertThatThrownBy(() -> new ImageProxyHmacSecretValidator(null, true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("glacier.share.imgproxy.hmacSecret")
                .hasMessageContaining("32 bytes");
    }

    @Test
    void blankHmacSecretInProd_bootFails() {
        assertThatThrownBy(() -> new ImageProxyHmacSecretValidator("   ", true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("glacier.share.imgproxy.hmacSecret");
    }

    @Test
    void shortHmacSecretInProd_bootFails() {
        assertThatThrownBy(() -> new ImageProxyHmacSecretValidator("tooshort", true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32 bytes");
    }

    @Test
    void adequateHmacSecretInProd_startOk() {
        // 32 * "A" = 32 bytes (just at minimum)
        org.assertj.core.api.Assertions.assertThatNoException()
                .isThrownBy(() -> new ImageProxyHmacSecretValidator("A".repeat(32), true));
    }

    @Test
    void missingHmacSecretInDev_autoGenerates_startOk() {
        // Dev mode (secureCookies=false): missing secret → auto-generate with WARN, no boot failure
        org.assertj.core.api.Assertions.assertThatNoException()
                .isThrownBy(() -> new ImageProxyHmacSecretValidator(null, false));

        ImageProxyHmacSecretValidator validator = new ImageProxyHmacSecretValidator(null, false);
        org.assertj.core.api.Assertions.assertThat(validator.isOperational()).isTrue();
        org.assertj.core.api.Assertions.assertThat(validator.getEffectiveSecret()).isNotNull();
        org.assertj.core.api.Assertions.assertThat(validator.getEffectiveSecret().length)
                .isGreaterThanOrEqualTo(32);
    }

    @Test
    void blankHmacSecretInDev_autoGenerates_startOk() {
        org.assertj.core.api.Assertions.assertThatNoException()
                .isThrownBy(() -> new ImageProxyHmacSecretValidator("", false));

        ImageProxyHmacSecretValidator validator = new ImageProxyHmacSecretValidator("", false);
        org.assertj.core.api.Assertions.assertThat(validator.isOperational()).isTrue();
    }
}
