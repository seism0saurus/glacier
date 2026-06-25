# Decision Record: Share-View Live Toot-Rendering Pipeline (MVP) — Planning

Date: 2026-06-24
Phase: Planning
Agents: ddd-tdd-architect, secure-feature-planner (2 rounds each)
Status: Accepted

## Summary

Wire the built-but-unwired share-view toot-rendering pipeline so **live** toots render on
the readonly share wall. `ShareRenderingService.renderForView(Status, ShareLinkId)` (the
sanitized `ReadonlyTootView` builder, currently called only from tests) is connected into the
relay; the catalog endpoint returns the sharer's subscribed hashtags and an empty
`initialToots`; the frontend is rewired to subscribe to live topics after the async catalog
resolves. History hydration and the HTTPS-e2e infra are explicitly out of scope; the e2e
`frontend/e2e/workflows/share-wall-roundtrip.spec.ts` stays `test.fixme`.

## Key Decisions

### ADR-RENDER-01: Per-link `ReadonlyTootView` rendering belongs in `ShareViewStompRelay`
**Decision**: Inject `ShareRenderingService` into `ShareViewStompRelay`; render per `ShareLinkId`
inside the existing `getActiveLinks` loop. `StompCallback` passes the Bigbone `Status` via a new
additive overload `relayTootEvent(wallId, hashtag, eventType, Status)` for the typed
CREATION/MODIFICATION paths; DELETION keeps the existing `Object`/`CacheEntry` overload (the
frontend does not subscribe to deletion).
**Rationale**: Image-proxy URLs are HMAC-signed per `ShareLinkId`, so a correct render is
link-specific; the per-link loop only exists in the relay. Rendering in the callback would be
link-agnostic (wrong) or duplicate the registry loop.
**Alternatives rejected**: render once in the callback and reuse the payload (breaks per-link
signing / SR-SHARE security model); change the single relay signature to `Status` (additive
overload chosen to minimize churn on accepted deletion/no-viewer/debounce tests).
**Source**: ddd-tdd-architect R1+R2.

### ADR-RENDER-02: `ShareViewController` depends on `SubscriptionManager` (share.web → mastodon)
**Decision**: `getCatalog` injects `SubscriptionManager` and derives `hashtags` from
`getSubscribedHashtags(link.sharerWallId())`, resolved entirely server-side after
`shareLinkService.resolve` confirms an active link. `sharerWallId` is never serialized.
**Rationale**: The catalog must tell the viewer which live topics to subscribe to; the live
subscription map is the single source of truth.
**Alternatives rejected**: persist hashtags on the `ShareLink` at creation (drifts from the live
set, adds at-rest data).
**Source**: ddd-tdd-architect R1; confirmed secure-feature-planner R2.

### ADR-RENDER-03: MVP ships `initialToots` empty (live-only)
**Decision**: `ShareCatalogResponse` gains `List<ReadonlyTootView> initialToots`, populated
`List.of()` for the MVP. History hydration deferred.
**Rationale**: The ring buffer stores only `CacheEntry` (no `Status`), so true history requires
caching the `Status` — out of scope. Empty-but-present fixes the frontend
`[...catalog.initialToots]` crash; the wall fills live.
**Source**: ddd-tdd-architect R1.

### Frontend FLAW-1 (blocker, found in planning)
`ReadonlyWallComponent.ngOnInit` reads `wallService.hashtags` synchronously before the async
catalog (`initialize()` HTTP GET) resolves, so it registers **zero** STOMP topic subscriptions.
Rewire to register subscriptions reactively after `catalogLoaded$`. Without this, the backend
fixes alone render nothing.

## Security Requirements (all CONFIRMED — secure-feature-planner R2)

- **SR-RENDER-01**: `renderForView(status, shareLinkId)` called inside the `getActiveLinks` loop
  with the current `shareLinkId`; never hoisted/cached/shared. Test: two active links → one
  `Status` → two distinct published bodies, each `renderForView`-sourced.
- **SR-RENDER-02**: every published `ReadonlyTootView` originates from `renderForView`; ArchUnit
  gate (in `ShareRelayArchitectureTest`) + grep that `new ReadonlyTootView(` exists only in
  `ShareRenderingService` (+tests). Confirmed enforceable, zero pre-existing violations.
- **SR-RENDER-03**: document the image proxy as a shared **bearer surface** (HMAC = integrity +
  per-link distinctness, NOT per-fetch authz); pin with a test that a LINK_A token is accepted
  without LINK_A's viewer cookie. No proxy code change in the MVP.
- **SR-FLAW2-01**: the generic path must not publish a half-rendered/mistyped shape; pinning test;
  implementer confirms the dockerized streaming path first (see Open Risks).
- **SR-CAT-01** (SR-SHARE-02): the full serialized catalog JSON must not contain `sharerWallId`;
  `initialToots` present and `[]`.
