# Architectural Plan — WebSocket HTTP Fallback

Phase 1, Round 1. Author: ddd-tdd-architect. Peers: secure-feature-planner, ux-ui-designer.

## 1. Bounded contexts & domain model

This is a **refinement of the existing Messaging/Subscription context**, not a new context. The existing context already owns "toot arrives from Bigbone → published to a principal's wall"; we are adding a second **delivery transport** (HTTP) that reads from the same source-of-truth and a new **transport-mode lifecycle** that decides which transport the client uses.

Ubiquitous language (all English in code, user-facing German is limited to docs):

- **Glacier Wall**: one browser's view, keyed by `wallId` (the principal). Unchanged.
- **StatusEvent**: the union `StatusCreated | StatusUpdated | StatusDeleted` already modelled as `StatusMessage` subclasses. **Aggregate root key = Mastodon status ID** (the user-locked dedup key).
- **MessageCache** (new, aggregate): the bounded, per-principal-per-hashtag ring buffer of recent `StatusEvent`s that is the **single source of truth for the fallback transport** and is written in lock-step with every STOMP publish. It is an in-memory, non-persistent aggregate with its lifetime bound to the subscription.
- **CacheEntry** (new, value object): `{type: CREATED|UPDATED|DELETED, statusId, url?, editedAt?, sequence}`. `sequence` is a monotonically increasing `long` per cache instance — the **cursor cursor** clients submit as `since=…`. Sequence is local to each `(principal, hashtag)` cache and never re-used.
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

## 2. Transport decision

**Decision: HTTP short-poll with a cursor query parameter.** Single endpoint, servlet-thread per call, cache-only read path.

Trade-offs considered:

