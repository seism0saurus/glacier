package de.seism0saurus.glacier.share.web;

import de.seism0saurus.glacier.share.application.ImageProxyUrlSigner;
import de.seism0saurus.glacier.share.domain.ShareLinkId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Base64;

/**
 * Signs image URLs for the server-side image proxy using HMAC-SHA256.
 *
 * <p>Only this class is permitted to call the signing path. The HMAC secret never
 * reaches the wire (ADR-SHARE-07).
 *
 * <p>Signed payload: {@code originalUrl + "|" + expiresAtEpoch + "|" + shareLinkId}.
 * The URL consumer ({@link ShareImageProxyController}) verifies this MAC before proxying.
 *
 * <p>Security: ADR-SHARE-07, OWASP A02 (Cryptographic Failures).
 * References: NIST SP 800-53 SC-17 (HMAC keying).
 */
@Component
public class ShareImageProxyUrlBuilder implements ImageProxyUrlSigner {

    private static final Logger log = LoggerFactory.getLogger(ShareImageProxyUrlBuilder.class);

    /** Proxy URL token validity window in seconds. Bounded to share link TTL (7 days). */
    private static final long TOKEN_VALIDITY_SECONDS = 7 * 24 * 3600L;

    private final ImageProxyHmacSecretValidator secretValidator;
    private final String glacierDomain;

    public ShareImageProxyUrlBuilder(
            final ImageProxyHmacSecretValidator secretValidator,
            @Value("${glacier.domain}") final String glacierDomain) {
        this.secretValidator = secretValidator;
        this.glacierDomain = glacierDomain;
    }

    /**
     * Signs {@code originalUrl} and returns a proxy URL pointing to
     * {@code /rest/share/img-proxy?u={signed-payload}}.
     *
     * @param originalUrl the image URL from the federation instance (already scheme-validated)
     * @param shareLinkId the active share link; used as HMAC context to scope tokens per link
     * @return the proxy URL with HMAC signature embedded; or {@code null} if signing fails
     */
    public String sign(final String originalUrl, final ShareLinkId shareLinkId) {
        if (originalUrl == null || originalUrl.isBlank()) return null;

        long expiresAt = Instant.now().getEpochSecond() + TOKEN_VALIDITY_SECONDS;
        String payload = originalUrl + "|" + expiresAt + "|" + shareLinkId.getValue();

        byte[] mac;
        try {
            mac = hmacSha256(secretValidator.getEffectiveSecret(), payload);
        } catch (Exception e) {
            log.warn("share.proxy.sign_failed url-hash={} reason={}",
                    de.seism0saurus.glacier.util.LogScrubber.hash8(originalUrl), e.getMessage());
            return null;
        }

        String signature = Base64.getUrlEncoder().withoutPadding().encodeToString(mac);
        String encodedPayload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        // Signed URL token: base64url(payload) + "." + base64url(mac)
        String signedToken = encodedPayload + "." + signature;

        return "https://share." + glacierDomain
                + "/rest/share/img-proxy?u="
                + URLEncoder.encode(signedToken, StandardCharsets.UTF_8);
    }

    /**
     * Verifies a signed token extracted from {@code u} query parameter.
     *
     * @param signedToken the token from the proxy URL
     * @return the original URL if valid; empty if signature invalid or expired
     */
    public java.util.Optional<String> verify(final String signedToken) {
        if (signedToken == null || signedToken.isBlank()) return java.util.Optional.empty();

        int dotIdx = signedToken.lastIndexOf('.');
        if (dotIdx < 0) return java.util.Optional.empty();

        String encodedPayload = signedToken.substring(0, dotIdx);
        String providedSig = signedToken.substring(dotIdx + 1);

        String payload;
        try {
            payload = new String(Base64.getUrlDecoder().decode(encodedPayload), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return java.util.Optional.empty();
        }

        // Verify HMAC (constant-time)
        byte[] expected;
        try {
            expected = hmacSha256(secretValidator.getEffectiveSecret(), payload);
        } catch (Exception e) {
            return java.util.Optional.empty();
        }

        byte[] provided;
        try {
            provided = Base64.getUrlDecoder().decode(providedSig);
        } catch (IllegalArgumentException e) {
            return java.util.Optional.empty();
        }

        if (!java.security.MessageDigest.isEqual(expected, provided)) {
            return java.util.Optional.empty(); // HMAC invalid
        }

        // Parse payload: originalUrl|expiresAt|shareLinkId
        String[] parts = payload.split("\\|", 3);
        if (parts.length < 2) return java.util.Optional.empty();

        long expiresAt;
        try {
            expiresAt = Long.parseLong(parts[1]);
        } catch (NumberFormatException e) {
            return java.util.Optional.empty();
        }

        if (Instant.now().getEpochSecond() > expiresAt) {
            return java.util.Optional.empty(); // expired
        }

        return java.util.Optional.of(parts[0]);
    }

    private static byte[] hmacSha256(byte[] key, String data) throws NoSuchAlgorithmException, InvalidKeyException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
    }
}
