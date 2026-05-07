package de.seism0saurus.glacier.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Sec-25: AUDIT event naming convention gate.
 *
 * <h2>Requirement</h2>
 * <p>All AUDIT log calls in rate-limiting classes must use event names that conform to
 * the {@code <domain>.<event>} or {@code <domain>.<subdomain>.<event>} naming convention
 * (all-lowercase, dot-separated, snake_case segments, no spaces, no raw PII).
 *
 * <h2>Why this matters</h2>
 * <p>Inconsistent AUDIT event names break log-aggregation pipelines, make alerting rules
 * fragile, and can accidentally introduce whitespace or mixed-case tokens that are silently
 * swallowed by SIEM parsers. A structural test catches naming drift at commit time before
 * it reaches production logs.
 *
 * <h2>Scope</h2>
 * <p>This test scans the following rate-limiting classes (Sec-18/P2-17 expansion targets):
 * <ul>
 *   <li>{@code HandshakeRateLimitInterceptor} — WebSocket handshake rate limit</li>
 *   <li>{@code SubscribeRateLimitInterceptor} — STOMP SUBSCRIBE rate limit</li>
 *   <li>{@code FallbackRateLimiter} — HTTP fallback rate limit</li>
 *   <li>{@code ShareRateLimiter} — share-link creation/CSRF/fallback/imgproxy rate limit</li>
 * </ul>
 *
 * <h2>Convention enforced</h2>
 * <p>The first positional argument to every {@code AUDIT.info()} / {@code AUDIT.warn()} /
 * {@code AUDIT.error()} call must:
 * <ol>
 *   <li>Start with a domain word ({@code [a-z]+}) followed by a dot.</li>
 *   <li>Consist only of lowercase letters, digits, underscores, and dots.</li>
 *   <li>Contain at least one dot (i.e., minimum two segments).</li>
 *   <li>Not contain raw spaces or upper-case letters in the event name part.</li>
 * </ol>
 *
 * <p>Key=value pairs that follow the event name (e.g., {@code axis=wallId}) are not
 * checked by this test — they are validated in class-specific tests.
 *
 * <p>Security: D-13 / SR-8 — structured AUDIT events; OWASP C9 — security events logged
 * consistently and parsably.
 */
class AuditEventNamingConventionTest {

    /**
     * Pattern to extract the first string literal argument from {@code AUDIT.*(...)}.
     * Captures the event name (first quoted segment before any space or closing quote).
     *
     * <p>Format: {@code AUDIT.info("domain.event.action key=value")} or
     * {@code AUDIT.info("domain.event.action key={}", ...)}.
     */
    private static final Pattern AUDIT_CALL = Pattern.compile(
            "AUDIT\\s*\\.\\s*(?:info|warn|error|debug)\\s*\\(\\s*\"([^\"]+)\"");

    /**
     * Valid event name part: {@code [a-z][a-z0-9_]*([.][a-z][a-z0-9_]*)+}.
     * The event name is the part before any space (key=value pairs are separate).
     */
    private static final Pattern VALID_EVENT_NAME = Pattern.compile(
            "^[a-z][a-z0-9_]*(?:\\.[a-z][a-z0-9_]*)+$");

    /**
     * Source files whose AUDIT event names must conform to the convention.
     * Sec-25: rate-limiting classes are the primary scope; the list is kept
     * explicit (no wildcards) to match ADR-FUZZ-01 discipline.
     */
    private static final List<String> RATE_LIMIT_CLASS_PATHS = List.of(
            "src/main/java/de/seism0saurus/glacier/webservice/security/HandshakeRateLimitInterceptor.java",
            "src/main/java/de/seism0saurus/glacier/webservice/security/SubscribeRateLimitInterceptor.java",
            "src/main/java/de/seism0saurus/glacier/webservice/cache/FallbackRateLimiter.java",
            "src/main/java/de/seism0saurus/glacier/share/web/ShareRateLimiter.java"
    );

