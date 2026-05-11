# Decision Record: TD-5 CWE-117 Cleanup — Planning

Date: 2026-04-30
Phase: Planning (Phase 1)
Agents: ddd-tdd-architect (Round 1 + Round 2), secure-feature-planner (Round 1 + Round 2)
Status: Accepted

## Summary

TD-5 closes three remaining CWE-117 / D-13 / SR-8 violations surfaced as informational findings during the TD-4 acceptance audit. Two violations are in `StompCallback.processTechnicalEvent`: `event.toString()` and `open.toString()` reach `logEvent()` via Java `%s` string formatting. The third is an `int totalLen` overflow risk in `LogScrubber.xfoSummary`. All three are fixed with minimal scope: no new helper, no Spring context change, no Angular change.

## Violation Sites

| ID | File:Line | Violation |
|----|-----------|-----------|
| TD-5-A | `StompCallback.java:555` | `default -> logEvent("got an unknown WebSocketEvent: %s".formatted(event))` — `event.toString()` on a peer-facing `WebSocketEvent` reaches `LOGGER.info` |
| TD-5-B | `StompCallback.java:545` | `logEvent("got an Open event: %s".formatted(open))` — `open.toString()` on `TechnicalEvent.Open` in a `logEvent()` path |
| TD-5-C | `LogScrubber.java:217` | `int totalLen` in `xfoSummary(List<String>)` — theoretically overflows for extremely large peer-supplied lists |

## Key Decisions

### ADR-TD5-A: Replace `event.toString()` with `event.getClass().getSimpleName()`
**Decision**: Replace `"got an unknown WebSocketEvent: %s".formatted(event)` with `"got an unknown WebSocketEvent (class=%s)".formatted(event.getClass().getSimpleName())` at `StompCallback.java:555`.
**Rationale**: `event` is a `WebSocketEvent` from OkHttp/Bigbone. The `%s` format calls `event.toString()`, which can include WebSocket frame payload or headers from a hostile Mastodon instance — a CWE-117 log injection risk. `getClass().getSimpleName()` returns a bounded JVM-internal string. Mirrors the TD-2 fix for `failure.getError().getClass().getSimpleName()` at line 551.
**Alternatives considered**: Drop the message entirely (loses operational diagnostic signal); use a `LogScrubber` helper (no value added — class name is already bounded).
**Source**: ddd-tdd-architect Round 1; secure-feature-planner Round 1 ACCEPT.

### ADR-TD5-B: Replace `open.toString()` with `open.getClass().getSimpleName()`
**Decision**: Replace `"got an Open event: %s".formatted(open)` with `"got an Open event (class=%s)".formatted(open.getClass().getSimpleName())` at `StompCallback.java:545`.
**Rationale**: `TechnicalEvent.Open` is a Kotlin `data object` with zero fields — its current `toString()` returns the constant string `"Open"` and is not peer-controlled today. However, D-13/SR-8 requires eliminating any `toString()` call on an external library type in a `logEvent()` path to prevent regressions if the library adds fields. This is a **preventive / latent-risk structural fix**, not an active exploit remediation. Using `getSimpleName()` is consistent with ADR-TD5-A and TD-2.
**Alternatives considered**: Static literal `"got an Open event"` (loses self-description if Bigbone adds `Open` variants); inline `open.toString()` is safe today but fragile.
**Source**: ddd-tdd-architect Round 1 (proposed static literal); secure-feature-planner Round 1 ADAPT (prefer `getSimpleName()`); architect Round 2 ACCEPT.

### ADR-TD5-C: Widen `int totalLen` to `long totalLen` in `xfoSummary`
**Decision**: Change `int totalLen = 0` → `long totalLen = 0L` in `LogScrubber.xfoSummary(List<String>)`. Return type stays `String` (`"xfo-values=N xfo-totallen=M"`). No caller breakage.
**Rationale**: A peer-supplied `List<String>` with very long elements could cause `int` overflow, producing a negative `xfo-totallen=` value in the log — incorrect but not a security vulnerability in practice (HTTP header size limits bound the realistic input). Widening to `long` eliminates the theoretical edge case cleanly.
**Alternatives considered**: Cap at `Integer.MAX_VALUE` (arbitrary truncation, hides the real count); leave as-is (theoretically incorrect output accepted as risk).
**Source**: secure-feature-planner Round 1; architect Round 1 ACCEPT.

### ADR-TD5-D: T7-gate scans LOGGER-bearing lines (consistent with TD-4)
**Decision**: The structural regression gate `T7-gate` scans physical lines containing `LOGGER.` (not just `logEvent(`-bearing lines). Primary check: no `.formatted(event)` or `.formatted(open)` substring on any LOGGER-bearing line. Secondary: `getSimpleName()` present on lines touching `event` or `open` identifiers.
**Rationale**: `logEvent` is a private wrapper that routes to `LOGGER.info` — scanning LOGGER lines is the stable, consistent choice across TD-4 (T6b-gate) and TD-5 (T7-gate). Scanning `logEvent(` would miss a hypothetical future direct `LOGGER.*` call. Whitespace normalisation applied before scanning.
**Source**: secure-feature-planner Round 1 OPEN QUESTION Q3; architect Round 2 answer: scan LOGGER-bearing lines.

### ADR-TD5-E: Existing test at StompCallbackTest:474 updated in fix commit
**Decision**: The legacy test at `StompCallbackTest.java:474` that exercises the default-branch behaviour is updated in the same commit as the production fix (the GREEN commit), not in a preparatory commit.
**Rationale**: The canary (T7a) is written RED against the pre-fix state. When the fix commit turns T7a GREEN, the legacy test :474 must simultaneously be updated to match the new bounded output. Splitting this across commits would leave the test suite inconsistent between the RED and GREEN states.
**Source**: secure-feature-planner Round 1 OPEN QUESTION Q2; architect Round 2 answer.

