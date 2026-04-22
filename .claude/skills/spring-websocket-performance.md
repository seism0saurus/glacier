---
name: spring-websocket-performance
owner: "@seism0saurus"
description: Configure Spring WebSocket/STOMP for reliability, backpressure, and performance in the Glacier backend's live-streaming pipeline. TRIGGER when editing WebSocketConfiguration, WebSocketHandler, STOMP broker config, session buffer sizes, heartbeat settings, subscription lifecycle (SubscriptionManagerImpl), SessionDisconnectEvent/SessionSubscribeEvent handlers, or when the user mentions WebSocket, STOMP, SockJS, broker, heartbeat, backpressure, session buffer, subscribe, unsubscribe, live streaming, fallback. SKIP for HTTP controller work, REST-only changes, frontend WebSocket client code, or CORS-only concerns (that's in spring-security-hardening).
---

# WebSocket/STOMP Performance for Glacier

Glacier's core feature is live Mastodon streaming over WebSocket with HTTP-polling fallback and a killswitch mode. Reliability here is directly visible to users as the Live / Fallback / Killswitch / Offline status in the frontend.

Existing touch-points:
- `src/main/java/de/seism0saurus/glacier/webservice/messaging/WebSocketConfiguration.java`
- `src/main/java/de/seism0saurus/glacier/mastodon/SubscriptionManagerImpl.java`
- `glacier.fallback.enabled` / `glacier.fallback.ratelimit.*` in `application.properties`

## Broker choice — in-memory vs external

Default `enableSimpleBroker` (in-memory) is fine for a single Glacier instance. For multi-instance deployments you need `enableStompBrokerRelay` to an external STOMP broker (RabbitMQ, ActiveMQ Artemis), otherwise events published on instance A are not delivered to subscribers on instance B.

**Scaling-trigger decision**: revisit the broker choice if Glacier is deployed with more than one backend instance behind the same Traefik.

## Heartbeats — non-negotiable

Without heartbeats, half-closed connections (laptop closed, network blip, client crashed) stay counted as "connected" indefinitely, consuming memory and emitting events into the void.

```java
@Override
public void configureMessageBroker(MessageBrokerRegistry registry) {
  ThreadPoolTaskScheduler ts = new ThreadPoolTaskScheduler();
  ts.setPoolSize(1);
  ts.setThreadNamePrefix("ws-heartbeat-");
  ts.initialize();

  registry.enableSimpleBroker("/topic", "/queue")
      .setHeartbeatValue(new long[] { 10_000, 10_000 })  // server-send / client-send (ms)
      .setTaskScheduler(ts);

  registry.setApplicationDestinationPrefixes("/app");
}
```

Typical values: 10–30 seconds both directions. Below 10s chatters too much; above 30s delays zombie-detection.

## Origin allowlist — security-critical

WebSocket handshake is **not** bound by same-origin policy the way HTTP CORS is. An explicit origin allowlist is the backstop:

```java
registry.addEndpoint("/api/ws")
    .setAllowedOrigins(
        "https://glacier.example.com",
        "https://glacier-staging.example.com"
    )
    // NEVER setAllowedOriginPatterns("*") in production
    .withSockJS();
```

See `spring-security-hardening` skill for CORS/origin treatment across the whole backend.

## Backpressure — always bound session buffers

One slow client must not stall the broker for everyone. Bound per-session buffer and per-message size:

```java
@Override
public void configureWebSocketTransport(WebSocketTransportRegistration registration) {
  registration
      .setSendBufferSizeLimit(512 * 1024)      // 512 KB per session
      .setSendTimeLimit(10_000)                // 10s to drain buffer
      .setMessageSizeLimit(128 * 1024)         // 128 KB per inbound message
      .setTimeToFirstMessage(30_000);          // drop silent clients after 30s
}
```

