# Decision Record: IframeEmbedPolicy Pattern.quote() Regex Bypass — Planning

Date: 2026-05-05
Phase: Planning (Phase 1)
Agents: ddd-tdd-architect (Round 1 + Round 2), secure-feature-planner (Round 1 + Round 2)
Status: Accepted

## Summary

`IframeEmbedPolicy.java` line 116 interpolates the operator-supplied `glacier.domain` string directly
into a `String.matches()` regex without quoting. A `.` in `glacier.events` acts as a regex wildcard,
allowing a lookalike host (`glacierXevents`) to satisfy the `frame-ancestors` check and pass an embed
that the CSP policy should have rejected. Fix: `Pattern.quote(domain.toUpperCase(Locale.ROOT))`.
Severity Medium (CWE-1287 + CWE-625). Eight regression tests + structural gate deliver defense in depth.

## Key Decisions

### ADR-PQ-01: Use Pattern.quote() over manual escape
**Decision**: Wrap `domain` in `java.util.regex.Pattern.quote()` before regex interpolation.
**Rationale**: `Pattern.quote()` produces `\Q...\E` quoting — a single JDK call that covers all
metacharacters including `.`, `+`, `*`, `(`, `)`, `[`, `]`, `{`, `}`, `^`, `$`, `|`, `\`, `?`.
Manual escape (e.g., `domain.replace(".", "\\.")`) is error-prone and misses other metacharacters.
**Alternatives considered**: Manual `replace(".", "\\.")` (rejected — partial, brittle);
compile-time constant domain validation (rejected — operator-configured at runtime).
**Source**: arch_plan (Round 1)

### ADR-PQ-02: Primary RED vector — dot-substitution lookalike
**Decision**: The canonical test vector is a domain where `.` is replaced with a single character
(e.g., `glacierXevents` for configured domain `glacier.events`). This vector directly demonstrates
the `.`-as-wildcard class of bypass.
**Rationale**: Unambiguous failure mode; visually illustrative for code review; mirrors the realistic
attacker case (register lookalike TLD).
**Source**: arch_plan (Round 1)

### ADR-PQ-03: Severity Medium (re-rated from initial Low)
**Decision**: CWE-625 / CWE-1287 vulnerability rated **Medium** per STRIDE analysis.
**Rationale**: Lookalike domain registration is trivial (~$10/yr) and exploit is one step. Browser
SOP limits credential-theft surface (no SOP bypass), but display-spoofing/phishing surface is real:
an attacker with `glacierxevents.com` can host malicious content embedded inside what users perceive
as Glacier-trustworthy framing. Not High (no direct credential theft / no SOP bypass). Not Low
(registration is trivial and exploit is one-step).
**Alternatives considered**: Low (rejected — underweights phishing surface); High (rejected — no SOP
bypass, no direct credential theft).
**Source**: secure-feature-planner Round 1; confirmed in arch_review Round 2

### ADR-PQ-04: Single dedicated AUDIT-logger test, not coupling to bypass tests
**Decision**: One dedicated test (`T-PQ-G: isEmbeddable_emitsAuditEventOnBlockedHost`) covers
security observability. The four bypass tests (T-PQ-A..D) assert only `isEmbeddable` return value.
**Rationale**: Coupling all bypass tests to `LOGGER.warn` invocation is a brittleness multiplier —
every AUDIT-logger migration or log-level change breaks 5 tests for non-security reasons. A single
dedicated observability test provides the structural protection without cross-cutting coupling.
Aligns with `glacier-structured-logging-logback` skill: security-relevant blocks → AUDIT logger;
dedicated test makes the future AUDIT-logger migration cheap (one test changes, not five).
**Consequences**: Any refactor that removes the security AUDIT log fails T-PQ-G. Future AUDIT-logger
migration touches 1 test, not 5.
**Source**: arch_review (Round 2 dispute → security_final acceptance)

### ADR-PQ-05: In-function blank-domain guard, startup validation deferred
**Decision**: `isEmbeddable` short-circuits `return false` when `domain == null || domain.isBlank()`.
Placed at function entry. `@ConfigurationProperties @NotBlank` startup validation is out-of-scope,
tracked as `SR-PQ-12-FU`.
**Rationale**: `Pattern.quote("")` produces a regex matching the empty string, over-permitting all
hosts. In-function guard is fail-closed at the actual decision point (defense-in-depth principle:
don't depend solely on upstream validation). Startup validation is a separate architectural concern
affecting all `glacier.*` properties; keeping this PR tight.
**Consequences**: Empty-domain bypass closed immediately. Misconfigured `glacier.domain=""` fails
closed at the function level, not at startup. Configuration-validation backlog item `SR-PQ-12-FU`
created for full `@NotBlank` coverage.
**Source**: security_final (Round 2 adaptation of SR-PQ-12)

### ADR-PQ-06: Structural gate in dedicated file, not merged into LocaleRootDisciplineTest
**Decision**: `IframeEmbedPolicyRegexInterpolationGateTest.java` (new file) hosts T-PQ-H. It does NOT
extend `LocaleRootDisciplineTest`.
**Rationale**: Locale-discipline and regex-interpolation are orthogonal invariants. Merging them
creates a god-test that fails for two unrelated reasons — diluted signal. Each gate has one clear
reason to fail.
**Source**: arch_review (Round 2 adaptation of SR-PQ-07)

## Security Requirements (Phase 2 authoritative)

| SR | Description | Test |
|----|-------------|------|
| SR-PQ-01 | `Pattern.quote()` wraps `domain` before regex interpolation | T-PQ-A..E |
| SR-PQ-02 | `toUpperCase(Locale.ROOT)` before `Pattern.quote()` (CWE-178) | — |
| SR-PQ-03 | Bounded grep audit of other regex-interpolation call sites; results in commit narrative | — |
| SR-PQ-04 | T-PQ-A: dot-substitution lookalike → `false` (RED-then-GREEN) | T-PQ-A |
| SR-PQ-05 | T-PQ-B: underscore substitution → `false` (exact-match proof) | T-PQ-B |
| SR-PQ-06 | T-PQ-C: `+` metacharacter substitution → `false` | T-PQ-C |
| SR-PQ-07 | `IframeEmbedPolicyRegexInterpolationGateTest.java` — structural source-scan gate | T-PQ-H |
| SR-PQ-08 | T-PQ-D: multi-token frame-ancestors lookalike blocked | T-PQ-D |
| SR-PQ-09 | T-PQ-E: legitimate exact domain → `true` (GREEN-only, over-rejection guard) | T-PQ-E |
| SR-PQ-10R2 | T-PQ-G: AUDIT-logger test (scrubbed payload, `reason=` field, no raw host) | T-PQ-G |
| SR-PQ-11 | `import java.util.regex.Pattern;` present in `IframeEmbedPolicy.java` | — |
| SR-PQ-12 | T-PQ-F: blank-domain guard → fail-closed; startup validation deferred | T-PQ-F |

## Test Plan

### 8 tests — 3-commit RED/GREEN/Gate shape

| ID | Test name | Class | Phase |
|----|-----------|-------|-------|
| T-PQ-A | `isEmbeddable_returnsFalse_whenFrameAncestorsContainsLookalikeHostWithSubstitutedDots` | `IframeEmbedPolicyTest` | RED → GREEN |
| T-PQ-B | `isEmbeddable_returnsFalse_whenFrameAncestorsContainsLookalikeHostWithUnderscoreSubstitution` | `IframeEmbedPolicyTest` | RED → GREEN |
| T-PQ-C | `isEmbeddable_returnsFalse_whenFrameAncestorsContainsLookalikeHostWithMetacharacterSubstitution` | `IframeEmbedPolicyTest` | RED → GREEN |
| T-PQ-D | `isEmbeddable_returnsFalse_whenFrameAncestorsMultiTokenContainsLookalikeHost` | `IframeEmbedPolicyTest` | RED → GREEN |
| T-PQ-E | `isEmbeddable_returnsTrue_whenFrameAncestorsContainsExactDomain` | `IframeEmbedPolicyTest` | GREEN-only |
| T-PQ-F | `isEmbeddable_returnsFalse_whenDomainIsBlank` | `IframeEmbedPolicyTest` | RED → GREEN |
| T-PQ-G | `isEmbeddable_emitsAuditEventOnBlockedHost` | `IframeEmbedPolicyTest` | RED → GREEN |
| T-PQ-H | `iframeEmbedPolicy_doesNotInterpolateRawDomainIntoRegex` | `IframeEmbedPolicyRegexInterpolationGateTest` | Structural (new file) |

### Commit shape
1. **Commit 1 (RED)**: T-PQ-A..F + T-PQ-G added → 6 RED (T-PQ-E GREEN-only)
2. **Commit 2 (GREEN)**: `Pattern.quote()` + blank-domain guard + AUDIT-logger migration → all GREEN
3. **Commit 3 (Gate)**: T-PQ-H structural gate (always GREEN) + SR-PQ-03 audit results in commit message

## Phase 2 Lane Partition

| Lane | Agent | Files | Concerns |
|------|-------|-------|----------|
| A — Behavioral tests | `tdd-ddd-implementer` | `IframeEmbedPolicyTest.java` | T-PQ-A, T-PQ-B, T-PQ-C, T-PQ-D, T-PQ-E |
| B — Fix + security tests | `secure-tdd-implementer` | `IframeEmbedPolicy.java` (production fix), `IframeEmbedPolicyTest.java` (T-PQ-F, T-PQ-G), `IframeEmbedPolicyRegexInterpolationGateTest.java` (T-PQ-H, new) | Pattern.quote fix, blank-domain guard, AUDIT-logger migration, structural gate |

**Note**: `IframeEmbedPolicyTest.java` is shared between lanes. Lane A adds T-PQ-A..E; Lane B adds
T-PQ-F and T-PQ-G. The implementers are dispatched sequentially (per project memory
`feedback_pipeline_sequential_impl.md`) to avoid merge conflicts.

## Resolved Conflicts

### Conflict 1: Severity Low vs Medium
**ddd-tdd-architect (Round 1)**: Did not explicitly rate severity.
**secure-feature-planner (Round 1)**: Medium via STRIDE.
**Resolution (2026-05-05)**: Medium accepted. ADR-PQ-03 documents rationale.

### Conflict 2: Test count 2 vs 5
**ddd-tdd-architect (Round 1)**: 2 tests proposed.
**secure-feature-planner (Round 1)**: 5 minimum (T-PQ-A..E).
**Resolution (2026-05-05)**: 8 tests accepted (T-PQ-A..H including blank-domain T-PQ-F, AUDIT T-PQ-G, gate T-PQ-H).

### Conflict 3: LOGGER.warn coupling mandatory vs optional
**ddd-tdd-architect (Round 2)**: Single dedicated T-PQ-G; bypass tests assert return value only.
**secure-feature-planner (Round 1)**: SR-PQ-10 — LOGGER.warn in every bypass test.
**Resolution (2026-05-05)**: Security planner accepted architect's adaptation; baked into SR-PQ-10R2
with two strengthening conditions: (a) AUDIT logger required (not class LOGGER), (b) scrubbed payload.

## User Approval

Date: 2026-05-05
Approval message (verbatim): "approve"

## Open Risks

| ID | Risk | Mitigation |
|----|------|-----------|
| OR-PQ-01 | Startup validation (`@ConfigurationProperties @NotBlank`) deferred | In-function blank-domain guard is fail-closed; misconfigured empty domain returns false (safe). `SR-PQ-12-FU` tracks the follow-up. |
| OR-PQ-02 | SR-PQ-03 codebase audit is one-shot grep, not a permanent gate | SR-PQ-07 structural gate is permanent for `IframeEmbedPolicy.java`. Broader ArchUnit rule deferred until audit finds additional interpolation sites. |

## References

- `src/main/java/de/seism0saurus/glacier/mastodon/IframeEmbedPolicy.java` (fix site: line 116)
- `src/test/java/de/seism0saurus/glacier/mastodon/IframeEmbedPolicyTest.java` (T-PQ-A..G)
- `src/test/java/de/seism0saurus/glacier/mastodon/IframeEmbedPolicyRegexInterpolationGateTest.java` (T-PQ-H, new)
- CWE-1287 (Improper Validation of Specified Type of Input)
- CWE-625 (Permissive Regular Expression)
- OWASP A04:2021 (Insecure Design)
- OWASP A03:2021 (Injection)
- ASVS 5.0.0 V5.3.6 (escape metacharacters before regex use)
- ASVS 5.0.0 V1.5.1 (server-side input validation)
- `glacier-structured-logging-logback` skill (AUDIT logger convention for SR-PQ-10R2)
- `spring-boot-testing-patterns` skill (test layer conventions)
