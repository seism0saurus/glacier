package de.seism0saurus.glacier.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Log hygiene utility (D-13, SR-8, OWASP A09: Logging &amp; Monitoring Failures).
 *
 * <p>Provides helper methods for safely constructing log messages that never leak:
 * <ul>
 *   <li>Raw {@code wallId} / cookie values</li>
 *   <li>Toot URLs, bodies, or {@code editedAt} timestamps</li>
 *   <li>Client IP addresses in full form</li>
 * </ul>
 *
 * <p>All methods in this class are pure functions with no side-effects; they can be
 * called from any logging statement.
 *
 * <p>The Logback configuration ({@code logback.xml}) additionally drops MDC keys
 * {@code cookie}, {@code setCookie}, and {@code authorization} from JSON output as a
 * belt-and-suspenders control (D-13, SR-8).
 */
public final class LogScrubber {

    /**
     * Field names that must never appear in log output as MDC keys or structured-log fields.
     * This set is enforced by {@code LoggingSmokeTest}.
     */
    public static final Set<String> FORBIDDEN_LOG_FIELDS = Set.of(
            "cookie", "setCookie", "set-cookie", "authorization", "wallId", "rawWallId"
    );

    /**
     * Pattern for detecting PII-like tokens that should not appear verbatim in log output.
     * Matches UUID-format strings (wallId is a UUID).
     */
    private static final Pattern UUID_PATTERN =
            Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}",
                    Pattern.CASE_INSENSITIVE);

    private LogScrubber() {
        // Utility class — not instantiable
    }

    /**
     * Returns the first 8 hex characters of SHA-256({@code value}) for safe log output.
     *
     * <p>This provides enough entropy to correlate events across log entries without
     * exposing the raw value.  The 8-char prefix corresponds to 32 bits of the digest —
     * collision probability is negligible for operational log correlation.
     *
     * @param value the sensitive value to hash; if {@code null}, returns {@code "null"}
     * @return an 8-character lowercase hex string (always deterministic)
     */
    public static String hash8(final String value) {
        if (value == null) return "null";
        if (value.isBlank()) return "blank";
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes).substring(0, 8);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is guaranteed in every JVM (NIST FIPS 180-4)
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /**
     * Returns {@code true} when {@code message} contains a UUID-pattern string.
     *
     * <p>Used by {@code LoggingSmokeTest} to assert that no log message inadvertently
     * includes a raw wallId value (which is UUID-formatted).
     *
     * @param message a log message string to inspect
     * @return {@code true} if a UUID pattern is detected
     */
    public static boolean containsRawUuid(final String message) {
        if (message == null) return false;
        return UUID_PATTERN.matcher(message).find();
    }

    /**
     * Masks the last octet of an IPv4 address or the last group of an IPv6 address.
     *
     * <p>This is a log-output helper only — the full IP is retained as the rate-limit key.
     *
     * @param ip the IP address string; may be {@code null}
     * @return a partially-masked string safe for log output
     */
    public static String maskIp(final String ip) {
        if (ip == null) return "null";
        int lastDot = ip.lastIndexOf('.');
        int lastColon = ip.lastIndexOf(':');
        if (lastDot > 0) return ip.substring(0, lastDot) + ".xxx";
        if (lastColon > 0) return ip.substring(0, lastColon) + ":xxxx";
        return "redacted";
    }

    /**
     * Truncates a hashtag to its length for safe logging — never logs the value itself.
     *
     * @param hashtag the raw hashtag; may be {@code null}
     * @return a string of the form {@code "len=N"} where N is the hashtag's character count
     */
    public static String hashtagLen(final String hashtag) {
        if (hashtag == null) return "len=null";
        return "len=" + hashtag.length();
    }
}
