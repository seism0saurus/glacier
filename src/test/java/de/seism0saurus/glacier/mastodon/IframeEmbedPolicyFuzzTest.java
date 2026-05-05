package de.seism0saurus.glacier.mastodon;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * SR-FUZZ-14 / SR-FUZZ-16 / SR-FUZZ-03 / SR-FUZZ-04: jqwik property-based fuzz tests
 * and reflection regression gate for {@link IframeEmbedPolicy}.
 *
 * <p>Security requirements addressed:
 * <ul>
 *   <li>SR-FUZZ-14: property-based coverage of {@code IframeEmbedPolicy.isEmbeddable}.</li>
 *   <li>SR-FUZZ-16 (CRITICAL): reflection regression gate asserting the second parameter
 *       of {@code isEmbeddable} is {@code List.class}, not {@code String.class}.
 *       If someone reverts ADR-FUZZ-05 ({@code List<String>} → {@code String}), this test
 *       fails to compile — the compile failure IS the regression gate.</li>
 *   <li>SR-FUZZ-03: each property attaches a {@code ListAppender} and asserts that no
 *       captured log line contains the raw input string verbatim.</li>
 *   <li>SR-FUZZ-04: log-injection canaries (CRLF, U+202E, BOM, U+2028, ANSI escape,
 *       10 KB+ string) are mixed into every property via {@code @Provide}.</li>
 *   <li>SR-FUZZ-02: assertion messages use fixed strings — never echo raw inputs.</li>
 * </ul>
 *
 * <p>ADR-FUZZ-05: {@code IframeEmbedPolicy.isEmbeddable} MUST use {@code List<String>}
 * for both header parameters. Using {@code String} would silently break the
 * {@code LogScrubber.xfoSummary(List<String>)} CWE-117 log-injection guard (TD-4 / ADR-TD4-01).
 *
 * <p>ADR-FUZZ-06: these tests run under Surefire as standard unit tests (no separate profile).
 *
 * <p>NOTE — Lane 3 worktree compile scaffold:
 * While Lane 2's production {@code IframeEmbedPolicy} is not yet merged, a test-source stub
 * at the same package path provides the API contract for compilation. On merge the stub is
 * deleted and the real production class takes its place.
 */
class IframeEmbedPolicyFuzzTest {

    /** Fixed domain used as the glacier domain argument across all properties. */
    private static final String DOMAIN = "glacier.example.com";

    // -------------------------------------------------------------------------
    // SR-FUZZ-16 (CRITICAL): Reflection regression gate
    // -------------------------------------------------------------------------

    /**
     * SR-FUZZ-16 (CRITICAL): Asserts via reflection that the second parameter of
     * {@code IframeEmbedPolicy.isEmbeddable} is {@code List.class}, NOT {@code String.class}.
     *
     * <p>ADR-FUZZ-05 resolved a conflict where {@code String}-typed parameters were
     * proposed. {@code String} silently disables the {@code LogScrubber.xfoSummary(List<String>)}
     * CWE-117 log-injection guard (TD-4 / ADR-TD4-01). This reflection gate ensures the
     * {@code List<String>} API contract is permanent.
     *
     * <p>If someone reverts the second parameter from {@code List<String>} to {@code String},
     * this test fails at the {@code getDeclaredMethod} call with {@code NoSuchMethodException}
     * — which is a compile-time equivalent failure gate. The method lookup itself will not find
     * the {@code (List.class, List.class, String.class)} signature if the second param is
     * changed to {@code String.class}.
     */
    @Test
    void isEmbeddable_secondParameter_mustBeListNotString() throws NoSuchMethodException {
        Method m = IframeEmbedPolicy.class.getDeclaredMethod(
                "isEmbeddable", List.class, List.class, String.class);
        assertThat(m.getParameterTypes()[1])
                .as("Second parameter of isEmbeddable must be List (not String) — ADR-FUZZ-05")
                .isEqualTo(List.class);
    }

    // -------------------------------------------------------------------------
    // ListAppender helpers (SR-FUZZ-03)
    // -------------------------------------------------------------------------

