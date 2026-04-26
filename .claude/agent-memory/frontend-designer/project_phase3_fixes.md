---
name: Phase 3 fixes context (ws-fallback + hashtag-prune)
description: Context for two Phase 3 fix cycles — ws-fallback (2026-04-22) and hashtag-prune (2026-04-24)
type: project
---

## Hashtag Prune Phase 3 fixes (2026-04-24, GitHub issue #29)

FIX-1: WAAPI animation not suppressed by CSS prefers-reduced-motion.
- `app.module.ts`: added conditional `AnimationDriver` (NoopAnimationDriver) + `ANIMATION_MODULE_TYPE` ('NoopAnimations') providers when `window.matchMedia('(prefers-reduced-motion: reduce)').matches` is true. BrowserAnimationsModule stays in imports. Both overrides required: token alone only changes what AnimationBuilder reads; driver controls what actually executes.
- `app.module.spec.ts` (NEW): 4 tests covering NoopAnimations token injection, `Element.prototype.animate` spy confirming WAAPI not called, normal-path checks.

FIX-2: Live-region dual-write race between service textContent write and Angular CD.
- `wall-announcer.service.ts`: removed `liveRegion` field and `setLiveRegion()`. Added `announcements$: BehaviorSubject<string>`. `_flush()` emits `''` then `text` (setTimeout 0) on the subject.
- `wall.component.ts`: removed `AfterViewInit`, `@ViewChild`, `ngAfterViewInit()`. Subscribes to `announcements$` in `ngOnInit()`, assigns to `this.announceText`. Unsubscribes on destroy.
- `wall.component.html`: removed `#liveRegion` template ref var.
- Specs rewritten: announcer service tests assert on `announcements$` emissions; wall component tests use `BehaviorSubject` mock property via `createSpyObj` third arg.

FIX-3/FIND-P3-SEC-11: e2e assertion tightening.
- UX2 timeout 3000→50 ms; UX3/UX4 regex assertions; UX5 DOM query; UX8 `page.evaluate()` node ID uniqueness.
- KS-UX2: extended to assert live region empty throughout full subscribe+remove sequence.

Karma: 501 → 506 (5 new tests, all passing).

---

## ws-fallback Phase 3 fixes (2026-04-22)

FIX A: Wired `environment.allowPlaintext` into INSECURE gate in `fallback.service.ts`.
FIX B: Added missing `cap.reached.snackbar.no.limit` i18n key to `messages.en.json`.
FIX C: Added WCAG 2.2 AA tag scoping to axe audits in e2e specs.

**Why:** Phase 3 acceptance audit returned PASS WITH CONDITIONS for both features.
