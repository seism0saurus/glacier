package de.seism0saurus.glacier.mastodon;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Parity test: every input that the legacy {@code StompCallback.getShortHandle(String)} rejected
 * must also be rejected by {@link MastodonShortHandle#parse(String)} (SR-P3A-06).
 *
 * <p>The legacy method rejected:
 * <ul>
 *   <li>{@code null} — {@link IllegalArgumentException} "A mastodon handle is needed"</li>
 *   <li>a string with no internal {@code @} after stripping the optional leading {@code @} —
 *       {@link IllegalArgumentException} "does not contain an @…"</li>
 *   <li>a string whose local part (before the first internal {@code @}) is blank —
 *       {@link IllegalArgumentException} "empty local part"</li>
 * </ul>
 *
 * <p>{@code parse()} must throw for all of these inputs AND for the additional new rejection
 * cases (multiple {@code @}, control characters, Unicode overrides, length cap). This test
 * only covers the legacy subset to demonstrate the "strict superset" property.
 *
 * <p>Note: {@code null} is special — legacy threw {@link IllegalArgumentException} but
 * {@code parse()} throws {@link NullPointerException} (distinguishing "missing" from
 * "invalid"). Both are runtime exceptions and both reject the input, so the superset
 * property holds: the input is rejected in both cases.
 */
class MastodonShortHandleVsLegacyParityTest {

    /**
     * Returns all inputs that the legacy {@code getShortHandle(String)} rejected.
     *
     * <p>These cases are documented in {@link StompCallbackTest}:
     * <ul>
     *   <li>{@code "peter.kropotkin"} — no {@code @} separator</li>
     *   <li>{@code ""} (empty string) — blank, no {@code @}</li>
     *   <li>{@code "@"} — only a leading {@code @}, empty local part after stripping</li>
     *   <li>{@code "@instance.social"} — after stripping leading {@code @}, local part is empty</li>
     * </ul>
     *
     * <p>Note: {@code null} is handled separately in
     * {@link MastodonShortHandleTest#parse_null_throwsNpe()} because parse() throws NPE,
     * not IAE, for null — both reject the value.
     */
    static Stream<String> legacyRejectedInputs() {
        return Stream.of(
                "peter.kropotkin",     // no internal @ → getShortHandle: "does not contain an @"
                "",                    // blank → no @ check fires
                "@",                   // stripped to "" → local part blank
                "@instance.social"     // stripped to "@instance.social", local part is ""
        );
    }

    /**
     * Parameterized assertion: every input the legacy method rejected must cause
     * {@link MastodonShortHandle#parse(String)} to throw a {@link RuntimeException}.
     *
     * <p>We assert on {@link RuntimeException} rather than a specific subtype because
     * {@code parse()} may throw {@link IllegalArgumentException} or
     * {@link NullPointerException} — both are runtime exceptions that signal rejection.
     *
     * <p>Arrange: an input that {@code getShortHandle} rejected.
     * Act: {@code MastodonShortHandle.parse(input)}.
     * Assert: a {@link RuntimeException} is thrown — the input is rejected.
     */
    @ParameterizedTest(name = "legacy-rejected input \"{0}\" must be rejected by parse()")
    @MethodSource("legacyRejectedInputs")
    void legacyRejectedInput_isAlsoRejectedByParse(String input) {
        assertThrows(RuntimeException.class, () -> MastodonShortHandle.parse(input));
    }
}
