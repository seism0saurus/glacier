# Decision Record: F-6-INFO-1 KNOWN_STREAM_EVENTS Update — Acceptance

Date: 2026-04-30
Phase: Acceptance (Phase 3)
Agents: security-auditor (Round 1 + Round 2), acceptance-test-auditor (Round 1)
Status: **PASSED**

## Summary

Phase 3 acceptance audit validated the F-6-INFO-1 change: `"notifications_merged"` added to `LogScrubber.KNOWN_STREAM_EVENTS` and two Javadoc version references updated to `"Mastodon 4.3"`. All five security requirements verified. Zero Critical/High findings. 846 unit + 173 integration = 1019 backend tests, 0 failures; Jacoco thresholds met. No fix cycles.

## Acceptance Disposition: PASSED

All requirements from [Phase 1 planning](2026-04-30-planning-f6info1-known-stream-events.md) are met. No Critical or High findings remain open.

## Security Requirements — Final Verification

| SR | Requirement | Status |
|----|-------------|--------|
| SR-F6INFO1-01 | `safeEventName("notifications_merged")` returns `"notifications_merged"` verbatim | VERIFIED — `safeEventName_notificationsMerged_isAllowlistedForMastodon43` + `safeEventName_knownEvents_returnVerbatim:341` |
| SR-F6INFO1-02 | Bounded fallback for all non-allowlisted inputs unchanged | VERIFIED — jqwik property + 4 existing injection-guard tests |
| SR-F6INFO1-03 | jqwik CRLF property still passes; bound `Math.max(s.length(), 25)` holds | VERIFIED — `notifications_merged` (20 chars) < max `announcement.reaction` (21 chars); property ran 1000 tries |
| SR-F6INFO1-04 | Case/padding/control-char variants hit bounded fallback (exact-equality only) | VERIFIED — `safeEventName_notificationsMerged_caseAndPaddingVariants_returnFallback` (4 variants: uppercase, trailing space, CRLF, RTL U+202E) |
| SR-F6INFO1-05 | `KNOWN_STREAM_EVENTS` Javadoc references `"Mastodon 4.3"` | VERIFIED — `LogScrubber.java:49` and `:186` both confirmed |

## Final Test Counts

| Suite | Count | Failures |
|-------|-------|----------|
| Java unit (Surefire) | 846 | 0 |
| Java integration (Failsafe) | 173 | 0 |
| Frontend (Karma/Jasmine) | 522 | 0 |
| **Java total** | **1019** | **0** |

Jacoco: instruction ≥ 45% / branch ≥ 35% — PASS.

## Finding Dispositions

| # | Description | Severity | Disposition |
|---|-------------|----------|-------------|
| 7 | `LogScrubberTest.java:446–448` Javadoc says `max(s.length(), 20)` but assertion uses `25` | Informational | Pre-existing doc/code drift — deferred; behavior correct |
| 8 | Literal U+202E RIGHT-TO-LEFT OVERRIDE character in test source (`LogScrubberTest.java:380`) | Informational | Optional hardening — replace with `‮` escape in a follow-up cosmetic PR |

## Regression Check

- `StompCallback.java:241` (sole production caller of `safeEventName`) — unchanged
- `RawWallIdLogHygieneTest` T3a (`"notification"` verbatim pass-through) and T3b (CRLF-injected fallback) — not regressed
- All prior F-6/TD-1/TD-2/F-6-FU-3 canaries — green in full 1019-test suite

## User Approval

Date: 2026-04-30
Approval message (verbatim): "approve"

## References

- [F-6-INFO-1 KNOWN_STREAM_EVENTS Update — Planning](2026-04-30-planning-f6info1-known-stream-events.md)
- [F-6-INFO-1 KNOWN_STREAM_EVENTS Update — Implementation](2026-04-30-implementation-f6info1-known-stream-events.md)
- [F-6 D-13/SR-8 Raw Logging Cleanup — Acceptance](2026-04-28-acceptance-f6-log-scrubbing.md) (INFO-1 origin)
- [CWE-117: Improper Output Neutralization for Logs](https://cwe.mitre.org/data/definitions/117.html)
- [OWASP Top 10 (2021) — A09: Security Logging and Monitoring Failures](https://owasp.org/Top10/A09_2021-Security_Logging_and_Monitoring_Failures/)
- ADR-F6-05: `KNOWN_STREAM_EVENTS` allowlist design (original F-6 planning doc)