| Option | Pro | Con |
|---|---|---|
| **Short-poll** (chosen) | Passes every firewall/proxy that allows plain HTTP; trivially compatible with `wallId` cookie + Spring MVC; zero long-held threads; idempotent and easy to rate-limit; dedup/cursor contract is the simplest to reason about. | Latency is bounded by poll interval (we choose 5 s in fallback mode); minor extra HTTP overhead. |
| Long-poll | Lower latency. | A firewall/proxy that terminates idle HTTP after 30 s will break it exactly the way WS is broken; holds a servlet thread (virtual threads help, but Tomcat's async dispatch is a more invasive change than we want); harder to rate-limit meaningfully. |
| SSE | Lower latency, native event framing. | `EventSource` is another long-lived connection; the same corporate proxies that strip/buffer WS also buffer SSE chunks (seen in the wild); still needs a polling fallback when SSE itself is blocked. Adds a third transport instead of two. |

Short-poll is the transport most likely to actually work in the "strange firewall" scenario the user described, and it leaves the simplest exactly-once contract. See `ADR-01`.

**Wire contract (proposed):**

- **Endpoint**: `GET /rest/messages?hashtag={tag}&since={seq}` (the `wallId` is read from the cookie via `@CookieValue`, never from the URL).
- **Response 200 OK** (JSON):
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
- **`since` semantics**:
  - `since` omitted → server returns everything it currently has in the cache (up to 20) plus `gap: false` (first call after a fresh start).
  - `since >= oldestSequence - 1` → server returns only events with `sequence > since`; `gap: false`.
  - `since < oldestSequence - 1` → the client's cursor has fallen off the ring. Server returns the full current buffer plus **`gap: true`** so the client can display a one-time warning ("Du hast länger keine Verbindung gehabt – ältere Toots könnten fehlen.") and reset its internal `MessageQueue` state consistently. This is the explicit gap signal required by Section 5.
- **Response 204 No Content**: the client's cursor is current and no new events have arrived (cheaper than a 200 with an empty array; reduces polling cost).
- **Response 400**: unknown hashtag for this principal (the client is polling a hashtag they did not subscribe to via STOMP — bug or stale state).
- **Response 401/403**: missing/invalid `wallId` cookie.
- **Response 429**: rate-limit exceeded. `Retry-After` header set. Client must back off; it must not flip back to WS on 429 (that is not a signal about WS health).
- **CORS**: add `/rest/messages` to the existing `addCorsMappings("/rest/*")` (the wildcard already covers this — no CORS change).

The UPDATED and DELETED cases are modelled inline in the same events stream so the client can replay them in the same order the STOMP path would have.

## 3. Server-side cache

### Shape and granularity

**Per `(principal, hashtag)`**, bounded at **20 entries**, FIFO-evict oldest. Rationale:

- The existing frontend `MessageQueue` is bounded to 20 **per wall (principal)** — but the wall aggregates *across* all hashtags into one queue. If the server cached per-principal only, two hot hashtags would evict each other on the server while the client's single queue still had room. Per-`(principal, hashtag)` matches the granularity of the STOMP topic tree (`/topic/hashtags/{principal}/{hashtag}/...`) and makes the `since` cursor trivially one-dimensional.
- 20 is the mandated ring size. Memory: ≤ 20 × (small CacheEntry ~200 bytes) × N hashtags × M principals ≈ negligible at homelab scale. See `ADR-02`.

### Concurrency

- Outer container: `ConcurrentHashMap<String, ConcurrentHashMap<String, PerTagRing>>` keyed `principal → hashtag → ring`.
- `PerTagRing`: a class containing an `ArrayDeque<CacheEntry>` (bounded to 20), a `long sequence` counter, plus a private `ReentrantLock`. All reads *and* writes take the lock — a `ReadWriteLock` is overkill for a 20-element deque and complicates the atomicity with `SimpMessagingTemplate.convertAndSend`, which must run inside the write critical section.
- `StompCallback` uses `cache.recordAndPublish(principal, hashtag, entry)` which internally acquires the lock for that `(principal, hashtag)` only. No global lock; different hashtags on different virtual threads do not contend.
- `FallbackController` takes a **snapshot-copy** of the deque under the lock (cheap — ≤ 20 entries) and serialises outside the lock. Never hands the live deque out.

### Lifecycle

- **Creation**: lazily on first `recordAndPublish` for a `(principal, hashtag)`, i.e. the first Bigbone event. Alternatively eagerly in `SubscriptionManagerImpl.subscribeToHashtag` — simpler and removes the "HTTP caller with no cache yet" 400 case. **Eager creation is preferred.**
- **Cleanup on permanent disconnect**: `SubscriptionListener` already tears down Bigbone subscriptions after the 5-minute reconnect grace period. We **coordinate** with it rather than duplicate it: the existing `terminateAllSubscriptions(principal)` call gets a new observable side-effect — a `MessageCache.evictPrincipal(principal)` call — added to the same code path. Before the 5-minute timer fires, cache entries persist, which is exactly the behaviour we want: a client that disconnects WS, spends 3 minutes on HTTP fallback, and then reconnects WS must find its cache still populated. See `ADR-05`.
- **Cleanup on explicit unsubscribe**: `SubscriptionManagerImpl.terminateSubscription(principal, hashtag)` calls `MessageCache.evictHashtag(principal, hashtag)`.
- No TTL per entry. The 20-entry bound handles growth; the lifecycle handles cleanup.

### Deletion semantics

Mastodon's `StatusDeleted` currently produces a STOMP message on `/deletion`. The client uses that to remove the status from its `MessageQueue`. The fallback must preserve this semantic. **Decision**: the cache stores the deletion as an explicit `CacheEntry{type: DELETED, statusId, sequence}`. It does **not** retroactively remove the original `CREATED` entry, because:

1. A client whose cursor is older than the CREATED event must still see the create-then-delete pair to stay consistent with the STOMP stream.
2. Once the CREATED entry naturally ages out of the ring, the DELETED entry will too.

The client applies DELETED events against its own `MessageQueue` exactly as it does over STOMP (`receivedMessages.dequeue(id)`). Cache readers that receive a DELETED for an ID they never saw created must ignore it (already the case: `dequeue(undefined)` is a no-op, and `dequeue` of an unknown id silently does nothing). See `ADR-04`.

## 4. Fallback lifecycle

### Client detection — concrete thresholds

The user's constraint is "3 failed reconnect attempts with increasing timeout". RxStomp exposes `connectionState$` (`OPEN/CLOSED/CONNECTING`) and `stompErrors$`. `reconnectDelay: 500` in the current config is too aggressive on its own to count, because it will fire 200+ times in 100 s. We therefore **replace** the single `reconnectDelay` with an explicit back-off schedule implemented in a new `FallbackService`:

- Attempt 1: wait **1 s** after disconnect, activate RxStomp.
- Attempt 2 (if 1 failed): wait **4 s**, activate again.
- Attempt 3 (if 2 failed): wait **16 s**, activate again.
- **Trigger**: if attempt 3 has not reached `OPEN` within a further 16 s, switch TransportMode to `FALLBACK`.

"Failed" = `connectionState$` observed `CLOSED` after transitioning out of `CONNECTING` (or a `stompErrors$` frame with a `receipt` error). We deactivate RxStomp during fallback to stop further reconnect attempts from flapping the indicator.

These exact values (1 s / 4 s / 16 s, then trigger) are committed in `ADR-03`; the secure-feature-planner does not need to revisit them.

### While in fallback

- `FallbackService` polls `GET /rest/messages?hashtag={tag}&since={seq}` for **every subscribed hashtag** every **5 seconds** (configurable via `environment.fallbackPollIntervalMs`).
- Responses are merged into the same `MessageQueue` via the same `enqueue/update/dequeue` code paths `SubscriptionService` already uses. **This is where client-side dedup happens** — the queue already filters by `id`, so an event that arrives over STOMP and then (because we recover) also over HTTP never reaches the UI twice.
- Cursor state `DeliveryCursor` is persisted per-hashtag in `localStorage` key `deliveryCursor` so a hard page reload during a fallback session resumes politely.
- **Gap handling**: if any response has `gap: true`, the client displays a dismissable Material snackbar/warning ("Einige ältere Toots könnten fehlen.") and updates its `MessageQueue` with what it got. It does **not** clear the queue — existing entries the client already has are still valid; only the older-than-cache ones are lost.

### Recovery — WS probe back-off (5-minute cap)

Concrete sequence: **10 s → 20 s → 40 s → 80 s → 160 s → 300 s → 300 s → …** (doubling with a cap at 300 000 ms). Running in parallel with the polling.

A probe = a bounded attempt to open a fresh STOMP session. TransportMode transitions to `PROBING` for the duration of the probe attempt only:

1. Instantiate a new RxStomp (do **not** re-use the deactivated one — simpler state model) with the same config.
2. Activate. Wait up to **10 s** for `connectionState$` to reach `OPEN` and for `/user/topic/subscriptions` ack traffic to confirm a full STOMP session is usable.
3. **On success**: set TransportMode to `WEBSOCKET`; *then* stop the poll timer; *then* resubscribe all hashtags via the normal STOMP path. Order matters — poll-stop must happen **after** WS is confirmed, otherwise a brief window with neither transport could drop messages. There is **no** resulting duplication because dedup is by `statusId` in `MessageQueue`, and the per-hashtag cache+STOMP writes are serialised by the server-side lock.
4. **On failure**: deactivate the new RxStomp, double the wait, re-enter the polling-only state.

### Server-side recovery

No server-side action is needed to recover: the cache keeps being populated by the virtual-thread Bigbone callback regardless of which transport the client is using. When the client re-establishes STOMP, its first `since` cursor picks up anything the cache still holds. The fallback and WS paths cannot duplicate because:

- Both read from the same cache writes (server side).
- The client dedups by `statusId` in `MessageQueue` (client side).

## 5. Exactly-once contract

**Ordering.** Within a single `(principal, hashtag)`, `StompCallback` is called from one virtual thread. All writes take the per-tag lock. Therefore events are linearised in the order Bigbone emits them, with a strictly increasing `sequence`. HTTP and STOMP consumers see the same order.

**Idempotence.** Each event has a stable server-assigned `sequence` plus a stable Mastodon `statusId`. The client keys dedup on `statusId`. Replaying the same sequence range via HTTP after a reconnect is safe: `MessageQueue.enqueue` silently drops duplicates by id, `update` is idempotent, `dequeue` of an absent id is a no-op.

**StatusUpdated (same id, new `editedAt`).** The cache stores the UPDATED event as a separate entry with its own `sequence`. The client's `MessageQueue.update()` is already idempotent (replaces the entry with the same id, adds a `cachebreaker`). If an older `editedAt` arrives after a newer one during an HTTP catch-up, it would normally stomp the newer one. **Mitigation**: `MessageQueue.update()` is extended to compare `editedAt` (string ISO-8601, lexicographic compare is correct) and only replace if `editedAt >= current.editedAt`. This is a minor, forward-compatible behaviour change covered by a unit test.

**StatusDeleted (tombstone).** Separate cache entry with its own `sequence`. Client `dequeue` by id; absent id → no-op.

**Cache eviction older than cursor.** The response sets `gap: true`. The UI surfaces a one-time warning. No duplication and no silent data loss — the client explicitly knows it missed something.

**Transport handoff windows.**

- WS → fallback: the poll starts with `since` = the last `sequence` the client saw on STOMP (tracked in `DeliveryCursor`). If the client saw no events over STOMP for this tag, `since` is omitted and the full buffer is returned, then deduped by id client-side.
- Fallback → WS: the WS subscription is re-established *before* the poll timer stops. Between "WS open" and "poll stopped" both transports may deliver. `MessageQueue` dedups. No loss, no duplicates in the UI.

## 6. Files / modules to touch

### Backend (new)

- `de.seism0saurus.glacier.webservice.cache.CacheEntry` — immutable record `(EventType type, String statusId, String url, String editedAt, long sequence)`.
- `de.seism0saurus.glacier.webservice.cache.PerTagRing` — package-private, holds the bounded `ArrayDeque<CacheEntry>`, a `long nextSequence`, a `ReentrantLock`; methods `append(CacheEntry)`, `snapshotSince(long since): Snapshot` where `Snapshot = (List<CacheEntry> events, long nextSince, boolean gap)`.
- `de.seism0saurus.glacier.webservice.cache.MessageCache` (interface) / `MessageCacheImpl` (`@Service`, singleton):
  - `void recordAndPublish(String principal, String hashtag, CacheEntry partial)` — allocates sequence, appends, publishes to STOMP.
  - `Snapshot snapshot(String principal, String hashtag, Long since)` — for the HTTP controller.
  - `void provisionHashtag(String principal, String hashtag)` — called eagerly by `SubscriptionManagerImpl.subscribeToHashtag`.
  - `void evictHashtag(String principal, String hashtag)`.
  - `void evictPrincipal(String principal)`.
- `de.seism0saurus.glacier.webservice.FallbackController` — `@RestController`, one `@GetMapping("/rest/messages")` method, reads `@CookieValue("wallId") String wallId`, delegates to `MessageCache.snapshot`, returns a response DTO.
- `de.seism0saurus.glacier.webservice.cache.FallbackResponse` — DTO: `{hashtag, nextSince, gap, events}`.
- `de.seism0saurus.glacier.webservice.cache.FallbackRateLimiter` — per-principal token bucket (use a tiny hand-rolled `Map<String, TokenBucket>` keyed by `wallId`; adding bucket4j is out of scope at homelab scale). Configurable: e.g. 30 req/min per wallId. Response on exceed: 429 with `Retry-After`.
- `de.seism0saurus.glacier.webservice.cache.EventType` (enum) — `CREATED, UPDATED, DELETED`.

### Backend (modified)

- `StompCallback.java` — all three `process*Event` methods and `sendMessage` stop calling `simpMessagingTemplate.convertAndSend` directly; instead they build a `CacheEntry` and call `messageCache.recordAndPublish(principal, hashtag, entry)`. The STOMP publish moves *into* `MessageCacheImpl.recordAndPublish`. `SimpMessagingTemplate` moves from `StompCallback` into `MessageCacheImpl`.
- `SubscriptionManagerImpl.java` — constructor now also takes `MessageCache`. `subscribeToHashtag` calls `messageCache.provisionHashtag(principal, hashtag)` before submitting the virtual thread; `terminateSubscription` calls `messageCache.evictHashtag(...)`; `terminateAllSubscriptions` calls `messageCache.evictPrincipal(...)`. Pass `MessageCache` through to the new `StompCallback` constructor in place of `SimpMessagingTemplate`.
- `SubscriptionListener.java` — no direct change needed; the cache cleanup is driven through `SubscriptionManager.terminateAllSubscriptions` which it already calls on timeout. Add a unit test that asserts the cache eviction happens on timeout.
- `application.properties` — new keys:
  - `glacier.cache.size=20`
  - `glacier.fallback.ratelimit.perMinute=30`
  - `glacier.fallback.enabled=true` (kill switch; if false the controller returns 404 — useful for security review and quick disable)
- `GlacierApplication.java` CORS — no change (the existing `/rest/*` mapping already covers `/rest/messages`).

### Frontend (new)

- `frontend/src/app/fallback/fallback.service.ts` — owns the poll timer, the `DeliveryCursor` persistence in `localStorage`, the switch logic, exposes `transportMode$: Observable<TransportMode>`. Depends on `HttpClient` and `SubscriptionService` (for the shared `MessageQueue` handles — see next bullet).
- `frontend/src/app/fallback/transport-mode.ts` — enum/type.
- `frontend/src/app/connection-status/connection-status.component.{ts,html,css}` — small Material badge/icon. Three visual states: green "Live" for `WEBSOCKET`, amber "Fallback-Modus" for `FALLBACK`, spinner "Verbinde…" for `PROBING`. Rendered in the header, top-right.

### Frontend (modified)

- `SubscriptionService` — extract `MessageQueue` (and its observable `messageSubject$`) into a shareable shape so `FallbackService` can push into the same queue. Keep this as *extension* not rewrite: expose a new `ingestCacheEntries(hashtag, entries)` method that applies CREATED/UPDATED/DELETED in-order.
- `MessageQueue.update()` — add the `editedAt` staleness guard described in Section 5.
- `rx-stomp.config.ts` / `rx-stomp.factory.ts` — tune `reconnectDelay` and expose `connectionState$` / disconnect signal to `FallbackService`. The trigger logic (1/4/16 s) lives in `FallbackService`, not in RxStomp config.
- `HeaderComponent` — embed `<app-connection-status></app-connection-status>`.

## 7. TDD test plan

Binding per CLAUDE.md; every new class, every modified method. **No Jacoco threshold lowering.** Instruction ≥ 0.45 / branch ≥ 0.35.

### Unit — Java (Surefire `*Test.java`)

- `PerTagRingTest`
  - Given empty ring, when `append` 21 entries, then size stays 20 and sequences are 1..21 strictly increasing.
  - Given empty ring, when `snapshotSince(null)`, then returns all, `nextSince` = last seq, `gap=false`.
  - Given ring with seqs 30..49, when `snapshotSince(40)`, then returns 41..49, `gap=false`.
  - Given ring with seqs 30..49, when `snapshotSince(5)` (older than oldest-1=29), then returns full ring, `gap=true`.
  - Given ring with seqs 30..49, when `snapshotSince(49)`, then returns empty, `gap=false`.
  - Concurrency: 1000 concurrent appends from 10 threads + 1000 concurrent snapshots; asserts sequences are strictly increasing and snapshots are internally consistent (i.e. no half-written entries). This is the critical correctness test for the lock.
- `MessageCacheImplTest`
  - `recordAndPublish` calls `SimpMessagingTemplate.convertAndSend` **exactly once** for a CREATED event, with the correct destination `/topic/hashtags/{p}/{h}/creation`.
  - Same for UPDATED → `/modification` and DELETED → `/deletion`.
  - `recordAndPublish` increments `sequence` across consecutive calls.
  - `snapshot` returns a defensive copy (mutating returned list does not affect subsequent snapshots).
  - `provisionHashtag` then `evictHashtag` round-trip; snapshot after evict returns empty cache or 400-equivalent marker.
  - `evictPrincipal` removes all hashtags for that principal but leaves others untouched.
- `StompCallbackTest` (modifications)
  - All existing assertions updated: `verify(messageCache).recordAndPublish(...)` instead of `verify(simpMessagingTemplate).convertAndSend(...)`, with equivalent argument captors.
  - Deletion via `GenericMessage` path also routes through `recordAndPublish`.
  - No direct `SimpMessagingTemplate` interaction remains on `StompCallback`.
- `SubscriptionManagerImplTest` (modifications)
  - `subscribeToHashtag` calls `messageCache.provisionHashtag(p, h)` **before** submitting the virtual thread.
  - `terminateSubscription` calls `messageCache.evictHashtag(p, h)`.
  - `terminateAllSubscriptions` calls `messageCache.evictPrincipal(p)`.
- `FallbackControllerTest` (pure MockMvc unit, no Spring context)
  - Missing `wallId` cookie → 401.
  - Unknown hashtag for this principal → 400.
  - Happy path with `since=` returns the expected DTO shape.
  - Rate limiter hit → 429 with `Retry-After`.
  - Feature flag disabled (`glacier.fallback.enabled=false`) → 404.
- `FallbackRateLimiterTest`
  - Within the per-minute quota → allows.
  - At the limit → denies, exposes a `Retry-After` seconds value.
  - Separate buckets per `wallId` do not interfere.

### Unit — Angular (Karma/Jasmine `*.spec.ts`)

- `fallback.service.spec.ts`
  - After 3 consecutive simulated WS failures, TransportMode transitions to `FALLBACK`.
  - In FALLBACK, `HttpClient.get('/rest/messages?...')` is called every 5 s per subscribed hashtag.
  - `gap: true` response emits a gap signal on a dedicated observable.
  - Probe back-off matches 10/20/40/80/160/300/300 s (use fake async / `jasmine.clock()`).
  - On successful probe, TransportMode flips to `WEBSOCKET` and the poll timer is cleared **after** WS `OPEN`.
  - 429 responses do **not** trigger a probe or flip back to WS.
- `subscription.service.spec.ts` (extensions)
  - `ingestCacheEntries` applied with a CREATED event enqueues it; a duplicate id enqueue is a no-op.
  - UPDATED with older `editedAt` than current does **not** replace.
  - DELETED removes the entry; DELETED for unknown id is a silent no-op.
- `connection-status.component.spec.ts`
  - Each of WEBSOCKET / FALLBACK / PROBING renders the correct icon/text (`data-testid="connection-status"`) — this is the hook the e2e tests use.

### Integration — Java (Failsafe `*IT.java`)

- `FallbackControllerIT`
  - Full Spring MVC context, `wiremock-spring-boot` stubs the embed HEAD check (so we reuse the real `isLoadable` path when the cache population is triggered from a Bigbone fake).
  - Simulates a sequence of `StatusCreated/Updated/Deleted` events hitting `StompCallback` (construct one against the real `MessageCache` bean), then asserts that `GET /rest/messages` (using a plain `TestRestTemplate` with a `wallId` cookie) returns the expected DTO.
  - Verifies atomicity: a STOMP subscriber and an HTTP poller see the same set of events by id.
  - Verifies auth: request without `wallId` cookie → 401.
  - Verifies rate limit: 31st request in 60 s → 429.
- `MessageCacheSubscriptionLifecycleIT`
  - End-to-end lifecycle: subscribe → events flow → terminate subscription → HTTP for that hashtag returns 400 (hashtag no longer provisioned for principal).
  - Disconnect + 5-min timer expiry (use the existing `glacier.timeouts.client_reconnect` override) → cache is evicted for that principal.

### E2E — Playwright (real dockerized Mastodon)

- `frontend/e2e/workflows/fallback.spec.ts` (new):
  1. **Happy WS path** — a single real toot (`createTextToot`) surfaces on the wall; `data-testid="connection-status"` shows `WEBSOCKET`. (Basically the existing happy path plus the indicator assertion.)
  2. **WS breaks → fallback takes over** — induce WS failure (see below), wait for indicator to show `FALLBACK`, post a toot via `mastodon-client.ts`, assert the toot appears within the poll window; wallet-shaped, assert it is a **single** `app-toot` (no duplicates).
  3. **WS recovery** — un-break WS, wait for indicator to return to `WEBSOCKET`, post another toot, assert it appears and there are still no duplicates.
  4. **Cache gap** — with WS broken, produce more than 20 toots via `mastodon-client.ts`, then open a fresh browser context/tab (fresh `wallId`) so `since` starts from 0, subscribe, assert the first fallback response is accepted, the `gap` warning snackbar appears exactly once, and the wall still shows exactly 20 `app-toot` elements.
  5. **Indicator visual regression** — screenshot the header in all three TransportMode states (pins a contract the ux-ui-designer will own).

**Inducing WS failure in docker-compose.** The most controllable option is a **traefik middleware that returns 503 on `/websocket`** for the `glacier` service, toggled via a file written into the mounted `dynamic.yml`. Playwright's helper writes the middleware, waits for it to apply (~2 s), and removes it on "recovery". Alternatives rejected: `iptables` in a helper container is harder to sync; closing the Spring endpoint via feature flag would bypass the thing we are actually testing (it's the *network* path that should fail, not the server). See `ADR-01` consequences.

