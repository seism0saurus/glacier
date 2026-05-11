# Decision Record: Hashtag Prune on Removal — Planning (Phase 1)

Date: 2026-04-24
Phase: Planning
Agents: `ddd-tdd-architect` (R1 + R2), `secure-feature-planner` (R1 + R2), `ux-ui-designer` (R1)
Status: Accepted

## Summary

When a user removes a hashtag from the followed list, any toot whose `hashtags[]` membership becomes empty as a result is removed from the wall immediately. This addresses GitHub issue #29 (toots persisting after the hashtag that brought them in is unsubscribed). The feature is entirely frontend — the backend's `StompCallback` and `SubscriptionManagerImpl` are untouched. The core domain change is replacing the opaque `SafeMessage` in `MessageQueue` with a new `WallMessage` value object that carries explicit hashtag membership, plus a `recentlyTerminated` guard that prevents late STOMP deliveries from re-adding pruned toots.

## Key Decisions

### ADR-1 — `WallMessage` with `hashtags[]`

**Decision**: Replace `SafeMessage` in `MessageQueue` with `WallMessage { id: string; url: string; editedAt?: string; hashtags: string[] }`, schema version `v:2` (`WallMessageSchemaVersion = 2`).
**Rationale**: Explicit per-toot hashtag membership is the only way to implement correct prune semantics without re-fetching from the server. Without it, the frontend has no record of which subscription caused a given toot to appear.
**Alternatives considered**: Keep `SafeMessage`, store a separate `Map<tootId, hashtags[]>` (rejected — two maps diverge; harder to serialize atomically).
**Source**: `ddd-tdd-architect` R1, confirmed R2.

### ADR-2 — `pruneByHashtag` / `pruneByHashtags`

**Decision**: `pruneByHashtag(h: string): PruneResult` removes `h` from each `WallMessage.hashtags`, then drops entries whose `hashtags` becomes empty. `pruneByHashtags(hashtags: string[])` handles cancel-all by calling `pruneByHashtag` for each. Returns `{ removed: string[]; remaining: WallMessage[] }`.
**Rationale**: Pure functions on immutable snapshots; trivially testable; no async paths to race.
**Alternatives considered**: Single-pass multi-tag prune (rejected — complicates announcement reporting per-hashtag).
**Source**: `ddd-tdd-architect` R1.

### ADR-3 — `recentlyTerminated` guard

**Decision**: `recentlyTerminated: Map<normalizedHashtag, expiresAt: number>` is seeded at step 2 of the 4-step ack sequence, before the prune (step 3). Any incoming STOMP message for a recently-terminated hashtag is dropped while the guard is active. TTL: `environment.prune.guardTtlMs` (default 10,000 ms). The map is bounded — an eviction sweep runs on each seed operation, removing entries with `expiresAt < Date.now()`.
**Rationale**: The ack from the backend and the last in-flight STOMP delivery may arrive out of order; a guard window prevents pruned toots from reappearing.
**Source**: `secure-feature-planner` R1 (raised as race condition); `ddd-tdd-architect` accepted in R2.

### ADR-4 — Clear-on-upgrade migration with dismissible banner (revised)

