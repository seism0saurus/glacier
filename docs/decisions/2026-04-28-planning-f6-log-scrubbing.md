# Decision Record: F-6 D-13/SR-8 Raw Logging Cleanup — Planning

Date: 2026-04-28
Phase: Planning (Phase 1)
Agents: ddd-tdd-architect (Round 1 + Round 2), secure-feature-planner (Round 1 + Round 2)
Status: Accepted

## Summary

Eleven log statements in `StompCallback` (6 lines) and `SubscriptionListener` (4 lines) emit raw sensitive
values (toot URLs, full WebSocket event objects, raw hashtags, raw STOMP destination strings, raw sessionIds)
in violation of Glacier's D-13/SR-8 structured-logging discipline. This pipeline fixes all violations
with no production behaviour change and adds a new `LogScrubber.safeEventName()` CWE-117 guard.

## Violations Addressed

| # | File:Line | Violation | Fix |
|---|---|---|---|
| 1 | `StompCallback:168` | Raw `hashtag` in constructor log | `LogScrubber.hashtagLen(hashtag)` |
| 2 | `StompCallback:194` | `event.toString()` at INFO — uncontrolled dump | Demote to DEBUG, emit `event-class={}` only |
| 3 | `StompCallback:235` | `genericMessageContent` full dump (raw URL + payload) | `streams-size={} event={}` using `safeEventName(getEvent())` |
| 4 | `StompCallback:282-283` | `payload.getUrl()` + `ex.getMessage()` (embeds full URL) | `urlHostHash(url)` + `ex.getClass().getSimpleName()` |
| 5 | `StompCallback:310` | Raw STOMP destination `/topic/hashtags/{principal}/{hashtag}/…` | Structured triple: `principal-hash + hashtag-len + event-type` |
| 6 | `StompCallback:425-426` | `status.getUrl()` + `ex.getMessage()` | Same as #4 |
| 7a | `SubscriptionListener:148` | Raw `sessionId` on connect-no-user | `hash8(sessionId)` → `session-hash` |
| 7b | `SubscriptionListener:152` | Raw `sessionId` + hashed principal | `hash8(sessionId)` |
| 7c | `SubscriptionListener:174` | Raw `sessionId` on disconnect-no-user | `hash8(sessionId)` → `session-hash` |
| 7d | `SubscriptionListener:178` | Raw `sessionId` + hashed principal | `hash8(sessionId)` |

## Key Decisions

### ADR-F6-01: `sessionId` is a forbidden raw log surface (D-13/SR-8)
**Decision**: WebSocket `simpSessionId` must be scrubbed via `LogScrubber.hash8()` before logging. Field name: `session-hash`.
**Rationale**: The `glacier-structured-logging-logback` skill explicitly lists `sessionId` as a forbidden MDC field. The `simpSessionId` is a UUID-format identifier that, combined with timestamps and IP, supports session tracking — qualifies as a personal-data correlator under GDPR Recital 30.
**Alternatives considered**: Leave raw (violates skill); drop entirely (loses reconnect-storm diagnostics).
**Source**: ddd-tdd-architect Round 1; secure-feature-planner Round 1.

### ADR-F6-02: `event.toString()` demoted from INFO to DEBUG with type-only rendering
**Decision**: `LOGGER.info(event.toString())` at `StompCallback:194` is replaced with `LOGGER.debug("stream.event type={}", event.getClass().getSimpleName())`.
**Rationale**: `event.toString()` is uncontrolled output that can include raw URLs, hashtags, account handles, and raw HTML toot content. The per-branch `logEvent("got a X event")` INFO lines already provide the type; the raw dump is only useful in active debugging sessions.
**Alternatives considered**: Per-subtype scrubbing renderer — rejected as fragile across Bigbone version bumps.
**Source**: ddd-tdd-architect Round 1.

### ADR-F6-03: STOMP destination string unchanged; only log rendering scrubbed
**Decision**: The `/topic/hashtags/{principal}/{hashtag}/{suffix}` string is still built and used as the STOMP wire contract. Only its appearance in `LOGGER.info("Sending message to {}", destination)` is replaced with a structured triple.
**Rationale**: The destination is a frontend-protocol contract (per CLAUDE.md). Changing it would require a coordinated frontend change — out of scope for F-6.
**Source**: ddd-tdd-architect Round 1.

### ADR-F6-04: `RestClientException.getMessage()` forbidden for outbound HTTP exceptions
**Decision**: When catching a `RestClientException` (or any subclass) from `RestTemplate.headForHeaders`, log `ex.getClass().getSimpleName()` only — never `ex.getMessage()`.
**Rationale**: Spring/JDK HTTP exception messages embed the full request URL verbatim (`I/O error on HEAD request for "https://host.example/path/embed": Connection refused`), re-leaking exactly the value that `urlHostHash()` on the same line is closing. Class-name is bounded, attacker-uncontrolled, and carries the diagnostic triage information operators actually need.
**Alternatives considered**: Regex-strip URLs from `ex.getMessage()` (fragile); length scalar (near-zero diagnostic value).
**Source**: secure-feature-planner CONFLICT-1 → ddd-tdd-architect Round 2 accepted.

