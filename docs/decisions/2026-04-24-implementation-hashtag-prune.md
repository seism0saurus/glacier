# Decision Record: Hashtag Prune on Removal — Implementation (Phase 2)

Date: 2026-04-24
Phase: Implementation
Agents: `tdd-ddd-implementer` (R1 + R2), `secure-tdd-implementer` (R1 + R2), `frontend-designer` (R1)
Status: Accepted

## Summary

Phase 2 delivered the complete frontend implementation of issue #29 across three sequential lanes. Karma unit tests grew from 328 to 466 (+138 new tests). All 13 SR-PRUNE security requirements are PASS or RISK-ACCEPTED. Three implementation defects found in Round 2 cross-review are agreed-upon and routed to the Phase 3 fix cycle — they do not affect the logical correctness of the core prune feature.

## Files Delivered

| File | Action | Lane |
|---|---|---|
| `frontend/src/app/model/wall-message.ts` | NEW | tdd-ddd |
| `frontend/src/app/util/hashtag.ts` | NEW | tdd-ddd |
| `frontend/src/app/util/safe-storage.ts` | NEW | tdd-ddd |
| `frontend/src/app/services/wall-announcer.service.ts` | NEW | tdd-ddd |
| `frontend/src/app/model/message-queue-validator.ts` | NEW | secure |
| `frontend/src/app/migration-banner/migration-banner.component.ts` | NEW | frontend |
| `frontend/src/app/migration-banner/migration-banner.component.html` | NEW | frontend |
| `frontend/src/app/migration-banner/migration-banner.component.css` | NEW | frontend |
| `frontend/src/app/subscription.service.ts` | MODIFIED | tdd-ddd + secure |
| `frontend/src/app/share/services/readonly-wall.service.ts` | MODIFIED | secure |
| `frontend/src/app/wall/wall.component.ts` | MODIFIED | tdd-ddd (type) + frontend |
| `frontend/src/app/wall/wall.component.html` | MODIFIED | frontend |
| `frontend/src/app/wall/wall.component.css` | MODIFIED | frontend |
| `frontend/src/app/hashtag/hashtag.component.ts` | MODIFIED | frontend |
| `frontend/src/app/hashtag/hashtag.component.html` | MODIFIED | frontend |
| `frontend/src/assets/i18n/messages.de.json` | MODIFIED | secure |
| `frontend/src/assets/i18n/messages.en.json` | MODIFIED | tdd-ddd |
| `frontend/src/environments/environment*.ts` | MODIFIED | tdd-ddd |
| `frontend/e2e/workflows/subscriptions.spec.ts` | MODIFIED (E1–E7) | frontend |
| `frontend/e2e/workflows/subscriptions-prune-ux.spec.ts` | NEW | frontend |
| `frontend/e2e/workflows/subscriptions-prune-a11y.spec.ts` | NEW | frontend |
| `frontend/e2e/workflows/fallback-unsubscribe-prune.spec.ts` | NEW | frontend |

## Key Decisions

### Decision: WallMessage v:2 envelope in localStorage

**Decision**: `MessageQueue.persist()` writes `{ v: WallMessageSchemaVersion, items: WallMessage[] }` (versioned envelope); `restore()` delegates to `validateMessageQueue` which rejects any absent or wrong `v` field.
**Rationale**: Without a versioned envelope, there is no reliable way to distinguish v:1 queues (flat arrays, no `hashtags[]`) from v:2 queues. Attempting per-item migration risks partial corruption.
**Source**: tdd-ddd-implementer R1; confirmed by secure-tdd-implementer R1.

### Decision: 4-step ack handler synchronous same-tick

**Decision**: Steps 1–4 (unsubscribe → seed guard → prune → announce) execute without any `async`/`await`/`setTimeout` between them in `handleTerminationAckMessage`.
**Rationale**: Inserting any async gap between Step 2 (guard seed) and Step 1 (STOMP unsubscribe) or Step 3 (prune) creates a window for late deliveries to escape the guard. Synchronous execution eliminates the race.
**Source**: Phase 1 ADR-3; confirmed by secure-tdd-implementer R2 (SR-PRUNE-13 PASS).

### Decision: validateMessageQueue wired at restore boundary

**Decision**: `MessageQueueValidator.validateMessageQueue` is called inside `MessageQueue.restore()` on the already-parsed value. The basic version check was insufficient alone; the full validator also covers prototype-pollution and oversized strings.
**Rationale**: The basic check was written before the validator existed; secure lane wired the full validator in Round 1.
**Source**: secure-tdd-implementer R1; SR-PRUNE-01/02.

### Decision: ReadonlyWallService returns remaining: []

**Decision**: `ReadonlyWallService.pruneByHashtag()` and `.pruneByHashtags()` return `{ removed: [], remaining: [] }`. This is intentional — the guard-rail should not expose the viewer's current toot snapshot to an attacker who manages to call prune via a crafted WebSocket frame.
**Source**: secure-tdd-implementer R1; SR-PRUNE-09.

