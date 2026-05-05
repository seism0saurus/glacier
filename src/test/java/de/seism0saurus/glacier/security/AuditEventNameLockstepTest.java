package de.seism0saurus.glacier.security;

import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * UT-sec-AUDIT-01: structural gate ensuring OWASP_COVERAGE_MATRIX.md and
 * SECURITY_TESTS.md cite the canonical dot-form AUDIT event tokens that
 * the code actually emits.
 *
 * <p>Prevents re-drift between docs and code. The authoritative names are
 * the dot-form literals emitted by the production rate-limit interceptors:
 * <ul>
 *   <li>{@code ws.handshake.rate_limited} — HandshakeRateLimitInterceptor</li>
 *   <li>{@code ws.subscribe.rate_limited} — SubscribeRateLimitInterceptor</li>
 * </ul>
 *
 * <p>Security: OWASP A09:2021 — Security Logging and Monitoring Failures.
 * SOC dashboards bind to AUDIT event-name literals; drift between docs and
 * code means alert rules built from docs never fire on real events.
 *
 * <p><b>Scope</b>: scans only OWASP_COVERAGE_MATRIX.md, SECURITY_TESTS.md, and
 * the planning ADR — NOT acceptance ADRs that narratively describe historical drift.
 *
 * <p><b>Mode applicability</b>: N/A (doc-only structural gate; not mode-dependent).
 *
 * <p>This is a Surefire unit test ({@code *Test.java}).
 */
class AuditEventNameLockstepTest {

    private static final List<String> CANONICAL_TOKENS = List.of(
            "ws.handshake.rate_limited",
            "ws.subscribe.rate_limited"
    );

    private static final List<String> BANNED_TOKENS = List.of(
            "WS_HANDSHAKE_RATE_LIMITED",
            "WS_SUBSCRIBE_RATE_LIMITED"
    );

    private static final List<Path> SCANNED_FILES = List.of(
            Path.of("infrastructure/security/OWASP_COVERAGE_MATRIX.md"),
            Path.of("infrastructure/security/SECURITY_TESTS.md"),
            Path.of("docs/decisions/2026-04-30-planning-owasp-matrix-completion.md")
    );

    /**
     * UT-sec-AUDIT-01: each scanned file must contain the canonical dot-form tokens
     * AND must NOT contain the legacy SCREAMING_SNAKE form.
     *
     * <p>Arrange: read the three documentation files from the project root.
     * Act: check for canonical token presence and banned token absence.
     * Assert: all six conditions (2 tokens × 3 files × presence + 2 tokens × 3 files × absence)
     * pass; failures are accumulated via {@link SoftAssertions} so all violations surface in one run.
     *
     * <p>Files deliberately excluded from the scan:
     * <ul>
     *   <li>{@code docs/decisions/2026-04-30-acceptance-owasp-matrix-completion.md} — this
     *       acceptance ADR narratively documents the historical drift ("Matrix references
     *       WS_HANDSHAKE_RATE_LIMITED; code emits ws.handshake.rate_limited") and must
     *       be preserved as-is as a historical record.</li>
     * </ul>
     */
    @Test
    void canonicalEventNamesAppearInDocsAndBannedFormIsAbsent() throws IOException {
        SoftAssertions soft = new SoftAssertions();

        for (Path file : SCANNED_FILES) {
            String content = Files.readString(file);
            for (String token : CANONICAL_TOKENS) {
                soft.assertThat(content)
                        .as("File %s must cite the canonical dot-form token '%s' "
                                + "(OWASP A09:2021 — SOC dashboards bind to emitted event names, "
                                + "not doc names)", file, token)
                        .contains(token);
            }
            for (String banned : BANNED_TOKENS) {
                soft.assertThat(content)
                        .as("File %s must NOT contain the legacy SCREAMING_SNAKE token '%s' "
                                + "(use dot-form to match what the code emits)", file, banned)
                        .doesNotContain(banned);
            }
        }

        soft.assertAll();
    }
}