**Jacoco.** The three new backend classes (`PerTagRing`, `MessageCacheImpl`, `FallbackController`) are exercised by unit + integration tests covering every non-trivial branch (gap signal, empty snapshot, rate limit, disabled flag, eviction, sequence increment). Rough estimate: new code ~400 LOC backend, covered branches ≥ 35 % trivially because every branch has at least one test above. Target remains the existing bundle-wide ≥ 0.45 / ≥ 0.35.

## 8. ADRs

### ADR-01: HTTP short-poll as the fallback transport

**Decision**: short-poll with a cursor, single `GET /rest/messages` endpoint.
**Rationale**: the only reason the fallback exists is firewalls/proxies that break WS. Short-poll degrades to ordinary HTTP and is the most likely path to actually work in that environment. Its exactly-once contract is the simplest.
**Alternatives considered**: long-poll (rejected: same idle-timeout class of failure as WS, and holds servlet threads); SSE (rejected: same buffering-proxy failure mode; adds a third transport).
**Consequences**: slightly higher latency (bounded by 5 s poll interval) and slightly more idle HTTP traffic per wall in fallback mode. Acceptable because fallback is explicitly the degraded mode. Playwright E2E needs a way to break WS without breaking HTTP — traefik middleware is the chosen mechanism.

### ADR-02: Cache granularity is per `(principal, hashtag)`, size 20

