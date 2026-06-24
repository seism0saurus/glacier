# Planning: Complete the Share-View Toot-Rendering Pipeline

- **Status:** Planning (not yet implemented). Discovered 2026-06-24 while building the
  social-wall + shared-wall e2e (`frontend/e2e/workflows/share-wall-roundtrip.spec.ts`,
  currently `test.fixme`).
- **Related:** [[2026-06-15-acceptance-share-view-stomp-relay-migration]] (relay *mechanics*
  accepted), [[2026-05-14-acceptance-share-link-sqlite-persistence]].
- **Branch context:** `feat/share-view-stomp-relay-migration`.

## Problem

The readonly share view renders nothing useful even after it loads: the **toot-rendering
pipeline was never wired into production**. The relay migration delivered the *transport*
(viewer registry, counting, topic routing, revoke/expire control frames) but deferred the
*content*. Concretely:

- `ShareRenderingService.renderForView(Status, ShareLinkId)`
  (`src/main/java/de/seism0saurus/glacier/share/application/ShareRenderingService.java:67`)
  builds the rich, sanitized `ReadonlyTootView` (text-only via Jsoup, per-link-signed image
  proxy URLs, bidi-stripped, `sharerWallId` never disclosed — ADR-SHARE-02/03). It is called
  **only from tests** (`ShareRenderingServiceTest`); there is **no production caller**.
- The relay emits the placeholder `CacheEntry` instead:
  `StompCallback.relayTootEvent(principal, hashtag, suffix, stored /* CacheEntry */)` at
  `StompCallback.java:357,510,574,591` → `ShareViewStompRelay.relayTootEvent(... Object payload)`
  → `messagingTemplate.convertAndSend(topic, payload)`.
- The frontend STOMP client parses every `…/{hashtag}/creation` and `…/{hashtag}/modification`
  message body as `ReadonlyTootView`
  (`frontend/src/app/share/services/readonly-wall-stomp-client.service.ts:152,297,306`), and
  `ReadonlyTootComponent` renders rich fields (`authorDisplayName`, `textContent`, `links`,
  `spoilerText`, `media`) — **not** an iframe (ADR-SHARE-01: minimum-surface bundle, no embed).
  `CacheEntry` (`type, statusId, url, editedAt, sequence`) shares none of those fields, so a
  live toot renders blank.
- The catalog endpoint is stubbed: `ShareViewController.getCatalog`
  (`ShareViewController.java:170`) returns `hashtags = List.of() // TODO` and
  `ShareCatalogResponse` has no `initialToots`, while the frontend does
  `this.toots$.next([...catalog.initialToots])`
  (`readonly-wall.service.ts:103`) → `TypeError: initialToots is not iterable`.

Net effect: the viewer subscribes to nothing (empty `hashtags`), and even if it did, live
toots arrive in the wrong shape. The share view cannot display toots.

## Current building blocks (exact contracts)

| Piece | Location | Notes |
|---|---|---|
| Rich view builder | `ShareRenderingService.renderForView(Status, ShareLinkId)` | Built + unit-tested; **unwired**. Needs the Bigbone `Status` + the target `ShareLinkId` (proxy URLs are signed per link). |
| View DTO (backend) | `share/application/ReadonlyTootView.java` | Positional record; Jackson order matches the frontend interface. |
| View DTO (frontend) | `frontend/src/app/share/model/readonly-toot-view.ts` | `ReadonlyTootView` + `ShareCatalog { …, initialToots: ReadonlyTootView[] }`. Contract already in place. |
| Relay | `ShareViewStompRelay.relayTootEvent(wallId, hashtag, eventType, Object payload)` | Iterates active share links for the wall; sends per link. Currently forwards `CacheEntry`. |
| Live emit sites | `StompCallback.java:510` (CREATION), `:574` (MODIFICATION), `:591` (DELETION) | **`Status` is in scope at each site** (`status.getId()`, `status.getUrl()`), so `renderForView` is feasible here. Frontend does NOT subscribe to `/deletion`. |
| Cache | `MessageCache.snapshot(PrincipalKey, hashtag, since)` → `Snapshot.events(): List<CacheEntry>` | Ring buffer stores **`CacheEntry` only** — no `Status`, so it cannot rebuild `ReadonlyTootView` for history. |
| Subscriptions | `SubscriptionManagerImpl.subscriptions: Map<principal, Map<hashtag, Future>>` (private) | Has `hasPrincipalSubscriptions` / `isHashtagSubscribedByPrincipal` / `numberOfSubscriptions`, but **no accessor returning a principal's hashtag set** — must be added. |
| Catalog response | `share/web/ShareCatalogResponse.java` | Add `List<ReadonlyTootView> initialToots`; update `active(...)`. |
| Owner principal | `ShareLink.sharerWallId()` (`ShareLink.java:154`) | Resolved server-side in the controller; never serialized (SR-SHARE-02). |
| Frontend event contract | `readonly-wall-stomp-client.service.ts:281-315` | Subscribes per hashtag to `creation` + `modification` only; control frames on `/topic/share/{id}/control`. |

