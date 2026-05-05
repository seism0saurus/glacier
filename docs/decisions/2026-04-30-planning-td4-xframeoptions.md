# Decision Record: TD-4 xFrameOptions Log Hygiene — Planning

Date: 2026-04-30
Phase: Planning (Phase 1)
Agents: ddd-tdd-architect (Round 1 + Round 2), secure-feature-planner (Round 1 + Round 2)
Status: Accepted

## Summary

TD-4 closes a CWE-117 / D-13 / SR-8 violation in `StompCallback.isLoadable` where `xFrameOptions` — a peer-controlled `List<String>` from a remote Mastodon `HEAD /embed` response — is logged verbatim at WARN level. The fix introduces `LogScrubber.xfoSummary(List<String>)` returning the bounded string `"xfo-values=N xfo-totallen=M"`, replaces the bare list argument at line 411, and guards against regression with a structural source-file gate covering both `xFrameOptions` and the sibling `csp` variable.

## Violation Site

`src/main/java/de/seism0saurus/glacier/mastodon/StompCallback.java`, line **411**:
```java
LOGGER.warn("FRAME-ANCESTORS header does not exists. X-Frame-Options has unknown or invalid value: {}", xFrameOptions);
```
`xFrameOptions = httpHeaders.get("X-Frame-Options")` — populated directly from the HTTP response headers of an untrusted third-party Mastodon instance. SLF4J resolves `{}` to `list.toString()`, reaching the JSON encoder with raw peer-controlled bytes (CRLF, ANSI, unicode controls). This is the sole offending site; lines 395/397/401/405/408 are static strings with no argument array.

## Key Decisions

### ADR-TD4-01: Add dedicated `LogScrubber.xfoSummary` helper
**Decision**: New `public static String xfoSummary(final List<String> values)` in `LogScrubber.java` returning:
- `"xfo-values=0 xfo-totallen=0"` for null or empty list
- `"xfo-values=N xfo-totallen=M"` otherwise, where N = slot count (null elements counted), M = sum of `String.length()` for non-null elements
**Rationale**: Matches the TD-1/TD-2/TD-3 pattern of dedicated scrubber helpers. Bounded integer-only output structurally eliminates all peer-controlled bytes. Javadoc must document null-element slot-counting semantics explicitly.
**Alternatives considered**: Inline `size()` (not unit-testable in isolation; violates TD pattern); hash-based summary (no operational value); drop WARN entirely (loses embed-gate diagnostic signal).
**Source**: ddd-tdd-architect Round 1; secure-feature-planner Round 1.

### ADR-TD4-02: Retain WARN level
**Decision**: The log call stays at WARN.
**Rationale**: An unknown XFO value from a remote Mastodon instance is an operational signal explaining why a toot was not embedded — exactly what WARN is for. Not a Glacier security event; AUDIT routing would dilute signal-to-noise.
**Source**: Both agents, confirmed in Round 2.

### ADR-TD4-03: Structural regression gate scope — bare-token regex (primary), format-string fragment (secondary)
**Decision**: T6b-gate scans `StompCallback.java` and, for each `LOGGER.`-bearing line, asserts no bare `xFrameOptions` token and no bare `csp` token (regex `\b<name>\b(?!\.)`). The format-string fragment `"unknown or invalid value: {}"` is a secondary belt-and-braces check only.
**Rationale**: A format-string-fragment check is brittle — a future edit with different wording would silently defeat the gate. The bare-token regex tests the structural invariant (peer-controlled identifier never bare in a logger call), which is stable across message-wording changes.
**Source**: secure-feature-planner Round 1 §1.6 (ADAPT); architect Round 2 ACCEPT.