When the buffer fills or time limit elapses, the session is closed. The client reconnects (or Glacier's fallback kicks in if WebSocket remains unreachable) — better than broker-wide stalls.

## Handler threading — blocking IO is OK **with virtual threads enabled**

`spring.threads.virtual.enabled=true` makes STOMP inbound channels run on virtual threads, so blocking IO inside `@MessageMapping` handlers is acceptable. Without virtual threads, you'd have to offload to a separate executor.

Verify: when the VT flag is present, `clientInboundChannel().taskExecutor(...)` should not override with a fixed-size pool. If it does, virtual-thread benefits are lost for the inbound path.

See `spring-virtual-threads` skill for the full context on VT usage.

## Subscription lifecycle — listen for BOTH disconnect and unsubscribe

Glacier tracks subscriptions explicitly in `SubscriptionManagerImpl` because cache eviction, rate-limit accounting, and fallback routing hook in.

Required event handlers:
```java
@EventListener
public void onSubscribe(SessionSubscribeEvent event) { ... }

@EventListener
public void onUnsubscribe(SessionUnsubscribeEvent event) { ... }

@EventListener
public void onDisconnect(SessionDisconnectEvent event) { ... }
```

Why both: **some clients disconnect without unsubscribing** (abrupt close, network drop), others unsubscribe without disconnecting (switch topic). Handle both to avoid state leaks.

## Per-principal resource limits — already enforced, keep them

Glacier's current caps:
```properties
glacier.cache.maxHashtagsPerPrincipal=${GLACIER_CACHE_MAX_HASHTAGS_PER_PRINCIPAL:10}
glacier.cache.maxPrincipals=${GLACIER_CACHE_MAX_PRINCIPALS:10000}
```

When modifying subscription logic:
- Enforce `maxHashtagsPerPrincipal` at subscribe time → reject with a WebSocket error frame if exceeded.
- Enforce `maxPrincipals` at connect time → reject connect.
- Never silently accept and overflow.

## Observability — the metrics to add

Track these in `MeterRegistry` (see `spring-observability-micrometer` skill):
- `glacier.websocket.sessions` — Gauge, `SessionManager::activeCount`
- `glacier.websocket.connect.total` — Counter, tagged `outcome` (success/rejected-rate-limit/rejected-max-principals)
- `glacier.websocket.disconnect.total` — Counter, tagged `reason` (client/server/timeout/buffer-full)
- `glacier.websocket.send.failed.total` — Counter, tagged `reason`
- `glacier.websocket.subscribe.total` — Counter, tagged `outcome`

Without these metrics, reliability regressions stay invisible until users complain.

## Fallback-mode coordination

Three modes exist: live (WS), fallback (polling), killswitch (fallback disabled). The backend announces capability via `glacier.fallback.enabled`; the frontend renders the matching UI. When changing WS reliability logic:
- Test all three transitions (live → fallback when WS drops, fallback → live when WS recovers, killswitch → offline when killswitch active).
- Ensure `SessionDisconnectEvent` triggers cleanup so fallback paths don't double-register subscriptions.

Glacier's Playwright tests include dedicated killswitch/insecure profiles — regression tests should run against at least chromium + killswitch.

## Reconnect-storm protection

When a Glacier instance restarts, all previously-connected clients try to reconnect at the same time. Without protection, the new instance gets hammered.

Mitigations:
- Frontend: randomized reconnect backoff (jitter).
- Backend: `glacier.fallback.ratelimit.perMinutePerIp=120` — already rate-limits fallback path per IP. Ensure the WS-connect path has equivalent protection (similar IP-based counter, possibly via `FallbackRateLimiter` extended or a parallel class).

## Message ordering and deduplication

Glacier's cache (`MessageCacheImpl` + `PerTagRing`) ensures per-hashtag ordering. When modifying the publish path, preserve:
- Monotonic ordering per topic (re-send after reconnect picks up from last-seen ID).
- Deduplication on replay (client may see a message via cache replay AND live stream during a reconnect overlap).

The `gap.snackbar.message` i18n key exists for exactly this: informing the user that some older toots may be missing after a gap — don't silently paper over gaps.

## What Claude gets wrong without this skill

- Uses `setAllowedOriginPatterns("*")` → WS-cross-site attack surface (CSRF-over-WebSocket).
- Forgets heartbeats → zombie sessions accumulate forever, memory grows.
- Leaves send buffer unlimited → one slow client OOMs the broker.
- Adds blocking IO in `@MessageMapping` under a fixed thread pool (VTs not enabled) → handler pool exhausts under load.
- Cleans up on `SessionUnsubscribeEvent` only, forgets `SessionDisconnectEvent` → memory leak on abrupt disconnect.
- Picks `enableSimpleBroker` and horizontally scales → cross-instance events don't flow.
- Doesn't enforce `maxHashtagsPerPrincipal` at subscribe time → cache overflow.

## References
- WebSocket config: `src/main/java/de/seism0saurus/glacier/webservice/messaging/WebSocketConfiguration.java`
- Subscription lifecycle: `src/main/java/de/seism0saurus/glacier/mastodon/SubscriptionManagerImpl.java`
- Cache: `src/main/java/de/seism0saurus/glacier/webservice/cache/` (`MessageCacheImpl`, `PerTagRing`)
- Limits: `glacier.cache.*`, `glacier.fallback.*` in `application.properties`
- Frontend counterpart: `frontend/src/app/connection-status/` (live/fallback/killswitch UI)
- Playwright fallback tests: `frontend/e2e/fallback-*.spec.ts`
