# Decision Record: F-6-INFO-1 KNOWN_STREAM_EVENTS Update — Implementation

Date: 2026-04-30
Phase: Implementation (Phase 2)
Agents: tdd-ddd-implementer (Lane A), secure-tdd-implementer (Lane B)
Status: Accepted

## Summary

Added `"notifications_merged"` to `LogScrubber.KNOWN_STREAM_EVENTS` and updated two Javadoc version references from `"Mastodon 4.x"` to `"Mastodon 4.3"`. Three tests added to `LogScrubberTest`: one assertion appended to the existing bulk verbatim test, one dedicated red-anchor, and one negative-control test covering 4 SR-F6INFO1-04 variants. Lane B verified the character-set audit and confirmed the red-anchor (removing the entry causes two tests to fail with `"unknown(len=20)"`). 53 `LogScrubberTest` tests pass (up from 51).

## Production Code Changes

| File | Change |
|------|--------|
| `LogScrubber.java` | Field Javadoc line 49: `"Mastodon 4.x"` → `"Mastodon 4.3"` |
| | `KNOWN_STREAM_EVENTS Set.of()`: `"notifications_merged"` added as 12th entry after `"conversation"` |
| | Method Javadoc line 186: `"Mastodon 4.x"` → `"Mastodon 4.3"` |

## Test Changes

| File | Change |
|------|--------|
| `LogScrubberTest.java` | `safeEventName_knownEvents_returnVerbatim`: 12th assertion appended; Javadoc updated to `"Mastodon 4.3"` |
| | Added `safeEventName_notificationsMerged_isAllowlistedForMastodon43` (dedicated red-anchor, cites F-6-INFO-1) |
| | Added `safeEventName_notificationsMerged_caseAndPaddingVariants_returnFallback` (4 SR-F6INFO1-04 variants) |

## Security Requirements Verified

| SR | Status | Evidence |
|----|--------|---------|
| SR-F6INFO1-01 — `safeEventName("notifications_merged")` returns verbatim | VERIFIED | `safeEventName_notificationsMerged_isAllowlistedForMastodon43` passes |
| SR-F6INFO1-02 — Bounded fallback for non-allowlisted inputs unchanged | VERIFIED | jqwik property passes (53/53) |
| SR-F6INFO1-03 — jqwik CRLF property passes; `Math.max(s.length(), 25)` bound holds | VERIFIED | `"notifications_merged"` (20 chars) < max `"announcement.reaction"` (21 chars) |
| SR-F6INFO1-04 — 4 negative-control variants hit bounded fallback | VERIFIED | `safeEventName_notificationsMerged_caseAndPaddingVariants_returnFallback` passes |
| SR-F6INFO1-05 — Javadoc references `"Mastodon 4.3"` | VERIFIED — both occurrences | Lane B confirmed verbatim |

## Red-Anchor Verification (Lane B)

| Mutation | Failing test | Failure message excerpt |
|----------|-------------|------------------------|
| `"notifications_merged"` removed from `KNOWN_STREAM_EVENTS` | `safeEventName_notificationsMerged_isAllowlistedForMastodon43` | `expected: "notifications_merged" but was: "unknown(len=20)"` |
| Same mutation | `safeEventName_knownEvents_returnVerbatim` | `expected: "notifications_merged" but was: "unknown(len=20)"` |

`safeEventName_notificationsMerged_caseAndPaddingVariants_returnFallback` correctly stays green during mutation (negative-control is independent of the allowlist entry). Entry restored before commit; 53/53 tests pass post-restore.

## Character-Set Audit (Lane B)

`"notifications_merged"` — 20 characters, exclusively `[a-z_]` (lowercase ASCII letters and underscore). No CRLF, no null bytes, no codepoints outside ASCII printable range. Byte-level inspection: Unix line endings only. Audit: PASS.

## Final Test Counts

| Suite | Before | After | Failures |
|-------|--------|-------|----------|
| `LogScrubberTest` JUnit | 50 | 52 | 0 |
| `LogScrubberTest` jqwik | 1 | 1 | 0 |
| **Total** | **51** | **53** | **0** |

## Pre-existing Failure Note

`ImageProxyUrlBuilderVerifyFuzzTest.anyMutationOfValidTokenReturnsEmpty` is a pre-existing flaky jqwik HMAC mutation test (seed-dependent). Not caused by this change; neither modified file touches the image proxy feature.

## Deviations from Phase 1 Plan

None. All ADRs implemented as planned.

## User Approval

Date: 2026-04-30
Approval message (verbatim): "approve"

## References

- Phase 1 planning: `docs/decisions/2026-04-30-planning-f6info1-known-stream-events.md`
- F-6 acceptance: `docs/decisions/2026-04-28-acceptance-f6-log-scrubbing.md` (INFO-1 origin)
- CWE-117: Improper Output Neutralization for Logs
- OWASP A09:2021 Security Logging and Monitoring Failures
- ADR-F6-05: `KNOWN_STREAM_EVENTS` allowlist design (original F-6 planning doc)
