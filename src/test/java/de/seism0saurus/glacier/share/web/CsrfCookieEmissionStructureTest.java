package de.seism0saurus.glacier.share.web;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * Structural gate: prevent re-introduction of the Cookie API dual-emission anti-pattern
 * in {@code de.seism0saurus.glacier.share.web}.
 *
 * <p>Before the fix (ADR-1, SR-CSRF-13), {@link CsrfTokenCookieFactory} and
 * {@link ShareViewerCookieFactory} called both {@code response.addCookie(Cookie)} AND
 * {@code response.addHeader("Set-Cookie", ...)} for the same cookie name, producing two
 * {@code Set-Cookie} headers — a violation of RFC 6265 / invariant I-CSRF-1.
 *
 * <p>This gate has five test cases:
 * <ol>
 *   <li><b>Vacuous-pass canary</b> — asserts the class under guard is loadable and the
 *       package is non-empty, preventing silent passes if the class is renamed.</li>
 *   <li><b>Ban {@code addCookie}</b> (ArchUnit) — no production class in {@code share.web}
 *       calls {@link jakarta.servlet.http.HttpServletResponse#addCookie}.</li>
 *   <li><b>Ban raw {@code addHeader("Set-Cookie", ...)} literal string</b> (source regex) — no
 *       source file in {@code share/web} contains the literal string
 *       {@code addHeader("Set-Cookie"} (only the constant form
 *       {@code addHeader(HttpHeaders.SET_COOKIE, ...)} is permitted).</li>
 *   <li><b>Require {@code ResponseCookie} import</b> (ArchUnit) — every production class in
 *       {@code share.web} that calls {@code addHeader} must import
 *       {@link org.springframework.http.ResponseCookie}.</li>
 *   <li><b>Regex gate SR-CSRF-11</b> — no {@code *IT.java} file that mentions
 *       {@code Set-Cookie} may use {@code .anyMatch(} on a Set-Cookie stream without a
 *       preceding {@code .filter(h -> h.startsWith(} in the same stream chain. This bans
 *       the false-green anti-pattern for cookie attribute assertions in integration tests.
 *       Unit test files are excluded from this gate because pre-existing helper patterns
 *       that check presence (not count) on a necessarily-single header are acceptable at
 *       the unit level; the count invariant is guarded by the {@code SingleSetCookieEmission}
 *       nested class in {@link CsrfTokenCookieFactoryTest}.</li>
 * </ol>
 *
 * <p>Security: I-CSRF-1, SR-CSRF-09, SR-CSRF-11, SR-CSRF-13, ADR-1.
 * OWASP C5 — Secure By Default; ASVS V7.1.1 (L1); WSTG-SESS-02.
 */
class CsrfCookieEmissionStructureTest {

    /** Production sources root — used for raw source regex gates. */
    private static final Path PRODUCTION_SOURCES_ROOT =
            Path.of("src/main/java/de/seism0saurus/glacier/share/web");

    /** Integration test sources root — used by the anyMatch ban gate (SR-CSRF-11). */
    private static final Path INTEGRATION_TEST_SOURCES_ROOT =
            Path.of("src/test/java");

    // -------------------------------------------------------------------------
    // Test 1: vacuous-pass canary
    // -------------------------------------------------------------------------

    /**
     * SR-CSRF-09 canary: verify that {@link CsrfTokenCookieFactory} is loadable and that
     * the {@code share.web} package contains at least one class.
     *
     * <p>If the production class is renamed or the package is emptied, the ArchUnit rules
     * below would silently pass (no classes to check → no violations to find). This canary
     * makes that scenario fail explicitly.
     */
    @Test
    void csrfCookieEmissionGate_canary_packageIsNonEmptyAndFactoryIsLoadable()
            throws ClassNotFoundException {
        // Assert the classes under guard are loadable
        Class<?> factoryClass =
                Class.forName("de.seism0saurus.glacier.share.web.CsrfTokenCookieFactory");
        assertThat(factoryClass)
                .as("SR-CSRF-09 canary: CsrfTokenCookieFactory must be loadable from its canonical "
                        + "class name — if this fails, the class was renamed without updating this gate")
                .isNotNull();

        Class<?> viewerFactoryClass =
                Class.forName("de.seism0saurus.glacier.share.web.ShareViewerCookieFactory");
        assertThat(viewerFactoryClass)
                .as("FU-R1 canary: ShareViewerCookieFactory must be loadable from its canonical "
                        + "class name — if this fails, the class was renamed without updating this gate")
                .isNotNull();

        // Assert the package has at least one loadable class (cannot be all renamed/deleted)
        JavaClasses shareWebClasses = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("de.seism0saurus.glacier.share.web");
        assertThat(shareWebClasses.size())
                .as("SR-CSRF-09 canary: the de.seism0saurus.glacier.share.web production package "
                        + "must contain at least one class — if this fails, the package was emptied or "
                        + "renamed and the ArchUnit gates below would silently pass on zero classes")
                .isGreaterThan(0);
    }

    // -------------------------------------------------------------------------
    // Test 2: ban addCookie() calls (ArchUnit bytecode gate)
    // -------------------------------------------------------------------------

    /**
     * SR-CSRF-09 (ADR-1): no production class in {@code de.seism0saurus.glacier.share.web}
     * may call {@link jakarta.servlet.http.HttpServletResponse#addCookie(
     * jakarta.servlet.http.Cookie)}.
     *
     * <p>The {@code addCookie} method emits a bare {@code Set-Cookie} header that cannot
     * carry {@code SameSite}, leading to the dual-emission pattern when paired with a manual
     * {@code addHeader("Set-Cookie", ...)} call. All cookies in this package must be emitted
     * via {@link org.springframework.http.ResponseCookie} only (ADR-1).
     *
     * <p>Uses ArchUnit bytecode analysis — fires even if a future caller is added in a
     * different compilation unit.
     */
    @Test
    void shareWebPackage_noProductionClassCallsAddCookie() {
        JavaClasses shareWebClasses = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("de.seism0saurus.glacier.share.web");

        // ASVS V7.1.1 (L1), C5 — Secure By Default: cookie emission must use the controlled
        // ResponseCookie path so SameSite and other attributes are always present.
        ArchRule noCookieApiAddCookie = noClasses()
                .should().callMethod(
                        jakarta.servlet.http.HttpServletResponse.class,
                        "addCookie",
                        jakarta.servlet.http.Cookie.class)
                .because("SR-CSRF-09 / ADR-1: addCookie() emits a Set-Cookie header without "
                        + "SameSite support. Use ResponseCookie.from(...).build() + "
                        + "response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString()) instead. "
                        + "I-CSRF-1: exactly one Set-Cookie per cookie name is enforced this way.");

        noCookieApiAddCookie.check(shareWebClasses);
    }

    // -------------------------------------------------------------------------
    // Test 3: ban raw addHeader("Set-Cookie", ...) literal string (source regex gate)
    // -------------------------------------------------------------------------

    /**
     * SR-CSRF-09 (ADR-1): no production source file in {@code share/web} may contain the
     * literal string {@code addHeader("Set-Cookie"}.
     *
     * <p>The permitted form is {@code addHeader(HttpHeaders.SET_COOKIE, ...)} (constant
     * reference), which satisfies OWASP A05 by making the header name type-safe and
     * centrally maintained. Raw string literals like {@code addHeader("Set-Cookie", ...)}
     * bypass the {@link org.springframework.http.HttpHeaders} constant and are banned.
     *
     * <p>Implemented as a source-file regex gate because ArchUnit's method-call DSL cannot
     * inspect the runtime value of a {@code String} argument.
     */
    @Test
    void shareWebPackage_noProductionSourceContainsRawAddHeaderSetCookieLiteral()
            throws IOException {
        List<Path> violators;
        try (Stream<Path> walk = Files.walk(PRODUCTION_SOURCES_ROOT)) {
            violators = walk
                    .filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> {
                        try {
                            String content = Files.readString(p);
                            // Ban the literal string addHeader("Set-Cookie" in production sources.
                            // The permitted form uses the constant: addHeader(HttpHeaders.SET_COOKIE, ...).
                            return content.contains("addHeader(\"Set-Cookie\"");
                        } catch (IOException e) {
                            throw new RuntimeException("Cannot read source file: " + p, e);
                        }
                    })
                    .collect(Collectors.toList());
        }

        assertThat(violators)
                .as("SR-CSRF-09 / ADR-1: production source files in share/web must not call "
                        + "addHeader(\\\"Set-Cookie\\\", ...) with a literal string. "
                        + "Use addHeader(HttpHeaders.SET_COOKIE, ...) instead (OWASP A05 — type-safe "
                        + "constant prevents typos and makes the intent explicit). "
                        + "Violating files: %s", violators)
                .isEmpty();
    }

    // -------------------------------------------------------------------------
    // Test 4: require ResponseCookie import in share.web classes that call addHeader
    // -------------------------------------------------------------------------

    /**
     * SR-CSRF-09 (ADR-2): every production class in {@code de.seism0saurus.glacier.share.web}
     * that calls {@code response.addHeader} with a Set-Cookie value must import
     * {@link org.springframework.http.ResponseCookie}.
     *
     * <p>The {@code ResponseCookie} import is the structural proof that the class has
     * adopted the approved single-emission pattern. A class that calls {@code addHeader}
     * for cookies but does NOT import {@code ResponseCookie} is building the Set-Cookie
     * value manually — the banned approach.
     *
     * <p>Implemented as a source-file regex gate because ArchUnit's import analysis
     * cannot distinguish which {@code addHeader} calls are cookie-related.
     */
    @Test
    void shareWebPackage_productionSourcesCallingAddHeaderMustImportResponseCookie()
            throws IOException {
        List<Path> violators;
        try (Stream<Path> walk = Files.walk(PRODUCTION_SOURCES_ROOT)) {
            violators = walk
                    .filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> {
                        try {
                            String content = Files.readString(p);
                            // A file that calls addHeader (for SET_COOKIE) but does not import
                            // ResponseCookie is constructing the header value manually.
                            boolean callsAddHeader = content.contains("addHeader(");
                            boolean importsResponseCookie = content.contains(
                                    "import org.springframework.http.ResponseCookie;");
                            return callsAddHeader && !importsResponseCookie;
                        } catch (IOException e) {
                            throw new RuntimeException("Cannot read source file: " + p, e);
                        }
                    })
                    .collect(Collectors.toList());
        }

        assertThat(violators)
                .as("SR-CSRF-09 / ADR-2: every production source in share/web that calls "
                        + "addHeader() must import org.springframework.http.ResponseCookie. "
                        + "A missing import means the Set-Cookie value is being assembled manually "
                        + "(dual-emission anti-pattern). "
                        + "Violating files (call addHeader but lack ResponseCookie import): %s",
                        violators)
                .isEmpty();
    }

    // -------------------------------------------------------------------------
    // Test 5: anyMatch ban gate (SR-CSRF-11) — IT files only
    // -------------------------------------------------------------------------

    /**
     * SR-CSRF-11: no {@code *IT.java} integration test file that mentions {@code Set-Cookie}
     * may use {@code .anyMatch(} on a Set-Cookie header stream without a preceding
     * {@code .filter(h -> h.startsWith(} in the same stream chain (within 5 source lines).
     *
     * <p><b>Why this is banned</b>: an {@code anyMatch} over an unfiltered Set-Cookie stream
     * produces false-green results when there are TWO headers for the same cookie name
     * (the dual-emission bug). Example of the banned anti-pattern:
     * <pre>
     *   result.getResponse().getHeaders(HttpHeaders.SET_COOKIE)
     *       .stream()
     *       .anyMatch(h -> h.contains("SameSite=Strict"))  // FALSE-GREEN if 2 headers emitted
     * </pre>
     * The correct pattern (ADR-4):
     * <pre>
     *   List&lt;String&gt; csrfHeaders = result.getResponse().getHeaders(HttpHeaders.SET_COOKIE)
     *       .stream()
     *       .filter(h -> h.startsWith("__Host-shareCsrf="))   // 1. filter by name
     *       .collect(Collectors.toList());
     *   assertThat(csrfHeaders).hasSize(1);                    // 2. assert count == 1
     *   assertThat(csrfHeaders.get(0)).contains("SameSite=Strict"); // 3. assert attribute
     * </pre>
     *
     * <p><b>Scope</b>: only {@code *IT.java} files are checked. Unit test files ({@code *Test.java})
     * may use {@code anyMatch} on a stored list variable when the list is guaranteed to have exactly
     * one element (enforced at the unit level by the {@code SingleSetCookieEmission} nested class
     * in {@link CsrfTokenCookieFactoryTest}). Integration tests must always use the filter+size
     * pattern because they exercise the full Spring MVC stack where dual-emission regressions are
     * most likely to manifest.
     *
     * <p>Security: SR-CSRF-11, ADR-4. Attribute names covered: SameSite, HttpOnly, Secure,
     * Max-Age, Path (Conflict 2 resolution: ban applies to ALL Set-Cookie attribute names).
     */
    @Test
    void integrationTests_setcookieAssertions_mustFilterByNameBeforeAnyMatch()
            throws IOException {
        List<String> violations;

        try (Stream<Path> walk = Files.walk(INTEGRATION_TEST_SOURCES_ROOT)) {
            violations = walk
                    .filter(p -> p.toString().endsWith("IT.java"))
                    .filter(p -> {
                        try {
                            return Files.readString(p).contains("Set-Cookie");
                        } catch (IOException e) {
                            throw new RuntimeException("Cannot read test file: " + p, e);
                        }
                    })
                    .flatMap(p -> detectAnyMatchWithoutFilter(p).stream())
                    .collect(Collectors.toList());
        }

        assertThat(violations)
                .as("SR-CSRF-11 (ADR-4): integration test files that mention Set-Cookie must "
                        + "NOT use .anyMatch( on an unfiltered Set-Cookie stream. "
                        + "The correct pattern is: filter by cookie name prefix → assert "
                        + "hasSize(1) → assert attribute on the single header. "
                        + "anyMatch over an unfiltered stream is false-green when two headers are "
                        + "emitted for the same cookie name (the dual-emission bug). "
                        + "Applies to ALL cookie attributes: SameSite, HttpOnly, Secure, Max-Age, Path. "
                        + "Violations (file:line — anyMatch without preceding filter): %s", violations)
                .isEmpty();
    }

    /**
     * Scans a single Java source file for occurrences of {@code .anyMatch(} that are NOT
     * preceded by {@code .filter(h -> h.startsWith(} within the previous 5 source lines
     * in the same stream chain.
     *
     * <p>A line that contains {@code .anyMatch(} and whose cookie-attribute predicate touches
     * a cookie attribute keyword (SameSite, HttpOnly, Secure, Max-Age, Path) is a candidate
     * for the anti-pattern. We require that within 5 lines before the {@code .anyMatch(} line
     * there is a {@code .filter(h -> h.startsWith(} guard.
     *
     * @param path the source file to scan
     * @return a list of violation descriptions in {@code "file:lineNum"} format
     */
    private List<String> detectAnyMatchWithoutFilter(Path path) {
        List<String> violations;
        List<String> lines;
        try {
            lines = Files.readAllLines(path);
        } catch (IOException e) {
            throw new RuntimeException("Cannot read file: " + path, e);
        }

        violations = new java.util.ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (!line.contains(".anyMatch(")) {
                continue;
            }

            // Only flag lines where the anyMatch lambda checks a cookie attribute keyword.
            // This avoids flagging unrelated anyMatch calls (e.g. log event assertions).
            boolean checksCookieAttribute = line.contains("SameSite")
                    || line.contains("HttpOnly")
                    || line.contains("httponly")
                    || line.contains("Secure")
                    || line.contains("Max-Age")
                    || line.contains("Path=");

            if (!checksCookieAttribute) {
                continue;
            }

            // Look back up to 5 lines for a .filter(h -> h.startsWith( guard
            int lookback = Math.max(0, i - 5);
            boolean hasFilter = false;
            for (int j = lookback; j < i; j++) {
                if (lines.get(j).contains(".filter(h -> h.startsWith(")) {
                    hasFilter = true;
                    break;
                }
            }

            if (!hasFilter) {
                violations.add(path.toAbsolutePath() + ":" + (i + 1)
                        + " — .anyMatch( checking cookie attribute without preceding "
                        + ".filter(h -> h.startsWith(");
            }
        }
        return violations;
    }
}
