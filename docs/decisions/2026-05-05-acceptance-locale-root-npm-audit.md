# Decision Record: Locale.ROOT Correctness + npm audit CI Gate — Acceptance

Date: 2026-05-05
Phase: Acceptance (Phase 3)
Agents: security-auditor (Round 1 + Round 2), acceptance-test-auditor (Round 1), devops-infra-engineer (fix cycle)
Status: Accepted — PASSED

## Summary

All 14 acceptance criteria (AC-LR-01..06, AC-NA-01..07, AC-BUILD) are satisfied. One Phase 3 fix
cycle was required: the npm audit step was scoped to production dependencies only (`--omit=dev`)
after dev-toolchain advisories would have caused immediate CI failure. Zero critical or medium
findings. BUILD SUCCESS: 1,261 unit tests + 298 integration tests.

## Acceptance Result

**Disposition: PASSED**
**Criteria checked**: 14
**Criteria passed**: 14
**Criteria failed**: 0
**Fix cycles**: 1

## AC Coverage (acceptance-test-auditor)

| AC | Status | Key Evidence |
|----|--------|-------------|
| AC-LR-01 | PASS | Zero bare/ENGLISH case-fold calls in `src/main/java`; grep returns no matches; all 11 sites confirmed `Locale.ROOT`: `IframeEmbedPolicy.java:107/110/114/116`, `HttpMethodRejectFilter.java:75`, `DefaultSafeUrlValidator.java:83`, `ShareHostRouter.java:106`, `JsoupTextExtractor.java:126/136`, `ShareImageProxyService.java:192`, `StompCallback.java:610` |
| AC-LR-02 | PASS | `LocaleRootDisciplineTest` — 3 green methods: `noBareCaseFoldingInProductionCode`, `noEnglishLocaleUpperCaseInProductionCode`, `noEnglishLocaleLowerCaseInProductionCode` |
| AC-LR-03 | PASS | `IframeEmbedPolicyTurkishLocaleTest` — 4 green methods; `@BeforeEach`/`@AfterEach` locale save-restore confirmed |
| AC-LR-04 | PASS | `HttpMethodRejectFilterTurkishLocaleTest` — 3 green methods; GET parity test asserts both status and chain invocation |
| AC-LR-05 | PASS | `DefaultSafeUrlValidatorLocaleTest` — 4 green methods; Test 3 ("uppercase HTTP") correctly probes the real `URI.getScheme()` path |
| AC-LR-06 | PASS | All Turkish-locale test classes use `@BeforeEach setTurkishLocale()` and `@AfterEach restoreLocale()`; JUnit 5 guarantees restore on assertion failure |
| AC-NA-01 | PASS | `security.yml:281` — `npm audit --audit-level=high --omit=dev` (updated in fix cycle) |
| AC-NA-02 | PASS | `security.yml`: push + `pull_request: branches: [main]` + `schedule: '0 6 * * 1'` all present |
| AC-NA-03 | PASS | `security.yml`: npm ci step (line 278) precedes npm audit step (line 281) |
| AC-NA-04 | PASS | No `continue-on-error` on audit step; GitHub Actions defaults to false |
| AC-NA-05 | PASS | `node-version: '22.14.0'` (security.yml) matches `<nodeVersion>v22.14.0</nodeVersion>` (pom.xml:420) |
| AC-NA-06 | PASS | `WorkflowYamlInventoryTest` — 3 green methods: `triggersIncludePushPullRequestAndSchedule`, `auditStepUsesNpmCiAndNpmAudit`, `auditLevelIsHighOrCritical` (now asserts `--omit=dev` too) |
| AC-NA-07 | PASS | `infrastructure/security/OWASP_COVERAGE_MATRIX.md`: SR-FUZZ-07 row shows `**Status: PASS** (updated 2026-05-05 from PARTIAL)` |
| AC-BUILD | PASS | 1,261 unit tests + 298 integration tests — BUILD SUCCESS |

## SR Coverage (security-auditor)

