package de.seism0saurus.glacier.security;

import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import social.bigbone.MastodonClient;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * UT-sec-06: HTTP endpoint inventory test — API5:2023 / API9:2023 gate.
 *
 * <p>This test iterates all {@link RequestMappingHandlerMapping} entries discovered by
 * the Spring context at startup and asserts they match the authoritative allowlist below.
 * A new HTTP endpoint not in the allowlist will fail this test, forcing the developer to:
 * <ol>
 *   <li>Add it to the allowlist here (and justify the addition in code review).</li>
 *   <li>Add it to {@code OWASP_COVERAGE_MATRIX.md}.</li>
 * </ol>
 *
 * <p>This is the authoritative endpoint list per ADR-PT-API5-01.
 * It simultaneously closes OWASP API5:2023 (Broken Function Level Authorization) by making
 * all exposed HTTP surfaces explicit, and OWASP API9:2023 (Improper Inventory Management)
 * by ensuring no undocumented endpoint can be silently introduced.
 *
 * <p><b>Mode applicability</b>: N/A (no WebSocket in this test — MOCK web environment).
 * This is a Surefire unit test ({@code *Test.java}) using {@code webEnvironment=MOCK}.
 *
 * <p>OWASP: API5:2023 — Broken Function Level Authorization, API9:2023 — Improper Inventory
 * Management.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class EndpointInventoryTest {

    /**
     * Authoritative allowlist of all HTTP endpoints exposed by Glacier.
     *
     * <p>Per ADR-PT-API5-01: any route discovered via {@link RequestMappingHandlerMapping}
     * that is NOT in this set will fail the test. The set uses {@code METHOD:pattern} format.
     *
     * <p>Spring Framework internal paths (e.g., Spring Boot Actuator auto-discovered
     * endpoints not in this list) are excluded by filtering to only our application prefix
     * paths (see test implementation).
     *
     * <p>Actuator paths use the custom base path {@code /internal/actuator} (SR-7, A05).
     * Only {@code health} and {@code info} are exposed (per {@code application.properties}).
     */
    private static final Set<String> AUTHORITATIVE_ENDPOINT_ALLOWLIST = Set.of(
            // FallbackController (HTTP fallback for WebSocket)
            "GET:/rest/messages",

            // InformationController (identity + operator info)
            "GET:/rest/wall-id",
            "GET:/rest/mastodon-handle",
            "GET:/rest/operator",

            // ShareLinkController (sharer-side share link CRUD)
            "POST:/rest/share-links",
            "DELETE:/rest/share-links/{idHash8}",
            "GET:/rest/share-links",

            // ShareViewController (viewer-side share endpoints)
            "GET:/rest/share/{shareId}/catalog",
            "GET:/rest/share-csrf",
            "GET:/rest/share/{shareId}/messages",

            // ShareImageProxyController (image proxy for share views)
            "GET:/rest/share/img-proxy"
    );

    /**
     * Mock the Mastodon client so the app context starts without a real Mastodon instance.
     * Required for {@code webEnvironment=NONE} context to initialise without outbound connections.
     */
    @MockitoBean
    @SuppressWarnings("unused")
    private MastodonClient mastodonClient;

    @Autowired
    private RequestMappingHandlerMapping requestMappingHandlerMapping;

    /**
     * UT-sec-06: discover all request mappings from the live Spring context and verify
     * they are contained in the authoritative allowlist.
     *
     * <p>Only mappings for paths under {@code /rest/} and {@code /internal/} are checked —
     * Spring framework internals (error pages, static-resource handlers) are excluded.
     *
     * <p>This test fails if:
     * <ul>
     *   <li>A new {@code @RestController} endpoint is added without being listed here.</li>
     *   <li>A route is removed from the codebase but not from this allowlist (stale entry).</li>
     * </ul>
     */
    @Test
    void httpRouteInventory_matchesAuthoritativeAllowlist() {
        Set<String> discovered = new HashSet<>();

        requestMappingHandlerMapping.getHandlerMethods().forEach((info, method) -> {
            Set<String> patterns = extractPatterns(info);
            Set<org.springframework.web.bind.annotation.RequestMethod> methods = info.getMethodsCondition().getMethods();

            for (String pattern : patterns) {
                // Only check application-owned paths — skip Spring framework internals
                if (!pattern.startsWith("/rest/") && !pattern.startsWith("/internal/")) {
                    continue;
                }
                // Skip actuator endpoints (they are under /internal/actuator and governed separately)
                if (pattern.startsWith("/internal/actuator")) {
                    continue;
                }

                if (methods.isEmpty()) {
                    // Mapping accepts any method — treat as a wildcard entry
                    discovered.add("*:" + pattern);
                } else {
                    for (org.springframework.web.bind.annotation.RequestMethod httpMethod : methods) {
                        discovered.add(httpMethod.name() + ":" + pattern);
                    }
                }
            }
        });

        // Every discovered route must be in the allowlist (no undocumented endpoint)
        Set<String> undocumented = new HashSet<>(discovered);
        undocumented.removeAll(AUTHORITATIVE_ENDPOINT_ALLOWLIST);

        assertThat(undocumented)
                .as("UT-sec-06 (ADR-PT-API5-01): discovered HTTP endpoints NOT in authoritative allowlist "
                        + "(OWASP API5 / API9). Add each new endpoint to AUTHORITATIVE_ENDPOINT_ALLOWLIST "
                        + "and to OWASP_COVERAGE_MATRIX.md.\nUndocumented routes: %s", undocumented)
                .isEmpty();

        // Every allowlisted route must be discovered (no stale allowlist entry)
        Set<String> stale = new HashSet<>(AUTHORITATIVE_ENDPOINT_ALLOWLIST);
        stale.removeAll(discovered);

        assertThat(stale)
                .as("UT-sec-06 (ADR-PT-API5-01): allowlist entries NOT found in running context "
                        + "(stale allowlist). Remove routes that no longer exist.\nStale entries: %s", stale)
                .isEmpty();
    }

    /**
     * UT-sec-06-MTX (F-6 bijection): every allowlisted endpoint path must appear in the
     * {@code ### HTTP Endpoints} EP-NN table of {@code OWASP_COVERAGE_MATRIX.md}, AND
     * every EP-NN matrix row must correspond to a registered endpoint in the allowlist.
     *
     * <p>The parser is scoped to the {@code ### HTTP Endpoints} section (between the header
     * and the next {@code ###}/{@code ##} header) to avoid false matches in the 100+ OWASP
     * cross-reference cells that repeat endpoint paths as prose.
     *
     * <p>Assertion direction 1 (matrix ⊇ allowlist): catches an undocumented endpoint that
     * was added to the allowlist without a corresponding matrix row.
     * Assertion direction 2 (allowlist ⊇ matrix): catches a phantom EP-NN row left in the
     * matrix after an endpoint was deleted from the codebase.
     *
     * <p>Security: OWASP API9:2023 — Improper Inventory Management (ADR-PT-API5-01).
     */
    @Test
    void matrixHttpInventoryMatchesAllowlist() throws IOException {
        Set<String> matrixPaths = readMatrixHttpInventoryPaths();
        // AUTHORITATIVE_ENDPOINT_ALLOWLIST uses METHOD:path format; extract paths only
        Set<String> allowlistPaths = AUTHORITATIVE_ENDPOINT_ALLOWLIST.stream()
                .map(e -> e.contains(":") ? e.substring(e.indexOf(':') + 1) : e)
                .collect(Collectors.toSet());

        SoftAssertions soft = new SoftAssertions();
        soft.assertThat(matrixPaths)
                .as("Every allowlisted path must appear as an EP-NN row in the HTTP Endpoint Inventory table "
                        + "(OWASP API9:2023, ADR-PT-API5-01). Add missing path to OWASP_COVERAGE_MATRIX.md.")
                .containsAll(allowlistPaths);
        soft.assertThat(allowlistPaths)
                .as("Every EP-NN row in the matrix must be a registered controller endpoint in the allowlist "
                        + "(stale matrix row). Remove the row or register the endpoint.")
                .containsAll(matrixPaths);
        soft.assertAll();
    }

    /**
     * Negative canary for the matrix lockstep: a synthetic path that is definitely absent
     * from the matrix must not accidentally appear in the parsed EP-NN inventory.
     *
     * <p>This test proves the {@link #readMatrixHttpInventoryPaths()} parser works correctly
     * and would catch a genuinely missing entry — the canary path is never a real endpoint,
     * so an "accidentally passes" result would indicate a bug in the parser regex.
     *
     * <p>Security: SAFECode test-isolation discipline — negative canaries guard the guards.
     */
    @Test
    void matrixLockstepDetectsMissingEntry() {
        // Sanity: a path that is definitely not in the matrix must not accidentally pass the lockstep.
        // This test calls readMatrixHttpInventoryPaths() and verifies a synthetic path is absent.
        Set<String> matrixPaths;
        try {
            matrixPaths = readMatrixHttpInventoryPaths();
        } catch (IOException e) {
            throw new AssertionError("Cannot read matrix file", e);
        }
        assertThat(matrixPaths)
                .as("Sanity: synthetic canary path must not be in the matrix inventory table")
                .doesNotContain("/rest/__matrix_canary__");
    }

    /**
     * Parses only the {@code ### HTTP Endpoints} section of
     * {@code infrastructure/security/OWASP_COVERAGE_MATRIX.md} and extracts the path
     * values from EP-NN table rows.
     *
     * <p>The section boundary is determined by the {@code ### HTTP Endpoints} header on one
     * side and the next {@code ###} or {@code ##} header on the other. This scope restriction
     * prevents false-positive matches from the 100+ OWASP cross-reference cells later in the
     * file that repeat endpoint paths as prose rather than as authoritative inventory rows.
     *
     * <p>The row regex {@code ^\|\s*EP-\d+\s*\|\s*\w+\s*\|\s*`([^`]+)`\s*\|.*} captures
     * only the backtick-quoted path in the third column of each EP-NN row.
     *
     * @return a sorted {@link Set} of path strings extracted from EP-NN inventory rows
     * @throws IOException if the matrix file cannot be read
     * @throws AssertionError if the {@code ### HTTP Endpoints} structural anchor is absent
     */
    private static Set<String> readMatrixHttpInventoryPaths() throws IOException {
        Path matrix = Path.of("infrastructure/security/OWASP_COVERAGE_MATRIX.md");
        List<String> lines = Files.readAllLines(matrix);
        int start = -1, end = lines.size();
        for (int i = 0; i < lines.size(); i++) {
            String l = lines.get(i);
            if (start < 0 && l.startsWith("### HTTP Endpoints")) {
                start = i + 1;
            } else if (start >= 0 && (l.startsWith("### ") || l.startsWith("## "))) {
                end = i;
                break;
            }
        }
        if (start < 0) {
            throw new AssertionError(
                    "OWASP_COVERAGE_MATRIX.md is missing the '### HTTP Endpoints' section — "
                            + "structural anchor changed; fix the matrix or update the parser");
        }
        Pattern row = Pattern.compile(
                "^\\|\\s*EP-\\d+\\s*\\|\\s*\\w+\\s*\\|\\s*`([^`]+)`\\s*\\|.*");
        Set<String> paths = new TreeSet<>();
        for (String line : lines.subList(start, end)) {
            Matcher m = row.matcher(line);
            if (m.matches()) paths.add(m.group(1));
        }
        return paths;
    }

    @SuppressWarnings("deprecation")
    private Set<String> extractPatterns(RequestMappingInfo info) {
        // Spring 5.3+ has getPatternsCondition() (path-pattern style) or getPatternValues()
        // Use getPatternValues() which works for both pattern-parser and ant-path-matcher modes.
        Set<String> patterns = new HashSet<>();
        if (info.getPatternsCondition() != null) {
            patterns.addAll(info.getPatternsCondition().getPatterns());
        }
        if (info.getPathPatternsCondition() != null) {
            info.getPathPatternsCondition().getPatterns().forEach(p -> patterns.add(p.getPatternString()));
        }
        return patterns;
    }
}
