# Implementation Plan: WebSocket HTTP Fallback

**Slug**: websocket-http-fallback
**Drafted**: 2026-04-21
**Author**: ddd-tdd-architect (Phase 1 Round 1)
**Status**: Draft
**Version**: v1
**Satisfies**: `docs/requirements/websocket-http-fallback.md` *(not yet written)*

> **Note**: This is a Round 1 output — produced before peer review by `secure-feature-planner` and `ux-ui-designer`. Open items for those agents are listed in Section 9. The document will be updated after Round 3 synthesis.

## Overview

Add a HTTP short-poll fallback transport so walls remain live when firewalls or proxies block WebSocket. A server-side per-`(principal, hashtag)` ring buffer (`MessageCache`) becomes the single source of truth written atomically with every STOMP publish; `FallbackController` serves it; `FallbackService` in the Angular frontend manages transport switching with a 1 s / 4 s / 16 s back-off schedule and exponential WS-recovery probing.

## 1. Domain Model / Ubiquitous Language

This is a **refinement of the existing Messaging/Subscription context**, not a new context. The existing context already owns "toot arrives from Bigbone → published to a principal's wall"; we are adding a second **delivery transport** (HTTP) that reads from the same source-of-truth and a new **transport-mode lifecycle** that decides which transport the client uses.

Ubiquitous language (all English in code, user-facing German is limited to docs):

- **Glacier Wall**: one browser's view, keyed by `wallId` (the principal). Unchanged.
- **StatusEvent**: the union `StatusCreated | StatusUpdated | StatusDeleted` already modelled as `StatusMessage` subclasses. **Aggregate root key = Mastodon status ID** (the user-locked dedup key).
- **MessageCache** (new, aggregate): the bounded, per-principal-per-hashtag ring buffer of recent `StatusEvent`s that is the **single source of truth for the fallback transport** and is written in lock-step with every STOMP publish. It is an in-memory, non-persistent aggregate with its lifetime bound to the subscription.
- **CacheEntry** (new, value object): `{type: CREATED|UPDATED|DELETED, statusId, url?, editedAt?, sequence}`. `sequence` is a monotonically increasing `long` per cache instance — the **cursor** clients submit as `since=…`. Sequence is local to each `(principal, hashtag)` cache and never re-used.
- **DeliveryCursor** (new, value object, client-side): `{hashtag → lastSeenSequence}`. Persisted in `localStorage` alongside `messageQueue`/`hashtags`.
- **TransportMode** (new, value object, client-side): `WEBSOCKET | FALLBACK | PROBING`. Drives the UI indicator.
- **ProbeSchedule** (new, client-side): exponential back-off state machine for WS recovery (10 s → 20 s → 40 s → 80 s → 160 s → 300 s → 300 s …).
- **TransportHealth** (new, client-side): the observable that flips the indicator and decides when to switch transports.

**Atomicity between STOMP publish and cache insert.** The exactly-once property across transports only holds if inserting into the cache and publishing to STOMP is one logical operation. We enforce this by making the cache the *primary* write and the STOMP publish a *derived side-effect of the same method*. `StompCallback` will call a single new method on `MessageCache`:

```java
cache.recordAndPublish(principal, hashtag, CacheEntry event); // or equivalent
```

…which, under the per-`(principal,hashtag)` write lock: (a) allocates the next `sequence`, (b) inserts/overwrites into the ring buffer, (c) publishes to the STOMP destination via the injected `SimpMessagingTemplate`, (d) releases the lock. Both transports therefore see the *same* event in the *same* order; a reader of the cache can never observe an event that was not also published, and vice versa. Publish failures (e.g. no one is subscribed to the STOMP topic) are logged but do **not** roll back the cache insert — the fallback is specifically designed to cover that case.

**Context map** (single context, two adapters):

```
Bigbone streaming ──► StompCallback ──► MessageCache.recordAndPublish
                                              │
                                              ├──► SimpMessagingTemplate ──► STOMP adapter ──► client
                                              └──► cache storage  ──► HTTP adapter (FallbackController) ──► client
```

## 2. API Design

### Transport Decision

**Decision: HTTP short-poll with a cursor query parameter.** Single endpoint, servlet-thread per call, cache-only read path.