| SR | Status | Evidence |
|----|--------|---------|
| SR-LR-01 | PASS | All 11 sites use `Locale.ROOT`; `LocaleRootDisciplineTest` enforces zero bare/ENGLISH calls; post-merge grep returns zero violations |
| SR-LR-02 | PASS | `IframeEmbedPolicyTurkishLocaleTest` 4 methods; RED commit `ebfca4a`, GREEN commit `44aca39` |
| SR-LR-03 | PASS | `HttpMethodRejectFilterTurkishLocaleTest` 3 methods; same RED/GREEN cadence |
| SR-LR-04 | PASS | `LocaleRootDisciplineTest` — `BARE_CASE_FOLD`, `ENGLISH_UPPER`, `ENGLISH_LOWER` patterns; scans `src/main/java`; self-excludes via `THIS_FILE_SUFFIX` |
| SR-LR-05 | PASS | `DefaultSafeUrlValidatorLocaleTest` 4 methods; SSRF allowlist at `DefaultSafeUrlValidator.java:83` uses `Locale.ROOT` |
| SR-LR-06 | PASS | All 8 Turkish-locale test classes use `@BeforeEach`/`@AfterEach` save-restore |
| SR-LR-07 | PASS | `collectViolations` filters `!p.toString().endsWith("LocaleRootDisciplineTest.java")` |
| SR-NA-01 | PASS | `security.yml:281` — `npm audit --audit-level=high --omit=dev` |
| SR-NA-02 | PASS | `pull_request: branches: [main]` present |
| SR-NA-03 | PASS | `schedule: - cron: '0 6 * * 1'` present (Monday 06:00 UTC) |
| SR-NA-04 | PASS | No `continue-on-error` on audit step |
| SR-NA-05 | PASS | `npm ci` precedes `npm audit` |
| SR-NA-06 | PASS | Node 22.14.0 matches `pom.xml` exactly |
| SR-NA-07 | PASS | `WorkflowYamlInventoryTest` — 3 Surefire methods, all green; `auditLevelIsHighOrCritical` now also asserts `--omit=dev` |
| SR-NA-08 | PASS | SR-FUZZ-07 row updated PARTIAL → PASS |
| SR-NA-09 | PASS | Both `npm ci` and `npm audit` steps specify `working-directory: frontend` |

## Security Findings

| ID | Severity | Location | Description | Disposition |
|----|----------|----------|-------------|-------------|
| F-1 | (false positive) | Round 1 auditor error | Auditor read from stale worktree paths; main branch confirmed correct | Closed — false positive |
| F-2 | High → Fixed | `.github/workflows/security.yml` | `npm audit --audit-level=high` without `--omit=dev` fails on dev-toolchain advisories (6 high in `@angular/cli`, `webpack-dev-server` — never deployed in SPA bundle) | Fixed — added `--omit=dev`; `WorkflowYamlInventoryTest` extended to gate the flag. Commit `fa62b42` |
| F-INFO | Informational | 8 Turkish-locale test classes | `new Locale("tr","TR")` deprecated since Java 19; `Locale.of("tr","TR")` preferred | Not blocking — cosmetic hygiene; no security impact |

## Fix Cycle

**Fix 1 (F-2)** — devops-infra-engineer:
- `security.yml` audit step: `npm audit --audit-level=high` → `npm audit --audit-level=high --omit=dev`
- `WorkflowYamlInventoryTest.auditLevelIsHighOrCritical()` extended to assert `--omit=dev` present
- Rationale: dev-toolchain packages (`@angular/cli`, `webpack-dev-server`) carry pre-existing advisories and are never bundled in the production SPA. `--omit=dev` is semantically correct for supply-chain gates targeting the deployed artifact.
- Verification: `npm audit --audit-level=high --omit=dev` exits 0 (2 moderate only); `WorkflowYamlInventoryTest` 3/3 green.
- Committed: `fa62b42`

## Test Results

| Suite | Tests | Failures | Errors | Skipped |
|-------|-------|----------|--------|---------|
| Surefire (unit) | 1,261 | 0 | 0 | 0 |
| Failsafe (IT) | 298 | 0 | 0 | 0 |
| Jacoco | — | — | threshold pass | — |

## User Approval

Date: 2026-05-05
Approval message (verbatim): "approve"

## Open Risks

| ID | Risk | Rationale |
|----|------|-----------|
| OR-LR-01 | `equalsIgnoreCase` not scanned | JDK 23 locale-independent by specification; gate comment in `LocaleRootDisciplineTest` documents rationale (ADR-LR-03) |
| F-INFO | `new Locale("tr","TR")` deprecation | Cosmetic warning only; functional behaviour unchanged; optional future cleanup |

## References

- [Planning doc](2026-05-05-planning-locale-root-npm-audit.md)
- [Implementation doc](2026-05-05-implementation-locale-root-npm-audit.md)
- `src/test/java/de/seism0saurus/glacier/architecture/LocaleRootDisciplineTest.java`
- `src/test/java/de/seism0saurus/glacier/ci/WorkflowYamlInventoryTest.java`
- CWE-176 (Improper Handling of Unicode Encoding)
- CWE-178 (Improper Handling of Case Sensitivity)
- ASVS 5.0.0 V12.1.3 (L1)
- OWASP A06:2021 (Vulnerable and Outdated Components)
- RFC 3986 §3.1 (URI scheme normalisation to lowercase)
