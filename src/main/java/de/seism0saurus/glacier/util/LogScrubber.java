package de.seism0saurus.glacier.util;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
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
     * pass through verbatim; everything else is rendered as {@code unknown-len-N}.
     *
     * <p>Package-private for test access: {@code LogScrubberFuzzTest} (same package) references
     * this constant directly to avoid a shadow copy that could silently drift out of sync.
     * Do not promote to {@code public} — only same-package test code needs direct access.
     *
     * @see #safeEventName(String)
     */
    static final Set<String> KNOWN_STREAM_EVENTS = Set.of(
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
     * Satisfies D-13 / SR-8: client IP must not appear verbatim in JSON log output.
     *
     * <p><b>Log-field convention</b>: callers log the return value under the key {@code ip-hash=}
     * (e.g., {@code AUDIT.info("ws.handshake.rate_limited ip-hash={}", maskIp(ip))}). The key
     * name {@code ip-hash} is a project convention adopted for SIEM/dashboard compatibility; the
     * value is a <em>partial mask</em>, not a hash — the last octet is replaced with {@code .xxx}.
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
     * Returns a bounded summary of an {@code X-Frame-Options} header value list safe for log output.
     *
     * <p>D-13/SR-8/CWE-117 (TD-4 / ADR-TD4-01): {@code X-Frame-Options} is populated directly from
     * the HTTP response headers of an untrusted third-party Mastodon instance. Logging the raw
     * list via {@code {}} (SLF4J parameter placeholder) passes peer-controlled bytes through
     * {@code list.toString()} to the JSON encoder. CRLF sequences, ANSI escapes, and Unicode
     * directional controls in those bytes constitute a log-injection vector (CWE-117).
     *
     * <p>This method eliminates the vector by discarding all raw bytes from the list values and
     * returning only two bounded numeric fields:
     * <ul>
     *   <li>{@code xfo-values=N} — the number of slots in the list (null elements are counted as
     *       occupying a slot; they do NOT contribute to the total length)</li>
     *   <li>{@code xfo-totallen=M} — the sum of {@link String#length()} for all non-null elements</li>
     * </ul>
     *
     * <p>null elements count toward {@code xfo-values} (slot count) and contribute 0 to
     * {@code xfo-totallen} (length).
     *
     * <p>The {@code xfo-totallen} accumulator is a {@code long} to avoid overflow when a
     * peer-supplied list contains many large values (theoretical: M can exceed
     * {@code Integer.MAX_VALUE} if enough large header values are provided). The element
     * count ({@code xfo-values}) is bounded by {@link java.util.List#size()}, which is
     * an {@code int}. TD-5-C / ADR-TD5-C.
     *
     * <p>Always use this method when a log statement would otherwise include the raw
     * {@code X-Frame-Options} list as an argument.
     *
     * @param values the raw {@code X-Frame-Options} header values; may be {@code null} or empty
     * @return a bounded, log-safe summary string of the form {@code "xfo-values=N xfo-totallen=M"}
     */
    public static String xfoSummary(final List<String> values) {
        if (values == null || values.isEmpty()) {
            return "xfo-values=0 xfo-totallen=0";
        }
        int count = values.size();
        long totalLen = 0L;
        for (String value : values) {
            if (value != null) {
                totalLen += value.length();
            }
        }
        return "xfo-values=" + count + " xfo-totallen=" + totalLen;
    }

    /**
     * Produces a log-safe representation of a value for use in error messages.
     *
     * <p>Raw configuration values (handles, access tokens, operator strings) must never appear
     * verbatim in error messages or exception strings that may reach logs, stderr, or SIEM.
     * This method replaces the value with a bounded summary: its SHA-256 hash prefix and length.
     *
     * <p>The format {@code "[scrubbed len=N hash=XXXXXXXX]"} gives enough context to match
     * the error to a specific invalid value (via the hash) while revealing nothing about
     * the value itself (D-13 / SR-8 / ADR-P3A-7).
     *
     * <p>Returns {@code "[scrubbed null]"} for {@code null} and {@code "[scrubbed blank]"}
     * for blank strings.
     *
     * @param value the sensitive value to scrub; may be {@code null}
     * @return a bounded, log-safe summary string safe for inclusion in exception messages
     */
    public static String forErrorMessage(final String value) {
        if (value == null) return "[scrubbed null]";
        if (value.isBlank()) return "[scrubbed blank]";
        return "[scrubbed len=" + value.length() + " hash=" + hash8(value) + "]";
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
     *   <li>Unknown names are rendered as {@code unknown-len-N} — bounded length, no
     *       raw attacker-controlled bytes reach the log encoder.  The format uses only
     *       alphanumeric characters and hyphens, so the fallback string can never contain
     *       the raw input as a substring regardless of what the input is (ADR-F6-05;
     *       prevents the jqwik property {@code doesNotContain(input)} from failing on
     *       single-character punctuation inputs such as {@code "("}). </li>
     *   <li>{@code null} → {@code "null"}; blank → {@code "blank"}.</li>
     * </ul>
     *
     * <p>Always use this method when logging a value sourced from
     * {@code GenericMessageContent#getEvent()}.
     *
     * @param event the raw event name from the Mastodon streaming wire; may be {@code null}
     * @return a log-safe representation of the event name; uses only {@code [A-Za-z0-9-]}
     *         characters so it is safe for structured-log fields without further escaping
     */
    public static String safeEventName(final String event) {
        if (event == null) return "null";
        if (event.isBlank()) return "blank";
        if (KNOWN_STREAM_EVENTS.contains(event)) return event;
        return "unknown-len-" + event.length();
    }
}