| Option | Pro | Con |
|---|---|---|
| **Short-poll** (chosen) | Passes every firewall/proxy that allows plain HTTP; trivially compatible with `wallId` cookie + Spring MVC; zero long-held threads; idempotent and easy to rate-limit; dedup/cursor contract is the simplest to reason about. | Latency bounded by poll interval (5 s in fallback mode); minor extra HTTP overhead. |
| Long-poll | Lower latency. | A firewall/proxy that terminates idle HTTP after 30 s will break it exactly the way WS is broken; holds a servlet thread; harder to rate-limit meaningfully. |
| SSE | Lower latency, native event framing. | Same corporate proxies that strip/buffer WS also buffer SSE chunks; still needs a polling fallback when SSE itself is blocked. Adds a third transport. |

### REST Endpoints

| Method | Path | Request | Response | Auth |
|--------|------|---------|----------|------|
| GET | `/rest/messages` | `?hashtag={tag}&since={seq}` | `FallbackResponse` JSON | `wallId` cookie |

**Response 200 OK** (JSON):
```json
{
  "hashtag": "glacierE2Etest",
  "nextSince": 42,
  "gap": false,
  "events": [
    {"type": "CREATED",  "id": "123", "url": "https://instance/@u/123/embed", "sequence": 40},
    {"type": "UPDATED",  "id": "123", "url": "...",  "editedAt": "2026-04-21T10:00:00Z", "sequence": 41},
    {"type": "DELETED",  "id": "123", "sequence": 42}
  ]
}
```

`events` is sorted ascending by `sequence`. `nextSince` is what the client should send on the next poll.

**`since` semantics**:
- `since` omitted → server returns everything it currently has in the cache (up to 20) plus `gap: false`.
- `since >= oldestSequence - 1` → server returns only events with `sequence > since`; `gap: false`.
- `since < oldestSequence - 1` → cursor has fallen off the ring. Server returns the full current buffer plus **`gap: true`**.

**Other status codes**: `204 No Content` (cursor is current, no new events), `400` (unknown hashtag for this principal), `401/403` (missing/invalid `wallId` cookie), `429` (rate-limit exceeded, `Retry-After` header set).

**CORS**: `/rest/*` wildcard mapping already covers this — no CORS change needed.

### STOMP Destinations

No new STOMP destinations. Existing destinations (`/topic/hashtags/{wallId}/{hashtag}/creation|modification|deletion`) are unchanged.

### Domain Events / Messages (new DTOs)

- `CacheEntry` — immutable record `(EventType type, String statusId, String url, String editedAt, long sequence)`.
- `FallbackResponse` — `{String hashtag, long nextSince, boolean gap, List<CacheEntry> events}`.

## 3. Module & File Structure

### Backend (CREATE)

- `de.seism0saurus.glacier.webservice.cache.CacheEntry` — immutable record.
- `de.seism0saurus.glacier.webservice.cache.EventType` (enum) — `CREATED, UPDATED, DELETED`.
- `de.seism0saurus.glacier.webservice.cache.PerTagRing` — package-private; bounded `ArrayDeque<CacheEntry>`, `long nextSequence`, `ReentrantLock`; methods `append(CacheEntry)`, `snapshotSince(long since): Snapshot`.
- `de.seism0saurus.glacier.webservice.cache.MessageCache` (interface) + `MessageCacheImpl` (`@Service`):
  - `void recordAndPublish(String principal, String hashtag, CacheEntry partial)`
  - `Snapshot snapshot(String principal, String hashtag, Long since)`
  - `void provisionHashtag(String principal, String hashtag)`
  - `void evictHashtag(String principal, String hashtag)`
  - `void evictPrincipal(String principal)`
- `de.seism0saurus.glacier.webservice.FallbackController` — `@RestController`, `@GetMapping("/rest/messages")`.
- `de.seism0saurus.glacier.webservice.cache.FallbackRateLimiter` — per-principal token bucket, configurable `glacier.fallback.ratelimit.perMinute`.
- `de.seism0saurus.glacier.webservice.cache.FallbackResponse` — response DTO.

### Backend (MODIFY)

- `StompCallback.java` — all `process*Event` / `sendMessage` calls stop calling `SimpMessagingTemplate` directly; build a `CacheEntry` and call `messageCache.recordAndPublish(...)`. `SimpMessagingTemplate` moves into `MessageCacheImpl`.
- `SubscriptionManagerImpl.java` — add `MessageCache` to constructor; `subscribeToHashtag` calls `provisionHashtag`; `terminateSubscription` calls `evictHashtag`; `terminateAllSubscriptions` calls `evictPrincipal`.
- `application.properties` — add `glacier.cache.size=20`, `glacier.fallback.ratelimit.perMinute=30`, `glacier.fallback.enabled=true`.

