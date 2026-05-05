# Decision Record: F-6-FU-3 T5b Canary — Planning

Date: 2026-04-29
Phase: Planning (Phase 1)
Agents: ddd-tdd-architect (Round 1 + Round 2), secure-feature-planner (Round 1 + Round 2)
Status: Accepted

## Summary

F-6 acceptance audit finding R1 flagged two vacuous canary tests in the log-hygiene suite: T5 (site 7a, `onConnectedEvent` no-principal WARN) and the absence of T5b (site 7c, `onDisconnectEvent` no-principal WARN). Both pass vacuously because the test helpers use `new MessageHeaders(null)` — `SimpMessageHeaderAccessor.wrap(event.getMessage())` reads from the `MessageHeaders` map, so the session ID canary is never injected into the production code path. The fix is a pure test change: patch T5 and add T5b, both in `RawWallIdLogHygieneTest.java`. No production code changes needed — `SubscriptionListener.java` is already fixed at lines 150 and 178.

## Key Decisions

### ADR-T5b-01: Header-map injection over mock accessor
**Decision**: Canary session ID must be supplied via `new MessageHeaders(Map.of(SimpMessageHeaderAccessor.SESSION_ID_HEADER, CANARY_SESSION))`. Mocking `SimpMessageHeaderAccessor` directly is dead code because production calls `SimpMessageHeaderAccessor.wrap(event.getMessage())` which reads real `MessageHeaders`.
**Rationale**: Without header-map injection, `getSessionId()` returns `null`, `hash8(null)` returns `"null"`, and the canary string is never at risk of appearing — the test passes vacuously regardless of whether `hash8(...)` is present or not.
**Alternatives considered**: Keep mock accessor (rejected — dead code); fix in `SubscriptionListenerTest.java` instead (rejected — venue rule: canary tests live in `RawWallIdLogHygieneTest.java`).
**Source**: ddd-tdd-architect Round 1; confirmed by secure-feature-planner Round 1.

### ADR-T5b-02: T5 (site 7a) fix bundled with T5b in same PR
**Decision**: T5 (`RawWallIdLogHygieneTest.java:502–525`) has the identical header-map injection defect as site 7c. Patch T5 in the same PR as the new T5b.
**Rationale**: Both sites share the same root cause (vacuous `MessageHeaders(null)` injection), the fix is a 2-line change, and F-6 R1 cited both sites. Separate tracking risks the defect persisting.
**Alternatives considered**: Defer T5 fix to a follow-up (rejected — F-6 R1 would remain partially open).
**Source**: Resolved CONFLICT-T5b-01 — secure-feature-planner raised, ddd-tdd-architect accepted in Round 2.

### ADR-T5b-03: Venue — `RawWallIdLogHygieneTest.java`
**Decision**: Both T5 fix and T5b live in `RawWallIdLogHygieneTest.java`, not in `SubscriptionListenerTest.java`.
**Rationale**: Log-hygiene canary tests for the D-13/SR-8 invariant are centralized in `RawWallIdLogHygieneTest`. The existing class infrastructure (`CANARY_SESSION`, `assertNoRawSessionId()`, `@BeforeEach` appender setup, AUDIT logger wiring) is already present there. T5 lives in this file; T5b belongs next to it.
**Source**: ddd-tdd-architect Round 2 (ADR-T5b-05 in arch_review).

### ADR-T5b-04: Static canary constant (reuse existing `CANARY_SESSION`)
**Decision**: Use the existing `CANARY_SESSION` constant defined in `RawWallIdLogHygieneTest.java`. No UUID suffix.
**Rationale**: Deterministic failure messages, grep-stable across CI runs. No entropy benefit for a non-secret sentinel. UUID suffix harms reproducibility without adding test isolation value (appender is per-test).
**Source**: secure-feature-planner Round 1; accepted by ddd-tdd-architect Round 2.

### ADR-T5b-05: Positive assertion required (silent-deletion guard)
**Decision**: SR-T5b-03 is mandatory. T5b must assert `getFormattedMessage().contains(LogScrubber.hash8(CANARY_SESSION))` in addition to the primary canary-absent assertion.
**Rationale**: Without the positive assertion, a test that captures zero events (because the `LOGGER.warn(...)` call is deleted) passes the "canary absent" assertion vacuously. The positive assertion turns that into a red failure.
**Source**: secure-feature-planner Round 1.

## Security Requirements

