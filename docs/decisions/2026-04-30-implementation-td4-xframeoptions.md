# Decision Record: TD-4 xFrameOptions Log Hygiene — Implementation

Date: 2026-04-30
Phase: Implementation (Phase 2)
Agents: tdd-ddd-implementer (Lane A + Round 2 review), secure-tdd-implementer (Lane B + Round 2 review)
Status: Accepted

## Summary

TD-4 implementation is complete. `LogScrubber.xfoSummary(List<String>)` was added as a new scrubbing helper returning `"xfo-values=N xfo-totallen=M"`. The bare `xFrameOptions` argument at `StompCallback.java` line 411 was replaced with `LogScrubber.xfoSummary(xFrameOptions)`. Canary tests T6a/T6b/T6c (behavioural, Lane A) and T6b-struct/T6b-gate (adversarial fuzz + structural gate, Lane B) were added. All 12 SR-TD4 requirements are satisfied. BUILD SUCCESS, Jacoco thresholds pass.

## Commits

| Hash | Message |
|------|---------|
| `8294ad1` | `feat(log): TD-4 add LogScrubber.xfoSummary helper and LST-T4-1..8 tests` |
| `4847f91` | `test(security): TD-4 add T6a/T6b/T6c canary tests for xFrameOptions log hygiene (RED)` |
| `18cce12` | `fix(security): TD-4 replace raw xFrameOptions list log arg with xfoSummary (CWE-117)` |
| `7fc7303` | `test(security): TD-4 T6b-struct and T6b-gate injection fuzz and structural regression gate (SR-TD4-05/07/08/10)` |

The three-commit TDD shape is preserved: commit `4847f91` leaves canaries RED (production unfixed); commit `18cce12` turns them GREEN. Commit `7fc7303` is additive hardening (Lane B structural tests added after the fix, on a green baseline).

## Files Changed

| File | Change |
|------|--------|
| `src/main/java/de/seism0saurus/glacier/util/LogScrubber.java` | Added `xfoSummary(List<String>)` at line 212 |
| `src/test/java/de/seism0saurus/glacier/util/LogScrubberTest.java` | Added LST-T4-1..8 (8 @Test + 1 jqwik @Property) |
| `src/main/java/de/seism0saurus/glacier/mastodon/StompCallback.java` | Replaced line 411 bare `xFrameOptions` arg with `LogScrubber.xfoSummary(xFrameOptions)` |
| `src/test/java/de/seism0saurus/glacier/mastodon/StompCallbackTest.java` | Added T6a, T6b, T6c (Lane A) + T6b-struct, T6b-gate (Lane B) |

No Spring context, DB, Angular, new dependency, outbound call, SSRF gating, or `isLoadable` signature change.

## Test Results

| Suite | Before TD-4 | After TD-4 | Failures |
|-------|-------------|------------|---------|
| StompCallbackTest executions | 106 | 125 (+19) | 0 |
| LogScrubberTest executions | (existing) | +9 new | 0 |
| Total unit tests (Surefire) | ~866 | ~893 | 0 |
| Jacoco instruction | ≥ 45% | PASS | — |
| Jacoco branch | ≥ 35% | PASS | — |

## SR-TD4 Implementation Status

| SR | Requirement | Status |
|---|---|---|
| SR-TD4-01 | `xfoSummary` returns `xfo-values=N xfo-totallen=M` | CONFIRMED |
| SR-TD4-02 | Null handling + Javadoc with null-element semantics | CONFIRMED |
| SR-TD4-03 | No control bytes in output | CONFIRMED (jqwik property) |
| SR-TD4-04 | Line 411 uses `xfoSummary`, not bare list | CONFIRMED |
| SR-TD4-05 | Message + arg-array: no raw peer bytes | CONFIRMED (T6b-struct) |
| SR-TD4-06 | `xfo-values=` preserved in WARN | CONFIRMED (T6b positive shape) |
| SR-TD4-07 | `csp` not bare on LOGGER lines | CONFIRMED (T6b-gate Invariant 2) |
| SR-TD4-08 | WARN level; AUDIT appender empty | CONFIRMED (T6b) |
| SR-TD4-09 | `isLoadable` return unchanged; `recordThenPublish` never called | CONFIRMED (T6a) |
| SR-TD4-10 | Structural gate: bare-token regex primary, format-string secondary | CONFIRMED (T6b-gate) |
| SR-TD4-11 | Scope limited to 4 files; no SSRF/context/DB change | CONFIRMED |
| SR-TD4-12 | Three-commit TDD shape | CONFIRMED |

## Deviations from Phase 1 Plan

None. Implementation matches the Phase 1 decision document exactly.

## Known Notes (non-blocking, deferred to Phase 3 review)

- **T6b-gate physical-line limitation**: gate scans physical lines; a future multi-line `LOGGER.*()` with argument on a continuation line would not be caught. Comment added in the test documenting this. All current logger calls in `StompCallback.java` are single-line (verified 2026-04-30).
- **Pre-existing `ImageProxyUrlBuilderVerifyFuzzTest` flakiness**: jqwik HMAC mutation at index 167 intermittently passes verification — unrelated to TD-4. One observation during TD-4 testing; passed on re-run. Separate investigation warranted.

## Resolved Conflicts

None — no `## ⚡ CONFLICT:` markers raised in either round of Phase 2.

## User Approval

Date: 2026-04-30
Approval message (verbatim): "approve"

## References

- [TD-4 xFrameOptions Log Hygiene — Planning](2026-04-30-planning-td4-xframeoptions.md)
- [TD-3 normaliseEditedAt Log Hygiene — Acceptance](2026-04-30-acceptance-td3-normaliseeditedat.md) (TD-4 originally deferred here)
- [CWE-117: Improper Output Neutralization for Logs](https://cwe.mitre.org/data/definitions/117.html)
- Glacier D-13 / SR-8 structured-logging discipline
- [TD-4 xFrameOptions Log Hygiene — Acceptance](2026-04-30-acceptance-td4-xframeoptions.md)
