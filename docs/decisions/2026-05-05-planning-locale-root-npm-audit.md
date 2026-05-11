# Decision Record: Locale.ROOT Correctness + npm audit CI Gate — Planning

Date: 2026-05-05
Phase: Planning (Phase 1)
Agents: ddd-tdd-architect (Round 1 + Round 2), secure-feature-planner (Round 1 + Round 2)
Status: Accepted

## Summary

Two independent fixes bundled into one pipeline:
1. **Locale.ROOT (11 sites, CWE-176/CWE-178)** — replace all bare `.toUpperCase()`/`.toLowerCase()` calls in `de.seism0saurus.glacier.*` production code with `Locale.ROOT`-parameterised forms; add a structural regression gate and Turkish-locale unit tests for every security-boundary site.
2. **npm audit CI gate (SR-FUZZ-07 → PASS)** — add a `frontend-audit` job to `.github/workflows/security.yml` running `npm audit --audit-level=high` on `push`, `pull_request`, and weekly `schedule`.

## Problem Statements

### P-1: Locale-unsafe case folding in security-critical parsing code
Two security gates use `.toUpperCase()`/`.toLowerCase()` without specifying a locale. On a JVM started with Turkish locale (`-Duser.language=tr`), `'I'.toLowerCase()` returns `'ı'` (dotless i, U+0131) instead of `'i'`, causing:
- `IframeEmbedPolicy`: CSP `frame-ancestors` directive detection may silently fail → iframe embed gate downgrades to ALLOW
- `HttpMethodRejectFilter`: TRACE/TRACK method token may fail to match → TRACE allowed through
- `DefaultSafeUrlValidator`: SSRF scheme allowlist comparison may produce incorrect results

A full grep reveals **11 total call sites** across 6 production files, only 5 of which were in the original scope.

### P-2: npm audit CI step absent (SR-FUZZ-07 PARTIAL)
The fuzz mutation testing acceptance cycle (2026-05-01) left SR-FUZZ-07 as PARTIAL because no CI workflow runs `npm audit` against `frontend/`. `fast-check` is pinned exactly but the broader Angular/Karma/Playwright dependency tree is unaudited. A high-severity transitive vulnerability could enter the embedded SPA bundle without any CI signal.

## All 11 Call Sites

| File | Line | Call | Severity | Lane |
|------|------|------|----------|------|
| `DefaultSafeUrlValidator.java` | 79 | `scheme.toLowerCase()` | HIGH — SSRF allowlist | B |
| `HttpMethodRejectFilter.java` | 71 | `method.toUpperCase()` | HIGH — TRACE/TRACK reject | B |
| `IframeEmbedPolicy.java` | 104 | `csp.getFirst().toUpperCase()` | HIGH — CSP directive | B |
| `IframeEmbedPolicy.java` | 107 | `policy.toUpperCase()` | HIGH — CSP filter | B |
| `IframeEmbedPolicy.java` | 111 | `policy.toUpperCase()` | HIGH — CSP match | B |
| `IframeEmbedPolicy.java` | 113 | `domain.toUpperCase()` | HIGH — domain compare | B |
| `ShareHostRouter.java` | 105 | `serverName.toLowerCase()` | MEDIUM — routing | A |
| `ShareImageProxyService.java` | 191 | `contentType...toLowerCase()` | MEDIUM — MIME parse | A |
| `JsoupTextExtractor.java` | 125 | `element.tagName().toLowerCase()` | LOW — HTML tag | A |
| `JsoupTextExtractor.java` | 135 | `element.tagName().toLowerCase()` | LOW — HTML tag | A |
| `StompCallback.java` | 609 | `scheme.toLowerCase()` | LOW — logging only | A |

## Key Decisions

### ADR-LR-01: `Locale.ROOT` for all protocol-token case folding
**Decision**: All 11 sites use `.toUpperCase(Locale.ROOT)` / `.toLowerCase(Locale.ROOT)`. `Locale.ENGLISH` is explicitly forbidden; the structural gate rejects both no-arg and ENGLISH-arg forms.
**Rationale**: `Locale.ROOT` is the semantically correct choice for protocol tokens (HTTP methods, URL schemes, CSP directives) — they have no language. `Locale.ENGLISH` carries English-specific semantics and would be wrong. Per-site human classification of "which sites are security-critical" is fragile (the original plan missed 6 of 11 sites including the SSRF allowlist); universal `Locale.ROOT` + structural gate is more maintainable.
**Alternatives considered**: `Locale.ENGLISH` (wrong semantics), manual ASCII fold (reinvents the wheel), tier-by-severity fix (fragile, leaks knowledge into history).