### ADR-F6-05: Mastodon streaming event-name allowlist in `LogScrubber`
**Decision**: Add `LogScrubber.safeEventName(String event)` with a private `KNOWN_STREAM_EVENTS` `Set` (Mastodon 4.x documented streaming events). Returns value verbatim if allowlisted; returns `unknown(len=N)` otherwise.
**Rationale**: `genericMessageContent.getEvent()` originates from the Mastodon streaming wire — a hostile or compromised instance can inject CRLF / control characters (CWE-117 log injection). Centralising in `LogScrubber` keeps D-13/SR-8 review surface in one file; allowlist-with-bounded-fallback is fail-safe.
**Alternatives considered**: Inline conditional in `StompCallback` (disperses policy); length-cap-and-strip (retains attacker-controlled bytes).
**Source**: secure-feature-planner CONFLICT-2 → ddd-tdd-architect Round 2 accepted with adaptation.

## Security Requirements

| SR | Requirement | Lane | OWASP |
|----|-------------|------|-------|
| SR-F6-01 | No log line emits raw hashtag string | A | A09:2021 |
| SR-F6-02 | `event.toString()` never at INFO or above | A | A09:2021 |
| SR-F6-03 | No raw toot URL (scheme+host+path+query) at any log level | A | A09:2021, A02:2021 |
| SR-F6-04 | `ex.getMessage()` on RestTemplate failure not logged — use `getSimpleName()` | A | A09:2021, NIST SI-11 |
| SR-F6-05 | `genericMessageContent.getEvent()` validated via allowlist before logging | A | A03:2021 CWE-117 |
| SR-F6-06 | `genericMessageContent.getStream()` not logged in full — `size()` only | A | A09:2021 |
| SR-F6-07 | Raw STOMP destination string not logged verbatim | A | A09:2021 |
| SR-F6-08 | Raw `sessionId` not logged at any level — `session-hash` only | A | A02:2021, GDPR |
| SR-F6-09 | All replacements use canonical `LogScrubber` helpers | A | A09:2021 |
| SR-F6-10 | Canary tests cover all 10 fix sites; harness checks both message and argument array | B | TSS-WEB §6.4 |

## Phase 2 Lane Partition

### Lane A — `tdd-ddd-implementer`
**Files**: `LogScrubber.java` (new `safeEventName` + `KNOWN_STREAM_EVENTS`), `StompCallback.java` (fixes #1–6), `SubscriptionListener.java` (fixes #7a–d), `LogScrubberTest.java` (new `safeEventName` unit + jqwik fuzz), `StompCallbackTest.java` (positive-shape assertions).

### Lane B — `secure-tdd-implementer`
**Files**: `RawWallIdLogHygieneTest.java` (extended with canaries for URL/hashtag/sessionId; 5 harness gaps fixed; T1–T8 with T3 split to T3a–d).
**5 harness gaps to fix**:
1. Add `assertNoRawUrl` and `assertNoRawHashtag` helpers (UUID-only assertion misses these)
2. Check `getArgumentArray()` in addition to `getFormattedMessage()`
3. Attach AUDIT logger to harness
4. Assert log levels (T2 must confirm demotion to DEBUG)
5. Split T3 into T3a–d (known event, unknown/injected event, payload, stream)

**Dependency**: Lane A merges first; Lane B rebases so T3b can compile against `safeEventName`.

## Phase 2 Carry-Forward Conditions

1. Every `RestClientException` catch must use `ex.getClass().getSimpleName()` — not `getMessage()`
2. `safeEventName` must apply allowlist before the value reaches any logger
3. Canary harness must check both `getFormattedMessage()` and `getArgumentArray()` at all log levels including AUDIT

## Resolved Conflicts

### CONFLICT-1: `ex.getMessage()` retained vs. scrubbed
**ddd-tdd-architect Round 1**: Proposed retaining `ex.getMessage()` for diagnostics.
**secure-feature-planner Round 1**: Spring exception messages embed full URL — retention defeats `urlHostHash` fix.
**Resolution (2026-04-28)**: `ex.getClass().getSimpleName()` only. Architect accepted in Round 2.

### CONFLICT-2: `genericMessageContent.getEvent()` raw vs. allowlisted
**ddd-tdd-architect Round 1**: Proposed logging raw event-name string (short identifier).
**secure-feature-planner Round 1**: CWE-117 log injection risk from wire-controlled data.
**Resolution (2026-04-28)**: `LogScrubber.safeEventName()` with allowlist + bounded fallback. Architect adapted (centralised in `LogScrubber`).

## User Approval

Date: 2026-04-28
Approval message (verbatim): "approve"

## Open Risks

- `safeEventName` allowlist drawn from Mastodon 4.x docs; new event types fall to `unknown(len=N)` until allowlist is updated (fail-safe)
- Demoting `event.toString()` from INFO to DEBUG means operators need DEBUG enabled to see raw event shapes during live debugging

## References

- [Pentest Findings — Acceptance](2026-04-28-acceptance-pentest-findings.md) (F-6 deferred there)
- CLAUDE.md: `glacier-structured-logging-logback` skill (authoritative D-13/SR-8 rules)
- [OWASP Top 10 (2021) — A09: Security Logging and Monitoring Failures](https://owasp.org/Top10/A09_2021-Security_Logging_and_Monitoring_Failures/)
- [CWE-117: Improper Output Neutralization for Logs](https://cwe.mitre.org/data/definitions/117.html)
- [GDPR Recital 30](https://www.privacy-regulation.eu/en/recital-30-GDPR.htm) (session identifiers as personal data)
- [F-6 D-13/SR-8 Raw Logging Cleanup — Implementation](2026-04-28-implementation-f6-log-scrubbing.md)
- [F-6 D-13/SR-8 Raw Logging Cleanup — Acceptance](2026-04-28-acceptance-f6-log-scrubbing.md)