## Security Requirements (SR-TD5-01 through SR-TD5-06)

| SR ID | Requirement (summary) | Verifying test |
|---|---|---|
| SR-TD5-01 | Default branch `getFormattedMessage()` + `getArgumentArray()`: no peer-controlled bytes, no canary fragment, no control chars | T7a, T7a-struct |
| SR-TD5-02 | Open branch `getFormattedMessage()` + `getArgumentArray()`: no peer-controlled bytes, no canary fragment, no control chars | T7b, T7b-struct |
| SR-TD5-03 | `xfoSummary` with `long totalLen`: output `"xfo-values=N xfo-totallen=M"` with M ≥ 0 for any non-overflowing input; `long` used in accumulation | LST-T5-1, LST-T5-2 |
| SR-TD5-04 | Structural gate: no `.formatted(event)` or `.formatted(open)` substring on any LOGGER-bearing line in `StompCallback.java` | T7-gate |
| SR-TD5-05 | Default branch still emits class name in formatted message (operational diagnostic signal preserved) | T7a positive-shape |
| SR-TD5-06 | Open branch still emits recognisable message in formatted message (operational signal preserved) | T7b positive-shape |

## Test Plan Summary

**Unit tests (Lane A — `LogScrubberTest.java`)**:
- LST-T5-1: `xfoSummary` with a `Collections.nCopies(2049, "x".repeat(1_048_576))` flyweight list (total > 2 GiB) — asserts no overflow (M > 0, format intact)
- LST-T5-2 (jqwik): any `List<String>` input → output starts with `"xfo-values="`, no codepoint < 0x20 except space, no U+2028/U+2029/U+202E/U+FEFF

**Behavioural canaries (Lane A — `StompCallbackTest.java`)**:
- T7a: default-branch WARN fires with `__CANARY_TD5_EVENT__` input; `getSimpleName()` value present in message; canary NOT in formatted message or arg-array
- T7b: Open-branch INFO fires; `getSimpleName()` value present; no raw `open.toString()` bytes in message or arg-array
- Legacy test at :474 updated to assert `"class="` present in default-branch message (not raw `toString()` value)

**Structural gates (Lane B — `StompCallbackTest.java`)**:
- T7a-struct: adversarial inputs to default-branch path (CRLF, ANSI, NUL, U+2028, U+202E, U+FEFF) → no control codepoint in formatted message or arg-array
- T7b-struct: same for Open-branch path
- T7-gate: for each LOGGER-bearing physical line (whitespace-normalised), no `.formatted(event)` or `.formatted(open)` substring; secondary: lines containing `event` or `open` identifiers include `getSimpleName()`

## Phase 2 Lane Partition

Sequential per pipeline feedback rule (Lane A first, then Lane B, then Round 2 cross-review):

| Lane | Agent | SRs owned | Files |
|---|---|---|---|
| **A** | `tdd-ddd-implementer` | SR-TD5-01, 02, 03, 05, 06 | `StompCallback.java` (lines 545, 555), `LogScrubber.java` (int→long + Javadoc), `StompCallbackTest.java` (T7a/T7b canaries + legacy :474 update), `LogScrubberTest.java` (LST-T5-1, LST-T5-2) |
| **B** | `secure-tdd-implementer` | SR-TD5-04 | `StompCallbackTest.java` (T7a-struct, T7b-struct injection fuzz, T7-gate structural gate) |

## Files in Phase 2 Scope

- `src/main/java/de/seism0saurus/glacier/mastodon/StompCallback.java`
- `src/main/java/de/seism0saurus/glacier/util/LogScrubber.java`
- `src/test/java/de/seism0saurus/glacier/mastodon/StompCallbackTest.java`
- `src/test/java/de/seism0saurus/glacier/util/LogScrubberTest.java`

No Spring context, DB, Angular, new dependency, outbound call, or `isLoadable` signature change.

## Resolved Conflicts

None — both agents converged with no `## ⚡ CONFLICT:` markers across all four rounds.

## Open Risks Accepted

- **Bigbone snapshot dependency**: `TechnicalEvent.Open` is a `data object` in a `2.0.0-SNAPSHOT` library. A future snapshot could add fields and change `toString()`. The structural T7-gate enforces the fix going forward; pinning to a stable release deferred as a separate follow-up.
- **LST-T5-1 OOM risk**: The overflow canary uses `Collections.nCopies(2049, "x".repeat(1_048_576))` — a flyweight list whose total would be > 2 GiB. Surefire forks a JVM but does not materialise the strings; safe within normal heap limits.

## User Approval

Date: 2026-04-30
Approval message (verbatim): "approve"

## References

- [TD-4 xFrameOptions Log Hygiene — Acceptance](2026-04-30-acceptance-td4-xframeoptions.md) (source of informational findings SA-TD4-I1, SA-TD4-I3, SA-TD4-I4)
- [TD-4 xFrameOptions Log Hygiene — Planning](2026-04-30-planning-td4-xframeoptions.md) (gate design pattern)
- [CWE-117: Improper Output Neutralization for Logs](https://cwe.mitre.org/data/definitions/117.html)
- [OWASP Top 10 (2021) — A09: Security Logging and Monitoring Failures](https://owasp.org/Top10/A09_2021-Security_Logging_and_Monitoring_Failures/)
- Glacier D-13 / SR-8 structured-logging discipline ([`glacier-structured-logging-logback`](../../.claude/skills/glacier-structured-logging-logback.md) skill)
