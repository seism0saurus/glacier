# Decision Record: TD-1 JsonProcessingException Logging Fix — Acceptance

Date: 2026-04-28
Phase: Acceptance (Phase 3)
Agents: security-auditor (Round 1 + Round 2), acceptance-test-auditor (Round 1)
Status: **PASSED**

## Summary

Phase 3 acceptance audit validated all 12 security requirements for TD-1. The one-line production fix at `StompCallback.java:163` removes the `Throwable` argument from `LOGGER.error`, preventing Jackson's `JsonProcessingException.getMessage()` (which embeds a verbatim wire-input fragment) from reaching JSON logs. 15/15 acceptance criteria met. No fix cycles. 107 unit + 1 IT, 0 failures.

## Acceptance Disposition: PASSED

All requirements from [Phase 1 planning](2026-04-28-planning-td1-json-parse-exception.md) are met. No Critical or High findings remain open.

## Security Requirements — Final Verification

| SR | Requirement | Status |
|----|-------------|--------|
| SR-TD1-01 | No wire-payload byte in any log line | VERIFIED — T-B1 all 4 variants |
| SR-TD1-02 | Structured `exception=<SimpleName>` at ERROR | VERIFIED — T-A1, T-A3 |
| SR-TD1-03 | Legacy Throwable-render format gone | VERIFIED — T-A4 was RED before fix |
| SR-TD1-04 | Tests check `getArgumentArray()` + `getThrowableProxy()` | VERIFIED — T-A4, T-B1 |
| SR-TD1-05 | TD1-INV-1 documented | VERIFIED — planning doc |
| SR-TD1-06 | Mode-neutral (no control-flow change) | VERIFIED — inspection |
| SR-TD1-07 | Existing F-6 canaries not regressed | VERIFIED — full suite |
| SR-TD1-08 | Bounded `ACCEPTABLE_PARSE_EXCEPTIONS` allowlist | VERIFIED — T-A2 |
| SR-TD1-09 | `getThrowableProxy() == null` strict | VERIFIED — T-A4:893, T-B1:1033 |
| SR-TD1-10 | CRLF injection contained | VERIFIED — T-B1[CRLF] |
| SR-TD1-11 | ANSI escape injection contained | VERIFIED — T-B1[ANSI] |
| SR-TD1-12 | MDC clean + AUDIT zero events | VERIFIED — T-A5 |

## Carry-Forward Conditions — Final Verification

| Condition | Status |
|-----------|--------|
| C1: No `Throwable` arg in catch-block LOGGER call | VERIFIED — `StompCallback.java:163` |
| C2: Bounded Jackson exception allowlist | VERIFIED — 5-entry `ACCEPTABLE_PARSE_EXCEPTIONS` |
| C3a: `getFormattedMessage()` checked | VERIFIED — T-B1:1014 |
| C3b: `getArgumentArray()` checked | VERIFIED — T-B1:1020–1029 |
| C3c: `getThrowableProxy() == null` strict | VERIFIED — T-A4:893, T-B1:1033 |

## Final Test Counts

| Suite | Count | Failures |
|-------|-------|----------|
| Java unit (Surefire) | 107 | 0 |
| Java integration (Failsafe) | 1 | 0 |
| **Total** | **108** | **0** |

Jacoco: instruction ≥ 45% / branch ≥ 35% — PASS.

## Finding Dispositions

| ID | Description | Severity | Disposition |
|----|-------------|----------|-------------|
| TD-1 | `JsonProcessingException` Throwable arg leaked wire-input fragment | Medium (pre-fix) | FIXED |
| Informational | Re-verify `StompCallback.java:163` after merge into main | Informational | Accepted — merge gate check |

## User Approval

Date: 2026-04-28
Approval message (verbatim): "approve"

## References

- [TD-1 JsonProcessingException Logging Fix — Planning](2026-04-28-planning-td1-json-parse-exception.md)
- [TD-1 JsonProcessingException Logging Fix — Implementation](2026-04-28-implementation-td1-json-parse-exception.md)
- [F-6 D-13/SR-8 Raw Logging Cleanup — Acceptance](2026-04-28-acceptance-f6-log-scrubbing.md) (TD-1 origin)
- [CWE-117: Improper Output Neutralization for Logs](https://cwe.mitre.org/data/definitions/117.html)
- [OWASP Top 10 (2021) — A09: Security Logging and Monitoring Failures](https://owasp.org/Top10/A09_2021-Security_Logging_and_Monitoring_Failures/)
- [NIST SP 800-53 SI-11: Error Handling](https://csrc.nist.gov/projects/cprt/catalog#/cprt/framework/version/SP_800_53_5_1_0/home?element=SI-11)
