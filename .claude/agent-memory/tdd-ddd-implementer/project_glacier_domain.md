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
