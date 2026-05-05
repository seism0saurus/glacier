# Decision Record: TD-3 normaliseEditedAt Log Hygiene — Implementation

Date: 2026-04-30
Phase: Implementation (Phase 2)
Agents: tdd-ddd-implementer (Lane A), secure-tdd-implementer (Lane B)
Status: Accepted

## Summary

`StompCallback.java:597` was fixed to replace the raw peer-controlled `editedAt` string with `raw.length()` and `ex.getErrorIndex()` (both `int`) in the `LOGGER.warn` format args. 13 new test entries were added to `StompCallbackTest.java`, covering all 15 security requirements.

## Files Modified

### `src/main/java/de/seism0saurus/glacier/mastodon/StompCallback.java`
- **Line 597 (now 602)**: Format string changed from `"Could not parse editedAt value '{}' — dropping update event (D-07)"` with arg `raw` to `"Could not parse editedAt — len={} errorIndex={} — dropping update event (D-07)"` with args `raw.length(), ex.getErrorIndex()`
- **Javadoc** on `normaliseEditedAt`: added D-13/SR-8/CWE-117 paragraph explaining `int`-only derivatives and NPE-safety of `raw.length()`

### `src/test/java/de/seism0saurus/glacier/mastodon/StompCallbackTest.java`
New tests added:
1. **Tightened** `onEvent_EventGenericMessage_MalformedEditedAt_dropsEvent` — added `noneSatisfy(contains("NOT_A_DATE"))` and `noneSatisfy(contains("value '"))` guards
2. `normaliseEditedAt_malformed_logsLengthNotRawValue` — `raw="not-a-date"` (len=10); asserts `len=10`, `errorIndex=`; asserts NOT `"not-a-date"`, NOT `"value '"`; `getArgumentArray()` assertions (SR-TD3-01/02/11/14)
3. `normaliseEditedAt_malformed_crlfPayload_doesNotReachLogEncoder` — `raw="x\r\nWARN  INJECTED FAKE LINE"` (len=27); asserts no `"INJECTED"`, asserts `len=27` (SR-TD3-03)
4. `normaliseEditedAt_malformed_oversizeInput_lengthDistinguishable` — `raw="A".repeat(5000)`; asserts `len=5000`, no 100-char A-run (SR-TD3-08)
5. `normaliseEditedAt_malformed_neverLogsRawValue` — `@ParameterizedTest` 8 rows: plain ASCII/CRLF/5KB/BOM(U+FEFF)/RTL(U+202E)/LSEP(U+2028)/PSEP(U+2029)/10KB; all assert raw not in message, `len=N` present, `getArgumentArray()` contains no raw string (SR-TD3-13/14)
6. `normaliseEditedAt_utcZForm_matchesExpectedPattern` — regex pin `^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d+)?Z$` for whole-second and sub-second forms (SR-TD3-12)
7. `normaliseEditedAt_noRawValueInAnyLoggerCall_structuralRegressionGate` — reads `StompCallback.java` programmatically; asserts `"value '"` absent; asserts no `LOGGER.*` line contains `\braw\b(?!\.)` (bare `raw` not followed by `.`) (SR-TD3-10)

## Security Requirements Coverage

| SR | Status |
|----|--------|
| SR-TD3-01 | PASS — `int` args prevent raw bytes; tested by N1 `getFormattedMessage()` |
| SR-TD3-02 | PASS — only `len={}` derivative of `raw`; confirmed by N1/N4 |
| SR-TD3-03 | PASS — `int` structural elimination; N2 CRLF test |
| SR-TD3-04 | PASS — key fragment preserved; existing tests at :1928/:1973 still fire |
| SR-TD3-05 | PASS — all 4 pre-existing tests pass |
| SR-TD3-06 | PASS — no new dependency/endpoint/RBAC |
| SR-TD3-07 | PASS — null guard at line 591; verified by code review |
| SR-TD3-08 | PASS — N3 (5000-char) and N4 (10240-char) parameterized rows |
| SR-TD3-09 | PASS — both callers (lines 323/502) route through same helper |
| SR-TD3-10 | PASS — structural regression gate test (Lane B) |
| SR-TD3-11 | PASS — `errorIndex={}` in format string; asserted by N1 |
| SR-TD3-12 | PASS — regex-pin test on happy path |
| SR-TD3-13 | PASS — 8-row `@ParameterizedTest` with all Unicode hazard chars |
| SR-TD3-14 | PASS — `getArgumentArray()` in N1 and N4 (parameterized) |
| SR-TD3-15 | ACCEPTED RISK — Jackson heap allocation, out of scope |

## Test Results

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

- SR-TD3-15: Jackson heap-materialisation of oversized `editedAt` — out of scope, tracked as separate finding.

## References

- [TD-3 normaliseEditedAt Log Hygiene — Planning](2026-04-30-planning-td3-normaliseeditedat.md)
- [CWE-117: Improper Output Neutralization for Logs](https://cwe.mitre.org/data/definitions/117.html)
- [OWASP Top 10 (2021) — A09: Security Logging and Monitoring Failures](https://owasp.org/Top10/A09_2021-Security_Logging_and_Monitoring_Failures/)
- glacier-structured-logging-logback (D-13/SR-8)
- [TD-3 normaliseEditedAt Log Hygiene — Acceptance](2026-04-30-acceptance-td3-normaliseeditedat.md)