### Frontend (CREATE)

- `frontend/src/app/fallback/fallback.service.ts` — poll timer, `DeliveryCursor` localStorage persistence, transport-switch logic, exposes `transportMode$: Observable<TransportMode>`.
- `frontend/src/app/fallback/transport-mode.ts` — enum/type `WEBSOCKET | FALLBACK | PROBING`.
- `frontend/src/app/connection-status/connection-status.component.{ts,html,css}` — Material badge/icon, three visual states.

### Frontend (MODIFY)

- `SubscriptionService` — expose `ingestCacheEntries(hashtag, entries)` so `FallbackService` can push into the shared `MessageQueue`.
- `MessageQueue.update()` — add `editedAt` staleness guard: only replace if `editedAt >= current.editedAt`.
- `rx-stomp.config.ts` / `rx-stomp.factory.ts` — expose `connectionState$` to `FallbackService`; WS reconnect schedule now controlled by `FallbackService` (1 s / 4 s / 16 s then flip).
- `HeaderComponent` — embed `<app-connection-status>`.

## 4. Fallback Lifecycle

### Client Detection

Back-off schedule:
- Attempt 1: wait **1 s** after disconnect, activate RxStomp.
- Attempt 2 (if 1 failed): wait **4 s**, activate again.
- Attempt 3 (if 2 failed): wait **16 s**, activate again.
- **Trigger**: if attempt 3 has not reached `OPEN` within a further 16 s → switch `TransportMode` to `FALLBACK`.

`FallbackService` polls every subscribed hashtag every **5 s** (`environment.fallbackPollIntervalMs`). Cursor state persisted in `localStorage`.

### WS Recovery Probe Back-off

**10 s → 20 s → 40 s → 80 s → 160 s → 300 s → 300 s → …** (doubling, capped at 300 000 ms).

On successful probe: set `TransportMode` to `WEBSOCKET` *then* stop poll timer *then* resubscribe all hashtags. Order matters — stop-poll must be after WS confirmed.

### Cache Lifecycle

- **Provisioned eagerly** in `SubscriptionManagerImpl.subscribeToHashtag` (removes the "no cache yet" 400 edge case).
- **Evicted** via `terminateSubscription` (hashtag-level) and `terminateAllSubscriptions` (principal-level). During the 5-min reconnect grace window the cache stays populated — exactly what the fallback client needs.
- No TTL per entry; the 20-entry FIFO ring handles growth.

### Deletion Semantics

`StatusDeleted` is appended as an explicit `CacheEntry(type=DELETED)` with its own `sequence`. The matching `CREATED` entry is NOT retroactively removed. Clients apply DELETED events against their `MessageQueue` exactly as over STOMP.

## 5. Test Pyramid

### Unit Tests (`*Test.java`)

- `PerTagRingTest`
  - Append 21 entries → size stays 20, sequences 1..21 strictly increasing.
  - `snapshotSince(null)` → returns all, `nextSince` = last seq, `gap=false`.
  - `snapshotSince(40)` with ring seqs 30..49 → returns 41..49, `gap=false`.
  - `snapshotSince(5)` with ring seqs 30..49 (older than oldest-1=29) → full ring, `gap=true`.
  - Concurrency: 1000 concurrent appends (10 threads) + 1000 concurrent snapshots; sequences strictly increasing, snapshots internally consistent.
- `MessageCacheImplTest`
  - `recordAndPublish` calls `SimpMessagingTemplate.convertAndSend` exactly once per event type with the correct destination.
  - Sequences increment across consecutive calls.
  - `snapshot` returns a defensive copy.
  - `provisionHashtag` / `evictHashtag` round-trip.
  - `evictPrincipal` removes only that principal's hashtags.
