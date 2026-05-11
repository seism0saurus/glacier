package de.seism0saurus.glacier.share.web;

import de.seism0saurus.glacier.GlacierCookieProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;

/**
 * Validates and holds the HMAC secret for the image proxy URL signing scheme.
 *
 * <p><strong>Prod profile fail-closed (SR-SHARE-10, user resolution 2026-04-22 option A):</strong>
 * When {@code glacier.cookie.secure=true}, this bean throws {@link IllegalStateException}
 * at construction time if the secret is absent, blank, or shorter than 32 bytes.
 * The Spring context therefore refuses to start — multi-replica deployments cannot silently
 * diverge by auto-generating independent secrets.
 *
 * <p><strong>Dev profile ({@code glacier.cookie.secure=false}):</strong>
 * Missing or blank secret is auto-generated via {@link SecureRandom} with a one-time WARN.
 * A short-but-present secret is accepted with a WARN (dev convenience only).
 *
 * <p>Security: SR-SHARE-10. References: NIST SP 800-53 SC-17 (HMAC keying),
 * OWASP A02 (Cryptographic Failures).
 */
@Component
public class ImageProxyHmacSecretValidator {

    private static final Logger log = LoggerFactory.getLogger(ImageProxyHmacSecretValidator.class);

    /** Minimum HMAC secret length in bytes (256 bits / 32 bytes). */
    public static final int MIN_SECRET_BYTES = 32;

    private final byte[] effectiveSecret;

    /**
     * Constructs the validator, enforcing fail-closed boot behaviour in production.
     *
     * @param configuredSecret the raw secret from {@code glacier.share.imgproxy.hmacSecret};
     *                         may be {@code null} or blank if not configured
     * @param secureCookies    {@code true} in production (fail-closed at boot);
     *                         {@code false} in dev (auto-generate with WARN)
     * @throws IllegalStateException in production if the secret is absent, blank, or too short.
     *                               The Spring context will not start.
     */
    public ImageProxyHmacSecretValidator(
            @Value("${glacier.share.imgproxy.hmacSecret:#{null}}") final String configuredSecret,
            final GlacierCookieProperties cookieProps) {
        final boolean secureCookies = Boolean.TRUE.equals(cookieProps.getSecure());

        if (configuredSecret == null || configuredSecret.isBlank()) {
            if (secureCookies) {
                // FAIL-CLOSED: refuse context startup in production (SR-SHARE-10, user option A).
                // Multi-replica deployments diverge if each pod auto-generates its own secret;
                // soft-fail contradicts "as hardened as possible" (secure-feature-planner R2).
                throw new IllegalStateException(
                        "SECURITY FAILURE: glacier.share.imgproxy.hmacSecret is not configured. "
                                + "The share image proxy cannot start without a secret of at least "
                                + MIN_SECRET_BYTES + " bytes (256 bits). "
                                + "Set this in your environment before starting the application.");
            } else {
                // Dev mode: auto-generate with a single WARN. Not suitable for production.
                byte[] generated = generateRandomSecret();
                log.warn("glacier.share.imgproxy.hmacSecret is not configured. "
                        + "Auto-generating a temporary secret for dev mode. "
                        + "This is NOT secure for production — set glacier.share.imgproxy.hmacSecret "
                        + "(minimum {} bytes).", MIN_SECRET_BYTES);
                this.effectiveSecret = generated;
                return;
            }
        }

        byte[] secretBytes = configuredSecret.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (secretBytes.length < MIN_SECRET_BYTES) {
            if (secureCookies) {
                // FAIL-CLOSED: short secret in prod also causes boot failure (SR-SHARE-10).
                throw new IllegalStateException(
                        "SECURITY FAILURE: glacier.share.imgproxy.hmacSecret is too short: "
                                + secretBytes.length + " bytes. Minimum is " + MIN_SECRET_BYTES
                                + " bytes (256 bits). Use a cryptographically random secret.");
            } else {
                log.warn("glacier.share.imgproxy.hmacSecret is shorter than recommended ({} < {} bytes). "
                        + "Acceptable in dev mode only.", secretBytes.length, MIN_SECRET_BYTES);
            }
        }
        this.effectiveSecret = secretBytes;
    }

    /**
     * Returns the effective HMAC secret bytes.
     *
     * <p>Always non-null because the constructor throws in production when the secret
     * is absent. In dev mode returns the auto-generated secret.
     *
     * @return the secret bytes; callers receive a defensive copy
     */
    public byte[] getEffectiveSecret() {
        return effectiveSecret.clone();
    }

    /**
     * Returns {@code true} if the proxy is properly configured and operational.
     *
     * <p>Always {@code true} because the constructor throws on misconfiguration in prod.
     * This method is retained for defensive checks in the proxy controller.
     */
    public boolean isOperational() {
        return true;
    }

    private static byte[] generateRandomSecret() {
        byte[] bytes = new byte[MIN_SECRET_BYTES];
        new SecureRandom().nextBytes(bytes);
        return bytes;
    }
}