**Decision**: On `localStorage` restore, if the `v:2` field is absent the queue is discarded entirely. A one-shot dismissible `MatCard` banner (`MigrationBannerComponent`) is shown once after the discard, informing the user that the wall was refreshed. The banner is not shown on subsequent loads.
**Rationale**: Silently discarding data without user feedback would be confusing. The banner closes the feedback loop. The banner is `role="alert"` so screen readers announce it immediately.
**Alternatives considered**: Attempt migration from v:1 to v:2 (rejected — the v:1 format lacks `id` and `hashtags[]`; partial migration produces corrupt membership data that defeats the feature's purpose).
**Source**: `ddd-tdd-architect` R2 (revised from R1's silent discard in response to `ux-ui-designer` feedback).

### ADR-5 — `WallAnnouncerService` with 250 ms debounce

**Decision**: Dedicated `WallAnnouncerService` accumulates prune events for 250 ms, then emits a single coalesced announcement to a `role="status" aria-live="polite"` live region sibling to `role="feed"`. ICU-plural for single-hashtag prune (count of removed toots); fixed string for cancel-all.
**Rationale**: If each prune emits immediately, a cancel-all with N hashtags would flood the live region with N announcements, confusing screen reader users.
**Source**: `ux-ui-designer` R1; accepted by `ddd-tdd-architect` R2.

### ADR-6 — `normalizeHashtag(s)` canonical helper

**Decision**: Single function exported from `frontend/src/app/util/hashtag.ts`. Strips leading `#`, lowercases, trims. All hashtag equality checks in `pruneByHashtag`, `recentlyTerminated`, `ingestCacheEntries`, and `ReadonlyWallService` must route through it. A pre-merge grep audit enforces no ad-hoc `toLowerCase`.
**Rationale**: Case-inconsistency between Mastodon-sourced tags (`#Glacier`) and user-typed tags (`glacier`) has historically caused silently wrong equality checks.
**Source**: `secure-feature-planner` R1 (SR-PRUNE-07/10); accepted by `ddd-tdd-architect` R2.

### ADR-7 — `safeSetItem` quota-safe localStorage write

**Decision**: `safeSetItem(key: string, value: string)` wraps `localStorage.setItem`. On `QuotaExceededError` it drops the oldest half of the queue and retries once. If retry fails, it logs an error and returns without throwing.
**Rationale**: Mobile browsers cap `localStorage` at 5 MB; a wall with 20 large toot URLs plus all share-link data can approach the limit. Silent quota failure is worse than a reduced queue.
**Source**: `secure-feature-planner` R1 (SR-PRUNE-05/11).

## Security Requirements

| SR-ID | Requirement | Phase 2 owner |
|---|---|---|
| SR-PRUNE-01 | `MessageQueueValidator`: full strict schema on restore; discard entire queue on any corruption | `secure-tdd-implementer` |
| SR-PRUNE-02 | Reject reserved prototype-pollution names (`__proto__`, `constructor`, `prototype`) in queue items | `secure-tdd-implementer` |
| SR-PRUNE-03 | `recentlyTerminated` bounded: eviction sweep on each seed; no unbounded growth | `tdd-ddd-implementer` |
| SR-PRUNE-04 | Use `Date.now()` (not `performance.now()`) for TTL — reduces timing side-channel | `tdd-ddd-implementer` |
| SR-PRUNE-05 | `safeSetItem`: no silent data loss on `QuotaExceededError`; drop oldest half, retry once, log | `tdd-ddd-implementer` |
| SR-PRUNE-06 | `recentlyTerminated` guard seeded at step 2 (before prune step 3) | `tdd-ddd-implementer` |
| SR-PRUNE-07 | `normalizeHashtag` used at every comparison site; grep audit before merge | `tdd-ddd-implementer` |
| SR-PRUNE-08 | `hashtags[]` populated correctly on ingest: at least 1 entry per toot, no empty string | `tdd-ddd-implementer` |
| SR-PRUNE-09 | `ReadonlyWallService` guard-rail: pruning disabled; injecting hashtags into existing toots blocked | `secure-tdd-implementer` |
| SR-PRUNE-10 | Single canonical `normalizeHashtag` exported from `hashtag.ts` | `tdd-ddd-implementer` |
| SR-PRUNE-11 | `safeSetItem` test covers `QuotaExceededError` path + retry-once invariant | `tdd-ddd-implementer` |
| SR-PRUNE-12 | `MessageQueueValidator` unit tests: pass valid `v:2`, reject `v:1`, reject `__proto__`, reject oversized | `secure-tdd-implementer` |
| SR-PRUNE-13 | Ack handler 4-step sequence tested as synchronous (no `await`/`setTimeout` between steps 1–3) | `tdd-ddd-implementer` |

## UX Requirements

- **WAI-ARIA structure**: `role="feed"` on toot list, `role="article"` per toot, `role="status" aria-live="polite"` live region as sibling (not nested in `role="feed"`).
- **Toot removal animation**: 150 ms opacity fade-out, `ease-out`; `@media (prefers-reduced-motion: reduce)` → instant removal. No snackbar, no undo affordance.
- **Settling spinner on hashtag chip**: Visible while `recentlyTerminated` guard is active for that hashtag. `aria-label` = `chip.settling.aria` i18n key.
- **Migration banner**: `MigrationBannerComponent` with `role="alert"`, shown once after v:2 upgrade. Dismissible via close button.
- **i18n strings** (German source → English catalog):

| `@@id` | de | en |
|---|---|---|
| `wall.heading` | `Pinnwand` | `Wall` |
| `wall.prune.announce.single` | ICU plural with `count` | ICU plural with `count` |
| `wall.prune.announce.cancelAll` | `Alle Abonnements beendet. Pinnwand ist leer.` | `All subscriptions ended. Wall is empty.` |
| `wall.migration.banner.heading` | `Pinnwand aktualisiert` | `Wall refreshed` |
| `wall.migration.banner.body` | one sentence explaining the refresh | one sentence explaining the refresh |
| `wall.migration.banner.dismiss.aria` | `Hinweis schließen` | `Dismiss notice` |
| `chip.settling.aria` | `Synchronisiert, kurz warten` | `Syncing, one moment` |
| `chip.settling.visual.tooltip` | `Synchronisiert …` | `Syncing…` |

## Test Plan

| Layer | IDs | Count |
|---|---|---|
| Frontend unit (Karma/Jasmine) | U1–U14 (domain) + U-SEC-01..13 (security) | 27 |
| Playwright e2e (chromium/firefox/webkit) | E1–E7 | 7 |
| Playwright e2e (fallback/killswitch) | KS-UX1, KS-UX2 | 2 |
| Playwright e2e (insecure) | IN-UX1 | 1 |
| Playwright UX | UX1–UX8 | 8 |
| Playwright accessibility (axe-core) | A1–A5 | 5 |
| Migration banner | B1–B2 | 2 |

## Phase 2 Lane Partition

| Agent | Files / Modules owned |
|---|---|
| `tdd-ddd-implementer` | `normalizeHashtag`, `safeSetItem`, `WallMessage`, `MessageQueue`, `pruneByHashtag/s`, `recentlyTerminated`, ack handler wiring, `WallAnnouncerService` logic, i18n keys, unit tests U1–U14 + U-SEC-03..11/13 |
| `secure-tdd-implementer` | `MessageQueueValidator`, `ReadonlyWallService` guard-rail, unit tests U-SEC-01/02/12, SR-PRUNE-01/02/09/12 |
| `frontend-designer` | `wall.component.{ts,html,scss}` (feed/article/live-region wiring, fade animation), `MigrationBannerComponent`, hashtag chip settling spinner, `environment.ts`, all Playwright specs (E1–E7, UX1–UX8, A1–A5, B1–B2, KS-UX1/2, IN-UX1) |

## Resolved Conflicts

### Migration strategy (ADR-4)

**`secure-feature-planner`**: discard entire queue on missing `v:2` (clear-on-upgrade).
**`ddd-tdd-architect` R1**: same — silent discard.
**`ux-ui-designer`**: requested user-facing feedback after discard.
**Resolution (2026-04-24)**: `ddd-tdd-architect` accepted in R2 — revised ADR-4 adds dismissible banner. No user escalation needed.

### Late-arrival STOMP race (SR-PRUNE-06)

**`secure-feature-planner`**: raised risk that a STOMP delivery arriving after ack could re-add a pruned toot.
**`ddd-tdd-architect`**: accepted `recentlyTerminated` guard seeded at step 2, before prune at step 3.
**Resolution (2026-04-24)**: Resolved at agent level. No user escalation needed.

### `localStorage` validation strictness (SR-PRUNE-01)

**`secure-feature-planner`**: required discard-entire-queue on any corruption (prototype-pollution safety).
**`ddd-tdd-architect` R1**: per-item-skip approach.
**Resolution (2026-04-24)**: `ddd-tdd-architect` aligned in R2 — discard-all adopted. No user escalation needed.

## User Approval

Date: 2026-04-24
Approval message (verbatim): "accept"

## Open Risks

- **`recentlyTerminated` guard TTL (10 s default)**: On very poor networks (STOMP delivery delayed >10 s), a pruned hashtag's toot could briefly re-appear then vanish. Accepted; TTL is configurable via `environment.prune.guardTtlMs`.
- **Playwright e2e against live Docker stack**: E2E specs are structurally correct; live verification happens in CI (`-P RunE2ETest`), not locally during Phase 2.
- **Banner copy review deferred to Phase 3**: The `wall.migration.banner.body` sentence is flagged for copy review; non-blocking per security sign-off.
- **No backend changes**: Feature is entirely frontend. Future Mastodon delivery ordering changes would require re-evaluating the `recentlyTerminated` guard window.

## References

- GitHub issue: #29
- CLAUDE.md — authoritative conventions and testing policy.
- [Hashtag Prune — Implementation](2026-04-24-implementation-hashtag-prune.md)
- [Hashtag Prune — Acceptance](2026-04-24-acceptance-hashtag-prune.md)
- Applicable skills: [`angular-i18n-localize`](../../.claude/skills/angular-i18n-localize.md), [`angular-karma-jasmine-testing`](../../.claude/skills/angular-karma-jasmine-testing.md), [`playwright-e2e-patterns`](../../.claude/skills/playwright-e2e-patterns.md), [`angular-a11y-patterns`](../../.claude/skills/angular-a11y-patterns.md), [`angular-material-theming`](../../.claude/skills/angular-material-theming.md), [`glacier-fallback-mode-discipline`](../../.claude/skills/glacier-fallback-mode-discipline.md)
