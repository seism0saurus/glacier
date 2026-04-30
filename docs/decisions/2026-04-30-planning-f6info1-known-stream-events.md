# Decision Record: F-6-INFO-1 KNOWN_STREAM_EVENTS Update — Planning

Date: 2026-04-30
Phase: Planning (Phase 1)
Agents: ddd-tdd-architect (Round 1 + Round 2), secure-feature-planner (Round 1)
Status: Accepted

## Summary

`LogScrubber.KNOWN_STREAM_EVENTS` was authored against Mastodon 4.x (4.0–4.2). Mastodon 4.3 introduced `notifications_merged` as a new streaming event; it currently renders as `unknown(len=20)` — fail-safe, but produces log noise and obscures legitimate events. This change adds `"notifications_merged"` to the allowlist and updates the Javadoc version reference from `"Mastodon 4.x"` to `"Mastodon 4.3"`. Security character-set audit confirmed safe. No other 4.3 or 4.4 events need adding.

## Key Decisions

### ADR-INFO1-01: Batch all confirmed Mastodon 4.3 events in one commit
**Decision**: Add all confirmed 4.3 events in a single commit. Verified: `notifications_merged` is the only new streaming event in Mastodon 4.3; no 4.4 additions found.
**Rationale**: Avoids serialising N follow-ups for cosmetic log fixes; allowlist is one set edited atomically.
**Source**: ddd-tdd-architect Round 1; confirmed by secure-feature-planner Round 1.

### ADR-INFO1-02: Update Javadoc version reference to `Mastodon 4.3`
**Decision**: Replace `"Mastodon 4.x"` with `"Mastodon 4.3"` in `KNOWN_STREAM_EVENTS` field Javadoc and `safeEventName` method Javadoc.
**Rationale**: `4.x` is misleading — the original list reflected 4.0–4.2; pinning a specific version makes future staleness detectable.
**Source**: Both agents, Round 1.

### ADR-INFO1-03: Keep jqwik bound comment unchanged
**Decision**: `"notifications_merged"` (20 chars) does not exceed the current longest allowlisted value `"announcement.reaction"` (21 chars). The `Math.max(s.length(), 25)` bound still holds. No comment update.
**Source**: ddd-tdd-architect Round 1; confirmed by secure-feature-planner.

### ADR-INFO1-04: Add negative-control test for case/padding/control-char variants (SR-F6INFO1-04)
**Decision**: Add `safeEventName_notificationsMerged_caseAndPaddingVariants_returnFallback` testing that `"Notifications_Merged"`, `"notifications_merged "`, `"notifications_merged\r\n"`, and `"notifications_merged‮"` all return `"unknown(len=..."`. The allowlist match is exact equality only.
**Rationale**: Locks in the discipline that allowlist entries are literal byte-sequences — case folding, trim, and Unicode-normalization variants must all fall through to the bounded fallback. Prevents future "helpful" normalization regressions.
**Source**: secure-feature-planner Round 1 (SR-F6INFO1-04); accepted by ddd-tdd-architect Round 2.

## Security Requirements

| SR | Requirement |
|----|-------------|
| SR-F6INFO1-01 | `safeEventName("notifications_merged")` returns `"notifications_merged"` verbatim |
| SR-F6INFO1-02 | Bounded fallback for all non-allowlisted inputs unchanged |
| SR-F6INFO1-03 | jqwik CRLF property still passes; bound `Math.max(s.length(), 25)` holds |
| SR-F6INFO1-04 | Case/padding/control-char variants of the new entry hit the bounded fallback (exact-equality only) |
| SR-F6INFO1-05 | `KNOWN_STREAM_EVENTS` Javadoc references `"Mastodon 4.3"` (versioned provenance) |

## Test Plan

### Lane A — `tdd-ddd-implementer`
**`LogScrubber.java`**:
- Add `"notifications_merged"` to `KNOWN_STREAM_EVENTS`
- Update two Javadoc strings from `"Mastodon 4.x"` to `"Mastodon 4.3"`

**`LogScrubberTest.java`**:
- Append `assertThat(LogScrubber.safeEventName("notifications_merged")).isEqualTo("notifications_merged")` to `safeEventName_knownEvents_returnVerbatim`; update that test's Javadoc
- Add `safeEventName_notificationsMerged_isAllowlistedForMastodon43` (dedicated red-anchor, cites F-6-INFO-1)
- Add `safeEventName_notificationsMerged_caseAndPaddingVariants_returnFallback` (negative-control, 4 variants)

### Lane B — `secure-tdd-implementer`
- Verify `"notifications_merged"` character set: `[a-z_]+` only, no CRLF, no null bytes, no Unicode hazards
- Confirm SR-F6INFO1-04 negative variants are complete and correct
- Confirm jqwik property still passes (red-run the new entry if possible)
- Red-anchor: confirm that removing `"notifications_merged"` from the set causes the dedicated test to fail

### Not changed
- `RawWallIdLogHygieneTest.java` T3a/T3b — T3a uses `"notification"` (still valid); T3b exercises the bounded fallback
- `StompCallback.java` — consumer, no call-site change needed

### Lane order
Lane A first (production + test), Lane B (boundary review + red-anchor), then joint Round 2.

## Resolved Conflicts

None.

## User Approval

Date: 2026-04-30
Approval message (verbatim): "approve"

## Open Risks

- **Mastodon version drift**: future 4.4/4.5 events will render as `unknown(len=N)` until manually added — safe failure mode; operator should monitor `unknown(len=...)` log lines for high-frequency occurrences and file a follow-up.

## References

- F-6 acceptance: `docs/decisions/2026-04-28-acceptance-f6-log-scrubbing.md` (INFO-1 origin)
- Mastodon streaming API: `https://docs.joinmastodon.org/methods/streaming/` (verified 2026-04-30)
- CWE-117: Improper Output Neutralization for Logs
- OWASP A09:2021 Security Logging and Monitoring Failures
- ADR-F6-05: `KNOWN_STREAM_EVENTS` allowlist design (original F-6 planning doc)