### ADR-LR-02: Structural gate as regex-over-source test
**Decision**: `LocaleRootDisciplineTest` walks `src/main/java`, applies regex `\.to(Upper|Lower)Case\s*\(\s*\)` plus `\.toUpperCase\(Locale\.ENGLISH\)` / `\.toLowerCase\(Locale\.ENGLISH\)`, asserts zero matches. Excludes its own source file via path-suffix check.
**Rationale**: ArchUnit bytecode approach cannot cleanly distinguish no-arg from `Locale`-arg overloads at the call-site level. Regex-over-source is direct, cheap, and runs in Surefire without Spring context.
**Consequence**: A `// LOCALE-OK:` line comment opt-out mechanism exists for legitimate future exceptions; none expected today.

### ADR-LR-03: `equalsIgnoreCase` out of scope
**Decision**: `equalsIgnoreCase` calls are not scanned by the structural gate.
**Rationale**: JDK 23 `String.equalsIgnoreCase` uses `Character.toUpperCase(int)` which is locale-independent (per JDK specification). Scanning it would produce false positives. A 2-line comment in `LocaleRootDisciplineTest` documents this reasoning.

### ADR-LR-04: `@BeforeEach`/`@AfterEach` locale restore (not `@Isolated` or perTest fork)
**Decision**: Each Turkish-locale test class saves `Locale.getDefault()` in `@BeforeEach` and restores it in `@AfterEach`. Surefire default fork mode is sufficient.
**Rationale**: One JVM fork per module is the default; per-test forking would add ~30 s per test class with no additional isolation benefit given the `@AfterEach` guarantee. `@Isolated` (JUnit Jupiter) is reserved for tests that truly cannot be parallelised.

### ADR-NA-01: npm audit triggers on push + pull_request + weekly schedule
**Decision**: The `frontend-audit` job in `security.yml` triggers on all three.
**Rationale**: `pull_request` is the load-bearing gate (blocks merge of vulnerable deps). `push` covers direct-to-main. Weekly cron catches CVEs disclosed after the last commit.

### ADR-NA-02: `--audit-level=high` threshold
**Decision**: `--audit-level=high`. Low/moderate advisories in Angular/Karma dev toolchain are too noisy; high/critical represent actionable items.

### ADR-NA-03: `npm ci` (not `npm install`) before audit
**Decision**: `npm ci` ensures the exact lockfile is installed before auditing, preventing drift. `npm install` may silently update packages.

## Security Requirements