    @Test
    void auditEvents_rateLimitingClasses_useConsistentNamingConvention() throws IOException {
        Path repoRoot = findRepoRoot();
        List<String> violations = new ArrayList<>();

        for (String relativePath : RATE_LIMIT_CLASS_PATHS) {
            Path file = repoRoot.resolve(relativePath);
            assertThat(file)
                    .as("Rate-limiting class must exist: " + relativePath)
                    .exists();

            List<String> lines = Files.readAllLines(file);
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i).trim();
                Matcher m = AUDIT_CALL.matcher(line);
                while (m.find()) {
                    String fullMessage = m.group(1);
                    // Event name is the first whitespace-delimited token
                    String eventName = fullMessage.split("\\s+")[0];
                    if (!VALID_EVENT_NAME.matcher(eventName).matches()) {
                        violations.add(String.format(
                                "%s:%d — event name '%s' in message '%s' violates convention "
                                        + "[a-z][a-z0-9_]*([.][a-z][a-z0-9_]*)+",
                                relativePath, i + 1, eventName, fullMessage));
                    }
                }
            }
        }

        assertThat(violations)
                .as("AUDIT event names in rate-limiting classes must use the "
                        + "<domain>.<subdomain>.<action> naming convention "
                        + "(Sec-25, D-13, SR-8, OWASP C9). Violations found:\n"
                        + String.join("\n", violations))
                .isEmpty();
    }

    @Test
    void auditEvents_rateLimitingClasses_containRatelimitKeyword() throws IOException {
        Path repoRoot = findRepoRoot();

        for (String relativePath : RATE_LIMIT_CLASS_PATHS) {
            Path file = repoRoot.resolve(relativePath);
            List<String> auditCalls = extractAuditEventNames(file);

            assertThat(auditCalls)
                    .as("Rate-limiting class '" + relativePath + "' must contain at least one "
                            + "AUDIT log call (Sec-25 — rate-limit events must be logged).")
                    .isNotEmpty();
        }
    }

    @Test
    void auditEvents_rateLimitingClasses_eventNamesContainRateLimitOrRatelimit() throws IOException {
        Path repoRoot = findRepoRoot();
        List<String> missingRatelimitEvents = new ArrayList<>();

        for (String relativePath : RATE_LIMIT_CLASS_PATHS) {
            Path file = repoRoot.resolve(relativePath);
            List<String> auditEvents = extractAuditEventNames(file);

            boolean hasRatelimitEvent = auditEvents.stream()
                    .anyMatch(e -> e.contains("ratelimit") || e.contains("rate_limit"));

            if (!hasRatelimitEvent) {
                missingRatelimitEvents.add(relativePath
                        + " — AUDIT events: " + auditEvents);
            }
        }

        assertThat(missingRatelimitEvents)
                .as("Each rate-limiting class must log at least one AUDIT event "
                        + "whose name contains 'ratelimit' or 'rate_limit' (Sec-25). "
                        + "This confirms rate-limit events are distinguishable in SIEM queries.\n"
                        + "Missing:\n" + String.join("\n", missingRatelimitEvents))
                .isEmpty();
    }

    @Test
    void auditEvents_rateLimitingClasses_noRawIpInEventNames() throws IOException {
        // Verify no AUDIT call embeds a literal IP address directly in the event name.
        // The IP must appear as a key=value pair using LogScrubber.maskIp().
        Path repoRoot = findRepoRoot();
        // IPv4 octet pattern that would indicate a raw IP in the message template string
        Pattern rawIpInTemplate = Pattern.compile("\\b\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\b");

        for (String relativePath : RATE_LIMIT_CLASS_PATHS) {
            Path file = repoRoot.resolve(relativePath);
            List<String> lines = Files.readAllLines(file);
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i).trim();
                Matcher m = AUDIT_CALL.matcher(line);
                while (m.find()) {
                    String template = m.group(1);
                    if (rawIpInTemplate.matcher(template).find()) {
                        assertThat(false)
                                .as("AUDIT call at " + relativePath + ":" + (i + 1)
                                        + " contains a raw IP address in the message template: '"
                                        + template + "'. Use LogScrubber.maskIp() instead "
                                        + "(D-13, SR-8 — no raw IPs in AUDIT events).")
                                .isTrue();
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private List<String> extractAuditEventNames(Path file) throws IOException {
        List<String> eventNames = new ArrayList<>();
        List<String> lines = Files.readAllLines(file);
        for (String line : lines) {
            Matcher m = AUDIT_CALL.matcher(line.trim());
            while (m.find()) {
                String msg = m.group(1);
                eventNames.add(msg.split("\\s+")[0]);
            }
        }
        return eventNames;
    }

    private Path findRepoRoot() throws IOException {
        Path candidate = Paths.get("").toAbsolutePath();
        while (candidate != null) {
            if (Files.exists(candidate.resolve("pom.xml"))) {
                return candidate;
            }
            candidate = candidate.getParent();
        }
        throw new IOException("Could not locate pom.xml above: " + Paths.get("").toAbsolutePath());
    }
}
