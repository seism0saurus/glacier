# Decision Record: TD-5-FU / F-6-INFO-2 R-1 Cleanup Bundle — Planning

Date: 2026-04-30
Phase: Planning (Phase 1)
Agents: ddd-tdd-architect (Round 1 + Round 2), secure-feature-planner (Round 1 + Round 2)
Status: Accepted

## Summary

Three deferred follow-up items from the 2026-04-30 acceptance round are bundled into a single cleanup pipeline. All changes are backend-only, touching two files (`StompCallback.java` and `LogScrubberTest.java`). No frontend, no infrastructure, no public API change. Five commits in RED→GREEN→gates→docs TDD order, split across two implementer lanes.

## Items Bundled

| ID | Source | Severity | Type | Description |
|----|--------|----------|------|-------------|
| TD-5-FU-1 | `docs/decisions/2026-04-30-acceptance-td5-cwe117-cleanup.md` | Low | Cleanup | `StompCallback.java:218,223` — replace `.getClass()` with `.getClass().getSimpleName()` in two `logEvent(...)` default-branch format strings. |
| TD-5-FU-2 | same doc | Informational | Documentation | `LogScrubberTest.java:~655,~667` — fix stale numeric strings: comment says `2_147_483_648L`; correct value is `2_148_532_224L` (= 2049 × 1_048_576). Runtime assertion uses computed variable and is correct; only documentary strings wrong. |
| F-6-INFO-2 R-1 | `docs/decisions/2026-04-30-acceptance-f6info2-event-type-constants.md` | Low | Security cleanup | Remove unused `destination` parameter from four private `StompCallback` methods (`processStatusCreatedEvent`, `processStatusEditedEvent`, `procesStatusDeletedEvent` [typo kept per ADR-4], `processGenericEvent`). Also remove the `baseDestination` local in `onEvent` (confirmed dead after signatures cleaned). The parameter carries D-13-sensitive bytes (`/{wallId}/{hashtag}`) but is never read inside these methods. |

## Security Requirements

| SR-ID | Item | Description |
|-------|------|-------------|
| SR-TD5-FU-01 | TD-5-FU-1 | `StompCallback.java:218` — the default-branch `logEvent` for unknown `StreamEvent` must contain only the SimpleName, not `Class.toString()` (`class fully.qualified.Name`). Canary T7c. |
| SR-TD5-FU-02 | TD-5-FU-1 | `StompCallback.java:223` — the outer-default `logEvent` for unknown `MastodonApiEvent` must contain only the SimpleName. Canary T7d. |
| SR-TD5-FU-03 | TD-5-FU-1 | Structural regression gate (T7-gate-FU): no line containing `LOGGER.` or `logEvent(` in `StompCallback.java` may contain `.getClass())` (bare, without `.getSimpleName()`). Regex: `\.getClass\(\)\)`, allowlisting lines that also contain `.getSimpleName()`. |
| SR-TD5-FU-04 | TD-5-FU-2 | Documentation-only fix accepted as risk: the stale numeric strings in comments/descriptions are cosmetic; runtime assertion uses computed value. No new test surface. |
| SR-F6INFO2-R1-01 | F-6-INFO-2 R-1 | None of the four private methods (`processStatusCreatedEvent`, `processStatusEditedEvent`, `procesStatusDeletedEvent`, `processGenericEvent`) may declare a `destination` parameter. Structural gate R-1-gate: parameterised scan of `StompCallback.java` source confirming absence. Gate description cites SR-F6INFO2-R1-01 + CWE-532 + SI-11 + AU-9. Additionally, `String baseDestination` must no longer appear in `onEvent`. |

## Key Decisions

### ADR-1: Bundle three items into one pipeline
**Decision**: Single `/feature` pipeline for all three items.
**Rationale**: All three are deferred from the same 2026-04-30 acceptance day, Low/Informational severity, touching only two files. Splitting triples overhead with no risk-isolation benefit.
**Alternatives considered**: Three separate pipelines (rejected — wasteful), won't-fix (rejected — Item 3 has real defense-in-depth value).
**Source**: ddd-tdd-architect Round 1.

### ADR-2: No structural gate for Item 2 (LogScrubberTest documentation)
**Decision**: Direct `Edit` of two stale string literals; no new test.
**Rationale**: A meta-test pinning description strings to computed formula values is over-engineering; the runtime assertion is already correct. Documentation drift is self-correcting once spotted.
**Alternatives considered**: Meta-test scanning source for `xfo-totallen=\d+` literals (rejected).
**Source**: ddd-tdd-architect Round 1.

### ADR-3: Remove `baseDestination` local from `onEvent`
**Decision**: After Item 3 removes `destination` from the four private methods, the `String baseDestination` local in `onEvent` (line ~208) has no remaining readers. Remove it in commit 4.
**Rationale**: Eliminates a D-13-sensitive local variable from the stack frame; reduces future log-regression surface. Confirmed dead by grep at secure-feature-planner Round 1.
**Alternatives considered**: Keep for "future use" (rejected — YAGNI).
**Source**: ddd-tdd-architect Round 1, secure-feature-planner Round 2.

