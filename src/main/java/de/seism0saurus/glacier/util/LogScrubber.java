package de.seism0saurus.glacier.util;

import java.net.URI;
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
 *   <li>Raw session identifiers</li>
 *   <li>Unvalidated Mastodon streaming event names (CWE-117 log injection guard)</li>
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

    /**
     * Allowlist of documented Mastodon 4.3 streaming event names.
     *
     * <p>ADR-F6-05: {@code genericMessageContent.getEvent()} originates from the Mastodon
     * streaming wire — a hostile or compromised instance can inject CRLF / control characters
     * (CWE-117 log injection). This allowlist is the CWE-117 guard: only values in this set
     * pass through verbatim; everything else is rendered as {@code unknown(len=N)}.
     *
     * @see #safeEventName(String)
     */
    private static final Set<String> KNOWN_STREAM_EVENTS = Set.of(
            "update", "status.update", "delete", "status.delete",
            "filters_changed", "announcement", "announcement.reaction",
            "announcement.delete", "encrypted_message", "notification", "conversation",
            "notifications_merged"
    );

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
     * <p>Returns {@code "null"} when {@code value} is {@code null}.
     * Returns {@code "blank"} when {@code value} is blank (empty or whitespace only).
     *
     * @param value the sensitive value to hash; if {@code null}, returns {@code "null"}
     * @return an 8-character lowercase hex string (always deterministic)
     */
    public static String hash8(final String value) {
        if (value == null) return "null";
        if (value.isBlank()) return "blank";
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
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
     * Satisfies D-13 / SR-8: client IP must not appear verbatim in JSON log output.
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
     * Returns the character length of the given hashtag as a safe log-field value.
     *
     * <p>Logging the length rather than the raw value satisfies D-13 requirements
     * while still providing enough signal to distinguish blank, short, and
     * suspiciously long inputs in audit events.
     *
     * <p>Returns {@code 0} when {@code hashtag} is {@code null}.
     *
     * @param hashtag the raw hashtag string; may be {@code null}
     * @return the character length of the hashtag, or {@code 0}
     */
    public static int hashtagLen(final String hashtag) {
        if (hashtag == null) return 0;
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

    /**
     * Returns the streaming event name verbatim if it is on the Mastodon 4.3 allowlist;
     * otherwise returns a bounded fallback that prevents CWE-117 log injection.
     *
     * <p>ADR-F6-05: {@code genericMessageContent.getEvent()} arrives from the Mastodon
     * streaming wire. A hostile or compromised instance can inject CRLF sequences or
     * control characters to corrupt log entries. This method is the CWE-117 guard:
     * <ul>
     *   <li>Allowlisted event names pass through unchanged — safe for structured logs.</li>
     *   <li>Unknown names are rendered as {@code unknown(len=N)} — bounded length, no
     *       raw attacker-controlled bytes reach the log encoder.</li>
     *   <li>{@code null} → {@code "null"}; blank → {@code "blank"}.</li>
     * </ul>
     *
     * <p>Always use this method when logging a value sourced from
     * {@code GenericMessageContent#getEvent()}.
     *
     * @param event the raw event name from the Mastodon streaming wire; may be {@code null}
     * @return a log-safe representation of the event name
     */
    public static String safeEventName(final String event) {
        if (event == null) return "null";
        if (event.isBlank()) return "blank";
        if (KNOWN_STREAM_EVENTS.contains(event)) return event;
        return "unknown(len=" + event.length() + ")";
    }
}
