---
name: Glacier domain architecture
description: MessageCache/SubscriptionManager/SubscriptionListener eviction chain, killswitch gating, ADR-05 memory-reclamation contract
type: project
---

## MessageCache eviction chain (ADR-05)

Three eviction entry points:
1. `SubscriptionManagerImpl.terminateSubscription` → `messageCache.evictHashtag(principal, hashtag)`
2. `SubscriptionManagerImpl.terminateAllSubscriptions` → `messageCache.evictPrincipal(principal)` (now unconditional — no early return for empty subscription map)
3. `SubscriptionListener.onDisconnectEvent` timer → `messageCache.evictPrincipal(principal)` (direct, idempotent fallback for fallback-only clients)

**Why:** `terminateAllSubscriptions` used to return early if the principal had no streaming subscriptions. Fallback-mode clients that provisioned the cache directly (without WebSocket) would never be evicted. The SubscriptionListener timer now calls `evictPrincipal` directly as a belt-and-suspenders guarantee.

**How to apply:** When modifying the disconnect/eviction chain, ensure all three entry points remain consistent. `evictPrincipal` is idempotent (ConcurrentHashMap.remove of non-existent key is a no-op).

## Kill-switch gating (D-11, glacier.fallback.enabled)

`MessageCacheImpl` has a `fallbackEnabled` boolean field (6th constructor arg). When false:
- `recordThenPublish`: skips ring.append(), STILL calls `simpMessagingTemplate.convertAndSend` for live WS clients. Returns null.
- `provisionHashtag`: returns immediately (no-op).

**Why:** Killswitch operators get 404 on /rest/messages (no cache), but live WebSocket clients must still see toots. STOMP fan-out is never gated.

## SubscriptionListener constructor (peer-lane change)

Production `SubscriptionListener` now takes 3 args: `(SubscriptionManager, MessageCache, long timeout)`. The `@Value("${glacier.timeouts.client_reconnect}")` annotation is on the `long timeout` parameter. Tests that need a different timeout must use the 3-arg form.

## Micrometer gauges (D-11)

`MessageCacheImpl` registers:
- `glacier.cache.principals.count` (Gauge) — size of outer ConcurrentHashMap
- `glacier.cache.entries.total` (Gauge) — total PerTagRing count across all principals

These require `spring-boot-starter-actuator` and `micrometer-core` in pom.xml.

**How to apply:** When writing tests with multiple `MessageCacheImpl` instances sharing the same `MeterRegistry`, use fresh `SimpleMeterRegistry` instances per test or you'll get duplicate-gauge registration errors.

## Frontend WallMessage / prune-on-removal domain (2026-04-24)

`WallMessage` replaces `SafeMessage` in `MessageQueue`. Carries `hashtags: string[]` for per-toot membership. Stored in localStorage as `{ v: 2, items: WallMessage[] }`. Key domain concepts:

- `normalizeHashtag(s)` — canonical normalisation; strip '#', lowercase, trim. Lives in `frontend/src/app/util/hashtag.ts`. All equality checks route through here (SR-PRUNE-07).
- `safeSetItem(key, value)` — quota-safe localStorage write. On QuotaExceededError: drop oldest half, retry once, log. Lives in `frontend/src/app/util/safe-storage.ts`.
- `recentlyTerminated: Map<normalised, expiresAt>` — lives on `SubscriptionService`. Seeded at step 2 of the 4-step ack handler. Eviction sweep on each seed. Uses `Date.now()` (not `performance.now()`). TTL from `environment.prune.guardTtlMs` (default 10 000 ms).
- 4-step ack handler: (1) RxStomp unsubscribe, (2) seed guard, (3) pruneByHashtag, (4) wallAnnouncerService.announce — all synchronous, same tick.
- `WallAnnouncerService` — 250 ms debounce, cancelAll takes precedence over prune count. Writes to `role="status" aria-live="polite"` live region. Lives in `frontend/src/app/services/wall-announcer.service.ts`.
- `SubscriptionService` constructor now requires `WallAnnouncerService` as second arg.
- German is compile-time default locale; English catalog is `messages.en.json` loaded at runtime for `navigator.language.startsWith('en')`. No `messages.de.json` file exists — German strings are embedded in templates via `$localize`.
