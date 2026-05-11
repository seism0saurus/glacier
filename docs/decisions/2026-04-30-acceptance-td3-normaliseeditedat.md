# Decision Record: TD-3 normaliseEditedAt Log Hygiene — Acceptance

Date: 2026-04-30
Phase: Acceptance (Phase 3)
Agents: security-auditor (Round 1 + Round 2), acceptance-test-auditor (Round 1)
Status: Accepted — PASSED

## Summary

TD-3 fixes the CWE-117 / D-13 / SR-8 violation in `StompCallback.normaliseEditedAt` where the peer-controlled `editedAt` string was logged verbatim on parse failure. All 15 security requirements are covered by tests. No fix requests were issued during acceptance. The implementation is approved for merge.

## Acceptance Disposition: PASSED

## Findings

### All 15 SR-TD3 requirements satisfied

| SR | Status | Key test |
|----|--------|----------|
| SR-TD3-01 | PASS | `normaliseEditedAt_malformed_logsLengthNotRawValue` |
| SR-TD3-02 | PASS | `normaliseEditedAt_malformed_logsLengthNotRawValue` — `len=10` asserted |
| SR-TD3-03 | PASS | `normaliseEditedAt_malformed_crlfPayload_doesNotReachLogEncoder` |
| SR-TD3-04 | PASS | Key fragment present in N1, existing tests at :1928/:1973 |
| SR-TD3-05 | PASS | All 4 pre-existing tests pass unchanged |
| SR-TD3-07 | PASS | Null guard at line 596 runs before `raw.length()` |
| SR-TD3-08 | PASS | N3 (5000-char) + parameterized 10 KB row |
| SR-TD3-09 | PASS | Both callers (lines 323/502) route through same helper |
| SR-TD3-10 | PASS | Structural gate `normaliseEditedAt_noRawValueInAnyLoggerCall_structuralRegressionGate` (red-before/green-after confirmed) |
| SR-TD3-11 | PASS | `errorIndex=` asserted in N1 |
| SR-TD3-12 | PASS | Regex pin `normaliseEditedAt_utcZForm_matchesExpectedPattern` |
| SR-TD3-13 | PASS | 8-row `@ParameterizedTest` covering BOM/RTL/LSEP/PSEP/CRLF/5KB/10KB |
| SR-TD3-14 | PASS | `event.getArgumentArray()` assertions in N1 and parameterized test |
| SR-TD3-15 | ACCEPTED RISK | Jackson heap-materialisation out of scope |

### Finding: TD-4 pre-existing — `xFrameOptions` raw List<String> at StompCallback:411 (Deferred)

**Severity**: Low
**Standard**: [CWE-117: Improper Output Neutralization for Logs](https://cwe.mitre.org/data/definitions/117.html) / D-13 / SR-8
**Finding**: `LOGGER.warn(... "{}", xFrameOptions)` passes a peer-controlled `List<String>` from a remote Mastodon `HEAD` response to the log encoder. Same class as TD-1/TD-2/TD-3 but at a different site.
**Pre-existing**: Confirmed via `git log -L 411,411` — introduced in commit `6c92974` (substantially pre-dating TD-3); TD-3 diff does not touch line 411.
**Disposition**: Deferred to TD-4 backlog item. Not blocking TD-3.

## Final Test Results

| Suite | Count | Result |
|-------|-------|--------|
| `StompCallbackTest` | 106 (was 93 pre-TD3, +13) | PASS |
| Backend unit suite | 866 | PASS |
| Integration suite | 173 | PASS |
| `./mvnw verify -DskipIntegrationTests` | — | BUILD SUCCESS |

## User Approval

Date: 2026-04-30
Approval message (verbatim): "approve"

## Open Risks

- SR-TD3-15: Jackson heap-materialisation of oversized `editedAt` — accepted as out of scope.
- TD-4: `xFrameOptions` raw List<String> at `StompCallback:411` — deferred, separate backlog item.

## References

- [TD-3 normaliseEditedAt Log Hygiene — Planning](2026-04-30-planning-td3-normaliseeditedat.md)
- [TD-3 normaliseEditedAt Log Hygiene — Implementation](2026-04-30-implementation-td3-normaliseeditedat.md)
- [CWE-117: Improper Output Neutralization for Logs](https://cwe.mitre.org/data/definitions/117.html)
- [OWASP Top 10 (2021) — A09: Security Logging and Monitoring Failures](https://owasp.org/Top10/A09_2021-Security_Logging_and_Monitoring_Failures/)
- glacier-structured-logging-logback (D-13/SR-8)