### Decision: E1–E7 run chromium-only

**Decision**: E1–E7 prune e2e specs are appended to `subscriptions.spec.ts`. Due to the pre-existing `testMatch` restrictions on firefox/webkit projects, they run in chromium only.
**Rationale**: The pre-existing firefox/webkit `testMatch` arrays were not widened to avoid unintended scope expansion. Chromium coverage is sufficient for the prune logic; cross-browser coverage of the subscription flow predates this feature.
**Source**: frontend-designer R1; tdd-ddd-implementer R2 (ADAPT notice).

## Test Results

| Layer | Count | Status |
|---|---|---|
| Backend unit (Surefire) | unchanged | n/a — backend untouched |
| Backend IT (Failsafe) | unchanged | n/a |
| Frontend Karma | 466 | BUILD SUCCESS, 0 failures |
| Playwright (parsed/listed) | E1–7, UX1–8, A1–5, KS-UX1/2, IN-UX1 | Structurally sound; live run deferred to CI |

## SR-PRUNE Coverage Summary

| SR-ID | Status |
|---|---|
| SR-PRUNE-01 | PASS |
| SR-PRUNE-02 | PASS |
| SR-PRUNE-03 | PASS |
| SR-PRUNE-04 | PASS |
| SR-PRUNE-05 | RISK — `dropOldestHalf` envelope bug; fix in Phase 3 (FIX-3) |
| SR-PRUNE-06 | PASS |
| SR-PRUNE-07 | PASS |
| SR-PRUNE-08 | PASS |
| SR-PRUNE-09 | PASS |
| SR-PRUNE-10 | PASS |
| SR-PRUNE-11 | PASS |
| SR-PRUNE-12 | PASS |
| SR-PRUNE-13 | PASS |

## Phase 3 Fix-Cycle Inputs

### FIX-1 (Medium): `prefers-reduced-motion` not suppressing Angular WAAPI animation

Angular's `@trigger('pruneLeave')` animation engine uses the Web Animations API (WAAPI), not CSS keyframes. The CSS `animation: none !important` rule in `wall.component.css` has no effect on WAAPI-driven animations. Users with `prefers-reduced-motion: reduce` set will still experience the 150 ms fade.

**Agreed fix**: Use `provideAnimations({ disableAnimations: window.matchMedia('(prefers-reduced-motion: reduce)').matches })` in root application providers (Angular 17+). Add a unit test asserting that the animation is not scheduled under simulated `matches: true`.

**Owner**: `frontend-designer` fix cycle.

### FIX-2 (Medium): Live region dual-write race

`WallAnnouncerService` writes directly to `ElementRef.nativeElement.textContent`. `wall.component.ts` has `{{ announceText }}` bound in the template, never updated from code. Angular's change detection resets the element to `''` after the service writes, silencing screen reader announcements.

**Agreed fix**: `WallAnnouncerService` exposes `Observable<string>` / `Subject<string>`. `WallComponent` subscribes and sets `this.announceText`. Remove all `liveRegion.textContent` DOM manipulation from the service. Add a `fakeAsync`/`tick` unit test verifying the announcement survives a change detection cycle.

**Owner**: `frontend-designer` + `tdd-ddd-implementer` (service refactor) fix cycle.

### FIX-3 (Low, integrity): `dropOldestHalf` incompatible with v:2 envelope

On `QuotaExceededError` for the `messageQueue` key, `dropOldestHalf` parses the value, finds a non-array object (`{ v: 2, items: [...] }`), and returns `'[]'`. The retry then writes `'[]'`, which `validateMessageQueue` rejects as a non-envelope on next restore, wiping the queue. The in-memory queue survives the session but the next reload starts empty.

**Agreed fix**: `dropOldestHalf` must detect the `{ v, items: [...] }` envelope, slice `items` (drop oldest half), and return a re-serialised envelope. The plain-array path (for the `hashtags` key) is preserved. Add a unit test for the envelope quota path.

**Owner**: `tdd-ddd-implementer` fix cycle.

## User Approval

Date: 2026-04-24
Approval message (verbatim): "approve"

## Open Risks

- **Playwright e2e not run against live Docker stack**: Specs parse correctly and are placed in the right projects; live verification happens in CI (`-P RunE2ETest`) against the full dockerized Mastodon fixture.
- **FIX-1/2/3 open**: Agreed-upon fixes; addressed in Phase 3 fix cycle. Do not affect the logical correctness of the prune feature.
- **hashtags localStorage key not validated**: The `hashtags` key (plain JSON array of subscribed tags) is restored without schema validation. An XSS attacker who writes to localStorage could inject crafted hashtag strings that are STOMP-published to the server. Server-side validation is the primary defence; this is an accepted residual risk at the client layer.

## References

- [Hashtag Prune — Planning](2026-04-24-planning-hashtag-prune.md)
- [Hashtag Prune — Acceptance](2026-04-24-acceptance-hashtag-prune.md)
- GitHub issue: #29
- CLAUDE.md — authoritative conventions and testing policy.