### ADR-TD4-04: Preventive `csp` sibling invariant
**Decision**: The structural gate (T6b-gate) also forbids bare `csp` on any `LOGGER.`-bearing line in `StompCallback.java`. The `csp` variable (line 360, `List<String> csp = httpHeaders.get("Content-Security-Policy")`) is currently never logged — this gate is forward-looking, preventing a future regression of the same class.
**Rationale**: `csp` is the same shape as `xFrameOptions` (peer-controlled `List<String>`); a one-line refactor could introduce the same CWE-117 violation. No new helper is needed (not currently logged); the structural gate suffices.
**Source**: secure-feature-planner Round 1 §3.5 / SR-TD4-07; architect Round 2 ACCEPT.

## Security Requirements (SR-TD4-01 through SR-TD4-12)

| SR ID | Requirement (summary) | Verifying test |
|---|---|---|
| SR-TD4-01 | `xfoSummary` exists and returns `xfo-values=N xfo-totallen=M` | LST-T4-1..4 |
| SR-TD4-02 | Null list → `xfo-values=0 xfo-totallen=0`; null elements counted/zero-length; Javadoc explicit | LST-T4-1, LST-T4-7 |
| SR-TD4-03 | Output contains no CR/LF/ESC/NUL/U+2028/U+2029/U+202E/U+FEFF | LST-T4-5, LST-T4-6, LST-T4-8 (jqwik) |
| SR-TD4-04 | Line 411 MUST use `LogScrubber.xfoSummary(xFrameOptions)`, not bare list | T6a, T6b-gate |
| SR-TD4-05 | Formatted message AND arg-array: no input bytes, no canary fragment, no control chars | T6a, T6c (8 rows), T6b-struct |
| SR-TD4-06 | WARN still contains `xfo-values=` and `xfo-totallen=` (operational signal preserved) | T6b (positive shape) |
| SR-TD4-07 | `csp` MUST NOT be passed bare to any `LOGGER.*` line (preventive) | T6b-gate |
| SR-TD4-08 | WARN level retained; AUDIT appender receives zero events for this branch | T6b (Level.WARN assert + AUDIT-negative) |
| SR-TD4-09 | `isLoadable` return value unchanged; toot not published; `recordThenPublish` never called | Existing 21-row parameterised test + T6a |
| SR-TD4-10 | Structural gate: for each `LOGGER.`-bearing line, no bare `\bxFrameOptions\b(?!\.)` and no bare `\bcsp\b(?!\.)` (primary); format-string fragment absent (secondary) | T6b-gate |
| SR-TD4-11 | No new outbound call, HEAD timeout, SSRF gating, or `isLoadable` signature change | Code review + MastodonConfigurationIT |
| SR-TD4-12 | Three-commit shape: (i) helper + tests GREEN; (ii) canaries RED; (iii) fix GREEN | git log -p review |

## Test Plan Summary

**Unit tests (Lane A — `LogScrubberTest.java`)**:
- LST-T4-1: `xfoSummary(null)` → `"xfo-values=0 xfo-totallen=0"`
- LST-T4-2: `xfoSummary([])` → `"xfo-values=0 xfo-totallen=0"`
- LST-T4-3: `xfoSummary(["DENY"])` → `"xfo-values=1 xfo-totallen=4"`; no element bytes in output
- LST-T4-4: `xfoSummary(["DENY","SAMEORIGIN"])` → `"xfo-values=2 xfo-totallen=14"`; no element bytes
- LST-T4-5: CRLF payload → no CR/LF in output; positive shape present
- LST-T4-6: ANSI ESC payload → no ESC in output; positive shape present
- LST-T4-7: `[null, "DENY"]` → `"xfo-values=2 xfo-totallen=4"` (slot counted, length 0)
- LST-T4-8 (jqwik): any `List<String>` input → output starts with `"xfo-values="`, no codepoint < 0x20 except space, no U+2028/U+2029/U+202E/U+FEFF

**Behavioural canaries (Lane A — `StompCallbackTest.java`)**:
- T6a: unknown-XFO WARN fires; `xfo-values=` + `xfo-totallen=` in message; no input bytes in message or arg-array; `verify(messageCache, never()).recordThenPublish(...)`
- T6b: `event.getLevel() == Level.WARN`; AUDIT appender empty; positive-shape present
- T6c (8 rows parameterised): CR, LF, ESC, NUL, U+2028, U+2029, U+202E, U+FEFF → each not in formatted message or arg-array elements