- `StompCallbackTest` — update all assertions: `verify(messageCache).recordAndPublish(...)` instead of `verify(simpMessagingTemplate).convertAndSend(...)`.
- `SubscriptionManagerImplTest` — verify `provisionHashtag` called before virtual-thread submit; `evictHashtag` on terminate; `evictPrincipal` on terminate-all.
- `FallbackControllerTest` (MockMvc, no Spring context) — missing cookie → 401; unknown hashtag → 400; happy path returns correct DTO; rate limit → 429; `glacier.fallback.enabled=false` → 404.
- `FallbackRateLimiterTest` — within quota allows; at limit denies with `Retry-After`; separate buckets per `wallId` do not interfere.

### Unit Tests (`*.spec.ts`)

- `fallback.service.spec.ts` — 3 WS failures → `FALLBACK`; poll interval per hashtag; `gap: true` emits on observable; probe back-off 10/20/40/80/160/300/300 s; 429 does not flip to WS.
- `subscription.service.spec.ts` — `ingestCacheEntries` deduplication; `update` staleness guard; `DELETED` for unknown id is no-op.
- `connection-status.component.spec.ts` — each `TransportMode` renders correct `data-testid="connection-status"` text.

### Integration Tests (`*IT.java`)

- `FallbackControllerIT` — full Spring MVC context; simulate `StatusCreated/Updated/Deleted` hitting `StompCallback`; assert `GET /rest/messages` returns expected DTO; verify atomicity (STOMP subscriber and HTTP poller see same events by id); verify auth (no cookie → 401); verify rate limit (31st req/60s → 429).
- `MessageCacheSubscriptionLifecycleIT` — subscribe → events flow → terminate → HTTP returns 400; disconnect + 5-min timer → cache evicted.

### End-to-End Tests (Playwright)

- `frontend/e2e/workflows/fallback.spec.ts` in `chromium` project:
  1. Happy WS path — toot surfaces; indicator shows `WEBSOCKET`.
  2. WS breaks (traefik `dynamic.yml` middleware) → indicator shows `FALLBACK`; toot posted via `mastodon-client.ts` appears within poll window; no duplicates.
  3. WS recovery — indicator returns to `WEBSOCKET`; next toot appears; still no duplicates.
  4. Cache gap — produce >20 toots with WS broken; fresh browser context (fresh `wallId`); gap warning snackbar appears exactly once; wall shows exactly 20 `app-toot` elements.
  5. Visual regression — screenshot header in all three `TransportMode` states.

**WS failure mechanism**: traefik middleware returning 503 on `/websocket` for the `glacier` service, toggled via `dynamic.yml` on the mounted volume.

## 6. Lane Partition

| Lane | Agent | Files / Modules |
|------|-------|-----------------|
| Domain & cache | `tdd-ddd-implementer` | `CacheEntry`, `EventType`, `PerTagRing`, `MessageCache`, `MessageCacheImpl`, modifications to `StompCallback`, `SubscriptionManagerImpl` |
| Security hardening | `secure-tdd-implementer` | `FallbackController`, `FallbackRateLimiter`, cookie attribute hardening in `InformationController`, security tests |
| Frontend & e2e | `frontend-designer` | `FallbackService`, `TransportMode`, `ConnectionStatusComponent`, `SubscriptionService` extensions, `MessageQueue.update()` staleness guard, Playwright specs |
| Infra / CI | `devops-infra-engineer` | not applicable (no new dependencies, no DB migrations, no CI changes beyond existing Playwright matrix) |

## 7. Implementation Order

1. **`PerTagRing` + `MessageCache`/`MessageCacheImpl`** — foundational; everything else depends on the cache interface.
2. **`StompCallback` refactoring** — wires the cache as the primary write; can be TDD'd against a mock `MessageCache`.
3. **`SubscriptionManagerImpl` changes** — provision/evict calls; depends on `MessageCache` interface.
4. **`FallbackController` + `FallbackRateLimiter`** — HTTP surface; depends on `MessageCache.snapshot`.
5. **Frontend `FallbackService` + `TransportMode`** — depends on backend endpoint being defined (contract from step 4).
6. **`ConnectionStatusComponent`** — depends on `FallbackService.transportMode$`.
7. **`SubscriptionService.ingestCacheEntries` + `MessageQueue.update` guard** — can be done in parallel with step 6.
8. **Playwright e2e specs** — last, after all other layers are stable.

## 8. ADRs

### ADR-01: HTTP short-poll as the fallback transport

