# Decision Record: Share-View Live Toot-Rendering Pipeline (MVP) — Implementation

Date: 2026-06-24
Phase: Implementation
Agents: tdd-ddd-implementer, secure-tdd-implementer, frontend-designer (R1 sequential + R2 cross-review)
Status: Accepted

## Summary

Implemented the MVP that wires the share-view live toot-rendering pipeline. The catalog now
returns the sharer's subscribed hashtags + an empty `initialToots`; the relay renders a
per-share-link `ReadonlyTootView` (via `ShareRenderingService`) for live CREATION/MODIFICATION;
`StompCallback` feeds it the `Status` on both the typed path and — per the user's FLAW-2 decision
(option B2) — the **generic** streaming path the dockerized Mastodon actually uses; and the
frontend registers STOMP topic subscriptions reactively after the catalog resolves. Combined
build green: backend 391 unit + ITs + Jacoco; frontend Karma 800 passing (1 expected `test.fixme`).

## Key Decisions

### FLAW-2 resolved as B2 — render on the generic streaming path
**Decision**: The dockerized Mastodon 4.x hashtag stream delivers events on the **generic** path
(`GenericMessage` raw JSON), not the typed `StatusCreated`/`StatusEdited` path. `StompCallback.sendMessage`
now parses the raw status JSON into a Bigbone `Status` (a lenient kotlinx `Json` —
`ignoreUnknownKeys=true`, `coerceInputValues=true` — + `Status.Companion.serializer()`) and calls the
new `relayTootEvent(wallId, hashtag, eventType, Status)` overload, so the generic path renders the
**full** per-link `ReadonlyTootView` through `ShareRenderingService`.
**Rationale**: Without this, the typed-path wiring never fires for live hashtag toots and the share
wall renders nothing — the MVP success criterion fails. A minimal CacheEntry-derived view (B1) would
render content-less cards (the readonly view is text-based, not iframe).
**Alternatives rejected**: A (accept dormant pipeline — no visible result); B1 (minimal id+embed-only
view — blank cards).
**Source**: secure-tdd-implementer FLAW-2 measurement; user decision "B2, route it back to secure-tdd-implementer".
**Security**: the B2 render-relay sits AFTER the existing gate chain (SSRF guard → HEAD/`isLoadable`
frame-ancestors → bot opt-in → cache write), so it cannot relay an ungated toot (C10). Lenient-JSON
parsing is bounded (shallow Bigbone `Status` graph, frame ≤1 MB upstream, malformed → caught at DEBUG
with no payload content logged).

### Lane consolidation
**Decision**: The catalog vertical (`getSubscribedHashtags` + `ShareCatalogResponse.initialToots` +
`getCatalog` wiring) was implemented as one lane by `tdd-ddd-implementer` (the Phase 1 doc had
`ShareCatalogResponse` in the secure lane).
**Rationale**: `getCatalog` has a compile dependency on `ShareCatalogResponse.initialToots`; keeping
the vertical together keeps each sequential changeset compilable.

### Frontend FLAW-1 — reactive topic subscription
**Decision**: `ReadonlyWallComponent` subscribes to `catalogLoaded$.pipe(filter(v=>v), take(1))` and
registers `tootEvents$(hashtag)` per hashtag after the catalog resolves (was a synchronous
`hashtags.forEach` in `ngOnInit` that ran before the async catalog, registering zero subscriptions).
**Source**: ddd-tdd-architect FLAW-1; implemented by frontend-designer.

## Files changed

Backend (production): `SubscriptionManager.java` (+Impl), `ShareCatalogResponse.java`,
`ShareViewController.java`, `ShareViewStompRelay.java`, `StompCallback.java`.
Backend (tests): `SubscriptionManagerImplTest`, `ShareViewControllerIT`, `ShareViewStompRelayRenderTest`
(7), `StompCallbackRenderTest` (9, incl. 5 B2), `ShareRelayArchitectureTest` (ArchUnit SR-RENDER-02
gate), `ImageProxyIT` (bearer-surface pin); `@WebMvcTest` slices repaired with `@MockitoBean SubscriptionManager`.
Frontend: `readonly-wall.component.ts` (+spec), `readonly-wall.service.spec.ts`.
Docs: `docs/decisions/2026-06-24-sr-csp-01-follow-up.md` + `README.md` index row.

## Test results (combined, authoritative — first run with all three lanes present)
- Backend `./mvnw verify`: 391 unit + ITs, 0 failures; Jacoco instruction/branch thresholds met; BUILD SUCCESS.
- Frontend Karma: 800 passing, 1 skipped (e2e `test.fixme`), 0 failures.

## Security requirements (all CONFIRMED — secure-tdd-implementer R2)
SR-RENDER-01 (per-link render in loop, both typed + B2 generic); SR-RENDER-02 (all views via
`renderForView`; ArchUnit gate, zero violations); SR-RENDER-03 (image proxy = documented bearer
surface, pinned); SR-CAT-01/SR-SHARE-02 (no `sharerWallId` in catalog body); SR-CAT-02/SR-SHARE-01
(hashtags only after `resolve`; anti-enumeration 404); SR-SUB-01/02 (copy-on-read snapshot);
SR-LOG-01 (no raw wallId/URL/token/text on new paths); C10 (gates precede B2 relay; lenient-JSON
bounded); SR-FLAW3-01 (`/messages` not patched; e2e `test.fixme`; divergence documented);
SR-CSP-01 (documented follow-up satisfies the planned condition).

## Resolved Conflicts
- **ADR-index seam break**: the SR-CSP-01 follow-up doc lacked a `README.md` row →
  `AdrIndexCompletenessSentinelTest` failed → fixed by adding the index row (no code change).
  No open security conflicts.

## User Approval
Date: 2026-06-24
Approval message (verbatim): "approved, proceed to Phase 3"
FLAW-2 decision (verbatim): "B2, route it back to secure-tdd-implementer"

## Open Risks / gaps deferred to Phase 3 (accepted at the gate)
- **SR-CSP-01**: inline-handler violation not located in `share/`; suspected `require-trusted-types-for`
  × Angular 19/zone.js. Shipped as a documented follow-up; Phase 3 to re-verify at runtime.
- **FLAW-3 (fallback renders blank)**: accepted-deferred; needs ring-buffer `Status` caching (out of scope).
- **SR-RENDER-03**: bearer surface; Phase 3 may probe cross-link HMAC reuse against the running stack.

## References
- Planning: `docs/decisions/2026-06-24-planning-share-view-toot-rendering-mvp.md`
- Parent plan: `docs/decisions/2026-06-24-planning-share-view-toot-rendering.md`
- SR-CSP-01 follow-up: `docs/decisions/2026-06-24-sr-csp-01-follow-up.md`
- e2e (test.fixme): `frontend/e2e/workflows/share-wall-roundtrip.spec.ts`
