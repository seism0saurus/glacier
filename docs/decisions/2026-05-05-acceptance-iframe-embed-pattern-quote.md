# Decision Record: IframeEmbedPolicy Pattern.quote() Regex Bypass — Acceptance

Date: 2026-05-05
Phase: Acceptance (Phase 3)
Agents: security-auditor (Round 1), acceptance-test-auditor (Round 1)
Status: Accepted — PASSED

## Summary

All 13 acceptance criteria (AC-PQ-01..12 + AC-BUILD) are satisfied. Zero fix cycles required.
Both auditors returned PASS independently with no conflicts. The `Pattern.quote()` bypass fix,
blank-domain fail-closed guard, AUDIT logger routing, and structural gate (count-match T-PQ-H) are
all correctly implemented and tested. BUILD SUCCESS: 1,269 unit tests + 298 IT.

## Acceptance Result

**Disposition: PASSED**
**Criteria checked**: 13
**Criteria passed**: 13
**Criteria failed**: 0
**Fix cycles**: 0

## AC Coverage (acceptance-test-auditor)

| AC | Status | Key Evidence |
|----|--------|-------------|
| AC-PQ-01 | PASS | T-PQ-A: `isEmbeddable_returnsFalse_whenFrameAncestorsContainsLookalikeHostWithSubstitutedDots` — `glacierXevents` vs `glacier.events` → `false` (`IframeEmbedPolicyTest.java:243-252`) |
| AC-PQ-02 | PASS | T-PQ-B: underscore substitution `glacier_events` → `false` — proves exact-match semantics (`IframeEmbedPolicyTest.java:271-280`) |
| AC-PQ-03 | PASS | T-PQ-C: `+` metacharacter `glacier+example+com` → `false` (`IframeEmbedPolicyTest.java:300-309`) |
| AC-PQ-04 | PASS | T-PQ-D: multi-token `'self' https://glacierXevents https://trusted.example.com` → `false` (`IframeEmbedPolicyTest.java:327-337`) |
| AC-PQ-05 | PASS | T-PQ-E: `https://glacier.events` → `true` (over-rejection guard; `IframeEmbedPolicyTest.java:354-363`) |
| AC-PQ-06 | PASS | T-PQ-F: blank-domain triple (`""`, `"   "`, `null`) all → `false` fail-closed (`IframeEmbedPolicyTest.java:386-394`) |
| AC-PQ-07 | PASS | T-PQ-G: captures `LoggerFactory.getLogger("AUDIT")` at `Level.INFO`; asserts `reason=domain_mismatch` present; asserts `glacier.events` and `trusted.example.com` absent (CWE-117 — `IframeEmbedPolicyTest.java:417-452`) |
| AC-PQ-08 | PASS | T-PQ-H: count-match gate — `wrapped.results().count() == total.results().count()`; CRLF/format-insensitive (`IframeEmbedPolicyRegexInterpolationGateTest.java:43-73`) |
| AC-PQ-09 | PASS | `IframeEmbedPolicy.java:140` — `+ Pattern.quote(domain.toUpperCase(Locale.ROOT))` |
| AC-PQ-10 | PASS | `IframeEmbedPolicy.java:112-115` — blank-domain guard at function entry, before state variable init |
| AC-PQ-11 | PASS | `IframeEmbedPolicy.java:70` — `private static final Logger AUDIT = LoggerFactory.getLogger("AUDIT");` |
| AC-PQ-12 | PASS | `IframeEmbedPolicy.java:10` — `import java.util.regex.Pattern;` |
| AC-BUILD | PASS | 1,269 unit tests + 298 integration tests — BUILD SUCCESS; Jacoco thresholds met |

## SR Coverage (security-auditor)

