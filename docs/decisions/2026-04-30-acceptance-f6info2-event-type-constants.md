# Decision Record: F-6-INFO-2 Event-Type Constants — Acceptance

Date: 2026-04-30
Phase: Acceptance (Phase 3)
Agents: security-auditor (Round 1 + Round 2), acceptance-test-auditor (Round 1)
Status: Accepted — PASSED

## Summary

F-6-INFO-2 replaces the fragile `destination.lastIndexOf('/')` event-type extraction in `StompCallback.sendMessage` with a closed `StompEventType` enum. All 9 security requirements (SR-F6INFO2-01 through -09) are covered by tests. One Low-severity gap (SR-F6INFO2-02 missing runtime modification-spelling assertion) was identified and fixed during the acceptance phase.

## Acceptance Disposition: PASSED

All security and functional requirements are met. The implementation is approved for merge.

## Findings

### Finding W-1 — SR-F6INFO2-02 modification-spelling runtime assertion (Fixed)

**Severity**: Low
**Standard**: OWASP A09 / CWE-117
**Finding**: SR-F6INFO2-02 originally lacked a runtime assertion that `status.update` events produce `event-type=modification` in the log — only the CREATION branch had an end-to-end runtime guard.
**Fix**: Added `stompMessagePublished_logsEventTypeModification_whenStatusUpdatedDispatched` to `StompCallbackTest.java` (lines 2471–2506). The test drives `GenericMessageContent.event("status.update")` through `callback.onEvent`, asserts `event-type=modification`, and guards against the regression spelling `event-type=hashtag`.
**Verified**: `StompCallbackTest` 93/93 pass after fix.

### Finding #1 — `eventTypeFor(StatusDeletedMessage.class)` returns empty (Info, Accepted)

Intentional — the deletion path routes through `procesStatusDeletedEvent` directly, bypassing `sendMessage`. No fix required; maps to ADR-F6-INFO-2-A.

### Finding #2 — `eventTypeFor(null)` returns empty (Info, Accepted)

Defensive null-safety; no production path passes null. No fix required.

### Finding #3 — Reflection probe doesn't exercise `sendMessage` public API end-to-end (Low, Accepted)

`sendMessage` is private and no production path reaches the unknown-class branch. Reflection is the correct shape for this structural guard. Accepted as optional improvement; no fix required.

### Recommendation R-1 — Dead `destination` parameter on 4 sibling private methods (Deferred)

After the F-6-INFO-2 refactor, `destination` remains a parameter on `processStatusCreatedEvent`, `processStatusEditedEvent`, `procesStatusDeletedEvent`, and the `processGenericEvent` call chain, but is never read inside those method bodies. The bytes carried are D-13-sensitive (`principal/hashtag`). This is a residual from the same class of issue as SR-F6INFO2-09. Out of F-6-INFO-2 scope; recommended as a follow-up cleanup ticket.

## Security Requirements Coverage

| SR | Requirement | Status |
|----|-------------|--------|
| SR-F6INFO2-01 | `eventTypeFor(StatusCreatedMessage.class)` returns `Optional.of(CREATION)` | PASS |
| SR-F6INFO2-02 | Runtime log assertion: `event-type=modification` for `status.update` path | PASS (fixed by W-1) |
| SR-F6INFO2-03 | No attacker-controlled bytes reach `event-type=` log field | PASS |
| SR-F6INFO2-04 | Unknown class → `Optional.empty()` + ERROR + return early (no virtual-thread kill) | PASS |
| SR-F6INFO2-05 | `event-type=` field name preserved | PASS |
| SR-F6INFO2-06 | Hashtag `"a/b"` logs `event-type=creation`, not `event-type=b` | PASS |
| SR-F6INFO2-07 | `StompEventType.suffix()` returns exactly the three wire strings | PASS |
| SR-F6INFO2-08 | No new MDC fields; no raw values in the affected log line | PASS |
| SR-F6INFO2-09 | `sendMessage` signature has no `destination` parameter | PASS |

## Final Test Results

| Suite | Count | Result |
|-------|-------|--------|
| `StompEventTypeTest` | 3 | PASS |
| `StompCallbackTest` | 93 (was 89 pre-F-6-INFO-2) | PASS |
| Backend unit suite | 853 | PASS |
| Integration suite | 173 | PASS |
| Frontend unit (Karma) | 522 | PASS |
| `./mvnw verify -DskipIntegrationTests` | — | BUILD SUCCESS |

## User Approval

Date: 2026-04-30
Approval message (verbatim): "approve"

## Open Risks

- Dead `destination` parameter on 4 sibling private methods (R-1) — deferred to follow-up cleanup ticket.
- Three parallel event-type vocabularies (`cache.EventType`, `mastodon.StompEventType`, `Status*Message.class`) remain separate — explicitly accepted per ADR-F6-INFO-2-A.

## References

- [F-6-INFO-2 Event-Type Constants — Planning](2026-04-30-planning-f6info2-event-type-constants.md)
- [F-6-INFO-2 Event-Type Constants — Implementation](2026-04-30-implementation-f6info2-event-type-constants.md)
- [CWE-117: Improper Output Neutralization for Logs](https://cwe.mitre.org/data/definitions/117.html)
- [OWASP Top 10 (2021) — A09: Security Logging and Monitoring Failures](https://owasp.org/Top10/A09_2021-Security_Logging_and_Monitoring_Failures/)
- glacier-structured-logging-logback (D-13/SR-8)
- glacier-fallback-mode-discipline (virtual-thread kill risk, ADR-D rationale)
