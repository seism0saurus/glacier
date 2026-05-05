# Decision Record: TD-3 normaliseEditedAt Log Hygiene — Planning

Date: 2026-04-30
Phase: Planning (Phase 1)
Agents: ddd-tdd-architect (Round 1 + Round 2), secure-feature-planner (Round 1)
Status: Accepted

## Summary

`StompCallback.normaliseEditedAt(String raw)` logs the peer-controlled `editedAt` string verbatim when ISO-8601 parsing fails (`StompCallback.java:597`). This violates CWE-117 and the project's D-13/SR-8 structured-logging discipline. The fix replaces the raw value with two `int`-typed safe representations — `raw.length()` and `ex.getErrorIndex()` — which structurally eliminate the CWE-117 vector while preserving diagnostic utility.

## Key Decisions

### ADR-TD3-1: Use `raw.length()` and `ex.getErrorIndex()` as safe representations
**Decision**: Replace the `raw` format argument with `raw.length()` (int) and `ex.getErrorIndex()` (int). Final format string: `"Could not parse editedAt — len={} errorIndex={} — dropping update event (D-07)"`.
**Rationale**: Both values are `int` — they structurally cannot carry CRLF, ANSI escapes, or oversized payloads. `len` distinguishes truncated ISO-8601 (small, e.g. 8) from injection/DoS attempts (large, e.g. 5000). `errorIndex` (character offset where parsing failed) adds diagnostic parity with F-6/TD-1/TD-2 precedent. `raw` is non-null at the call site (null handled by early return at line 591), so `raw.length()` is NPE-safe.
**Alternatives considered**: `LogScrubber.hash8(raw)` (rejected — no correlation value for editedAt); exception class only (rejected — always `DateTimeParseException`, zero info); `LogScrubber.editedAtLen()` helper (rejected — over-engineering for one site).
**Source**: ddd-tdd-architect Round 1; amended by secure-feature-planner Round 1 to add `errorIndex`; accepted by architect Round 2.

### ADR-TD3-2: Preserve log-message key fragment "Could not parse editedAt"
**Decision**: The substring `Could not parse editedAt` is preserved verbatim. Existing assertions at `StompCallbackTest.java:1928` and `:1973` continue to fire without modification.
**Rationale**: TD-1/TD-2 precedent — security fixes must not change SIEM key fragments. Future structured-key rename (e.g. `stream.editedAt.parse_failed`) tracked as a separate follow-up.
**Source**: ddd-tdd-architect Round 1; accepted by secure-feature-planner.

### ADR-TD3-3: No LogScrubber extension — inline `raw.length()` is idiomatic
**Decision**: No new helper added to `LogScrubber.java`. The `raw.length()` call is inline at the single call site.
**Rationale**: Single call site; pattern already established by `hashtagLen()` and `safeEventName()`. Extract if a second call site appears.
**Source**: ddd-tdd-architect Round 1; accepted by secure-feature-planner.

## Security Requirements

| SR | Requirement |
|----|-------------|
| SR-TD3-01 | No raw peer bytes from `editedAt` reach the log encoder |
| SR-TD3-02 | `len={}` is the only derivative of `raw` logged (its `int` length) |
| SR-TD3-03 | `int` argument structurally eliminates CRLF / ANSI escape injection |
| SR-TD3-04 | Key fragment `"Could not parse editedAt"` preserved in format string |
| SR-TD3-05 | All 4 pre-existing `normaliseEditedAt`/editedAt tests continue to pass |
| SR-TD3-06 | No new dependency, endpoint, RBAC, or network exposure |
| SR-TD3-07 | `raw.length()` is NPE-safe (null handled by early return at line 591) |
| SR-TD3-08 | `len=N` distinguishes truncated ISO-8601 (small) from injection attempt (large) |
| SR-TD3-09 | Fix covers both callers (line 323 GenericMessage, line 502 StreamEvent) via the shared helper |
| SR-TD3-10 | No other raw-value `LOGGER` calls remain in `StompCallback` (structural regression gate) |
| SR-TD3-11 | `errorIndex={}` from `ex.getErrorIndex()` logged alongside `len={}` |
| SR-TD3-12 | Happy-path output matches `^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d+)?Z$` |
| SR-TD3-13 | 8-row `@ParameterizedTest` covering: normal malformed, CRLF, 5 KB, BOM (U+FEFF), RTL (U+202E), LSEP (U+2028), PSEP (U+2029), 10 KB |
| SR-TD3-14 | Assertions on both `getFormattedMessage()` and `getArgumentArray()` per canary-harness-limits |
| SR-TD3-15 | Residual: Jackson heap-materialisation of oversized `editedAt` is out of scope (separate finding) |

