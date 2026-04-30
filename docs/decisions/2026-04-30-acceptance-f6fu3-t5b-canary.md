# Decision Record: F-6-FU-3 T5b Canary — Acceptance

Date: 2026-04-30
Phase: Acceptance (Phase 3)
Agents: security-auditor (Round 1 + Round 2), acceptance-test-auditor (Round 1)
Status: **PASSED**

## Summary

Phase 3 acceptance audit validated the F-6-FU-3 test-only change. T5 (`onConnectedEvent` no-principal WARN, site 7a) was vacuous due to `MessageHeaders(null)` suppressing `getSessionId()`; it is now patched with real session-id injection and a positive hash assertion. T5b (new, `onDisconnectEvent` no-principal WARN, site 7c) is added with all 8 SR assertions. Production code (`SubscriptionListener.java`) is unchanged. No fix cycles. 794 unit + 173 IT = 967 backend tests, 0 failures; Jacoco thresholds met.

## Acceptance Disposition: PASSED

All requirements from Phase 1 planning (`docs/decisions/2026-04-29-planning-f6fu3-t5b-canary.md`) are met. No Critical or High findings remain open. F-6 acceptance finding R1 is fully closed.

## Security Requirements — Final Verification

| SR | Requirement | Status |
|----|-------------|--------|
| SR-T5b-01 | `getFormattedMessage()` does NOT contain `CANARY_SESSION` (primary red-anchor) | VERIFIED — `assertNoRawSessionId` at line 600 |
| SR-T5b-02 | `getArgumentArray()` elements do not contain `CANARY_SESSION` | VERIFIED — same helper; covers argument array |
| SR-T5b-03 | `getFormattedMessage()` DOES contain `"session-hash="` AND `LogScrubber.hash8(CANARY_SESSION)` | VERIFIED — lines 603–608 |
| SR-T5b-04 | `getLevel()` == `Level.WARN` | VERIFIED — lines 611–613 |
| SR-T5b-05 | `getThrowableProxy()` == null | VERIFIED — lines 616–618 |
| SR-T5b-06 | `getLoggerName()` == `SubscriptionListener.class.getName()` | VERIFIED — lines 621–623 (class literal) |
| SR-T5b-07 | `getMessage()` (pre-format) == exact format string | VERIFIED — lines 626–628 |
| SR-T5b-08 | Exactly 1 event on subscriptionListenerAppender; AUDIT 0 canary events | VERIFIED — lines 593–595 + 631–634 |

## Red-Anchor Verification (Phase 2)

| Mutation | Failing test | Failure message excerpt |
|----------|-------------|------------------------|
| Line 178: `hash8(getSessionId())` → `getSessionId()` | SR-T5b-01 | `"...canary-session-uuid-12345 disconnected..."` in formattedMessage |
| Line 150: `hash8(getSessionId())` → `getSessionId()` | T5 SR-F6-08 | `"...canary-session-uuid-12345 connected..."` in formattedMessage |

No cross-firing. Both production lines restored before commit.

## Live Log Confirmation

Acceptance-test-auditor observed in stdout during `./mvnw verify`:
```
{"level":"WARN","logger":"de.seism0saurus.glacier.webservice.messaging.SubscriptionListener","message":"Client with session-hash=7fc4a57b connected but has no user associated with it"}
{"level":"WARN","logger":"de.seism0saurus.glacier.webservice.messaging.SubscriptionListener","message":"Client with session-hash=7fc4a57b disconnected but has no user associated with it"}
```
Raw `canary-session-uuid-12345` absent from both lines.

## Final Test Counts

| Suite | Count | Failures |
|-------|-------|----------|
| Java unit (Surefire) | 794 | 0 |
| Java integration (Failsafe) | 173 | 0 |
| Angular Karma | 522 | 0 |
| **Java total** | **967** | **0** |

Jacoco: instruction ≥ 45% / branch ≥ 35% — PASS.

## Finding Dispositions

| ID | Description | Severity | Disposition |
|----|-------------|----------|-------------|
| F-6 R1 (T5) | `RawWallIdLogHygieneTest.T5` canary was vacuous — `MessageHeaders(null)` prevented real session-id injection | Medium | FIXED |
| F-6 R1 (T5b) | No dedicated canary for site 7c (`onDisconnectEvent` no-user WARN) | Medium | FIXED — T5b added with 8 SRs |
| Informational | T4/T6/T6b still use `MessageHeaders(null)` — non-vacuous for their `CANARY_UUID`/principal concern | Informational | Accepted — session-id hardening for those tests deferred |

## User Approval

Date: 2026-04-30
Approval message (verbatim): "approve"

## References

- Phase 1 planning: `docs/decisions/2026-04-29-planning-f6fu3-t5b-canary.md`
- Phase 2 implementation: `docs/decisions/2026-04-30-implementation-f6fu3-t5b-canary.md`
- F-6 acceptance: `docs/decisions/2026-04-28-acceptance-f6-log-scrubbing.md` (R1 origin)
- CWE-532: Insertion of Sensitive Information into Log File
- OWASP A09:2021 Security Logging and Monitoring Failures
- Glacier D-13/SR-8/ADR-F6-01