- **SR-CAT-02** (SR-SHARE-01): hashtags only via `getSubscribedHashtags(link.sharerWallId())`
  after `resolve` confirms active; never caller-supplied; unknown/expired/revoked → 404.
- **SR-SUB-01 / SR-SUB-02**: `getSubscribedHashtags` returns a null-safe **copy-on-read** snapshot
  (`new HashSet<>(inner.keySet())`), never null, never the live view. (Both map levels verified
  `ConcurrentHashMap` — `keySet()` read is thread-safe; staleness is benign.)
- **SR-LOG-01** (D-13/SR-8): no new log line emits raw wallId / full toot URL / signed proxy
  token / viewer cookie / toot text; `hash8` ids, `maskIp` IPs.
- **SR-CSP-01**: readonly route loads with zero `script-src` inline-handler CSP violation, or a
  documented follow-up with the captured violation report.
- **SR-FLAW3-01**: live-vs-fallback divergence documented (ADR); e2e stays `test.fixme`; the
  fallback `/messages` path is NOT patched to a silently mis-parsed shape.

## Proposed Phase 2 Lane Partition

- **`tdd-ddd-implementer`** (domain/application happy-path):
  - `SubscriptionManager` (+`SubscriptionManagerImpl`): `getSubscribedHashtags(principal)` with
    copy-on-read snapshot (SR-SUB-01/02) + `SubscriptionManagerImplTest`.
  - `ShareViewController.getCatalog` happy-path wiring: inject `SubscriptionManager`, populate
    `hashtags` + `initialToots = List.of()`; happy-path assertions in `ShareViewControllerIT`.
- **`secure-tdd-implementer`** (security-invariant + relay/cache fan-out):
  - `ShareCatalogResponse`: add `initialToots` + factory; SR-CAT-01 full-body assertion.
  - Relay render wiring in `ShareViewStompRelay` (inject `ShareRenderingService`, per-link render
    in loop, per-link failure containment) + `ShareViewStompRelayRelayTest` (SR-RENDER-01/02).
  - `StompCallback`: pass `Status` on typed CREATION/MODIFICATION; keep DELETION minimal; resolve
    FLAW-2 (measure streaming path; 2a default, else clarification→2b) + pinning test.
  - ArchUnit gate (SR-RENDER-02), log-hygiene (SR-LOG-01), bearer-surface pin (SR-RENDER-03),
    SR-CAT-02 expired-link 404.
- **`frontend-designer`** (UI):
  - FLAW-1 reactive rewire of `ReadonlyWallComponent` + Karma test (subscribe-after-catalog).
  - `readonly-wall.service` empty-`initialToots` handling + Karma.
  - SR-CSP-01 inline-handler hunt/fix (runtime source outside `share/`).
- **Shared-test-file convention** (Round 2): the happy-path assertion comes from
  `tdd-ddd-implementer`; the security-invariant assertion is appended by `secure-tdd-implementer`
  to avoid lane collisions in `ShareViewControllerIT`.

## Resolved Conflicts

### C2 — where per-link isolation lives
**ddd-tdd-architect (R1 wording)**: implied the image-proxy HMAC enforces per-fetch isolation.
**secure-feature-planner**: the proxy `verify()` checks HMAC + expiry only (a bearer surface);
runtime isolation is the relay per-link loop + the pre-existing `ShareViewTopicAuthInterceptor`.
**Resolution (2026-06-24)**: architect accepted the planner's correction in R2; reworded as
SR-RENDER-03. No proxy code change. No open conflict.

## User Approval
Date: 2026-06-24
Approval message (verbatim): "approved, proceed to Phase 2"
(Step 0 scope approval, verbatim: "approved, exclude ux-ui-designer and ship initialToots empty")

## Open Risks (explicitly accepted by the user at the Phase 1 gate)

- **FLAW-3 (fallback renders blank), DEFERRED**: pre-existing; correctness/availability only, no
  confidentiality/integrity impact. Conditions: ADR documented, e2e stays `test.fixme`,
  `/messages` not patched to a half-shape. Fix needs history caching (out of scope).
- **FLAW-2 (generic streaming path)**: default 2a (render typed path only); if the implementer
  measures that dockerized `StatusCreated` routes through the generic path, 2b (adapt payload
  through the `renderForView` seam) is mandatory — raise a clarification before the render commit.
- **Relay change touches accepted relay-migration code/tests**: per-link assertions replace
  shared-payload assertions. Reversible.

## References
- Parent plan: `docs/decisions/2026-06-24-planning-share-view-toot-rendering.md`
- Relay mechanics: `docs/decisions/2026-06-15-acceptance-share-view-stomp-relay-migration.md`
- e2e (test.fixme): `frontend/e2e/workflows/share-wall-roundtrip.spec.ts`
