# Decision Record: TD-2 TechnicalEvent.Failure/Closing/Closed Log Scrubbing — Implementation

Date: 2026-04-29
Phase: Implementation (Phase 2)
Agents: tdd-ddd-implementer (Lane A + Round 2), secure-tdd-implementer (Lane B + Round 2)
Status: Accepted

## Summary

Three production lines in `StompCallback.processTechnicalEvent` replaced: `failure.getError().getMessage()` (line 532) with `getClass().getSimpleName()`, `closing.toString()` (line 527) with `closing.getCode()`, and `closed.toString()` (line 529) with `closed.getCode()`. Peer-controlled `reason` and exception message strings no longer reach JSON logs. TDD-verified: 8 shape/restart tests (T-A1–T-A6, T-A4b, T-A5b) + 1 parameterised 7-variant injection canary (T-B1). 2032 tests, 0 failures.

## Production Code Changes

| File | Line | Change |
|------|------|--------|
| `mastodon/StompCallback.java` | 527 | `logEvent("got a Closing event: %s".formatted(closing))` → `logEvent("got a Closing event — code=%d".formatted(closing.getCode()))` |
| `mastodon/StompCallback.java` | 529 | `logEvent("got a Closed event: %s".formatted(closed))` → `logEvent("got a Closed event — code=%d".formatted(closed.getCode()))` |
| `mastodon/StompCallback.java` | 532 | `logEvent("got a Failure event. Restarting subscription. The error is: %s".formatted(failure.getError().getMessage()))` → `logEvent("got a Failure event. Restarting subscription. exception=%s".formatted(failure.getError().getClass().getSimpleName()))` |

## Test Changes

| File | Tests added/changed | Type |
|------|---------------------|------|
| `mastodon/StompCallbackTest.java` | T-A1: `failureEvent_logsInfoLevel` | Unit |
| | T-A2: `failureEvent_includesAllowlistedExceptionSimpleName` — `EXPECTED_TECHNICAL_FAILURE_EXCEPTIONS` | Unit |
| | T-A3: `failureEvent_messagePrefixMatches` — prefix `"got a Failure event. Restarting subscription. exception="` | Unit |
| | T-A4: `failureEvent_attachesNoThrowable` — `getThrowableProxy() == null` strict **[red→green anchor]** | Unit |
| | T-A5: `failureEvent_doesNotPolluteMdcOrAudit` — MDC clean + AUDIT zero events | Unit |
| | T-A6: `failureEvent_restartsSubscription` — `terminateSubscription` + `subscribeToHashtag` verified; **replaces** old `onEvent_EventTechnicalFailure` | Unit |
| | T-A4b: `closingEvent_logsCodeNotReason` — code present, canary reason absent | Unit |
| | T-A5b: `closedEvent_logsCodeNotReason` — same for Closed | Unit |
| | T-B1: `failureEvent_doesNotLeakAttackerControlledFragment` `@ParameterizedTest` — 7 variants (ASCII/host:port/CRLF/ANSI/null-byte/HTTP2-GOAWAY/JSON-breakout) | Unit |
| | Updated: `onEvent_EventTechnicalClosing` → asserts `code=1000` not old `toString()` | Unit |
| | Updated: `onEvent_EventTechnicalClosed` → same | Unit |
| | Deleted: `onEvent_EventTechnicalFailure` (was asserting buggy `"The error is: Error Message"` format) | — |

**Final counts**: 2032 unit + integration = 2032 total, 0 failures. Jacoco thresholds met.

## Security Requirements Verified

| SR | Status |
|----|--------|
| SR-TD2-01: No peer-controlled byte in any log line | VERIFIED — T-B1 all 7 variants |
| SR-TD2-02: Failure at INFO, `exception=<SimpleName>` | VERIFIED — T-A1, T-A3 |
| SR-TD2-03: Legacy `"The error is:"` format removed | VERIFIED — T-A3 + grep gate |
| SR-TD2-04: `getArgumentArray()` + `getThrowableProxy()` checked | VERIFIED — T-A4, T-B1 |
| SR-TD2-05: TD1-INV-1 confirmed (no production gate) | VERIFIED — production fix unconditional |
| SR-TD2-06: Mode-discipline neutrality | VERIFIED — inspection, no mode branch added |
| SR-TD2-07: F-6 + TD-1 canaries not regressed | VERIFIED — full suite 2032/0 |
| SR-TD2-08: `getSimpleName()` from bounded allowlist | VERIFIED — T-A2, T-B1 |
| SR-TD2-09: `getThrowableProxy() == null` strict | VERIFIED — T-A4, T-B1 |
| SR-TD2-10: CRLF injection contained | VERIFIED — T-B1[CRLF] |
| SR-TD2-11: ANSI escape injection contained | VERIFIED — T-B1[ANSI] |
| SR-TD2-12: MDC clean + AUDIT zero events | VERIFIED — T-A5, T-B1 |
| SR-TD2-13: Restart calls still fire | VERIFIED — T-A6 |
| SR-TD2-14: Closing/Closed log code only; reason absent | VERIFIED — T-A4b, T-A5b |
| SR-TD2-15: Sibling TD-1/F-6 canaries not regressed | VERIFIED — full suite |

## Grep Gates

```
git grep "failure.getError().getMessage()" src/main/  →  0 hits
git grep "The error is:" src/main/                    →  0 hits
```

## Deviations from Phase 1 Plan

One deviation: two pre-existing tests (`onEvent_EventTechnicalClosing`, `onEvent_EventTechnicalClosed`) also became RED after the production fix because they asserted the old `toString()` format. Updated to assert the new `code=1000` format. This is the expected consequence of bundling Closing/Closed into TD-2 scope (ADR-TD2-03).

## User Approval

Date: 2026-04-29
Approval message (verbatim): "approve"

## References

- [TD-2 TechnicalEvent.Failure/Closing/Closed Log Scrubbing — Planning](2026-04-29-planning-td2-technical-failure.md)
- [F-6 D-13/SR-8 Raw Logging Cleanup — Acceptance](2026-04-28-acceptance-f6-log-scrubbing.md) (F-6-FU-2 origin)
- [TD-1 JsonProcessingException Logging Fix — Implementation](2026-04-28-implementation-td1-json-parse-exception.md) (sibling reference)
- [CWE-117: Improper Output Neutralization for Logs](https://cwe.mitre.org/data/definitions/117.html)
- [OWASP Top 10 (2021) — A09: Security Logging and Monitoring Failures](https://owasp.org/Top10/A09_2021-Security_Logging_and_Monitoring_Failures/)
- [RFC 6455 — The WebSocket Protocol](https://www.rfc-editor.org/rfc/rfc6455) §5.5.1 (WebSocket Close frame reason field)
- [TD-2 TechnicalEvent.Failure/Closing/Closed Log Scrubbing — Acceptance](2026-04-29-acceptance-td2-technical-failure.md)