| SR | Status | Evidence |
|----|--------|---------|
| SR-PQ-01 | PASS | `Pattern.quote(domain.toUpperCase(Locale.ROOT))` at `IframeEmbedPolicy.java:140`; T-PQ-A..E confirm bypass class closed |
| SR-PQ-02 | PASS | `toUpperCase(Locale.ROOT)` before `Pattern.quote()` — CWE-178 ordering correct |
| SR-PQ-03 | PASS | Bounded grep audit — 5 other sites all SAFE (documented in commit `0bd6371`) |
| SR-PQ-04 | PASS | T-PQ-A dot-substitution RED commit `29a8b38`, GREEN after `2163cdd` |
| SR-PQ-05 | PASS | T-PQ-B underscore substitution — proves exact-match semantics beyond metachar escaping |
| SR-PQ-06 | PASS | T-PQ-C `+` metacharacter — confirms `Pattern.quote()` covers full metacharacter set |
| SR-PQ-07 | PASS | T-PQ-H count-match structural gate; dedicated file per ADR-PQ-06 |
| SR-PQ-08 | PASS | T-PQ-D multi-token frame-ancestors lookalike blocked |
| SR-PQ-09 | PASS | T-PQ-E legitimate exact domain continues to return `true` |
| SR-PQ-10R2 | PASS | T-PQ-G captures `"AUDIT"` at INFO; `reason=domain_mismatch` static literal; no raw values |
| SR-PQ-11 | PASS | `import java.util.regex.Pattern;` at `IframeEmbedPolicy.java:10` |
| SR-PQ-12 | PASS | Blank-domain guard at function entry; T-PQ-F triple assertion; SR-PQ-12-FU tracked |

## Security Findings

| ID | Severity | Location | Description | Disposition |
|----|----------|----------|-------------|-------------|
| F-INFO-1 | Informational | `IframeEmbedPolicy.java:162-163` | Dual-channel logging (`LOGGER.warn` + `AUDIT.info`) — intentional per ADR-PQ-04 | Accepted by design |
| F-INFO-2 | Informational | `IframeEmbedPolicyTest.java:443` | T-PQ-G uses `auditEvents.get(0)` — first-event coupling | Accepted; `anySatisfy` hardening recorded as follow-up |
| F-INFO-3 | Informational | `IframeEmbedPolicy.java:113` | Blank-domain LOGGER.warn is operator-only (no AUDIT) — correct per logback skill | Accepted by design |

## Fix Cycles

None. Both auditors returned PASS on first round; no `## FIX REQUEST →` markers raised.

## Test Results

| Suite | Tests | Failures | Errors | Skipped |
|-------|-------|----------|--------|---------|
| Surefire (unit) | 1,269 | 0 | 0 | 0 |
| Failsafe (IT) | 298 | 0 | 0 | 0 |
| Jacoco | — | — | threshold pass | — |

## User Approval

Date: 2026-05-05
Approval message (verbatim): "approve"

## Open Risks

| ID | Risk | Rationale |
|----|------|-----------|
| OR-PQ-01 | `@ConfigurationProperties @NotBlank` startup validation deferred (`SR-PQ-12-FU`) | In-function blank-domain guard is fail-closed; `glacier.domain=""` returns `false` at the function boundary — safe. Configuration-validation backlog item for full `@NotBlank` coverage. |
| OR-PQ-02 | T-PQ-G first-event coupling (`auditEvents.get(0)`) | Safe today — exactly one AUDIT.info in blocked-domain branch. Switch to `anySatisfy` recommended but non-blocking. |
| OR-PQ-03 | T-PQ-H structural gate bounded to `IframeEmbedPolicy.java` only | SR-PQ-03 one-shot audit confirms no other interpolation sites at time of fix. |

## References

- [Planning doc](2026-05-05-planning-iframe-embed-pattern-quote.md)
- [Implementation doc](2026-05-05-implementation-iframe-embed-pattern-quote.md)
- `src/main/java/de/seism0saurus/glacier/mastodon/IframeEmbedPolicy.java`
- `src/test/java/de/seism0saurus/glacier/mastodon/IframeEmbedPolicyTest.java`
- `src/test/java/de/seism0saurus/glacier/mastodon/IframeEmbedPolicyRegexInterpolationGateTest.java`
- CWE-625 (Permissive Regular Expression)
- CWE-1287 (Improper Validation of Specified Type of Input)
- CWE-117 (Log Injection — mitigated by static `reason=` message)
- OWASP A04:2021 (Insecure Design)
- OWASP A03:2021 (Injection)
- ASVS 5.0.0 V5.3.6 (escape metacharacters before regex use)
- `glacier-structured-logging-logback` skill (AUDIT logger convention)
- `spring-boot-testing-patterns` skill (test layer conventions)
