# Decision Record: F-6 D-13/SR-8 Raw Logging Cleanup — Acceptance

Date: 2026-04-28
Phase: Acceptance (Phase 3)
Agents: security-auditor (Round 1 + Round 2), acceptance-test-auditor (Round 1)
Status: **PASSED**

## Summary

Phase 3 acceptance audit validated all 10 security requirements for F-6 (D-13/SR-8 raw logging violations in `StompCallback` and `SubscriptionListener`). All carry-forward conditions verified. No Critical/High/Medium findings. No fix cycles required. 165 unit + 1 IT, 0 failures. Pipeline signed off.

## Acceptance Disposition: PASSED

All requirements from [Phase 1 planning](2026-04-28-planning-f6-log-scrubbing.md) are met. No Critical or High findings remain open.

## Security Requirements — Final Verification

| SR | Requirement | Status |
|----|-------------|--------|
| SR-F6-01 | No log line emits raw hashtag string | VERIFIED |
| SR-F6-02 | `event.toString()` never at INFO or above | VERIFIED |
| SR-F6-03 | No raw toot URL at any log level | VERIFIED |
| SR-F6-04 | `ex.getMessage()` on RestTemplate failure not logged | VERIFIED |
| SR-F6-05 | `genericMessageContent.getEvent()` validated via allowlist (CWE-117) | VERIFIED |
| SR-F6-06 | `genericMessageContent.getStream()` not logged — size only | VERIFIED |
| SR-F6-07 | Raw STOMP destination string not logged verbatim | VERIFIED |
| SR-F6-08 | Raw `sessionId`/principal not logged — hashed only | VERIFIED — all 7 sync + 3 timer-lambda lines |
| SR-F6-09 | All replacements use canonical `LogScrubber` helpers | VERIFIED |
| SR-F6-10 | Canary tests cover all 10 fix sites; harness checks both message and argument array | VERIFIED |

## Carry-Forward Conditions — Final Verification

| Condition | Status |
|-----------|--------|
| C1: Every `RestClientException` catch uses `getSimpleName()`, not `getMessage()` | VERIFIED — exactly 2 catch sites, both confirmed |
| C2: `safeEventName` applies allowlist before value reaches any logger | VERIFIED — `KNOWN_STREAM_EVENTS` checked before any verbatim return |
| C3: Canary harness checks both `getFormattedMessage()` and `getArgumentArray()` including AUDIT | VERIFIED — all 4 assertion helpers + AUDIT appender wired |

## No-Production-Behavior-Change Verification

| Property | Status |
|----------|--------|
| STOMP wire contract (`/topic/hashtags/{principal}/{hashtag}/{suffix}`) unchanged (ADR-F6-03) | VERIFIED |
| `isLoadable()` gatekeeper logic unchanged | VERIFIED |
| No new Spring beans | VERIFIED |
| `logback.xml` unchanged | VERIFIED |
| `pom.xml` only adds `test`-scope `jqwik` | VERIFIED |

## Final Test Counts

| Suite | Count | Failures |
|-------|-------|----------|
| Java unit (Surefire) | 165 | 0 |
| Java integration (Failsafe) | 1 | 0 |
| **Total** | **166** | **0** |

Jacoco: instruction ≥ 45% / branch ≥ 35% — PASS.

## Finding Dispositions

| ID | Description | Severity | Disposition |
|----|-------------|----------|-------------|
| TD-1 | `JsonProcessingException` in `sendMessage` — exception stack may contain malformed-input fragment | LOW | Accepted — pre-existing, not F-6 scope; follow-up ticket created |
| TD-2 | `TechnicalEvent.Failure.getError().getMessage()` in `processTechnicalEvent` — may include remote host | LOW–MEDIUM | Accepted — pre-existing, not F-6 scope; follow-up ticket created |
| INFO-1 | Mastodon 4.3 `notifications_merged` not in `KNOWN_STREAM_EVENTS` allowlist | Informational | Accepted — fail-safe `unknown(len=19)` behavior; follow-up ticket to track allowlist drift |
| INFO-2 | `eventType = destination.lastIndexOf('/')` — fragile to future STOMP suffix shape changes | Informational | Accepted — no current attack surface; follow-up refactor ticket created |
| INFO-3 | `hash8` is unsalted SHA-256 prefix — wallId possessor can confirm their hash | Informational | Accepted — consistent with D-13/SR-8 threat model |
| R1 | Fix site 7c (`onDisconnectEvent` no-user warn) has no dedicated canary test | Informational | Accepted — covered transitively by T5; follow-up test ticket created |

## Accepted Residual Risks

| Risk | Rationale |
|------|-----------|
| `JsonProcessingException` fragment (TD-1) | Jackson truncates error context; no full URL or wallId; pre-existing |
| `TechnicalEvent.Failure.getMessage()` (TD-2) | OkHttp error not user-controlled wallId or hashtag; pre-existing |
| No dedicated T5b for site 7c (R1) | Structurally identical to verified 7a; read-verified; T5 covers the event path |
| Allowlist drift on Mastodon bump (INFO-1) | Fail-safe; functional impact only (log noise), no security gap |

## Follow-Up Items Created

| Item | Type | Description |
|------|------|-------------|
| F-6-FU-1 | Security fix | Fix `JsonProcessingException` path in `StompCallback.sendMessage` to log `e.getClass().getSimpleName()` only |
| F-6-FU-2 | Security fix | Fix `TechnicalEvent.Failure.getError().getMessage()` in `processTechnicalEvent` to log `getSimpleName()` only |
| F-6-FU-3 | Test | Add T5b canary for fix site 7c — `SubscriptionListener.onDisconnectEvent` no-user warn |
| F-6-FU-4 | Maintenance | Update `LogScrubber.KNOWN_STREAM_EVENTS` allowlist when Bigbone upgrades to Mastodon 4.3+ (`notifications_merged`) |
| F-6-FU-5 | Refactor | Extract STOMP event-type suffixes as constants in `StompCallback` to replace `lastIndexOf('/')` extraction |

## User Approval

Date: 2026-04-28
Approval message (verbatim): "accept and create follow up items for the open risks"

## References

- [F-6 D-13/SR-8 Raw Logging Cleanup — Planning](2026-04-28-planning-f6-log-scrubbing.md)
- [F-6 D-13/SR-8 Raw Logging Cleanup — Implementation](2026-04-28-implementation-f6-log-scrubbing.md)
- [OWASP Top 10 (2021) — A09: Security Logging and Monitoring Failures](https://owasp.org/Top10/A09_2021-Security_Logging_and_Monitoring_Failures/)
- [CWE-117: Improper Output Neutralization for Logs](https://cwe.mitre.org/data/definitions/117.html)
- [GDPR Recital 30](https://www.privacy-regulation.eu/en/recital-30-GDPR.htm) — session identifiers as personal data
- [NIST SP 800-53 SI-11: Error Handling](https://csrc.nist.gov/projects/cprt/catalog#/cprt/framework/version/SP_800_53_5_1_0/home?element=SI-11)
