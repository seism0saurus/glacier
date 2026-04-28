package de.seism0saurus.glacier.util;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * LogScrubber provides sanitised representations of sensitive values for structured audit logs.
 *
 * <p>D-13 / SR-8 mandate that raw personal identifiers, session tokens, URLs, and
 * access-control artefacts must never reach the JSON log encoder. Every method in this
 * class returns a short, irreversible digest that is safe to emit into log fields while
 * still allowing correlation of events from the same source.</p>
 *
 * <p>All digests use SHA-256 truncated to the first 8 hex characters (32 bits).
 * This length provides adequate collision resistance for operational correlation
 * while keeping log output compact.</p>
 *
 * <p>This class is a non-instantiable utility — use its static methods directly.</p>
 */
public final class LogScrubber {

    /**
     * Private constructor — this class is a non-instantiable utility holder.
     */
    private LogScrubber() {}

    /**
     * Returns the first 8 hex characters of the SHA-256 digest of the given value.
     *
     * <p>Suitable for correlating events from the same principal, token, or other
     * opaque identifier without exposing the raw value in logs.</p>
     *
     * <p>Returns {@code "null"} when {@code value} is {@code null}.</p>
     *
     * @param value the raw string to hash; may be {@code null}
     * @return an 8-character lowercase hex digest, or {@code "null"}
     */
    public static String hash8(String value) {
        if (value == null) {
            return "null";
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : bytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.substring(0, 8);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is guaranteed to be available on every Java platform
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    /**
     * Returns the length of the given hashtag as a safe log-field value.
     *
     * <p>Logging the length rather than the raw value satisfies D-13 requirements
     * while still providing enough signal to distinguish blank, short, and
     * suspiciously long inputs in audit events.</p>
     *
     * <p>Returns {@code 0} when {@code hashtag} is {@code null}.</p>
     *
     * @param hashtag the raw hashtag string; may be {@code null}
     * @return the character length of the hashtag, or {@code 0}
     */
    public static int hashtagLen(String hashtag) {
        if (hashtag == null) {
            return 0;
        }
        return hashtag.length();
    }

    /**
     * Returns a hash of the host and normalised port extracted from the given URL.
     *
     * <p>Scheme-specific port normalisation: if no explicit port is present, port 443
     * is assumed for {@code https} schemes and port 80 for everything else.
     * The hash input is {@code "host:port"}, ensuring that two URLs pointing to the
     * same logical host always produce the same hash regardless of path or query.</p>
     *
     * <p>Malformed URLs, URLs whose host component cannot be extracted, and
     * {@code null} or blank inputs all produce the hash of the sentinel string
     * {@code "unparseable"}.</p>
     *
     * <p>This is the correct method to use in SSRF-related audit log events such as
     * {@code stomp.embed.ssrf_blocked} — it surfaces which remote host triggered the
     * guard without logging the raw URL or any path components that could contain
     * sensitive data.</p>
     *
     * @param rawUrl the raw URL string to extract a host hash from; may be {@code null}
     * @return an 8-character lowercase hex digest of {@code "host:port"},
     *         or a digest of {@code "unparseable"} for invalid input
     */
    public static String urlHostHash(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            return hash8("unparseable");
        }
        try {
            URI uri = URI.create(rawUrl);
            String host = uri.getHost();
            if (host == null) {
                return hash8("unparseable");
            }
            int port = uri.getPort();
            if (port == -1) {
                port = "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
            }
            return hash8(host + ":" + port);
        } catch (IllegalArgumentException e) {
            return hash8("unparseable");
        }
    }
}
