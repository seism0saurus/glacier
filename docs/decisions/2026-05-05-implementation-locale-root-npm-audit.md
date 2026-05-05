# Decision Record: Locale.ROOT Correctness + npm audit CI Gate — Implementation

Date: 2026-05-05
Phase: Implementation (Phase 2)
Agents: tdd-ddd-implementer (Lane A), secure-tdd-implementer (Lane B), devops-infra-engineer (Lane C) — Round 1 + Round 2 cross-review
Status: Accepted

## Summary

28 unit tests (+28 delta) and zero integration test changes implement two independent security fixes across three parallel lanes:
1. All 11 bare `.toUpperCase()`/`.toLowerCase()` call sites in production code replaced with `Locale.ROOT`-parameterised forms, backed by Turkish-locale behavioural tests and a `LocaleRootDisciplineTest` structural gate.
2. A `frontend-audit` job added to `.github/workflows/security.yml` running `npm audit --audit-level=high` on push, pull_request, and weekly schedule; backed by `WorkflowYamlInventoryTest`.

## Files Changed

| File | Lane | Change |
|------|------|--------|
| `src/main/java/de/seism0saurus/glacier/share/web/ShareHostRouter.java` | A | `serverName.toLowerCase()` → `serverName.toLowerCase(Locale.ROOT)` |
| `src/main/java/de/seism0saurus/glacier/share/application/JsoupTextExtractor.java` | A | `element.tagName().toLowerCase()` → `Locale.ROOT` (×2) |
| `src/main/java/de/seism0saurus/glacier/share/application/ShareImageProxyService.java` | A | `contentType.toLowerCase()` → `Locale.ROOT` |
| `src/main/java/de/seism0saurus/glacier/mastodon/StompCallback.java` | A | `scheme.toLowerCase()` → `Locale.ROOT` |
| `src/test/java/de/seism0saurus/glacier/share/web/ShareHostRouterLocaleTest.java` | A | **Created** — Turkish-locale smoke tests |
| `src/test/java/de/seism0saurus/glacier/share/application/JsoupTextExtractorLocaleTest.java` | A | **Created** — Turkish-locale smoke tests |
| `src/test/java/de/seism0saurus/glacier/share/application/ShareImageProxyServiceLocaleTest.java` | A | **Created** — Turkish-locale smoke tests |
| `src/test/java/de/seism0saurus/glacier/mastodon/StompCallbackLocaleTest.java` | A | **Created** — Turkish-locale smoke tests |
| `src/main/java/de/seism0saurus/glacier/mastodon/IframeEmbedPolicy.java` | B | 4× `toUpperCase()` → `toUpperCase(Locale.ROOT)` |
| `src/main/java/de/seism0saurus/glacier/webservice/HttpMethodRejectFilter.java` | B | `method.toUpperCase()` → `method.toUpperCase(Locale.ROOT)` |
| `src/main/java/de/seism0saurus/glacier/share/application/DefaultSafeUrlValidator.java` | B | `scheme.toLowerCase()` → `scheme.toLowerCase(Locale.ROOT)` |
| `src/test/java/de/seism0saurus/glacier/mastodon/IframeEmbedPolicyTurkishLocaleTest.java` | B | **Created** — 4 Turkish-locale tests (CSP gate) |
| `src/test/java/de/seism0saurus/glacier/webservice/HttpMethodRejectFilterTurkishLocaleTest.java` | B | **Created** — 3 Turkish-locale tests (TRACE/TRACK reject) |
| `src/test/java/de/seism0saurus/glacier/share/application/DefaultSafeUrlValidatorLocaleTest.java` | B | **Created** — 4 Turkish-locale tests (SSRF allowlist) |
| `src/test/java/de/seism0saurus/glacier/architecture/LocaleRootDisciplineTest.java` | B | **Created** — structural gate (3 test methods) |
| `.github/workflows/security.yml` | C | Added `pull_request` + `schedule` triggers; added `frontend-audit` job |
| `src/test/java/de/seism0saurus/glacier/ci/WorkflowYamlInventoryTest.java` | C | **Created** — 3 structural gate methods |
| `infrastructure/security/OWASP_COVERAGE_MATRIX.md` | C | SR-FUZZ-07 PARTIAL → PASS |

## Test Results

| Layer | Before | After | Delta |
|-------|--------|-------|-------|
| Unit (Surefire) | 1,233 | **1,261** | +28 |
| Integration (Failsafe) | 298 | 298 | 0 |
| Jacoco | Met | Met | — |
| BUILD | SUCCESS | SUCCESS | — |

