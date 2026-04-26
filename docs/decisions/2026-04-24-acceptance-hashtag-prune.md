# Decision Record: Hashtag Prune on Removal — Acceptance (Phase 3)

Date: 2026-04-24
Phase: Acceptance
Agents: `security-auditor` (R1 + R2 + re-verify), `acceptance-test-auditor` (R1)
Status: Accepted

## Summary

The "Hashtag Prune on Removal" feature (GitHub issue #29) passed Phase 3 acceptance after one fix cycle. All 13 security findings from Round 1 were fixed and verified. A new acceptance criterion AC-11 (URL scheme allowlist in `MessageQueueValidator`) was adopted per unanimous agent recommendation. The final build state is 603 backend unit + 147 IT + 506 Karma — BUILD SUCCESS, 0 failures.

## Final Build State

| Layer | Count | Failures |
|---|---|---|
| Backend unit (Surefire) | 603 | 0 |
| Backend IT (Failsafe) | 147 | 0 |
| Frontend Karma | 506 | 0 |
| Jacoco instruction (bundle) | 86.9% | ≥ 45% req. ✓ |
| Jacoco branch (bundle) | 75.5% | ≥ 35% req. ✓ |
| Playwright (parsed) | 113 across 24 files | 0 parse errors |

## Fix Cycles

### Phase 3 fix cycle (tdd-ddd-implementer)

| Fix | File | Change |
|---|---|---|
| FIX-3 / FIND-P3-SEC-1 | `util/safe-storage.ts` | `dropOldestHalf` now envelope-aware: detects `{v, items:[...]}`, slices items, re-serialises envelope instead of returning `'[]'` |
| FIND-P3-SEC-8 | `subscription.service.ts` | Empty-hashtag ingest silently dropped (STOMP + HTTP fallback paths); misdirected unit test corrected |
| FIND-P3-SEC-5/6 | `subscription.service.ts` | `_settlingTimerHandles: Set` tracks all `setTimeout` handles; `clearSettlingTimers()` called from `terminateAllSubscriptions` |

### Phase 3 fix cycle (secure-tdd-implementer)

| Fix | File | Change |
|---|---|---|
| FIND-P3-SEC-4 | `model/message-queue-validator.ts` | `validateHashtagsList` added; wired into `subscription.service.ts` constructor |
| FIND-P3-SEC-7 / AC-11 | `model/message-queue-validator.ts` | `validateItem` enforces `https:`/`http:` scheme via `new URL()` try/catch; 7 `U-SEC-14` tests added |

### Phase 3 fix cycle (frontend-designer)

| Fix | File | Change |
|---|---|---|
| FIX-1 | `app.module.ts` | Conditional `NoopAnimationDriver` + `ANIMATION_MODULE_TYPE: 'NoopAnimations'` when `matchMedia('(prefers-reduced-motion: reduce)').matches`; 4 unit tests |
| FIX-2 | `wall-announcer.service.ts`, `wall.component.ts`, `wall.component.html` | `WallAnnouncerService` exposes `announcements$: BehaviorSubject<string>`; all `liveRegion.textContent` writes removed; `WallComponent` subscribes and sets `this.announceText`; Angular change detection is sole DOM writer; `fakeAsync` regression test added |
| FIND-P3-SEC-11 | `subscriptions-prune-ux.spec.ts`, `fallback-unsubscribe-prune.spec.ts` | UX2 timeout 3 s → 50 ms; UX3/4 regex on catalog strings; UX5 DOM query; UX8 mutation observer; KS-UX2 full cycle assertion |

### Jackson version fix (backend — orchestrator)

| Fix | File | Change |
|---|---|---|
| `NoSuchMethodError JsonProperty.isRequired()` | `pom.xml` | Removed explicit `jackson-databind:2.19.0` pin; all Jackson components now at `2.18.4` via Spring Boot BOM (`FallbackControllerTest` 12/12 → PASS) |

## Acceptance Criteria Results

| AC | Criterion | Result |
|---|---|---|
| AC-1 | Remove hashtag → exclusive toots disappear | PASS |
| AC-2 | Shared tag survives removal | PASS |
| AC-3 | `hashtags[]` populated on ingest (≥1 entry, normalised) | PASS |
| AC-4 | `recentlyTerminated` guard prevents late re-injection | PASS |
| AC-5 | Cancel-all removes all toots | PASS |
| AC-6 | Migration banner shown once, dismissible, does not reappear | PASS |
| AC-7 | Live region announces prune count to screen readers | PASS (FIX-2 resolved) |
| AC-8 | v:2 envelope persisted + restored correctly | PASS (FIX-3 resolved) |
| AC-9 | `MessageQueueValidator` discards entire queue on any corruption | PASS |
| AC-10 | `normalizeHashtag` canonical at all comparison sites | PASS |
| AC-11 (NEW) | Restored WallMessage URLs must use `http(s)` scheme | PASS (adopted from FIND-P3-SEC-7) |

## Security Findings Summary

| ID | Severity | Finding | Disposition |
|---|---|---|---|
| FIND-P3-SEC-1 | High | `dropOldestHalf` strips v:2 envelope on `QuotaExceededError` | FIXED |
| FIND-P3-SEC-2 | Medium | Prototype-pollution check covers item keys AND hashtag values | VERIFIED (pre-existing) |
| FIND-P3-SEC-3 | Low | Oversized string DoS guards | VERIFIED (pre-existing) |
| FIND-P3-SEC-4 | High | `hashtags` localStorage key unvalidated → STOMP publish | FIXED |
| FIND-P3-SEC-5 | Medium | `recentlyTerminated` setTimeout handles not tracked | FIXED |
| FIND-P3-SEC-6 | Medium | No `clearSettlingTimers` on teardown | FIXED |
| FIND-P3-SEC-7 | Medium-High | `javascript:` URLs admitted to `bypassSecurityTrustResourceUrl` | FIXED (AC-11) |
| FIND-P3-SEC-8 | Medium | Empty-hashtag ingest violates `WallMessage` invariant | FIXED |
| FIND-P3-SEC-9 | High | FIX-3 (duplicate entry for envelope-strip — see SEC-1) | FIXED |
| FIND-P3-SEC-10 | Medium | FIX-1 + FIX-2 a11y dual-issue | FIXED |
| FIND-P3-SEC-11 | Medium | Weak e2e assertions (UX2/3/4/5/8/KS-UX2) | FIXED |
| FIND-P3-SEC-12 | Low | `recentlyTerminated` bounded growth | VERIFIED (pre-existing eviction sweep) |
| FIND-P3-SEC-13 | Low | `Date.now()` over `performance.now()` for TTL | VERIFIED (pre-existing) |

## Key Decision: AC-11 Adoption (Conflict #1)

**Decision**: Add `https:`/`http:` scheme allowlist to `validateItem` in `MessageQueueValidator` (Option A).
**Rationale**: `bypassSecurityTrustResourceUrl` disables Angular's built-in URL sanitiser, making `safe-url.pipe.ts` a single point of failure. The validator's existing value-level guards (length, prototype-pollution) already establish the pattern; URL scheme belongs in the same place. Both `security-auditor` and `acceptance-test-auditor` recommended Option A unanimously.
**Source**: Agents' unanimous recommendation; adopted autonomously per pipeline protocol.

## Open Risks Accepted

- **`hashtag.component.ts sanitize()` uses ad-hoc `toLowerCase`** (SR-PRUNE-07 consistency gap): functionally equivalent to `normalizeHashtag`, not exploitable; deferred post-merge.
- **Playwright live run deferred to CI**: 113 specs parse correctly; first live run against dockerized Mastodon via `-P RunE2ETest`.
- **`http:` URLs accepted in validator**: supports dev/test environments; mixed-content blocking and `StompCallback.isLoadable()` protect production.
- **`hashtags` key charset validation**: validated with Unicode-aware regex; server-side `SubscriptionController` remains the primary enforcement boundary.

## User Approval

Date: 2026-04-24
Approval message (verbatim): "approve"

## References

- Phase 1 decision: `docs/decisions/2026-04-24-planning-hashtag-prune.md`
- Phase 2 decision: `docs/decisions/2026-04-24-implementation-hashtag-prune.md`
- GitHub issue: #29
- CLAUDE.md: authoritative conventions and testing policy