**Decision**: one ring per `(principal, hashtag)`, bounded to 20 entries.
**Rationale**: matches the STOMP destination tree and makes the `since` cursor one-dimensional. Per-principal caching would cross-evict unrelated hashtags and create a many-to-one cursor problem. Size 20 matches the frontend `MessageQueue` capacity the user explicitly called out.
**Alternatives considered**: per-principal with 20-total (cross-eviction); per-principal with larger size (wasted memory, still has the dimensionality problem); unbounded with TTL (memory risk).
**Consequences**: memory is `O(principals × hashtags × 20)`. At homelab scale this is trivial. If a principal subscribes to dozens of hashtags this grows linearly; the user can tune `glacier.cache.size` downward if needed.

### ADR-03: Fallback trigger is 1 s / 4 s / 16 s then flip

**Decision**: three reconnect attempts with 1 s / 4 s / 16 s waits; flip to fallback if attempt 3 has not reached OPEN within a further 16 s.
**Rationale**: "increasing timeout" per the user. Total time-to-fallback ≈ 37 s, which is tolerable in a UX sense and also long enough that a momentary WS blip doesn't trip the indicator.
**Alternatives considered**: tighter schedule (e.g. 500 ms/1 s/2 s) — too twitchy, would flap the indicator over a typical mobile-network hiccup; looser schedule (e.g. 5 s/15 s/45 s) — users would stare at a dead wall for over a minute before getting any feedback.
**Consequences**: the current `rx-stomp.config.ts` value of `reconnectDelay: 500` is superseded by `FallbackService` control — either remove it or set it to `0` (disabled) so RxStomp doesn't also reconnect on its own schedule.

