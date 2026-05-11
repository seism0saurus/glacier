# Decision Record: TD-4 xFrameOptions Log Hygiene — Acceptance

Date: 2026-04-30
Phase: Acceptance (Phase 3)
Agents: security-auditor (Round 1 + Round 2), acceptance-test-auditor (Round 1)
Status: Accepted — PASSED

## Summary

TD-4 fixes the CWE-117 / D-13 / SR-8 violation in `StompCallback.isLoadable` where the peer-controlled `xFrameOptions` `List<String>` (from a remote Mastodon `HEAD /embed` response) was passed verbatim as a SLF4J `{}` argument. All 12 security requirements are covered by tests. No Critical, High, or Medium findings were raised during acceptance. The implementation is approved for merge.

## Acceptance Disposition: PASSED

## Findings

### All 12 SR-TD4 requirements satisfied

| SR | Status | Key test(s) |
|----|--------|-------------|
| SR-TD4-01 | PASS | `LogScrubberTest` LST-T4-1..4 — format `xfo-values=N xfo-totallen=M` confirmed |
| SR-TD4-02 | PASS | LST-T4-1 (null list), LST-T4-7 (null element in `[null, "DENY"]` → `xfo-values=2 xfo-totallen=4`); Javadoc explicit |
| SR-TD4-03 | PASS | LST-T4-5 (CRLF), LST-T4-6 (ANSI ESC), LST-T4-8 (jqwik `@Property` — no codepoint < 0x20 except space, no U+2028/2029/202E/FEFF) |
| SR-TD4-04 | PASS | `StompCallback.java:413–414` reads `LogScrubber.xfoSummary(xFrameOptions)`; T6a canary confirms `xfo-values=` / `xfo-totallen=` in message, no raw bytes |
| SR-TD4-05 | PASS | T6b-struct (8-row injection fuzz: ASCII canary, CRLF, 5000-char blob, ANSI, NUL, U+2028, U+202E, U+FEFF) — no fragment in `getFormattedMessage()` or `getArgumentArray()` |
| SR-TD4-06 | PASS | T6b positive-shape asserts `xfo-values=2` and `xfo-totallen=` present in formatted message |
| SR-TD4-07 | PASS | T6b-gate Invariant 2 — `\bcsp\b(?!\.)` absent on every `LOGGER.`-bearing line; `csp` confirmed never on a LOGGER line |
| SR-TD4-08 | PASS | T6b: `event.getLevel() == Level.WARN`; AUDIT appender size == 0 |
| SR-TD4-09 | PASS | T6a: `verify(messageCache, never()).recordThenPublish(...)`; 21-row pre-existing parameterised test passes unchanged |
| SR-TD4-10 | PASS | T6b-gate primary regex `\bxFrameOptions\b(?!\.)` + `\bcsp\b(?!\.)` on every LOGGER line; secondary: `"unknown or invalid value: {}"` absent from source |
| SR-TD4-11 | PASS | Diff bounded to 4 files; no new Spring context, DB, Angular, outbound call, SSRF gating, or `isLoadable` signature change |
| SR-TD4-12 | PASS | Four commits; RED→GREEN shape preserved: 8294ad1 (helper+LST GREEN), 4847f91 (canaries RED), 18cce12 (fix GREEN), 7fc7303 (Lane B additive on green baseline) |

### CWE-117 boundary verification

Peer-controlled bytes reaching the log encoder are blocked at three independent layers:

1. **Helper unit tests** (LST-T4-1..7): `xfoSummary` output never contains input bytes; no CR/LF/ESC.
2. **Behavioural canary at the call site** (T6a + T6b-struct 8 adversarial rows): every `ILoggingEvent.getFormattedMessage()` and `getArgumentArray()` element inspected; no adversarial fragment passes through.
3. **Property-based fuzz** (LST-T4-8 jqwik): for arbitrary `List<String>` inputs, output contains no codepoint < 0x20 and no U+2028/2029/202E/FEFF.

### Informational / Low findings (non-blocking, accepted)

| ID | Finding | Severity | Disposition |
|----|---------|----------|-------------|
| SA-TD4-I1 | `int totalLen` in `xfoSummary` theoretically overflows for 2B+ null-free entries | Informational | Accepted — bounded by HTTP header size limits in practice |
| SA-TD4-I2 | Physical-line gate cannot catch a future multi-line `LOGGER.warn(...)` with argument on a continuation line | Informational | Accepted — documented with comment in T6b-gate; all current calls are single-line (verified 2026-04-30) |
| SA-TD4-I3 | Structural gate regex `\b<var>\b(?!\.)` is a regression guard for the specific bare-`List` fix — accessor patterns (`csp.getFirst()`, `xFrameOptions.stream()`) are not covered by the gate | Informational | Accepted — the gate is belt-and-braces for the exact prior regression; T6b-struct 8-row behavioural fuzz carries the broad CWE-117 guarantee independently. Note: `\b<var>\b(?!\.)` is not a general CWE-117 invariant; the behavioural layer is the primary CWE-117 assertion. |
| SA-TD4-I4 | `processTechnicalEvent` default branch still logs `event.toString()` — pre-existing TD-5 candidate | Informational | Deferred — pre-existing, not TD-4 scope; separate backlog item |

### Pre-existing flake (unrelated to TD-4)

`ImageProxyUrlBuilderVerifyFuzzTest` — HMAC mutation at index 167 intermittently passes verification; confirmed pre-existing and unrelated to TD-4 by both auditors. Separate investigation warranted.

## Final Test Results

| Suite | Count | Result |
|-------|-------|--------|
| `StompCallbackTest` | 125 (was 106 pre-TD4, +19) | PASS |
| `LogScrubberTest` | 61 (was 52 pre-TD4, +9) | PASS |
| Backend unit suite (Surefire) | ~893 | PASS |
| Integration suite (Failsafe) | PASS | PASS |
| Jacoco instruction | ≥ 45% threshold | PASS |
| Jacoco branch | ≥ 35% threshold | PASS |

## Resolved Conflicts

None — no `## ⚡ CONFLICT:` markers raised in either round of Phase 3.

## User Approval

Date: 2026-04-30
Approval message (verbatim): "approve"

## Open Risks

- SR-TD4-15 (equivalent to TD-3's SR-TD3-15 pattern): none — TD-4's scope is narrower than TD-3; no Jackson heap-materialisation exposure.
- SA-TD4-I1 through SA-TD4-I4: accepted as described in Findings above.
- Pre-existing `processTechnicalEvent` TD-5 candidate: deferred.

## References

- [TD-4 xFrameOptions Log Hygiene — Planning](2026-04-30-planning-td4-xframeoptions.md)
- [TD-4 xFrameOptions Log Hygiene — Implementation](2026-04-30-implementation-td4-xframeoptions.md)
- [TD-3 normaliseEditedAt Log Hygiene — Acceptance](2026-04-30-acceptance-td3-normaliseeditedat.md) (TD-4 originally deferred here)
- [F-6 D-13/SR-8 Raw Logging Cleanup — Acceptance](2026-04-28-acceptance-f6-log-scrubbing.md)
- [CWE-117: Improper Output Neutralization for Logs](https://cwe.mitre.org/data/definitions/117.html)
- [OWASP Top 10 (2021) — A09: Security Logging and Monitoring Failures](https://owasp.org/Top10/A09_2021-Security_Logging_and_Monitoring_Failures/)
- Glacier D-13 / SR-8 structured-logging discipline ([`glacier-structured-logging-logback`](../../.claude/skills/glacier-structured-logging-logback.md) skill)
