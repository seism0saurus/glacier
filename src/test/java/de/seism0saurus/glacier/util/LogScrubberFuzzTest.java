package de.seism0saurus.glacier.util;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SR-FUZZ-02 / SR-FUZZ-03 / SR-FUZZ-04: jqwik property-based fuzz tests for
 * {@link LogScrubber}.
 *
 * <p>Security requirements addressed:
 * <ul>
 *   <li>SR-FUZZ-02: assertion messages MUST NOT echo raw arbitrary inputs — all
 *       failure messages use fixed strings or length/category descriptors only.</li>
 *   <li>SR-FUZZ-03: each property attaches a {@code ListAppender} and asserts that
 *       no captured log line contains the raw input string verbatim.</li>
 *   <li>SR-FUZZ-04: the {@code @Provide logInjectionCanaries} arbitrary mixes in
 *       CRLF, U+202E, BOM, U+2028, ANSI escape, and a 10 KB+ repetition string.</li>
 * </ul>
 *
 * <p>ADR-FUZZ-06: these tests run under Surefire as standard unit tests (no separate
 * Maven profile). jqwik integrates via its JUnit 5 platform engine.
 *
 * <p>D-13 / SR-8: raw wallId / IP / hashtag / cookie values must never reach JSON
 * log output. These properties verify that {@code LogScrubber} methods never let
 * attacker-controlled wire bytes escape to the log encoder.
 */
class LogScrubberFuzzTest {