**Structural gates (Lane B — `StompCallbackTest.java`)**:
- T6b-gate: for each `LOGGER.`-bearing line (or normalised statement), no match for `\bxFrameOptions\b(?!\.)` and `\bcsp\b(?!\.)` ; secondary: `"unknown or invalid value: {}"` absent
- T6b-struct: adversarial XFO inputs (CRLF, ANSI, NUL, U+2028/U+2029/U+202E/U+FEFF) → no control codepoint in any formatted message or arg-array element

## Phase 2 Lane Partition

Sequential per `feedback_pipeline_sequential_impl.md` (Lane A first, then Lane B, then Round 2 cross-review):

| Lane | Agent | SRs owned | Files |
|---|---|---|---|
| **A** | `tdd-ddd-implementer` | SR-TD4-01, 02, 03, 04, 06, 09, 11, 12 | `LogScrubber.java` (add `xfoSummary`), `LogScrubberTest.java` (LST-T4-1..8), `StompCallback.java` (line 411 fix), `StompCallbackTest.java` (T6a/T6b/T6c) |
| **B** | `secure-tdd-implementer` | SR-TD4-05, 07, 08, 10 | `StompCallbackTest.java` (T6b-struct injection fuzz, T6b-gate structural gate) |

## Files in Phase 2 Scope

- `src/main/java/de/seism0saurus/glacier/util/LogScrubber.java`
- `src/test/java/de/seism0saurus/glacier/util/LogScrubberTest.java`
- `src/main/java/de/seism0saurus/glacier/mastodon/StompCallback.java` (line 411 only)
- `src/test/java/de/seism0saurus/glacier/mastodon/StompCallbackTest.java`

No Spring context, DB, Angular, new dependency, outbound call, SSRF gating, or `isLoadable` signature change.

## Resolved Conflicts

None — both agents converged with no `## ⚡ CONFLICT:` markers across all four rounds.

## Implementation Note for Phase 2 (non-blocking)

The structural gate T6b-gate should ideally scan logical statements (normalised to handle multi-line `LOGGER.*` calls up to the terminating `;`) rather than raw physical lines, to remain robust against line-wrapping. All current logger calls in `StompCallback.java` are single-line, so per-physical-line iteration is also acceptable — but `secure-tdd-implementer` must add a comment in the gate documenting this limitation.

## Open Risks Accepted

- **Forensic loss (T-TD4-09 / Low)**: operators see `xfo-values=1 xfo-totallen=N` rather than exact XFO bytes. Acceptable trade-off; TRACE-level local logging available for deep debug if ever needed.
- **Line-wrap gate evasion (Informational)**: per-physical-line gate cannot catch a future multi-line `LOGGER.warn(...)` with `xFrameOptions` on a continuation line. Mitigated by the Phase 2 implementation note above.

## User Approval

Date: 2026-04-30
Approval message (verbatim): "approve"

## References

- [TD-3 normaliseEditedAt Log Hygiene — Acceptance](2026-04-30-acceptance-td3-normaliseeditedat.md) (TD-4 deferred from §Finding)
- [F-6 D-13/SR-8 Raw Logging Cleanup — Acceptance](2026-04-28-acceptance-f6-log-scrubbing.md)
- [OWASP Top 10 (2025) — A09: Security Logging & Monitoring Failures](https://owasp.org/Top10/A09_2021-Security_Logging_and_Monitoring_Failures/)
- [CWE-117: Improper Output Neutralization for Logs](https://cwe.mitre.org/data/definitions/117.html)
- Glacier D-13 / SR-8 structured-logging discipline
- CLAUDE.md: `glacier-structured-logging-logback` skill
- [TD-4 xFrameOptions Log Hygiene — Implementation](2026-04-30-implementation-td4-xframeoptions.md)
- [TD-4 xFrameOptions Log Hygiene — Acceptance](2026-04-30-acceptance-td4-xframeoptions.md)