### ADR-04: Deletion events are stored, not applied retroactively to the ring

**Decision**: a `StatusDeleted` event is appended to the cache as its own `CacheEntry(type=DELETED)` with its own `sequence`. The matching `CREATED` entry is not retroactively removed from the ring.
**Rationale**: a client whose cursor is older than the CREATED event must observe the create-then-delete pair to stay consistent with the STOMP stream (so its `MessageQueue` transient state matches the WS case). Retroactively removing would hide state transitions.
**Alternatives considered**: tombstone-with-erase (rejected — breaks client determinism); coalesce CREATED+DELETED in the ring (rejected — same reason).
**Consequences**: the ring can contain correlated pairs and thus a 20-entry ring can represent fewer than 20 distinct toots. Acceptable; matches the STOMP stream exactly.

### ADR-05: Cache eviction is coordinated through `SubscriptionManager`, not `SubscriptionListener`

**Decision**: `MessageCache.evictHashtag/evictPrincipal` is called from `SubscriptionManagerImpl.terminateSubscription/terminateAllSubscriptions`, which is *already* the method `SubscriptionListener` calls on the 5-minute disconnect timeout.
**Rationale**: single point of truth for "a subscription is going away"; avoids double teardown or split-brain where the Bigbone subscription is dead but the cache lives on (or vice versa).
**Alternatives considered**: wiring `SubscriptionListener` directly to `MessageCache` — rejected because it duplicates authority and requires `SubscriptionListener` to know the set of hashtags for a principal.
**Consequences**: during the 5-min grace window, the cache stays populated; a fallback client that hangs on through the grace window sees its toots. Exactly the desired behaviour.