## Target design

1. **Live path (renders new toots).** Move per-link `ReadonlyTootView` rendering into the
   relay. `relayTootEvent` (for CREATION/MODIFICATION) takes the `Status` (or a pre-render
   hook), and for each active `ShareLinkId` calls
   `shareRenderingService.renderForView(status, shareLinkId)` and sends **that** on
   `…/{hashtag}/{creation|modification}`. DELETION: frontend doesn't subscribe — keep the
   existing minimal emit (or a dedicated id-only deletion frame if delete-rendering is added
   later). Inject `ShareRenderingService` into `ShareViewStompRelay`.

2. **Catalog hashtags.** Add `SubscriptionManagerImpl.getSubscribedHashtags(String principal):
   Set<String>` (snapshot of the inner `keySet()`); `getCatalog` populates `hashtags` from
   `link.sharerWallId()`. This is what lets the viewer subscribe to the live topics.

3. **`initialToots` (history hydration).** The ring holds only `CacheEntry`, so building
   `ReadonlyTootView` history needs one of:
   - **(a) Cache the views** — store `ReadonlyTootView` (or the `Status`) per `(principal,
     hashtag)` alongside/instead of `CacheEntry`. Cleanest for fidelity; biggest change
     (touches `PerTagRing`, `MessageCacheImpl`, the fallback `/messages` shape, and their
     tests). Note per-link proxy signing: a cached view is link-agnostic unless re-signed at
     read time — prefer caching the `Status` and rendering per requesting `ShareLinkId` in the
     catalog, mirroring the live path.
   - **(b) Re-fetch `Status`** by `statusId` from Mastodon at catalog time — simple data model
     but N API calls per catalog load (latency, rate-limit, failure handling); not recommended.
   - **MVP fallback:** ship `initialToots: []` (field present so the frontend doesn't crash);
     the wall starts empty and fills live. Acceptable first increment; the e2e posts the shared
     toot *after* the viewer subscribes, so it passes with live-only.

   **Recommendation:** MVP first (items 1+2 + empty `initialToots`), then (a) caching the
   `Status` for true history.

4. **Minor:** an inline event-handler CSP violation observed in the readonly view
   (`script-src 'self' 'nonce-…'` blocked an inline handler) — find and remove the inline
   handler (use Angular event binding) so the readonly view is CSP-clean.

## Work breakdown

- [ ] `ShareCatalogResponse`: add `initialToots` + factory; `getCatalog` returns it. (+IT)
- [ ] `SubscriptionManager(+Impl)`: `getSubscribedHashtags(principal)`; `getCatalog` populates
  `hashtags`. (+unit tests; +IT asserting catalog hashtags for a subscribed sharer)
- [ ] `ShareViewStompRelay` + `StompCallback`: render per-link `ReadonlyTootView` for
  CREATION/MODIFICATION; inject `ShareRenderingService`. (+update `ShareViewStompRelay`/
  `StompCallback` tests; the relay shape change touches accepted code)
- [ ] (Full) History: cache `Status` (or views) for `initialToots`; render per `ShareLinkId`
  at catalog time. (+cache tests; fallback `/messages` shape review)
- [ ] Remove the readonly-view inline event handler (CSP). (+frontend test)
- [ ] Flip `share-wall-roundtrip.spec.ts` from `test.fixme` → `test` once toots render.

## Dependencies / not in this scope

- The realistic-HTTPS e2e stack (Traefik routing + cert SANs in `dynamic.yml` / `v3.ext` /
  `proxy.crt`, which live in the **gitignored** `infrastructure-content.tar.gz`) must be
  re-packed to persist (`sudo tar -czf infrastructure-content.tar.gz mastodon mastodon.env
  postgres redis proxy.* v3.ext dynamic.yml traefik.yml`). See
  `project_share_view_https_e2e` memory. The e2e needs both this pipeline AND that infra.

## Security checklist (must hold)

- `sharerWallId` resolved server-side only; never serialized in catalog or relay payloads (SR-SHARE-02).
- All toot content flows through `ShareRenderingService` sanitization (Jsoup text-only,
  `SafeUrlValidator`, image-proxy rewrite, bidi strip) — ADR-SHARE-03; do not bypass it for
  initial or live toots.
- Image-proxy URLs are signed per `ShareLinkId`; render per requesting link (don't share a
  signed view across links).
- Anti-enumeration (SR-SHARE-01) and host isolation (ADR-SHARE-09) unchanged.