| SR | Requirement | Standard |
|----|-------------|----------|
| SR-LR-01 | All 11 sites use `Locale.ROOT`; structural gate enforces zero bare/ENGLISH calls | [CWE-176](https://cwe.mitre.org/data/definitions/176.html), [CWE-178](https://cwe.mitre.org/data/definitions/178.html), [ASVS V12.1.3](https://raw.githubusercontent.com/OWASP/ASVS/refs/heads/v5.0.0/5.0/docs_en/OWASP_Application_Security_Verification_Standard_5.0.0_en.json) |
| SR-LR-02 | `IframeEmbedPolicyTurkishLocaleTest` — 4 methods, RED-before-GREEN | [OWASP Proactive C3](https://top10proactive.owasp.org/), C8 |
| SR-LR-03 | `HttpMethodRejectFilterTurkishLocaleTest` — 3 methods, RED-before-GREEN | [OWASP Proactive C1](https://top10proactive.owasp.org/), C3 |
| SR-LR-04 | `LocaleRootDisciplineTest` structural gate — regex over src/main | [CWE-176](https://cwe.mitre.org/data/definitions/176.html), [CWE-178](https://cwe.mitre.org/data/definitions/178.html) |
| SR-LR-05 | `DefaultSafeUrlValidatorLocaleTest` — 4 methods, Turkish-locale scheme allowlist | [OWASP Proactive C3](https://top10proactive.owasp.org/), SSRF, [A10:2021](https://owasp.org/Top10/A10_2021-Server-Side_Request_Forgery_(SSRF)/) |
| SR-LR-06 | `@BeforeEach`/`@AfterEach` locale restore in all Turkish-locale test classes | Test hygiene |
| SR-LR-07 | Structural gate excludes its own source file (path-suffix check) | Self-consistency |
| SR-NA-01 | `npm audit --audit-level=high` step in `security.yml` | [OWASP Proactive C6](https://top10proactive.owasp.org/), SR-FUZZ-07 |
| SR-NA-02 | `pull_request` trigger present in `security.yml` | Supply-chain gate |
| SR-NA-03 | Weekly `schedule` trigger present | Post-commit CVE detection |
| SR-NA-04 | `continue-on-error: false` (or absent) on the audit step | Gate integrity |
| SR-NA-05 | `npm ci` before audit | Lockfile fidelity |
| SR-NA-06 | Node version pinned to match `frontend-maven-plugin` in `pom.xml` | Reproducibility |
| SR-NA-07 | `WorkflowYamlInventoryTest` — 3 Surefire methods covering triggers, npm ci+audit, threshold | Structural regression gate |
| SR-NA-08 | `OWASP_COVERAGE_MATRIX.md` SR-FUZZ-07 row updated PARTIAL → PASS | Matrix accuracy |
| SR-NA-09 | Audit step `working-directory: frontend` (or `cd frontend &&`) | Correctness |

## Phase 2 Lane Partition

| Lane | Agent | Scope |
|------|-------|-------|
| A | `tdd-ddd-implementer` | `ShareHostRouter.java:105`, `JsoupTextExtractor.java:125,135`, `ShareImageProxyService.java:191`, `StompCallback.java:609` — add `Locale.ROOT`; smoke Turkish-locale tests for each |
| B | `secure-tdd-implementer` | `DefaultSafeUrlValidator.java:79`, `IframeEmbedPolicy.java:104,107,111,113`, `HttpMethodRejectFilter.java:71` — add `Locale.ROOT`; `IframeEmbedPolicyTurkishLocaleTest`, `HttpMethodRejectFilterTurkishLocaleTest`, `DefaultSafeUrlValidatorLocaleTest`, `LocaleRootDisciplineTest` structural gate |
| C | `devops-infra-engineer` | `.github/workflows/security.yml` — new `frontend-audit` job; `WorkflowYamlInventoryTest`; `OWASP_COVERAGE_MATRIX.md` SR-FUZZ-07 update |

## Mandatory Tightenings (from security planner Round 2)

1. **RED-before-GREEN two-commit cadence**: each Turkish-locale test class must be committed first (RED, verified failing), then the production fix (GREEN). Commit message on the RED commit must note "RED — verifiable against current code."
2. **`WorkflowYamlInventoryTest` method naming**: methods must be named for structural intent (`triggersIncludePushPullRequestAndSchedule`, `auditStepUsesNpmCi`, `auditLevelIsHighOrCritical`) — not free-text grep.
3. **`LocaleRootDisciplineTest` rejects both `Locale.ENGLISH` and no-arg**: the regex or assertion covers both forbidden forms.

## Resolved Conflicts

### Scope undercount (R1 → R2)
**ddd-tdd-architect R1**: scoped to 5 call sites.
**secure-feature-planner R1**: flagged 11 total sites including `DefaultSafeUrlValidator` (SSRF, HIGH).
**Resolution (2026-05-05)**: Architect accepted in full. Scope expanded to all 11 sites; DefaultSafeUrlValidator assigned to Lane B.

### npm audit PR-blind gate (R1 → R2)
**ddd-tdd-architect R1**: specified `security.yml` step without naming triggers.
**secure-feature-planner R1**: flagged that push-only trigger misses PRs.
**Resolution (2026-05-05)**: Architect accepted. All three triggers (push, pull_request, schedule) required.

## User Approval

Date: 2026-05-05
Approval message (verbatim): "approve"

## Open Risks

| ID | Risk | Mitigation |
|----|------|-----------|
| GAP-3 | No npm audit allowlist mechanism — first CI run may fail on a pre-existing moderate advisory promoted to high by npm | `--audit-level=high` requires a real high advisory; if a baseline failure occurs, document a suppression strategy in ADR-NA-01 follow-up |
| GAP-4 | `DefaultSafeUrlValidator` bare-IP URL bypass is a pre-existing separate gap | Out of scope for this pipeline; separate ticket |
| OR-LR-01 | `equalsIgnoreCase` not scanned | JDK 23 locale-independent by spec; gate comment documents rationale |

## References

- [Fuzz Mutation Testing — Acceptance](2026-05-01-acceptance-fuzz-mutation-testing.md) — SR-FUZZ-07 PARTIAL source
- [OWASP Standards Integration — Acceptance](2026-05-04-acceptance-owasp-standards-integration.md) — Locale.ROOT deferred minor
- `src/main/java/de/seism0saurus/glacier/mastodon/IframeEmbedPolicy.java`
- `src/main/java/de/seism0saurus/glacier/webservice/HttpMethodRejectFilter.java`
- `src/main/java/de/seism0saurus/glacier/share/application/DefaultSafeUrlValidator.java`
- [CWE-176: Improper Handling of Unicode Encoding](https://cwe.mitre.org/data/definitions/176.html)
- [CWE-178: Improper Handling of Case Sensitivity](https://cwe.mitre.org/data/definitions/178.html)
- [OWASP ASVS 5.0](https://raw.githubusercontent.com/OWASP/ASVS/refs/heads/v5.0.0/5.0/docs_en/OWASP_Application_Security_Verification_Standard_5.0.0_en.json) V12.1.3 (L1)
- [OWASP Top 10 Proactive Controls (2024)](https://top10proactive.owasp.org/) C3 (Input Validation), C6 (Components)