+28 unit tests:
- 11 — Lane A Turkish-locale smoke tests (4 classes × ~3 tests each)
- 14 — Lane B Turkish-locale security tests + `LocaleRootDisciplineTest` (3 + 4 + 3 + 4)
- 3 — Lane C `WorkflowYamlInventoryTest`

## Security Requirements Delivered

| SR | Status | Key Evidence |
|----|--------|-------------|
| SR-LR-01 | ✅ | All 11 call sites use `Locale.ROOT`; `LocaleRootDisciplineTest` gates zero bare/ENGLISH calls |
| SR-LR-02 | ✅ | `IframeEmbedPolicyTurkishLocaleTest` — 4 methods, RED commit `ebfca4a`, GREEN commit `44aca39` |
| SR-LR-03 | ✅ | `HttpMethodRejectFilterTurkishLocaleTest` — 3 methods, same RED/GREEN cadence |
| SR-LR-04 | ✅ | `LocaleRootDisciplineTest` — 3 regex patterns, scans `src/main/java`, self-excludes via path-suffix |
| SR-LR-05 | ✅ | `DefaultSafeUrlValidatorLocaleTest` — 4 methods; `DefaultSafeUrlValidator.java` line 83 uses `Locale.ROOT` |
| SR-LR-06 | ✅ | All Turkish-locale test classes save/restore `Locale.getDefault()` in `@BeforeEach`/`@AfterEach` |
| SR-LR-07 | ✅ | `THIS_FILE_SUFFIX = "LocaleRootDisciplineTest.java"` excludes own source via path-suffix filter |
| SR-NA-01 | ✅ | `security.yml` line 281: `npm audit --audit-level=high` |
| SR-NA-02 | ✅ | `security.yml`: `pull_request: branches: [main]` present |
| SR-NA-03 | ✅ | `security.yml`: `schedule: - cron: '0 6 * * 1'` (Monday 06:00 UTC) |
| SR-NA-04 | ✅ | No `continue-on-error` on audit step; GitHub Actions defaults to false |
| SR-NA-05 | ✅ | `npm ci` step precedes `npm audit` step in `frontend-audit` job |
| SR-NA-06 | ✅ | `node-version: '22.14.0'` matches `pom.xml` `<nodeVersion>v22.14.0</nodeVersion>` |
| SR-NA-07 | ✅ | `WorkflowYamlInventoryTest` — 3 methods: triggers, steps, threshold |
| SR-NA-08 | ✅ | `OWASP_COVERAGE_MATRIX.md` SR-FUZZ-07: PARTIAL → PASS |
| SR-NA-09 | ✅ | Both `npm ci` and `npm audit` steps specify `working-directory: frontend` |

## Round 2 Cross-Review Findings

| Item | Disposition |
|------|-------------|
| `DefaultSafeUrlValidatorLocaleTest` Test 3 "uppercase HTTP" (A1) | Accepted — `URI.getScheme()` normalises scheme to lowercase per RFC 3986; fix is correct defence-in-depth |
| `auditStepUsesNpmCiAndNpmAudit()` ordering non-enforcement (A2) | Accepted — misordered workflow fails loudly at runtime; lockfile-keyed cache closes the gap |
| `frontend-audit` job `needs:` dependency (infra Q1) | Correct with no dependency — independent parallel execution is the right DAG shape |
| `LocaleRootDisciplineTest` partially RED in Lane B isolation (C1) | Expected by design — 5 Lane A violations were present until merge; fully GREEN post-merge |

## Deviations from Phase 1 Plan

None. All deliverables match the Phase 1 decision doc. The Lane B structural gate was documented as expected-partially-RED in isolation (Lane A files), per ADR-LR-02 rationale.

## User Approval

Date: 2026-05-05
Approval message (verbatim): "approve"

## Open Risks

| ID | Risk | Mitigation |
|----|------|-----------|
| GAP-3 | First CI run of `npm audit` may fail on a pre-existing advisory promoted to high | `--audit-level=high` requires a real high advisory; if a baseline failure occurs, document a suppression strategy in ADR-NA-01 follow-up |
| OR-LR-01 | `equalsIgnoreCase` not scanned | JDK 23 locale-independent by spec (ADR-LR-03); gate comment documents rationale |

## References

- `docs/decisions/2026-05-05-planning-locale-root-npm-audit.md` — Phase 1 planning
- CWE-176 (Improper Handling of Unicode Encoding)
- CWE-178 (Improper Handling of Case Sensitivity)
- ASVS 5.0.0 V12.1.3 (L1)
- OWASP Proactive Controls C3 (Input Validation), C6 (Components)
- RFC 3986 §3.1 (URI scheme normalisation to lowercase)