## Test Plan

### Lane A — `tdd-ddd-implementer`

**`StompCallback.java`** edit (Step 2 — after tests written):
- Line 597: `LOGGER.warn("Could not parse editedAt value '{}' — dropping update event (D-07)", raw)` →
  `LOGGER.warn("Could not parse editedAt — len={} errorIndex={} — dropping update event (D-07)", raw.length(), ex.getErrorIndex())`
- Javadoc update on `normaliseEditedAt` (lines 578–589): add D-13/SR-8/CWE-117 sentence

**`StompCallbackTest.java`** additions (Step 1 — TDD red first):
1. `normaliseEditedAt_malformed_logsLengthNotRawValue` — `raw="not-a-date"` (len=10); asserts `len=10`, `errorIndex=`, no `not-a-date`, no `value '`; assertions on both `getFormattedMessage()` and `getArgumentArray()` (SR-TD3-14)
2. `normaliseEditedAt_malformed_crlfPayload_doesNotReachLogEncoder` — `raw="x\r\nWARN  INJECTED FAKE LINE"` (len=28); asserts no `INJECTED`, asserts `len=28`; asserts no raw CR/LF in message portion
3. `normaliseEditedAt_malformed_oversizeInput_lengthDistinguishable` — `raw="A".repeat(5000)`; asserts `len=5000`, no 100-char A-run
4. `@ParameterizedTest` (8 rows): normal/CRLF/5000-char/BOM/RTL/LSEP/PSEP/10KB — all assert no raw value, all assert `len=` field (SR-TD3-13)
5. `normaliseEditedAt_utcZForm_matchesExpectedPattern` — regex pin `^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d+)?Z$` on happy path (SR-TD3-12)
6. Tighten `onEvent_EventGenericMessage_MalformedEditedAt_dropsEvent` (Step 3): add `noneSatisfy(contains("NOT_A_DATE"))` and `noneSatisfy(contains("value '"))` assertions (SR-TD3-01)

### Lane B — `secure-tdd-implementer`

- Verify SR-TD3-10: confirm grep of all LOGGER calls in `StompCallback` leaves no raw-value argument (structural regression gate — assert via a canary that fails if any `LOGGER.*` line contains a raw unscubbed argument after the fix)
- Verify `getArgumentArray()` assertions are present and probe the correct event (SR-TD3-14)
- Red-anchor verification: run tests before the production fix to confirm N1/N2/N3 fail

## Phase 2 Lane Partition

| Lane | Owner | Files |
|------|-------|-------|
| Lane A | `tdd-ddd-implementer` | `StompCallback.java` (fix + Javadoc), `StompCallbackTest.java` (new tests + tightened existing) |
| Lane B | `secure-tdd-implementer` | `StompCallbackTest.java` (SR-TD3-10 structural gate, `getArgumentArray()` verification, red-anchor check) |

## Resolved Conflicts

None — full alignment between architect and security planner in Round 2. ADR-TD3-1 amended (not disputed) to add `errorIndex={}`.

## User Approval

Date: 2026-04-30
Approval message (verbatim): "approve"

## Open Risks

- SR-TD3-15: A hostile peer can send a multi-MB `editedAt` string; Jackson materialises it as a Java String before `normaliseEditedAt` is called. `Instant.parse` fails fast but heap is allocated. Out of scope for TD-3; tracked as a separate finding.
- Future structured-key rename (`stream.editedAt.parse_failed`) deferred as a separate cleanup, not blocked on TD-3.

## References

- [CWE-117: Improper Output Neutralization for Logs](https://cwe.mitre.org/data/definitions/117.html)
- [OWASP Top 10 (2021) — A09: Security Logging and Monitoring Failures](https://owasp.org/Top10/A09_2021-Security_Logging_and_Monitoring_Failures/)
- glacier-structured-logging-logback (D-13/SR-8 rules)
- [TD-1 JsonProcessingException Logging Fix — Planning](2026-04-28-planning-td1-json-parse-exception.md) (log-key stability precedent)
- [TD-2 TechnicalEvent.Failure/Closing/Closed Log Scrubbing — Planning](2026-04-29-planning-td2-technical-failure.md) (Unicode-controls canary precedent)
- [TD-3 normaliseEditedAt Log Hygiene — Implementation](2026-04-30-implementation-td3-normaliseeditedat.md)
- [TD-3 normaliseEditedAt Log Hygiene — Acceptance](2026-04-30-acceptance-td3-normaliseeditedat.md)