### ADR-4: Keep typo `procesStatusDeletedEvent` as-is
**Decision**: Do not rename the method in this pipeline.
**Rationale**: Out-of-scope change; would create churn unrelated to security cleanup. R-1-gate's `@ValueSource` lists the misspelled name explicitly for consistency.
**Alternatives considered**: Rename in separate follow-up commit (acceptable but deferred).
**Source**: User instruction.

### ADR-5: Promote `TestLogAppender` to `@BeforeEach`/`@AfterEach` in `RawWallIdLogHygieneTest.java`
**Decision**: With T7c/T7d joining T7a/T7b (four canaries on `StompCallback.class` logger), lift the per-test try/finally to class-level `@BeforeEach`/`@AfterEach`. Appender bound explicitly to `StompCallback.class` logger (not AUDIT logger).
**Rationale**: Project threshold: ≥ 3 canaries on the same logger → promote. AUDIT logger must remain uncontaminated by test instrumentation per `glacier-structured-logging-logback` discipline.
**Source**: ddd-tdd-architect Round 1 + secure-feature-planner Round 1.

## Phase 2 Lane Partition

| Lane | Agent | Commits | Files |
|------|-------|---------|-------|
| Lane B (security) | `secure-tdd-implementer` | 1, 2, 3 | `RawWallIdLogHygieneTest.java` (T7c/T7d/TestLogAppender lift), `StompCallback.java` (lines 218, 223 fix), `StompCallbackTest.java` (T7-gate-FU) |
| Lane A (domain/docs) | `tdd-ddd-implementer` | 4, 5 | `StompCallback.java` (signature + Javadoc sweep + baseDestination removal), `StompCallbackTest.java` (R-1-gate), `LogScrubberTest.java` (doc strings) |

**Sequencing rule**: commits 1–3 (`secure-tdd-implementer`) must land before commits 4–5 (`tdd-ddd-implementer`) per the project's sequential Phase 2 policy.

## Five-Commit Implementation Plan

| # | Shape | Lane | Key SR |
|---|-------|------|--------|
| 1 | `test(security): add T7c/T7d canaries + TestLogAppender lift (RED)` | secure-tdd | SR-TD5-FU-01/02 |
| 2 | `fix(security): TD-5-FU-1 replace getClass() with getSimpleName() at lines 218,223` | secure-tdd | SR-TD5-FU-01/02 |
| 3 | `test(security): T7-gate-FU structural gate banning bare getClass() on logEvent lines` | secure-tdd | SR-TD5-FU-03 |
| 4 | `refactor: F-6-INFO-2 R-1 remove unused destination from StompCallback private methods` | tdd-ddd | SR-F6INFO2-R1-01 |
| 5 | `docs(test): TD-5-FU-2 fix stale overflow-value strings in LogScrubberTest` | tdd-ddd | SR-TD5-FU-04 |

## Non-Blocking Refinements for Phase 2 Implementers

1. **T7-gate-FU allowlist**: the gate's trigger must be `.getClass())` (bare) AND the line does NOT contain `.getSimpleName()` — to avoid false-positive on already-secure line 207.
2. **R-1-gate `baseDestination` check**: gate should additionally assert `String baseDestination` is absent from `onEvent` body, closing the same regression class at the local-variable level.

## Resolved Conflicts

None — no `## ⚡ CONFLICT:` markers raised in either round.

## User Approval

Date: 2026-04-30
Approval message (verbatim): "approve"

## Open Risks

- **Bigbone sealed-class hierarchy**: If `MastodonApiEvent` or `ParsedStreamEvent` is a Kotlin sealed class blocking Mockito mocking, Phase 2 implementers fall back to minimal private test subclasses. Compile-time issue with well-defined fallback; no runtime risk.
- **SR-TD5-FU-04 (doc-only)**: Stale numeric strings could mislead a future reader, but runtime assertion is already correct. Risk accepted.

## References

- [TD-5 CWE-117 Cleanup — Acceptance](2026-04-30-acceptance-td5-cwe117-cleanup.md) (TD-5-FU-1, TD-5-FU-2 deferral source)
- [F-6-INFO-2 Event-Type Constants — Acceptance](2026-04-30-acceptance-f6info2-event-type-constants.md) (R-1 deferral source)
- [CWE-117: Improper Output Neutralization for Logs](https://cwe.mitre.org/data/definitions/117.html)
- [CWE-532: Insertion of Sensitive Information into Log File](https://cwe.mitre.org/data/definitions/532.html)
- [OWASP Top 10 (2021) — A09: Security Logging and Monitoring Failures](https://owasp.org/Top10/A09_2021-Security_Logging_and_Monitoring_Failures/)
- glacier-structured-logging-logback (D-13/SR-8)
- glacier-fallback-mode-discipline (N/A — confirmed by both agents)
