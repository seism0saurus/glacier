# Decision Record: F-6 D-13/SR-8 Raw Logging Cleanup — Implementation

Date: 2026-04-28
Phase: Implementation (Phase 2)
Agents: tdd-ddd-implementer (Lane A + fix cycle), secure-tdd-implementer (Lane B + Round 2 review)
Status: Accepted

## Summary

All 11 D-13/SR-8 violations fixed in `StompCallback` (6 sites) and `SubscriptionListener` (4+1 sites). One bonus violation in the `logEvent()` helper discovered during testing and fixed. `LogScrubber.safeEventName()` added with Mastodon 4.x allowlist (CWE-117 guard). Full canary harness extended in `RawWallIdLogHygieneTest`. 165 unit + 1 IT, 0 failures.

## Production Code Changes

| File | Change |
|------|--------|
| `util/LogScrubber.java` | Added `safeEventName(String)` with `KNOWN_STREAM_EVENTS` `Set` of 11 Mastodon 4.x streaming event names; unknown inputs return `unknown(len=N)` |
| `mastodon/StompCallback.java` | Fix #1: constructor uses `hashtagLen(hashtag)`; Fix #2: `event.toString()` demoted to DEBUG; Fix #3: genericMessage→`streams-size + safeEventName`; Fix #4: `urlHostHash(payload.getUrl()) + ex.getClass().getSimpleName()`; Fix #5: STOMP dest→structured triple `principal-hash/hashtag-len/event-type`; Fix #6: same as #4 for `status.getUrl()`; Bonus: `logEvent()` helper uses `hash8(principal)` |
| `webservice/SubscriptionListener.java` | Fixes #7a–d: all 4 synchronous sessionId/principal log lines use `hash8()`; Fix #8: all 3 timer-lambda log lines use `hash8(event.getUser().getName())` |

## Test Changes

| File | Change |
|------|--------|
| `util/LogScrubberTest.java` | Added 5 unit tests for `safeEventName` (null, blank, known, unknown, CRLF injection) + jqwik property fuzz test (1 000 iterations, all CRLF-free) |
| `mastodon/StompCallbackTest.java` | Added 4 positive-shape log assertions: constructor hashtag-len, event.toString() demotion, unhandled generic structured fields, stomp.message.published structured triple |
| `mastodon/RawWallIdLogHygieneTest.java` | Extended T1–T8 canary harness: 5 gaps filled (assertNoRawUrl/Hashtag helpers, `getArgumentArray()` in all assertion helpers, AUDIT appender, INFO-level filter for T2, T3 split); T3 → T3a (known event), T3b (injected/CWE-117), T3c (canary URL), T3d (stream size); T6b flipped to `isFalse()` after Fix #8 |

**Final counts**: 165 unit + 1 IT = 166 total, 0 failures. Jacoco: instruction ≥ 45% / branch ≥ 35% — PASS.

## Security Requirements Verified

| SR | Status |
|----|--------|
| SR-F6-01: No raw hashtag | PASS |
| SR-F6-02: event.toString() never at INFO or above | PASS |
| SR-F6-03: No raw URL at any log level | PASS |
| SR-F6-04: ex.getMessage() not logged — use getSimpleName() | PASS — both RestClientException catch sites verified |
| SR-F6-05: safeEventName allowlist before logging | PASS |
| SR-F6-06: stream size only | PASS |
| SR-F6-07: No raw STOMP destination | PASS |
| SR-F6-08: No raw sessionId/principal | PASS — 7 outer-method lines + 3 timer-lambda lines scrubbed |
| SR-F6-09: All replacements use canonical LogScrubber helpers | PASS |
| SR-F6-10: Canary tests cover all 10 fix sites; checks both getFormattedMessage() and getArgumentArray() | PASS |

## Fix Cycles

### Fix #8 — Timer-Lambda Raw Principal (discovered in Round 2 cross-review)

The reconnect timer lambda in `SubscriptionListener.onDisconnectEvent` contained three log lines emitting `event.getUser().getName()` (raw wallId UUID) — outside the stated Fix #7a–7d scope which covered only the synchronous outer method. The `secure-tdd-implementer` Round 2 identified this as a real SR-F6-08 violation with T6b as its canary anchor. Fixed in a Phase 2 fix cycle rather than deferred to Phase 3: `hash8(event.getUser().getName())` applied to all three lambda log lines; T6b assertion flipped from `isTrue()` (confirming bug) to `isFalse()` (confirming fix).

### Bonus logEvent() helper fix (discovered by StompCallbackTest)

`StompCallback.logEvent(String msg)` logged raw `principal` before `hash8` wrapping. Caught by `sendMessage_logsStructuredPublishedEvent_notRawDestination` via `noneSatisfy(msg -> contains(rawPrincipal))`. Fixed consistently with Fix #1 pattern.

## Open Technical Debt (non-blocking)

| Item | Description | Risk |
|------|-------------|------|
| `JsonProcessingException` path | `StompCallback.sendMessage` logs exception via `LOGGER.error(String, Throwable)` — Jackson exception can include fragment of malformed input | Low — truncated, not full URL; pre-existing |
| `processTechnicalEvent` Failure branch | Logs `failure.getError().getMessage()` (OkHttp WebSocket error) | Low — not user-controlled value; pre-existing |

## Worktree

Final implementation state: `worktree-agent-a973ec6c2f7655fce`

## User Approval

Date: 2026-04-28
Approval message (verbatim): "approve"

## References

- Phase 1 planning doc: `docs/decisions/2026-04-28-planning-f6-log-scrubbing.md`
- OWASP A09:2021 Security Logging and Monitoring Failures
- CWE-117: Improper Output Neutralization for Logs
- GDPR Recital 30 (session identifiers as personal data)