    /**
     * Raw UUID pattern — {@code hash8} output must never match this.
     * Matches the standard UUID format: 8-4-4-4-12 hex characters.
     * (SR-FUZZ-02: pattern is a constant, not the raw input.)
     */
    private static final Pattern UUID_FORMAT =
            Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}",
                    Pattern.CASE_INSENSITIVE);

    /**
     * Pattern matching a non-negative integer string (digits only).
     * Used to verify {@code hashtagLen} return values.
     */
    private static final Pattern NON_NEGATIVE_INTEGER = Pattern.compile("\\d+");

    // -------------------------------------------------------------------------
    // ListAppender helper
    // -------------------------------------------------------------------------

    /**
     * Attaches a fresh {@code ListAppender} to the given class's logger and returns it.
     * The appender is started before being attached.
     *
     * @param clazz the class whose logger should be monitored
     * @return a started {@code ListAppender} capturing all log events from that logger
     */
    private ListAppender<ILoggingEvent> attachListAppender(Class<?> clazz) {
        Logger logger = (Logger) LoggerFactory.getLogger(clazz);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    /**
     * Detaches the appender from the logger and stops it to avoid cross-test pollution.
     *
     * @param clazz    the class whose logger was monitored
     * @param appender the appender to detach and stop
     */
    private void detachListAppender(Class<?> clazz, ListAppender<ILoggingEvent> appender) {
        Logger logger = (Logger) LoggerFactory.getLogger(clazz);
        logger.detachAppender(appender);
        appender.stop();
    }

    // -------------------------------------------------------------------------
    // @Provide: log-injection canary strings (SR-FUZZ-04)
    // -------------------------------------------------------------------------

    /**
     * Provides strings that are known log-injection canaries (SR-FUZZ-04):
     * CRLF, U+202E (right-to-left override), BOM (U+FEFF), U+2028 (line separator),
     * ANSI red escape, and a 10 KB+ string of repeated 'A' characters.
     *
     * <p>These are mixed into regular arbitrary strings so that every property sees
     * both ordinary inputs and the worst-case attacker-controlled inputs.
     */
    @Provide
    Arbitrary<String> logInjectionCanaries() {
        Arbitrary<String> canaries = Arbitraries.of(
                "\r\n",
                "‮",
                "﻿",
                " ",
                "[31m",
                "A".repeat(10_240)
        );
        // Interleave canaries with general arbitrary strings so properties see both
        return Arbitraries.frequencyOf(
                net.jqwik.api.Tuple.of(3, Arbitraries.strings()),
                net.jqwik.api.Tuple.of(1, canaries)
        );
    }

    // -------------------------------------------------------------------------
    // Property 1: hash8 — never returns raw UUID pattern (SR-FUZZ-02/03/04)
    // -------------------------------------------------------------------------

    /**
     * SR-FUZZ-02/03/04: {@code hash8} must never throw, and its return value must
     * never match the raw UUID format (8-4-4-4-12 hex) regardless of input content.
     *
     * <p>This guards against a hypothetical implementation bug where {@code hash8}
     * returns the input unchanged for UUID-shaped inputs — which would expose
     * raw wallId values in log output (D-13 / SR-8).
     *
     * <p>SR-FUZZ-03: after each invocation, asserts that no captured log line contains
     * the raw input string verbatim.
     * SR-FUZZ-02: the failure message uses a fixed string, never the raw input.
     */
    @Property(tries = 200)
    void hash8_neverReturnsRawUuidPattern(@ForAll("logInjectionCanaries") String input) {
        ListAppender<ILoggingEvent> appender = attachListAppender(LogScrubber.class);
        try {
            String result = LogScrubber.hash8(input);

            // SR-FUZZ-02: failure message is fixed — does not echo raw input
            assertThat(result)
                    .as("hash8 should not return raw UUID pattern")
                    .doesNotMatch(UUID_FORMAT.pattern());

            // SR-FUZZ-03: log must not contain raw input verbatim
            assertNoRawInputInLogs(appender, input, "hash8");
        } finally {
            detachListAppender(LogScrubber.class, appender);
        }
    }

    // -------------------------------------------------------------------------
    // Property 2: maskIp — never exposes last octet of IPv4 (SR-FUZZ-02/03/04)
    // -------------------------------------------------------------------------

    /**
     * SR-FUZZ-02/03/04: {@code maskIp} must never throw.
     *
     * <p>If the input matches an IPv4 address pattern ({@code \d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}}),
     * the output must end with {@code .xxx} — meaning the last octet is masked and
     * never appears verbatim in the log-safe result.
     *
     * <p>SR-FUZZ-03: asserts no log line contains the raw input string.
     * SR-FUZZ-02: failure messages use fixed strings.
     */
    @Property(tries = 200)
    void maskIp_neverExposesLastOctet(@ForAll("logInjectionCanaries") String input) {
        ListAppender<ILoggingEvent> appender = attachListAppender(LogScrubber.class);
        try {
            String result = LogScrubber.maskIp(input);

            // SR-FUZZ-02: failure message is fixed
            assertThat(result).as("maskIp should never throw and always return a value").isNotNull();

            // If input looks like IPv4, output must end with .xxx
            if (input != null && input.matches("\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}")) {
                assertThat(result)
                        .as("maskIp output must end with .xxx for IPv4-shaped input")
                        .endsWith(".xxx");
            }

            // SR-FUZZ-03: log must not contain raw input verbatim
            assertNoRawInputInLogs(appender, input, "maskIp");
        } finally {
            detachListAppender(LogScrubber.class, appender);
        }
    }

    // -------------------------------------------------------------------------
    // Property 3: hashtagLen — never throws, output is non-negative integer
    // -------------------------------------------------------------------------

    /**
     * SR-FUZZ-02/03/04: {@code hashtagLen} must never throw and must return a
     * non-negative integer for all inputs.
     *
     * <p>The return type is {@code int}; this property verifies via
     * {@code >= 0} — no letters or special characters should appear in the result,
     * consistent with the implementation returning only a raw length count.
     *
     * <p>SR-FUZZ-03: asserts no log line contains the raw input string.
     * SR-FUZZ-02: failure messages use fixed strings.
     */
    @Property(tries = 200)
    void hashtagLen_neverThrows_outputIsLengthOnly(@ForAll("logInjectionCanaries") String input) {
        ListAppender<ILoggingEvent> appender = attachListAppender(LogScrubber.class);
        try {
            int result = LogScrubber.hashtagLen(input);

            // SR-FUZZ-02: failure message is fixed
            assertThat(result)
                    .as("hashtagLen must return a non-negative integer")
                    .isGreaterThanOrEqualTo(0);

            // SR-FUZZ-03: log must not contain raw input verbatim
            assertNoRawInputInLogs(appender, input, "hashtagLen");
        } finally {
            detachListAppender(LogScrubber.class, appender);
        }
    }

    // -------------------------------------------------------------------------
    // Property 4: safeEventName — never throws, output is allowlisted or sentinel
    // -------------------------------------------------------------------------

    /**
     * SR-FUZZ-02/03/04: {@code safeEventName} must never throw.
     *
     * <p>The result must be either:
     * <ul>
     *   <li>One of the 12 known Mastodon 4.3 streaming event names, or</li>
     *   <li>The sentinel {@code "null"} (for null input),</li>
     *   <li>The sentinel {@code "blank"} (for blank input),</li>
     *   <li>A string matching {@code unknown(len=N)} for all other inputs.</li>
     * </ul>
     *
     * <p>Critically: no raw attacker-controlled bytes may appear in the output
     * for non-allowlisted inputs (CWE-117 guard — ADR-F6-05).
     *
     * <p>SR-FUZZ-03: asserts no log line contains the raw input string.
     * SR-FUZZ-02: failure messages use fixed strings.
     */
    @Property(tries = 200)
    void safeEventName_neverThrows_outputIsAllowlisted(@ForAll("logInjectionCanaries") String input) {
        ListAppender<ILoggingEvent> appender = attachListAppender(LogScrubber.class);
        try {
            String result = LogScrubber.safeEventName(input);

            // SR-FUZZ-02: failure message is fixed
            assertThat(result)
                    .as("safeEventName must return a non-null value")
                    .isNotNull();

            // Result must be one of: known event, "null", "blank", or "unknown(len=N)"
            boolean isKnownEvent = KNOWN_STREAM_EVENTS.contains(result);
            boolean isSentinel = "null".equals(result) || "blank".equals(result);
            boolean isUnknownPattern = result.startsWith("unknown(len=") && result.endsWith(")");

            assertThat(isKnownEvent || isSentinel || isUnknownPattern)
                    .as("safeEventName output must be a known event name, sentinel, or unknown(len=N) pattern")
                    .isTrue();

            // For non-allowlisted inputs: raw input bytes must NOT appear in the result
            if (!isKnownEvent) {
                // The output must not contain the raw input at all (CWE-117 guard)
                if (input != null && !input.isBlank() && !isKnownEvent) {
                    assertThat(result)
                            .as("safeEventName must not echo raw input for non-allowlisted values")
                            .doesNotContain(input);
                }
            }

            // SR-FUZZ-03: log must not contain raw input verbatim
            assertNoRawInputInLogs(appender, input, "safeEventName");
        } finally {
            detachListAppender(LogScrubber.class, appender);
        }
    }

    // -------------------------------------------------------------------------
    // Property 5: xfoSummary — never exposes raw values (SR-FUZZ-02/03/04)
    // -------------------------------------------------------------------------

    /**
     * SR-FUZZ-02/03/04: {@code xfoSummary} must never throw and the output string
     * must NOT contain any of the raw input values verbatim.
     *
     * <p>This is the core CWE-117 guard property (TD-4 / ADR-TD4-01): an untrusted
     * Mastodon instance may inject CRLF, ANSI escapes, or Unicode directional controls
     * in X-Frame-Options header values. {@code xfoSummary} must discard all raw bytes
     * and return only numeric counts.
     *
     * <p>SR-FUZZ-03: asserts no log line from {@code LogScrubber} contains any of the
     * raw input strings from the list.
     * SR-FUZZ-02: failure messages use fixed strings.
     */
    @Property(tries = 200)
    void xfoSummary_neverExposesRawValues(
            @ForAll("listOfStrings") List<String> inputList) {
        ListAppender<ILoggingEvent> appender = attachListAppender(LogScrubber.class);
        try {
            String result = LogScrubber.xfoSummary(inputList);

            // SR-FUZZ-02: failure message is fixed
            assertThat(result)
                    .as("xfoSummary must return a non-null value")
                    .isNotNull();

            // Output must match the "xfo-values=N xfo-totallen=M" format
            assertThat(result)
                    .as("xfoSummary output must match the safe summary format")
                    .matches("xfo-values=\\d+ xfo-totallen=\\d+");

            // Critical CWE-117 guard: the output must ONLY contain safe characters.
            // The format "xfo-values=N xfo-totallen=M" uses only lowercase letters,
            // digits, hyphens, equals signs, and a single ASCII space separator.
            // Asserting the regex match above is the primary guard; additionally,
            // assert that multi-character raw values with injection-dangerous content
            // do not appear verbatim (e.g., CRLF, ANSI, Unicode directional controls).
            // Single-character inputs are excluded because they trivially appear in
            // the fixed format string (e.g., a digit or space character from the canary
            // set may coincide with the format string's own separator or count digits).
            if (inputList != null) {
                for (String rawValue : inputList) {
                    if (rawValue != null && rawValue.length() > 1) {
                        // Only assert non-containment for multi-char strings that have
                        // real injection potential; the format-only regex above guards
                        // against single-char leakage in full.
                        assertThat(result)
                                .as("xfoSummary output must not contain any multi-char raw input value verbatim (CWE-117)")
                                .doesNotContain(rawValue);
                    }
                }
            }

            // SR-FUZZ-03: log must not contain any of the raw input strings
            if (inputList != null) {
                for (String rawValue : inputList) {
                    assertNoRawInputInLogs(appender, rawValue, "xfoSummary");
                }
            }
        } finally {
            detachListAppender(LogScrubber.class, appender);
        }
    }

    /**
     * Provides arbitrary lists of strings with 0–5 elements for {@code xfoSummary} testing.
     * Includes both general strings and log-injection canaries.
     */
    @Provide
    Arbitrary<List<String>> listOfStrings() {
        Arbitrary<String> elements = Arbitraries.frequencyOf(
                net.jqwik.api.Tuple.of(3, Arbitraries.strings()),
                net.jqwik.api.Tuple.of(1, Arbitraries.of(
                        "\r\n", "‮", "﻿", " ", "[31m",
                        "A".repeat(10_240)
                ))
        );
        // jqwik 1.8.4 API: list() is a method on the Arbitrary itself, not a static factory
        return elements.list().ofMinSize(0).ofMaxSize(5);
    }

    // -------------------------------------------------------------------------
    // SR-FUZZ-03 assertion helper
    // -------------------------------------------------------------------------

    /**
     * SR-FUZZ-03: Asserts that no log event captured by the given {@code ListAppender}
     * has a formatted message that contains the raw input string verbatim.
     *
     * <p>SR-FUZZ-02: the assertion message is a fixed string; it does NOT echo the
     * raw input value.
     *
     * @param appender  the ListAppender that captured log events
     * @param rawInput  the raw input that must not appear verbatim in any log event
     * @param methodName the name of the method under test (for fixed assertion message)
     */
    private void assertNoRawInputInLogs(
            ListAppender<ILoggingEvent> appender,
            String rawInput,
            String methodName) {
        if (rawInput == null || rawInput.isEmpty()) {
            return; // null/empty cannot leak meaningfully
        }
        // SR-FUZZ-02: assertion message is fixed; does not echo rawInput
        boolean leaked = appender.list.stream()
                .anyMatch(event -> event.getFormattedMessage().contains(rawInput));
        assertThat(leaked)
                .as("Log must not contain raw input for method " + methodName)
                .isFalse();
    }

    // -------------------------------------------------------------------------
    // Property 6: urlHostHash — never throws, output never contains raw input
    // -------------------------------------------------------------------------

    /**
     * SR-FUZZ-02/03/04: {@code urlHostHash} must never throw unhandled exceptions for
     * any arbitrary string input, including adversarial SSRF-relevant schemes
     * ({@code jar://}, {@code file:///etc/passwd}, {@code data:text/html,...}) and
     * inputs with embedded newlines or log-injection canaries.
     *
     * <p>Additional invariant: the output (an 8-char hex hash or hash of sentinel
     * "unparseable") must NOT contain the raw input string verbatim — ensuring that
     * attacker-controlled URL bytes never escape to log output in any form.
     *
     * <p>SR-FUZZ-03: asserts no log line from {@code LogScrubber} contains the raw input.
     * SR-FUZZ-02: failure messages use fixed strings; never echo raw input.
     */
    @Property(tries = 200)
    void urlHostHash_arbitraryUrls_neverThrows(@ForAll("logInjectionCanaries") String input) {
        ListAppender<ILoggingEvent> appender = attachListAppender(LogScrubber.class);
        try {
            // Must never throw — IllegalArgumentException for malformed URIs must be caught internally
            String result = LogScrubber.urlHostHash(input);

            // SR-FUZZ-02: failure message is fixed
            assertThat(result)
                    .as("urlHostHash must return a non-null 8-char hex string")
                    .isNotNull()
                    .hasSize(8)
                    .matches("[0-9a-f]{8}");

            // Output must not contain raw input verbatim (CWE-117 / SSRF audit log guard)
            // Only check for non-trivial inputs to avoid false positives on 1-char inputs
            // that could appear in the fixed hex output by coincidence.
            if (input != null && input.length() > 8) {
                assertThat(result)
                        .as("urlHostHash output must not contain raw input verbatim")
                        .doesNotContain(input);
            }

            // SR-FUZZ-03: log must not contain raw input verbatim
            assertNoRawInputInLogs(appender, input, "urlHostHash");
        } finally {
            detachListAppender(LogScrubber.class, appender);
        }
    }

    /**
     * SR-FUZZ-02: Port-normalisation branch: an https URL with explicit port 443 must produce
     * the same hash as the same URL without an explicit port, because both resolve to
     * {@code "host:443"} when normalised.
     *
     * <p>Mutants that skip the {@code port == -1} branch or hard-code {@code 80} for all
     * schemes would produce a different hash for the no-port case, failing this assertion.
     *
     * <p>Verified from source: {@code urlHostHash} defaults port to 443 for https when no
     * explicit port is present, and to 80 for all other schemes. The hash input is
     * {@code "host:port"} — so {@code https://example.com} → hash("example.com:443") and
     * {@code https://example.com:443} → hash("example.com:443"). They must be equal.
     */
    @Property(tries = 1)
    void urlHostHash_httpsNoPort_equalsExplicitPort443() {
        String withoutPort = LogScrubber.urlHostHash("https://example.com/some/path");
        String withExplicitPort = LogScrubber.urlHostHash("https://example.com:443/some/path");

        // SR-FUZZ-02: failure message is fixed
        assertThat(withoutPort)
                .as("https URL without port must hash the same as with explicit port 443 (normalisation branch)")
                .isEqualTo(withExplicitPort);
    }

    /**
     * Port-normalisation branch: an http URL without explicit port must produce the same
     * hash as the same URL with explicit port 80, because both resolve to {@code "host:80"}.
     *
     * <p>Mutants that hard-code 443 for all schemes or skip the http/80 branch fail here.
     */
    @Property(tries = 1)
    void urlHostHash_httpNoPort_equalsExplicitPort80() {
        String withoutPort = LogScrubber.urlHostHash("http://example.com/some/path");
        String withExplicitPort = LogScrubber.urlHostHash("http://example.com:80/some/path");

        // SR-FUZZ-02: failure message is fixed
        assertThat(withoutPort)
                .as("http URL without port must hash the same as with explicit port 80 (normalisation branch)")
                .isEqualTo(withExplicitPort);
    }

    /**
     * Cross-scheme asymmetry: {@code https://host} (→ port 443) must NOT produce the same hash
     * as {@code http://host} (→ port 80) — they are different logical hosts.
     *
     * <p>Mutants that treat both schemes as port 80 or always return the same value would
     * be killed by this assertion.
     */
    @Property(tries = 1)
    void urlHostHash_httpsPort443_differsFromHttpPort80() {
        String httpsHash = LogScrubber.urlHostHash("https://example.com/");
        String httpHash = LogScrubber.urlHostHash("http://example.com/");

        // SR-FUZZ-02: failure message is fixed
        assertThat(httpsHash)
                .as("https (port 443) hash must differ from http (port 80) hash for same host")
                .isNotEqualTo(httpHash);
    }

    // -------------------------------------------------------------------------
    // Known-event allowlist — direct reference to LogScrubber.KNOWN_STREAM_EVENTS
    // -------------------------------------------------------------------------

    /**
     * Reference to the package-private {@code LogScrubber.KNOWN_STREAM_EVENTS} constant.
     *
     * <p>Maintenance note: this field is NOT a shadow copy — it references the authoritative
     * constant from {@code LogScrubber} directly. If {@code LogScrubber} adds a new event name,
     * this test automatically picks it up. Do not replace this reference with a local Set literal.
     *
     * @see LogScrubber#safeEventName(String)
     */
    private static final java.util.Set<String> KNOWN_STREAM_EVENTS = LogScrubber.KNOWN_STREAM_EVENTS;
}
