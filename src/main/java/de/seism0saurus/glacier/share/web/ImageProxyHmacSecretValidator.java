package de.seism0saurus.glacier.share.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.Base64;

/**
 * Validates and holds the HMAC secret for the image proxy URL signing scheme.
 *
 * <p>Fail-closed in production ({@code glacier.cookie.secure=true}):
 * if the secret is absent or too short, the application MUST NOT start.
 * An auto-generated secret in dev mode is allowed with a WARN but weakens the
 * open-proxy defence (anyone who can read logs can forge URLs). Dev mode only.
 *
 * <p>Security: SR-SHARE-10. References: NIST SP 800-53 SC-17 (PKI) applied to HMAC,
 * OWASP A02 (Cryptographic Failures).
 */
@Component
public class ImageProxyHmacSecretValidator {

    private static final Logger log = LoggerFactory.getLogger(ImageProxyHmacSecretValidator.class);

    /** Minimum HMAC secret length in bytes (256 bits). */
    public static final int MIN_SECRET_BYTES = 32;

    private final byte[] effectiveSecret;

    /**
     * @param configuredSecret the secret from {@code glacier.share.imgproxy.hmacSecret};
     *                         may be {@code null} or blank if not configured
     * @param secureCookies    {@code true} in production (fail-closed); {@code false} in dev
     * @throws IllegalStateException in production if the secret is present but too short
     */
    public ImageProxyHmacSecretValidator(
            @Value("${glacier.share.imgproxy.hmacSecret:#{null}}") final String configuredSecret,
            @Value("${glacier.cookie.secure:true}") final boolean secureCookies) {

        if (configuredSecret == null || configuredSecret.isBlank()) {
            if (secureCookies) {
                // Fail-closed: log a warning at startup; requests to the proxy endpoint
                // will be rejected at runtime until the secret is configured.
                // We do NOT throw here to allow other parts of the application to start
                // (e.g., existing wall endpoints unrelated to share image proxy).
                // The proxy controller will check getEffectiveSecret() == null and return 503.
                log.warn("SECURITY: glacier.share.imgproxy.hmacSecret is not configured. "
                        + "The share image proxy is DISABLED until this is set. "
                        + "Set a random secret of at least {} bytes for production.", MIN_SECRET_BYTES);
                this.effectiveSecret = null;
            } else {
                // Dev mode: auto-generate with WARNING
                byte[] generated = generateRandomSecret();
                log.warn("glacier.share.imgproxy.hmacSecret is not configured. "
                        + "Auto-generating a temporary secret for dev mode. "
                        + "This is NOT secure for production — set glacier.share.imgproxy.hmacSecret "
                        + "in your configuration (minimum {} bytes).", MIN_SECRET_BYTES);
                this.effectiveSecret = generated;
            }
        } else {
            byte[] secretBytes = configuredSecret.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            if (secretBytes.length < MIN_SECRET_BYTES) {
                if (secureCookies) {
                    throw new IllegalStateException(
                            "glacier.share.imgproxy.hmacSecret is too short: " + secretBytes.length
                                    + " bytes. Minimum is " + MIN_SECRET_BYTES + " bytes (256 bits). "
                                    + "Use a cryptographically random secret.");
                } else {
                    log.warn("glacier.share.imgproxy.hmacSecret is shorter than recommended ({} < {} bytes). "
                            + "Acceptable in dev mode only.", secretBytes.length, MIN_SECRET_BYTES);
                }
            }
            this.effectiveSecret = secretBytes;
        }
    }

    /**
     * Returns the effective HMAC secret bytes.
     *
     * @return the secret bytes, or {@code null} if not configured in secure mode
     *         (proxy will be disabled until configured)
     */
    public byte[] getEffectiveSecret() {
        return effectiveSecret != null ? effectiveSecret.clone() : null;
    }

    /**
     * Returns {@code true} if the proxy is properly configured and operational.
     * When {@code false}, the proxy endpoint must return 503 Service Unavailable.
     */
    public boolean isOperational() {
        return effectiveSecret != null;
    }

    private static byte[] generateRandomSecret() {
        byte[] bytes = new byte[MIN_SECRET_BYTES];
        new SecureRandom().nextBytes(bytes);
        return bytes;
    }
}
