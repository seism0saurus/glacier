# Decision Record: F-6-FU-3 T5b Canary — Implementation

Date: 2026-04-30
Phase: Implementation (Phase 2)
Agents: tdd-ddd-implementer (Lane A + Round 2), secure-tdd-implementer (Lane B)
Status: Accepted

## Summary

Patched T5 (fix site 7a, `onConnectedEvent` no-principal WARN) and added T5b (fix site 7c, `onDisconnectEvent` no-principal WARN) in `RawWallIdLogHygieneTest.java`. Both sites were previously vacuous because `new MessageHeaders(null)` prevented `CANARY_SESSION` from reaching the production code path. The fix injects the canary via `MessageHeaders(Map.of(SESSION_ID_HEADER, CANARY_SESSION))` and adds all 8 SR assertions. No production code changes. 844 unit + 173 IT = 1017 total tests, 0 failures.

## Production Code Changes

None. `SubscriptionListener.java` is already fixed at lines 150 and 178.

## Test Changes

| File | Change |
|------|--------|
| `RawWallIdLogHygieneTest.java` | Added `import java.util.Map;` |
| | **T5 patched** (lines 502–525): `MessageHeaders(null)` → `MessageHeaders(Map.of(SESSION_ID_HEADER, CANARY_SESSION))`; positive assertion `assertThat(events).anySatisfy(e -> assertThat(e.getFormattedMessage()).contains(LogScrubber.hash8(CANARY_SESSION)))` added |
| | **T5b added** (new test after T5): `T5b_subscriptionListener_onDisconnectEvent_noPrincipal_doesNotLogRawSession` with all 8 SR assertions |

## Security Requirements Verified

| SR | Status | Assertion |
|----|--------|-----------|
| SR-T5b-01 | VERIFIED | `assertNoRawSessionId(captured, CANARY_SESSION)` — formattedMessage clean |
| SR-T5b-02 | VERIFIED | `assertNoRawSessionId(captured, CANARY_SESSION)` — argumentArray clean |
| SR-T5b-03 | VERIFIED | `getFormattedMessage().contains("session-hash=")` + `.contains(LogScrubber.hash8(CANARY_SESSION))` |
| SR-T5b-04 | VERIFIED | `getLevel() == Level.WARN` |
| SR-T5b-05 | VERIFIED | `getThrowableProxy() == null` |
| SR-T5b-06 | VERIFIED | `getLoggerName() == SubscriptionListener.class.getName()` (class literal) |
| SR-T5b-07 | VERIFIED | `getMessage()` == `"Client with session-hash={} disconnected but has no user associated with it"` |
| SR-T5b-08 | VERIFIED | `subscriptionListenerAppender.list.hasSize(1)` + AUDIT 0 canary events |

## Red-Anchor Verification

| Mutation | Failing assertion | Failure message |
|----------|------------------|-----------------|
| Line 178: `hash8(getSessionId())` → `getSessionId()` | SR-T5b-01 | `"Client with session-hash=canary-session-uuid-12345 disconnected..."` contains raw canary |
| Line 150: `hash8(getSessionId())` → `getSessionId()` | T5 SR-F6-08 | `"Client with session-hash=canary-session-uuid-12345 connected..."` contains raw canary |

No cross-firing between the two tests. Both production lines restored before commit.

## Final Test Counts

| Suite | Before | After | Failures |
|-------|--------|-------|----------|
| Java unit (Surefire) | 843 | 844 | 0 |
| Java integration (Failsafe) | 173 | 173 | 0 |
| **Total** | **1016** | **1017** | **0** |

Jacoco: instruction ≥ 45% / branch ≥ 35% — PASS.

## Deviations from Phase 1 Plan

None. All ADRs implemented as planned.

## User Approval

Date: 2026-04-30
Approval message (verbatim): "approve"

## References

- Phase 1 planning: `docs/decisions/2026-04-29-planning-f6fu3-t5b-canary.md`
- F-6 acceptance: `docs/decisions/2026-04-28-acceptance-f6-log-scrubbing.md` (R1 finding + F-6-FU-3 origin)
- CWE-532: Insertion of Sensitive Information into Log File
- OWASP A09:2021 Security Logging and Monitoring Failures
- Glacier D-13/SR-8/ADR-F6-01