## 9. Risks and open items

**For the secure-feature-planner:**

- **New HTTP surface `GET /rest/messages`**. Auth is the existing `wallId` cookie. Consider: (a) is `wallId` too guessable if treated as a bearer token? It's a UUIDv4, so cryptographically fine, but the cookie is not currently marked `Secure`/`HttpOnly`/`SameSite` (see `InformationController#readCookie`). The fallback endpoint makes this cookie a bigger target because it now returns message content, not just an ack. **Recommend**: tighten the cookie attributes in the same PR. The secure-feature-planner should call this explicitly.
- **Rate limiting** is in-process and per-`wallId`. An attacker who can generate `wallId` values at will (they can — the endpoint is anonymous) can spread load across cookies. Recommend an additional per-remote-IP limiter, or rely on an upstream reverse proxy. Secure-feature-planner decision.
- **Cache size × principals** is a memory DoS vector at the edge: an attacker who discovers many hashtags per wallId, across many wallIds, can grow memory. At homelab scale low-concern, but document a hard cap.
- **CORS**. `/rest/*` currently allows `http://localhost:4200` only. This is enforced on preflight only — the browser. A direct HTTP tool bypasses it. That's fine for confidentiality because `wallId` is the real gate, but the secure-feature-planner should confirm no CSRF risk on a GET (it's idempotent — likely fine).
- **Cookie exposure to subframes / iframes**. The toots are already rendered as iframes to third-party Mastodon instances; those iframes do not see our `/rest/messages` calls (same-origin), so no new exposure. Worth confirming.
- **Feature flag `glacier.fallback.enabled`** is a deliberate kill switch — lets operators disable the new surface without a redeploy if a security issue is found.
- **Supply chain**: no new backend dependencies are proposed (no bucket4j, no Guava cache). Frontend adds no new npm dependencies.