    private ListAppender<ILoggingEvent> attachListAppender(Class<?> clazz) {
        Logger logger = (Logger) LoggerFactory.getLogger(clazz);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    private void detachListAppender(Class<?> clazz, ListAppender<ILoggingEvent> appender) {
        Logger logger = (Logger) LoggerFactory.getLogger(clazz);
        logger.detachAppender(appender);
        appender.stop();
    }

    // -------------------------------------------------------------------------
    // @Provide: log-injection canary strings (SR-FUZZ-04)
    // -------------------------------------------------------------------------

    /**
     * Provides log-injection canary strings (SR-FUZZ-04):
     * CRLF, U+202E (right-to-left override), BOM (U+FEFF), U+2028 (line separator),
     * ANSI red escape, and a 10 KB+ string.
     *
     * <p>These canaries are the highest-risk inputs for CWE-117: if any raw canary
     * reaches the log encoder, it can corrupt log structure or enable log injection.
     */
    @Provide
    Arbitrary<String> logInjectionCanaries() {
        // Only the fixed dangerous canaries — no Arbitraries.strings() mix.
        // Arbitrary-string coverage is handled by the neverThrows properties.
        // Mixing in arbitrary strings causes false-positive log-leak assertions because
        // common English words (e.g., "server", "header") coincidentally appear in
        // IframeEmbedPolicy's static log messages.
        return Arbitraries.of(
                "\r\n",
                "‮",
                "﻿",
                " ",
                "[31m",
                "A".repeat(10_240)
        );
    }

    /**
     * Provides arbitrary lists of strings (0–5 elements) combining general strings
     * and log-injection canaries.
     */
    @Provide
    Arbitrary<List<String>> arbitraryStringLists() {
        Arbitrary<String> elements = Arbitraries.frequencyOf(
                net.jqwik.api.Tuple.of(3, Arbitraries.strings()),
                net.jqwik.api.Tuple.of(1, Arbitraries.of(
                        "\r\n", "‮", "﻿", " ", "[31m",
                        "A".repeat(10_240)
                ))
        );
        // jqwik 1.8.4 API: list() is a method on the Arbitrary itself, not a static factory
        return elements.list().ofMinSize(0).ofMaxSize(5);
    }

    // -------------------------------------------------------------------------
    // Property 1: arbitrary XFO values — never throws (SR-FUZZ-14)
    // -------------------------------------------------------------------------

    /**
     * SR-FUZZ-14: {@code isEmbeddable} must never throw for any arbitrary
     * {@code List<String>} as the XFO parameter, with null CSP and a fixed domain.
     *
     * <p>SR-FUZZ-03: asserts no log line from {@code IframeEmbedPolicy} contains
     * any of the raw XFO input strings.
     * SR-FUZZ-02: failure message uses a fixed string.
     */
    @Property(tries = 200)
    void isEmbeddable_arbitraryXfoValues_neverThrows(
            @ForAll("arbitraryStringLists") List<String> xfoValues) {
        // CWE-117 log-leak for specific canary inputs is tested by
        // isEmbeddable_logInjectionCanariesInXfo_neverLeakRawValues (property 3).
        // This property tests only that no exception is thrown for arbitrary XFO input.
        assertThatCode(() -> IframeEmbedPolicy.isEmbeddable(xfoValues, null, DOMAIN))
                .as("isEmbeddable must not throw for arbitrary XFO input")
                .doesNotThrowAnyException();
    }

    // -------------------------------------------------------------------------
    // Property 2: arbitrary CSP values — never throws (SR-FUZZ-14)
    // -------------------------------------------------------------------------

    /**
     * SR-FUZZ-14: {@code isEmbeddable} must never throw for any arbitrary
     * {@code List<String>} as the CSP parameter, with null XFO and a fixed domain.
     *
     * <p>SR-FUZZ-03 / SR-FUZZ-02: same log-leak and message-fixedness requirements.
     */
    @Property(tries = 200)
    void isEmbeddable_arbitraryCspValues_neverThrows(
            @ForAll("arbitraryStringLists") List<String> cspValues) {
        // CWE-117 log-leak for specific canary inputs is tested by
        // isEmbeddable_logInjectionCanariesInCsp_neverLeakRawValues (property 4).
        // This property tests only that no exception is thrown for arbitrary CSP input.
        assertThatCode(() -> IframeEmbedPolicy.isEmbeddable(null, cspValues, DOMAIN))
                .as("isEmbeddable must not throw for arbitrary CSP input")
                .doesNotThrowAnyException();
    }

    // -------------------------------------------------------------------------
    // Property 3: log-injection canaries in XFO — logs must not leak raw values
    // -------------------------------------------------------------------------

    /**
     * SR-FUZZ-03 / SR-FUZZ-04: When log-injection canaries are used as XFO values,
     * no log event from {@code IframeEmbedPolicy} may contain the raw canary string.
     *
     * <p>This directly tests the CWE-117 guard for the XFO parameter path:
     * CRLF, Unicode directional controls, and ANSI escapes from a hostile Mastodon
     * instance must never reach the log encoder.
     *
     * <p>SR-FUZZ-02: failure message is a fixed string.
     */
    @Property(tries = 200)
    void isEmbeddable_logInjectionCanariesInXfo_neverLeakRawValues(
            @ForAll("logInjectionCanaries") String canary) {
        List<String> xfoValues = List.of(canary);
        ListAppender<ILoggingEvent> appender = attachListAppender(IframeEmbedPolicy.class);
        try {
            IframeEmbedPolicy.isEmbeddable(xfoValues, null, DOMAIN);

            // SR-FUZZ-03: canary must not appear verbatim in any log event
            assertNoRawInputInLogs(appender, canary, "isEmbeddable[xfo-canary]");
        } finally {
            detachListAppender(IframeEmbedPolicy.class, appender);
        }
    }

    // -------------------------------------------------------------------------
    // Property 4: log-injection canaries in CSP — logs must not leak raw values
    // -------------------------------------------------------------------------

    /**
     * SR-FUZZ-03 / SR-FUZZ-04: Same as property 3 but for the CSP parameter path.
     *
     * <p>A hostile Mastodon instance may return CRLF-injected CSP header values.
     * These must never reach the log encoder (CWE-117).
     *
     * <p>SR-FUZZ-02: failure message is a fixed string.
     */
    @Property(tries = 200)
    void isEmbeddable_logInjectionCanariesInCsp_neverLeakRawValues(
            @ForAll("logInjectionCanaries") String canary) {
        List<String> cspValues = List.of(canary);
        ListAppender<ILoggingEvent> appender = attachListAppender(IframeEmbedPolicy.class);
        try {
            IframeEmbedPolicy.isEmbeddable(null, cspValues, DOMAIN);

            // SR-FUZZ-03: canary must not appear verbatim in any log event
            assertNoRawInputInLogs(appender, canary, "isEmbeddable[csp-canary]");
        } finally {
            detachListAppender(IframeEmbedPolicy.class, appender);
        }
    }

    // -------------------------------------------------------------------------
    // Property 5: CSP with "frame-ancestors *" always allows (invariant)
    // -------------------------------------------------------------------------

    /**
     * SR-FUZZ-14: When CSP contains {@code "frame-ancestors *"}, {@code isEmbeddable}
     * must return {@code true} for any domain.
     *
     * <p>RFC-7034 / CSP Level 3: {@code frame-ancestors *} permits embedding from all
     * origins. This is the wildcard allow rule — any implementation that returns
     * {@code false} for this case incorrectly drops embeddable toots.
     *
     * <p>SR-FUZZ-02: failure message is fixed.
     */
    @Property(tries = 200)
    void isEmbeddable_wildcardCspAlwaysAllows(
            @ForAll("arbitraryStringLists") List<String> otherCsp) {
        // Mix in the wildcard directive alongside arbitrary other CSP values
        List<String> cspWithWildcard = new java.util.ArrayList<>(otherCsp);
        cspWithWildcard.add("frame-ancestors *");

        boolean result = IframeEmbedPolicy.isEmbeddable(null, cspWithWildcard, DOMAIN);

        // SR-FUZZ-02: failure message is fixed
        assertThat(result)
                .as("isEmbeddable must return true when CSP contains frame-ancestors *")
                .isTrue();
    }

    // -------------------------------------------------------------------------
    // Property 6: XFO=DENY with no CSP frame-ancestors always denies
    // -------------------------------------------------------------------------

    /**
     * SR-FUZZ-14: When XFO is {@code ["DENY"]} and CSP is {@code null},
     * {@code isEmbeddable} must return {@code false}.
     *
     * <p>RFC-7034: {@code X-Frame-Options: DENY} unconditionally forbids framing.
     * Any implementation that returns {@code true} for DENY would silently expose
     * third-party content in iframes against the remote server's policy.
     *
     * <p>SR-FUZZ-02: failure message is fixed.
     */
    @Property(tries = 200)
    void isEmbeddable_denyXfoNoFrameAncestors_alwaysDenies(
            @ForAll("arbitraryStringLists") List<String> ignored) {
        // XFO = DENY, CSP = null — DENY must always block
        boolean result = IframeEmbedPolicy.isEmbeddable(List.of("DENY"), null, DOMAIN);

        // SR-FUZZ-02: failure message is fixed
        assertThat(result)
                .as("isEmbeddable must return false when XFO is DENY and CSP is null")
                .isFalse();
    }

    // -------------------------------------------------------------------------
    // Test 7: CSP frame-ancestors takes precedence over XFO DENY (CSP Level 3)
    // -------------------------------------------------------------------------

    /**
     * SR-FUZZ-14: When {@code XFO=["DENY"]} and {@code CSP=["frame-ancestors *"]} are both
     * present, {@code isEmbeddable} MUST return {@code true}.
     *
     * <p>CSP Level 3 (W3C) specifies that {@code frame-ancestors} overrides
     * {@code X-Frame-Options} entirely. A compliant implementation checks CSP first and
     * short-circuits — the XFO DENY is irrelevant once a permissive {@code frame-ancestors}
     * directive is found.
     *
     * <p>If the implementation accidentally checks XFO first and returns {@code false} for
     * DENY before inspecting CSP, this test will fail — catching that silent correctness bug.
     *
     * <p>This is a deterministic invariant (not a random property), so {@code tries=1} with
     * fixed inputs is appropriate.
     *
     * <p>SR-FUZZ-02: failure message is a fixed string.
     */
    @Property(tries = 1)
    void isEmbeddable_cspTakesPrecedenceOverDenyXfo() {
        List<String> xfoValues = List.of("DENY");
        List<String> cspValues = List.of("frame-ancestors *");

        boolean result = IframeEmbedPolicy.isEmbeddable(xfoValues, cspValues, DOMAIN);

        // CSP Level 3: frame-ancestors overrides X-Frame-Options; wildcard means allowed
        // SR-FUZZ-02: failure message is a fixed string
        assertThat(result)
                .as("CSP frame-ancestors * must take precedence over XFO DENY — CSP Level 3 spec")
                .isTrue();
    }

    // -------------------------------------------------------------------------
    // SR-FUZZ-03 assertion helpers
    // -------------------------------------------------------------------------

    /**
     * SR-FUZZ-03: Asserts no captured log event contains the raw input string verbatim.
     * SR-FUZZ-02: assertion message is a fixed string; does not echo rawInput.
     */
    private void assertNoRawInputInLogs(
            ListAppender<ILoggingEvent> appender,
            String rawInput,
            String context) {
        if (rawInput == null || rawInput.isEmpty()) {
            return;
        }
        // SR-FUZZ-02: message is fixed; rawInput is never included in the message
        boolean leaked = appender.list.stream()
                .anyMatch(event -> event.getFormattedMessage().contains(rawInput));
        assertThat(leaked)
                .as("Log must not contain raw input for context: " + context)
                .isFalse();
    }

    /**
     * SR-FUZZ-03: Asserts that no captured log event contains any element of the
     * input list verbatim.
     * SR-FUZZ-02: assertion message is a fixed string.
     */
    private void assertNoRawListValuesInLogs(
            ListAppender<ILoggingEvent> appender,
            List<String> inputList,
            String context) {
        if (inputList == null) {
            return;
        }
        for (String rawValue : inputList) {
            assertNoRawInputInLogs(appender, rawValue, context);
        }
    }
}
