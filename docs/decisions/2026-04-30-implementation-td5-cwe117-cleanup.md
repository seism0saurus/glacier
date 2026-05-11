# Decision Record: TD-5 CWE-117 Cleanup — Implementation

Date: 2026-04-30
Phase: Implementation (Phase 2)
Agents: tdd-ddd-implementer (Lane A + Round 2 review), secure-tdd-implementer (Lane B + Round 2 review)
Status: Accepted

## Summary

TD-5 implementation is complete. Two `%s".formatted(...)` expressions in `StompCallback.processTechnicalEvent` were replaced with `getClass().getSimpleName()` calls (TD-5-A at line 555, TD-5-B at line 545). `LogScrubber.xfoSummary` was widened from `int totalLen` to `long totalLen` (TD-5-C). All 6 SR-TD5 requirements are satisfied. Three-commit TDD shape preserved. BUILD SUCCESS, Jacoco thresholds pass.

## Commits

| Hash | Message |
|------|---------|
| `e84b2e0` | `test(security): TD-5 add T7a/T7b/LST-T5-1/LST-T5-2 canary tests (RED)` |
| `f33f2bf` | `fix(security): TD-5 replace event.toString()/open.toString() with getSimpleName() and widen xfoSummary totalLen to long (CWE-117, D-13/SR-8)` |
| `58771e6` | `test(security): TD-5 T7a-struct/T7b-struct injection fuzz and T7-gate structural regression gate (SR-TD5-01/02/04)` |

Three-commit TDD shape preserved: `e84b2e0` leaves canaries RED (production unfixed); `f33f2bf` turns them GREEN. `58771e6` is additive Lane B hardening on the green baseline.

## Files Changed

| File | Change |
|------|--------|
| `src/main/java/de/seism0saurus/glacier/mastodon/StompCallback.java` | TD-5-A: line 555 `%s".formatted(event)` → `event.getClass().getSimpleName()`; TD-5-B: line 545 `%s".formatted(open)` → `open.getClass().getSimpleName()` |
| `src/main/java/de/seism0saurus/glacier/util/LogScrubber.java` | TD-5-C: `int totalLen = 0` → `long totalLen = 0L`; Javadoc updated |
| `src/test/java/de/seism0saurus/glacier/mastodon/StompCallbackTest.java` | Added T7a, T7b (Lane A canaries); updated legacy tests at ~:460/:533 (fix commit); added T7a-struct, T7b-struct (8-row injection fuzz each), T7-gate (Lane B) |
| `src/test/java/de/seism0saurus/glacier/util/LogScrubberTest.java` | Added LST-T5-1 (long overflow canary), LST-T5-2 (jqwik property) |

No Spring context, DB, Angular, new dependency, outbound call, SSRF gating, or `isLoadable` signature change.

## Test Results

| Suite | Before TD-5 | After TD-5 | Failures |
|-------|-------------|------------|---------|
| StompCallbackTest executions | 125 | 144 (+19) | 0 |
| LogScrubberTest executions | 61 | +2 new | 0 |
| Total unit tests (Surefire) | ~893 | ~916 | 0 |
| Jacoco instruction | ≥ 45% | PASS | — |
| Jacoco branch | ≥ 35% | PASS | — |

## SR-TD5 Implementation Status

| SR | Requirement | Status |
|---|---|---|
| SR-TD5-01 | No peer bytes in message/args for TD-5-A default branch | CONFIRMED (T7a + T7a-struct 8 rows) |
| SR-TD5-02 | No peer bytes in message/args for TD-5-B Open branch | CONFIRMED (T7b + T7b-struct 8 rows) |
| SR-TD5-03 | `long totalLen` — xfoSummary overflow-safe | CONFIRMED (LST-T5-1 + LST-T5-2) |
| SR-TD5-04 | T7-gate: no `.formatted(event|open)` on LOGGER lines | CONFIRMED |
| SR-TD5-05 | Default branch emits `class=` in formatted message | CONFIRMED (T7a + T7a-struct positive-shape) |
| SR-TD5-06 | Open branch emits `got an Open event (class=` | CONFIRMED (T7b-struct stronger assertion) |

## Deviations from Phase 1 Plan

- **LST-T5-1 arithmetic correction**: The Phase 1 planning doc stated the expected `xfo-totallen` value as `2_147_483_648`. The correct value is `2_148_532_224` (2049 × 1_048_576). The test uses the correct computed value; the planning doc had a rounding error.
- **T7b-struct positive-shape stronger than planned**: T7b-struct asserts `"got an Open event (class="` (full prefix) rather than just `"class="`. This is a beneficial improvement — it pins the full bounded message template.
- **ADR-TD5-D secondary gate assertion**: The secondary check (`getSimpleName()` presence on event/open LOGGER lines) was not implemented in T7-gate. This was not promoted to an SR; T7a-struct and T7b-struct provide equivalent behavioural coverage.

## Resolved Conflicts

None — no `## ⚡ CONFLICT:` markers raised in either round of Phase 2.

## User Approval

Date: 2026-04-30
Approval message (verbatim): "approve"

## References

- [Phase 1 planning](2026-04-30-planning-td5-cwe117-cleanup.md)
- [TD-4 predecessor](2026-04-30-acceptance-td4-xframeoptions.md) (SA-TD4-I1, SA-TD4-I3, SA-TD4-I4 source findings)
- [CWE-117: Improper Output Neutralization for Logs](https://cwe.mitre.org/data/definitions/117.html)
- Glacier D-13 / SR-8 structured-logging discipline