| SR | Requirement | Test |
|----|-------------|------|
| SR-T5b-01 | `getFormattedMessage()` does NOT contain `CANARY_SESSION` (primary red-anchor) | T5b assertion 1 |
| SR-T5b-02 | `getArgumentArray()` elements do not contain `CANARY_SESSION` (defence-in-depth) | T5b assertion 2 |
| SR-T5b-03 | `getFormattedMessage()` DOES contain `"session-hash="` AND `hash8(CANARY_SESSION)` (silent-deletion guard) | T5b assertion 3 |
| SR-T5b-04 | `captured.getLevel()` == `Level.WARN` | T5b assertion 4 |
| SR-T5b-05 | `captured.getThrowableProxy()` is `null` | T5b assertion 5 |
| SR-T5b-06 | `captured.getLoggerName()` == `SubscriptionListener.class.getName()` | T5b assertion 6 |
| SR-T5b-07 | `captured.getMessage()` (pre-format) matches expected format string | T5b assertion 7 |
| SR-T5b-08 | Exactly 1 event on SubscriptionListener appender; AUDIT appender has 0 canary events | T5b assertion 8 |

## Test Plan

### Lane A — `tdd-ddd-implementer` (test shape + T5 patch)

**Patch T5** (site 7a, `RawWallIdLogHygieneTest.java:502–525`):
- Replace `new MessageHeaders(null)` with `new MessageHeaders(Map.of(SimpMessageHeaderAccessor.SESSION_ID_HEADER, CANARY_SESSION))`
- Add positive assertion: `assertThat(formattedMessage).contains(LogScrubber.hash8(CANARY_SESSION))`

**New T5b** (site 7c, after T5 in `RawWallIdLogHygieneTest.java`):
- Given: `CANARY_SESSION` injected into `MessageHeaders.SESSION_ID_HEADER`; `event.getUser()` returns `null`
- When: `subscriptionListener.onDisconnectEvent(event)` called
- Then: SR-T5b-01..08 all pass

### Lane B — `secure-tdd-implementer` (assertion hardening + red-anchor verification)

- Verify `assertNoRawSessionId()` helper covers `argumentArray` (or add explicit loop)
- Confirm SR-T5b-07 format-string identity assertion is present
- Verify red-anchor: temporarily revert `hash8(...)` at `SubscriptionListener.java:178` and confirm SR-T5b-01 fails; restore before commit
- Verify T5 red-anchor: temporarily revert `hash8(...)` at `SubscriptionListener.java:150` and confirm T5 now fails; restore

### Lane order

Lane A first (test shape + infrastructure), then Lane B (assertion hardening), then joint Round 2 review.

## Resolved Conflicts

### CONFLICT-T5b-01: T5 (site 7a) scope
**ddd-tdd-architect**: T5b scope is `SubscriptionListenerTest.java` only; no changes to existing tests.
**secure-feature-planner**: T5 is vacuous for the same reason; fix both in the same PR.
**Resolution (2026-04-29)**: "approve" — bundled per security team recommendation.

## User Approval

Date: 2026-04-29
Approval message (verbatim): "approve"

## Open Risks

- `assertNoRawSessionId()` helper in `RawWallIdLogHygieneTest.java` must cover `argumentArray` — implementer to verify or add explicit sweep.
- `SubscriptionListenerTest.java` remains unchanged; the structural (non-canary) tests for site 7c (`testOnDisconnectEvent_WithoutPrincipal`) continue to use `MessageHeaders(null)` which returns `null` session ID — that is acceptable for structural tests but means they do not serve as canaries. This is the correct division of responsibilities.

## References

- [F-6 D-13/SR-8 Raw Logging Cleanup — Acceptance](2026-04-28-acceptance-f6-log-scrubbing.md) (R1 finding + F-6-FU-3 origin)
- [F-6 D-13/SR-8 Raw Logging Cleanup — Implementation](2026-04-28-implementation-f6-log-scrubbing.md) (fix sites 7a/7c)
- [CWE-532: Insertion of Sensitive Information into Log File](https://cwe.mitre.org/data/definitions/532.html)
- [OWASP Top 10 (2021) — A09: Security Logging and Monitoring Failures](https://owasp.org/Top10/A09_2021-Security_Logging_and_Monitoring_Failures/)
- Glacier D-13/SR-8/ADR-F6-01
- [F-6-FU-3 T5b Canary — Implementation](2026-04-30-implementation-f6fu3-t5b-canary.md)
- [F-6-FU-3 T5b Canary — Acceptance](2026-04-30-acceptance-f6fu3-t5b-canary.md)