**For the ux-ui-designer:**

- Connection-status indicator UX: three states, placement (header top-right proposed), colour palette compatible with existing Material theme, localisation strings (German: "Live" / "Fallback-Modus" / "Verbinde…"), accessibility (aria-live region so screen readers announce state changes).
- Gap warning: snackbar (dismissable) vs. persistent banner vs. inline notice in the wall — designer's call; I've only placeholder'd it.
- Behaviour when offline entirely (both WS and HTTP fail): out of scope for this feature but UX should at least not show a stale `WEBSOCKET` badge. Suggestion: add a fourth `OFFLINE` state (detected when 3 consecutive polls also fail) — flag for the designer to confirm.

**Open items for the user to confirm (non-blocking, default answers picked):**

- Poll interval in fallback: **5 s** chosen. Fine?
- Rate limit: **30 req/min per wallId** chosen (≈ 6 hashtags × 5 s). Fine?
- Feature flag default-on (`glacier.fallback.enabled=true`): confirm.

No `⚡ CONFLICT` items — the user-locked constraints are all reconcilable with this design.

---

## Summary for downstream agents

- **secure-feature-planner** — critical review items are in Section 9 (cookie hardening, per-IP rate limit, CORS/CSRF on GET, feature flag). The new HTTP endpoint shape is in Section 2.
- **ux-ui-designer** — the indicator component, the gap warning, and the three/four TransportMode visual states are the UI surface; see Sections 6 (frontend additions) and 9 (UX open items).

This plan is ready for peer review by the `secure-feature-planner` and (since there is a visible UI change) the `ux-ui-designer`.
