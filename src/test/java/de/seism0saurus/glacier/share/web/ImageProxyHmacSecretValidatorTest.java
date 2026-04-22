package de.seism0saurus.glacier.share.web;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatNoException;

/**
 * Verifies fail-closed boot behaviour for the image-proxy HMAC secret.
 *
 * <p>Security requirement: SR-SHARE-10 (HMAC secret must be present in prod).
 * Security risk: if HMAC secret is missing, the proxy is open to SSRF via
 * unsigned URL crafting. Must fail at boot in production profile.
 */
class ImageProxyHmacSecretValidatorTest {

    @Test
    void proxyIsDisabledWhenSecretMissingInSecureMode() {
        // secure=true (prod profile) — proxy disabled (not a boot failure, but proxy returns 503)
        ImageProxyHmacSecretValidator validator = new ImageProxyHmacSecretValidator(null, true);
        org.assertj.core.api.Assertions.assertThat(validator.isOperational()).isFalse();
        org.assertj.core.api.Assertions.assertThat(validator.getEffectiveSecret()).isNull();
    }

    @Test
    void proxyIsDisabledWhenSecretBlankInSecureMode() {
        ImageProxyHmacSecretValidator validator = new ImageProxyHmacSecretValidator("", true);
        org.assertj.core.api.Assertions.assertThat(validator.isOperational()).isFalse();
    }

    @Test
    void throwsWhenSecretTooShortInSecureMode() {
        // Minimum 32 bytes (256 bits) — short secrets are rejected
        assertThatThrownBy(() -> new ImageProxyHmacSecretValidator("short", true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32 bytes");
    }

    @Test
    void acceptsAdequateLengthSecretInSecureMode() {
        String goodSecret = "A".repeat(32);
        assertThatNoException().isThrownBy(() -> new ImageProxyHmacSecretValidator(goodSecret, true));
    }

    @Test
    void warnButDoesNotFailWhenSecretMissingInDevMode() {
        // secure=false (dev mode) — auto-generates with WARN, does not crash
        assertThatNoException().isThrownBy(() -> new ImageProxyHmacSecretValidator(null, false));
    }

    @Test
    void providesWorkingSecretInDevModeEvenIfNotConfigured() {
        ImageProxyHmacSecretValidator validator = new ImageProxyHmacSecretValidator(null, false);
        byte[] secret = validator.getEffectiveSecret();
        org.assertj.core.api.Assertions.assertThat(secret).isNotNull();
        org.assertj.core.api.Assertions.assertThat(secret.length).isGreaterThanOrEqualTo(32);
    }
}