**Decision**: short-poll with a cursor, single `GET /rest/messages` endpoint.
**Rationale**: the only reason the fallback exists is firewalls/proxies that break WS. Short-poll degrades to ordinary HTTP and is the most likely path to actually work. Its exactly-once contract is the simplest.
**Alternatives considered**: long-poll (same idle-timeout failure class as WS; holds servlet threads); SSE (same buffering-proxy failure mode; adds a third transport).
**Consequences**: latency bounded by 5 s poll interval in fallback mode. Playwright E2E uses a traefik middleware to break WS without breaking HTTP.

### ADR-02: Cache granularity is per `(principal, hashtag)`, size 20

**Decision**: one ring per `(principal, hashtag)`, bounded to 20 entries.
**Rationale**: matches the STOMP topic tree; makes the `since` cursor one-dimensional. Per-principal caching would cross-evict unrelated hashtags. Size 20 matches the frontend `MessageQueue` capacity.
**Alternatives considered**: per-principal with 20-total (cross-eviction); unbounded with TTL (memory risk).
**Consequences**: memory is `O(principals × hashtags × 20)` — negligible at homelab scale. Configurable via `glacier.cache.size`.

### ADR-03: Fallback trigger is 1 s / 4 s / 16 s then flip

**Decision**: three reconnect attempts with 1 s / 4 s / 16 s waits; flip to fallback if attempt 3 does not reach OPEN within a further 16 s. Total time-to-fallback ≈ 37 s.
**Alternatives considered**: tighter (too twitchy); looser (users stare at a dead wall for >1 min).
**Consequences**: current `rx-stomp.config.ts` `reconnectDelay: 500` is superseded by `FallbackService` control — set to `0` or removed.

### ADR-04: Deletion events are stored, not applied retroactively

**Decision**: `StatusDeleted` is appended as its own `CacheEntry(type=DELETED)`. The matching `CREATED` is not retroactively removed.
**Rationale**: a client whose cursor is older than the CREATED event must observe the create-then-delete pair to stay consistent with the STOMP stream.
**Consequences**: ring can contain create+delete pairs, so 20 entries may represent fewer than 20 distinct toots. Matches STOMP stream exactly.

### ADR-05: Cache eviction coordinated through `SubscriptionManager`

**Decision**: `evictHashtag/evictPrincipal` called from `SubscriptionManagerImpl.terminateSubscription/terminateAllSubscriptions`.
**Rationale**: single point of truth for "a subscription is going away"; avoids double teardown.
**Consequences**: cache stays populated during the 5-min reconnect grace window — the desired behaviour for a fallback client.

## 9. Technical Risks & Open Decisions

**For `secure-tdd-implementer`** (open items from Round 1):

- **Cookie attributes**: `wallId` is not currently `Secure`/`HttpOnly`/`SameSite`. The fallback endpoint returns message content, making this cookie a bigger target. **Recommend**: tighten cookie attributes in the same PR.
- **Rate limiting scope**: per-`wallId` token bucket is bypassable by generating many `wallId` values. Recommend additional per-IP limiter or upstream reverse-proxy rate limiting.
- **Memory DoS**: many hashtags × many `wallId`s can grow cache unboundedly. Consider a hard cap on `wallId` count per JVM.
- **CORS/CSRF on GET**: `/rest/*` already restricts origins; `GET` is idempotent — no CSRF risk expected, but confirm.
- **Feature flag `glacier.fallback.enabled`** acts as kill switch without redeploy.

**For `ux-ui-designer`** (open items from Round 1):

- Indicator placement (header top-right proposed), colour palette compatibility with existing Material theme.
- German strings: "Live" / "Fallback-Modus" / "Verbinde…"; `aria-live` region for screen readers.
- Gap warning: snackbar (dismissable) vs. persistent banner vs. inline — designer's call.
- Consider a fourth `OFFLINE` state when both WS and HTTP fail (3 consecutive poll failures).

**Open items for user confirmation** (defaults already chosen):

- Poll interval in fallback: **5 s**
- Rate limit: **30 req/min per `wallId`**
- Feature flag default: **on** (`glacier.fallback.enabled=true`)

## References

- Requirements: `docs/requirements/websocket-http-fallback.md` *(not yet produced)*
- Feature description: `docs/feature/websocket-http-fallback.md` *(not yet produced)*
- Related prior decisions: `docs/decisions/` *(planning decision doc not yet produced)*

## Revision History

- 2026-04-21 v1: initial plan, Phase 1 Round 1 (ddd-tdd-architect)
