# Decision Record: WebSocket HTTP Fallback — Planning

Date: 2026-04-21
Phase: Planning
Agents: ddd-tdd-architect, secure-feature-planner, ux-ui-designer
Status: Accepted

## Summary

Glacier gains an HTTP short-poll fallback transport so users on networks that block STOMP/WebSocket continue to receive toots. A server-side per-`(principal, hashtag)` ring buffer is populated atomically with every STOMP publish in `StompCallback`; a new `GET /rest/messages` endpoint serves it to the client using a cursor. The client owns a three-state `TransportMode` machine (`WEBSOCKET | PROBING | FALLBACK | OFFLINE | KILLSWITCHED | INSECURE`), surfaced as a header-right chip-pill indicator with `MatSnackBar` gap warnings. Exactly-once delivery is preserved end-to-end via Mastodon `statusId` dedup. The feature includes `wallId` cookie hardening (`HttpOnly` + `Secure` + `SameSite=Lax`), per-wallId and per-IP rate limiting, `Cache-Control: no-store` headers, memory DoS caps, and the introduction of `@angular/localize` runtime i18n with `de` as the default locale.

## User feature statement (verbatim)

> I want a fallback, if the websockets with stomp don't work as intended because of strange firewall configuraitons. Cache all relevant mastodon messages and provide them via http native fallback. Ensure that each message is only delivered once. It does not matter, if the messages was delivered via websocket or the fallback. I want a small indicator on the frontend, that shows if websockets are working or if the fallback is active. The system should try to restore the default websocket functionality after some time.

## User-locked constraints (Step 0)

1. Dedup key = Mastodon status ID. Client dedupes across both transports; server cache is the fallback source.
2. Bounded ring buffer with 20-item capacity mirroring the existing frontend `MessageQueue`.
3. 3 failed reconnect attempts with increasing timeout before switching to fallback.
4. Exponential back-off on WS recovery probes, capped at 5 minutes.
5. `wallId` cookie auth on the fallback endpoint + per-wallId rate limiting.
6. Transport choice delegated to architect.

## Key decisions

### D-01 — HTTP short-poll is the fallback transport
**Decision**: single `GET /rest/messages?hashtag={tag}&since={seq}` endpoint. 5 s poll interval per subscribed hashtag while in `FALLBACK` mode. 204 when cursor is current; 200 with `{hashtag, nextSince, gap, events[]}` otherwise. `since < oldestSequence - 1` → full buffer + `gap:true`.
**Rationale**: degrades to ordinary HTTP (the target class of networks the user described); simplest dedup/cursor contract; no long-held servlet threads; trivially rate-limitable.
**Alternatives considered**: long-poll (same idle-timeout failure mode as WS; holds threads); SSE (same proxy-buffering failure class; adds a third transport).
**Source**: architect §2; ADR-01.

### D-02 — Cache granularity per `(principal, hashtag)`, 20 entries, FIFO
**Decision**: `MessageCache` keyed on `(principal, hashtag)` with a 20-entry `PerTagRing` per key. `ConcurrentHashMap` outer, `ArrayDeque` + `ReentrantLock` inner. Snapshot reads take a defensive copy under the lock.
**Rationale**: matches the STOMP destination tree (`/topic/hashtags/{p}/{h}/…`); one-dimensional cursor per hashtag; matches the user-locked 20.
**Alternatives considered**: per-principal (cross-hashtag eviction, 2-D cursor); unbounded + TTL (memory risk).
**Source**: architect §3; ADR-02.

### D-03 — Atomic `recordThenPublish` in the cache service, not in `StompCallback`
**Decision**: `StompCallback` stops calling `SimpMessagingTemplate.convertAndSend` directly. It calls `MessageCache.recordThenPublish(principal, hashtag, partialEntry)`, which under a per-tag lock: allocates next `sequence`, inserts into the ring, publishes to STOMP, releases. Publish failure is logged at WARN with `{principal-hash, hashtag, sequence, statusId, eventType}` and a counter `glacier.fallback.publish.failures`; the cache entry is **not** rolled back — that is precisely the fallback scenario.
**Rationale**: exactly-once across transports requires a single insert point that both transports read from.
**Name rationale**: "Then" (not "And") communicates that the two steps are sequential, not transactional across subsystems. [security review]
**Source**: architect §1, §5; security planner SR-2.4, T-10; ADR-04 consequences.

### D-04 — Fallback trigger: 1 s / 4 s / 16 s, then flip
**Decision**: `FallbackService` owns reconnect scheduling. RxStomp's `reconnectDelay` is set to `0` (disabled). Attempts 1/2/3 at 1 s / 4 s / 16 s delay respectively. If attempt 3 has not reached `OPEN` within a further 16 s, `transportMode$` emits `FALLBACK`.
**Rationale**: "increasing timeout" as user-locked; absorbs mobile hiccups without flapping; ~37 s total to a visible state change is prompt feedback without dead-wall panic.
**Alternatives considered**: 0.5/1/2 s (flaps); 5/15/45 s (too slow).
**Source**: architect §4; ADR-03.

### D-05 — Recovery: exponential back-off 10/20/40/80/160/300/300 s (5-min cap)
**Decision**: while in fallback, schedule WS probes on the sequence above. Probe = instantiate a fresh RxStomp, activate, wait ≤10 s for `connectionState$ = OPEN`. On success: emit `WEBSOCKET` on `transportMode$` *immediately* (indicator flips), resubscribe hashtags, stop poll timer on first STOMP ack. On failure: deactivate, double the wait (capped at 300 s), re-enter poll-only.
**Rationale**: user-locked 5-min cap; flipping the indicator on WS OPEN (not after poll stops) prevents the user seeing "Fallback-Modus" while toots flow over WS; `statusId` dedup in `MessageQueue` absorbs the brief double-delivery window.
**Source**: architect §4 (revised in Round 2); UX §7.4 adaptation.

### D-06 — Client-side dedup keyed on `statusId` only
**Decision**: `SubscriptionService.MessageQueue` continues to dedupe by `id` (Mastodon status ID). `sequence` is server-internal and not load-bearing for security or correctness on the client. `DeliveryCursor` is `{hashtag → lastSeenSequence}` persisted in `localStorage`, parsed with `Number.parseInt` + clamped to `[0, Number.MAX_SAFE_INTEGER]`; corrupt/NaN → 0 (request full buffer).
**Rationale**: `statusId` is the stable aggregate identity; `sequence` resets to 0 on server restart, so a client must not rely on its monotonicity.
**Source**: architect §5; security planner SR-10.

### D-07 — `editedAt` normalised to UTC before compare
**Decision**: Server: `Instant.parse(payload.getEditedAt()).toString()` in `StompCallback.sendMessage` before constructing the `CacheEntry`. `DateTimeParseException` → WARN-and-drop the update, do not propagate (the ingestion thread must survive malformed fork input). Client: `MessageQueue.update()` compares `new Date(incoming.editedAt).toISOString() >= new Date(current.editedAt).toISOString()` — both operands are `YYYY-MM-DDTHH:mm:ss.sssZ`, so lex compare = chronological compare.
**Rationale**: lex string compare on ISO-8601 is only safe when both sides are UTC with `Z`; Mastodon forks can emit offset forms.
**Source**: security planner §4 clarification; architect §3 Round 2 implementation commitment.

### D-08 — Security posture: cookie-only, same-origin, `Cache-Control: no-store` (ADR-06)
**Decision**: `/rest/messages` authenticates via the `wallId` cookie only; rejects any principal passed in URL/body/non-Cookie header. Every response (2xx and 4xx) carries `Cache-Control: no-store`, `Pragma: no-cache`, `Vary: Cookie`, `X-Content-Type-Options: nosniff`, `Content-Security-Policy: default-src 'none'`, emitted by a new `FallbackSecurityHeadersFilter` (`jakarta.servlet.Filter`, `@Order(HIGHEST_PRECEDENCE)`, path-guarded on `/rest/messages`). CORS allowed-origins list is exactly `[http://localhost:4200, https://${glacier.domain}]`, `allowCredentials=false`, never `*`.
**Rationale**: closes T-01 (BOLA-via-URL), T-17 (shared caching proxy), T-18 (CORS widening regression); `default-src 'none'` is defense-in-depth against content sniffing.
**Alternatives considered**: token-in-header auth (rejected — reintroduces cross-origin surface, weakens T-02 mitigation); `Cache-Control: private` (rejected — `no-store` strictly stronger for shared proxies); `@RestControllerAdvice` for header emission (rejected — cannot decorate CORS preflight or 405).
**Source**: security planner SR-5, SR-6, ADR-06; architect §6 Round 2.

### D-09 — `wallId` cookie hardened: `HttpOnly; Secure; SameSite=Lax`
**Decision**: rewrite `InformationController#readCookie` to emit a `ResponseCookie` via `response.addHeader("Set-Cookie", cookie.toString())` with: `HttpOnly=true` unconditional, `Secure=${glacier.cookie.secure:true}`, `SameSite=Lax`, `Path=/`, `Max-Age=2592000`. Flag-less legacy cookies are still accepted on read (no forced user logout); rotation happens naturally on 30-day TTL.
**Rationale**: T-02 (XSS theft — `HttpOnly`); T-03 (plaintext leakage — `Secure`); T-04 (CSRF-on-read — `SameSite=Lax`; `Strict` would break top-level-nav first requests, `None` is unacceptable).
**Source**: security planner SR-3.

### D-10 — Two-axis rate limiting (per-wallId + per-IP) with `NONE` header-trust default
**Decision**: `FallbackRateLimiter` holds two `ConcurrentHashMap<String, TokenBucket>` — one keyed by `wallId`, one by remote IP. Defaults: 30/min per wallId, 120/min per IP. 429 responses carry `Retry-After`. 429 does **not** trigger a WS probe or mode flip (client-side rule). Stale buckets (full for > 10 min) are evicted by `FallbackRateLimiter.evictStaleBuckets()`. `server.forward-headers-strategy=${FORWARD_HEADERS_STRATEGY:NONE}` — `FRAMEWORK` is an explicit operator opt-in for deployments behind a trusted reverse proxy (documented in `infrastructure/README.md`).
**Rationale**: per-wallId is the primary defense; per-IP catches cookie-farming (T-08). `NONE` default prevents IP spoofing when the jar is run bare without a trusted proxy.
**Source**: security planner SR-4, T-08; architect Round 2 refinement.

### D-11 — Memory DoS caps: 10 hashtags/principal, 10 000 principals
**Decision**: `MessageCache.provisionHashtag` throws `CacheCapacityException` (unchecked) when either cap is exceeded and the tuple is not already provisioned (idempotent re-provision stays a no-op). `SubscriptionManagerImpl.subscribeToHashtag` catches, does not start the Bigbone virtual thread, returns control to `SubscriptionController`, which emits a negative `SubscriptionAckMessage` with a structured `rejection`. Micrometer gauges `glacier.cache.principals.count` and `glacier.cache.entries.total`. Kill switch `glacier.fallback.enabled=false` → 404 on `/rest/messages` and no cache write path runs.
**Source**: security planner SR-7.

### D-12 — `SubscriptionAckMessage` gains `rejection` field
**Decision**:
```java
public class SubscriptionAckMessage extends StatusMessage {
    private String principal;
    private String hashtag;
    private boolean isSubscribed;
    private SubscriptionRejection rejection; // null on success
}
public class SubscriptionRejection {
    private RejectionCode code;
    private Map<String, Object> details; // e.g. {"limit": 10}
}
public enum RejectionCode { CAP_EXCEEDED, INVALID_HASHTAG, INTERNAL_ERROR }
```
Frontend uses `rejection.code` for branching, `rejection.details.limit` to fill `{N}` in the localised CAP_REACHED snackbar. `details` map must not contain raw `wallId`, raw hashtag, or IP — operational scalars only (unit test iterates `RejectionCode` values and asserts no sensitive keys).
**Source**: UX designer §3 CAP_REACHED; architect Round 2 §3 pin-down 6; security planner Round 2 §3 hygiene reminder.

### D-13 — Log hygiene: wallId hashed, no toot content, dedicated AUDIT logger
**Decision**: `MessageCacheImpl` logs only `{principal-hash, hashtag, sequence, eventType, statusId}` — never the toot URL, raw `wallId`, `editedAt`, or toot body. `principal-hash` = first 8 hex chars of SHA-256(wallId). Logback `CompositeJsonEncoder` drops fields named `cookie`, `setCookie`, `authorization`. A dedicated `AUDIT` logger emits `fallback.ratelimit.hit`, `fallback.auth.fail`, `cache.capacity.exhausted` at INFO to stdout.
**Source**: security planner SR-8.

### D-14 — TLS posture: client derives transport from `window.location.protocol`
**Decision**: `FallbackService` reads `window.location.origin` only — no environment override to `http://` from production builds. New build-time constant `environment.allowPlaintext` (default `false` in `environment.production.ts`). WS probe URL is `(location.protocol === 'https:' ? 'wss:' : 'ws:') + '//' + location.host + '/websocket'`. When `environment.production && location.protocol === 'http:'`, the client enters the `INSECURE` state and does not start the fallback poller. `glacier.devmode` is scoped to `MastodonConfiguration` and **must not** be read outside `mastodon/*` — enforced by `CodebaseConstraintTest` running a grep assertion.
**Source**: security planner SR-9; architect Round 2 commitment.

### D-15 — Six-state indicator with chip-pill in header-right (ADR-07)
**Decision**: 32 px Material chip-pill, right-aligned in the existing header flex container via `margin-inline-start:auto`. Six states: `WEBSOCKET` (green "Live"), `PROBING` (yellow "Verbinde…", rotating icon → static under `prefers-reduced-motion`), `FALLBACK` (orange "Fallback-Modus"), `OFFLINE` (red "Offline"), `KILLSWITCHED` (grey "Eingeschränkt"), `INSECURE` (red "Unsichere Verbindung", lock-open icon). WCAG 2.2 AA contrast verified (text ≥4.5:1, graphical ≥3:1). Native `<button type="button">` — focusable, activates a `MatMenu` popover with state detail + reload CTA (where applicable). State flips do not move focus. Visually-hidden `<span role="status" aria-live="polite">` carries narration copy on transitions (suppressed on initial `WEBSOCKET` render).
**Source**: UX §1; ADR-07.

### D-16 — Gap warning via `MatSnackBar` with per-hashtag debounce
**Decision**: `gap: true` on a fallback response emits on `GapObserved$(hashtag)` debounced 5 s. UI surfaces a `MatSnackBar` (Material default bottom-centre) with text "Einige ältere Toots könnten fehlen." / "Some older toots may be missing.", action button "Schließen" / "Dismiss", 10 s duration (Material default 5 s too short), `role="alert"`. Per-hashtag ack flag prevents stacking; same-hashtag re-toast only after the cache catches up and re-gaps. When the gap snackbar and indicator flip fire simultaneously, suppress the indicator's live-region update for 1.5 s to prevent overlapping screen-reader narration.
**Source**: UX §2.

### D-17 — Session-expired banner, rate-limited snackbar, killswitch invisible-while-WS-up
**Decision**:
- **401 SESSION_EXPIRED**: full-width banner `#fbe6e6` / `#3a0f0f`, `role="alert"`, primary "Neu laden" button triggers `location.reload()`. Indicator flips to `OFFLINE`. No silent retry.
- **429 RATE_LIMITED**: one-shot snackbar "Zu viele Anfragen. Wir versuchen es gleich erneut." / "Too many requests. Retrying shortly." Indicator stays on `FALLBACK`. If `Retry-After > 60 s`, popover shows "Nächster Versuch in {N}s".
- **404 KILLSWITCHED**: indicator grey state + popover with operator explanation + reload CTA. Invisible while WS is up.
- **INSECURE**: indicator red state + popover body "Diese Seite wird über eine unsichere Verbindung ausgeliefert. Der Fallback-Modus ist zu Ihrem Schutz deaktiviert." No reload.
- **CAP_REACHED**: snackbar near `#hashtag-…` chip area, de "Maximal {N} Hashtags pro Sitzung. '{tag}' wurde nicht hinzugefügt." Rolls back the optimistic chip DOM addition.
- **NO_HASHTAGS_SUBSCRIBED_YET**: centred placeholder in `#toots` with CTA focusing the hashtag input.
**Source**: UX §3.

### D-18 — i18n via `@angular/localize` runtime catalogues, default `de` (ADR-08)
**Decision**: all new user-visible strings use `$localize` tagged templates with stable `@@id` keys (full list in UX plan §5). Default compile-time locale is `de` (strings authored in German). English catalogue loaded at runtime from `/assets/i18n/messages.en.json` via `loadTranslations()` in `main.ts`; locale detection via `navigator.language` starts-with `en`. No UI switcher in v1. `angular.json` registers the `@angular/build:extract-i18n` builder (one-line change). Existing components' hardcoded English strings are not retrofitted — explicit Phase-2-PR-out-of-scope boundary. CSS uses logical properties (`margin-inline-*`) for later RTL.
**Rationale**: runtime loading keeps a single build artefact (compatible with `frontend-maven-plugin` → `ng build` pipeline); avoids per-locale bundle explosion; `@angular/localize` is already a transitive dep. Default `de` matches the project's German-speaking audience.
**Source**: UX §5; architect Round 2 pin-down 10; ADR-08.

### D-19 — E2E test plumbing: two docker-compose overrides, four new Playwright specs
**Decision**:
- `infrastructure/docker-compose.override.killswitch.yaml` adds `GLACIER_FALLBACK_ENABLED: "false"` to the `glacier` service environment.
- `infrastructure/docker-compose.override.insecure.yaml` exposes `127.0.0.1:8081:8080` (loopback-only per security hardening) on the `glacier` service.
- Neither override touches the main `infrastructure/docker-compose.yaml`.
- `frontend/playwright.config.ts` adds two new projects: `killswitch`, `insecure`.
- `.github/workflows/verify.yml` adds two new CI stages running each override via `docker compose -f docker-compose.yaml -f docker-compose.override.<name>.yaml up … --project=<name>`.
- Four new specs: `frontend/e2e/workflows/fallback.spec.ts`, `frontend/e2e/workflows/fallback-ux.spec.ts`, `frontend/e2e/workflows/fallback-killswitch.spec.ts`, `frontend/e2e/workflows/fallback-insecure.spec.ts`.
- WS failure inside the main stack is induced by writing a traefik middleware returning 503 on `/websocket` into the mounted `dynamic.yml` (Playwright writes/removes it and waits ~2 s for traefik to apply). Rejected alternatives: iptables helper (sync hard), Spring feature flag closing the STOMP endpoint (bypasses the network path under test).
- `infrastructure/README.md` gains a "TEST FIXTURE ONLY — do not include in production compose" banner on the override files and documents `FORWARD_HEADERS_STRATEGY=FRAMEWORK` as mandatory behind traefik.
**Source**: UX §6.3; architect Round 2 pin-down 7; security planner Round 2 §5.

### D-20 — `@axe-core/playwright` accepted as a frontend dev dep
**Decision**: add `@axe-core/playwright` (pinned exact version, dependabot-governed) as a devDependency. Used in Playwright specs 1, 2, 3, 5, 9 to assert zero `serious|critical` violations per state.
**Rationale**: Deque is the canonical a11y tooling maintainer; dev-dep only, no runtime bundle impact; `axe-core` pure-JS, no postinstall scripts; transitive surface minimal.
**Source**: UX §6.5; security planner Round 2 §6 supply-chain verdict.

### D-21 — 200 ms toot-removal fade deferred to Phase 2 UI work (tracked, not blocking)
**Decision**: the 200 ms fade-out on `TootComponent` removal is a pure Angular animation, not coupled to the fallback transport. Not included in this plan; captured as a follow-up so it is not forgotten.
**Source**: UX §7.3; architect Round 2 §3 pin-down 9.

## ADRs (full text)

### ADR-01 — HTTP short-poll as the fallback transport
**Decision**: short-poll with cursor, single `GET /rest/messages`.
**Rationale**: the fallback exists precisely for firewalls/proxies that break WS; short-poll degrades to ordinary HTTP, most likely to actually work in that environment, and has the simplest exactly-once contract.
**Alternatives considered**: long-poll (same idle-timeout failure class, holds servlet threads); SSE (same proxy-buffering failure class, adds a third transport).
**Consequences**: latency bounded by 5 s poll interval; some idle HTTP traffic while in fallback; Playwright needs a way to break WS without breaking HTTP — traefik middleware is the chosen mechanism.

### ADR-02 — Cache granularity is per `(principal, hashtag)`, size 20
**Decision**: one ring per `(principal, hashtag)`, bounded to 20.
**Rationale**: matches STOMP destination tree; one-dimensional cursor; matches the user-locked 20-item capacity.
**Alternatives considered**: per-principal (cross-eviction between unrelated hashtags, 2D cursor problem); unbounded + TTL (memory risk).
**Consequences**: memory = `O(principals × hashtags × 20)`; tunable via `glacier.cache.size`; hard-capped at 10 000 × 10 × 20 by D-11.

### ADR-03 — Fallback trigger is 1 s / 4 s / 16 s then flip
**Decision**: three reconnect attempts at 1/4/16 s; flip to fallback if attempt 3 has not reached OPEN within a further 16 s.
**Rationale**: satisfies "increasing timeout"; long enough to absorb a mobile-network hiccup without flapping; short enough to give users feedback quickly.
**Alternatives considered**: tighter (500 ms/1 s/2 s — flaps); looser (5/15/45 s — dead-wall period too long).
**Consequences**: existing `rx-stomp.config.ts reconnectDelay: 500` must be neutralized to `0` so RxStomp doesn't race `FallbackService`.

### ADR-04 — Deletion events stored, not applied retroactively
**Decision**: `StatusDeleted` → its own `CacheEntry(type=DELETED)`; CREATED not retroactively removed.
**Rationale**: a client whose cursor predates CREATED must observe the create-then-delete pair to stay consistent with STOMP semantics.
**Alternatives considered**: tombstone-erase / coalesce (both break client determinism vs. STOMP).
**Consequences**: the 20-entry ring can contain correlated pairs and thus fewer than 20 distinct toots — matches STOMP stream exactly. `glacier.fallback.publish.failures` counter (added in Round 2) exposes STOMP-publish failures to operators without rolling back the cache.

### ADR-05 — Cache eviction coordinated through `SubscriptionManager`
**Decision**: `MessageCache.evictHashtag/evictPrincipal` called from `SubscriptionManagerImpl.terminateSubscription/terminateAllSubscriptions`, which `SubscriptionListener` already invokes on its 5-min timeout.
**Rationale**: single point of truth for "subscription is going away"; avoids double teardown / split-brain.
**Alternatives considered**: wiring `SubscriptionListener` directly to `MessageCache` — duplicates authority.
**Consequences**: during the 5-min grace window the cache stays populated — exactly the behaviour a fallback-mode client needs.

### ADR-06 — `/rest/messages` is cookie-only, same-origin, cache-control headers
**Decision**: The fallback endpoint authenticates **only** via the `wallId` cookie; refuses any attempt to authorize via URL parameter, body, or non-`Cookie` header. Responses carry `Cache-Control: no-store`, `Pragma: no-cache`, `Vary: Cookie`, `X-Content-Type-Options: nosniff`, `Content-Security-Policy: default-src 'none'`. Same-origin with the SPA. CORS allowed-origins list is exactly `[http://localhost:4200, https://${glacier.domain}]`, `allowCredentials=false`.
**Rationale**: cookie-only auth eliminates BOLA-via-URL (T-01); `no-store` + `Vary: Cookie` blocks shared-proxy leakage (T-17); exact-origin CORS blocks cross-site exfil (T-18); `CSP default-src 'none'` is defense-in-depth.
**Alternatives considered**: token-in-header auth (rejected — reintroduces cross-origin attack surface + JS-accessible token weakens T-02 mitigation); `Cache-Control: private` (rejected — `no-store` strictly stronger).
**Consequences**: `FallbackSecurityHeadersFilter` is the enforcement point; `FallbackControllerIT` asserts headers on 200/204/400/401/429. Cookie attribute change (D-09) is prerequisite; flag-less legacy cookies still honored on read — no forced user logout.

### ADR-07 — Indicator placement, snackbar gap warning, banner session-expired
**Decision**: 32 px mat-chip pill right-aligned in header; six states with icon+label, WCAG 2.2 AA, `prefers-reduced-motion` compliance, visually-hidden `role="status" aria-live="polite"` narration. Cache-gap via `MatSnackBar` (10 s, per-hashtag debounce, `role="alert"`). 401 via full-width banner with Reload. 429, 404, INSECURE via indicator state changes + popover.
**Rationale**: header-right is primary scan path, does not occlude the wall (relevant in fullscreen/F11 kiosk mode); icon+label avoids colour-only signalling; snackbar is the Material pattern for non-blocking transient notifications; banner is appropriate only for terminal states that require user action.
**Alternatives considered**: footer (below fold); floating badge (covers content); inline per-hashtag indicators (clutters wall, confuses per-wall `TransportMode`).
**Consequences**: `HeaderComponent` + `AppModule` add one element + `MatSnackBarModule` + `MatMenuModule`. Six-state machine owned by `FallbackService`. All new strings via `$localize`. Tests: unit per state + axe-core Playwright audits.

### ADR-08 — `@angular/localize` with runtime catalogues, default `de`
**Decision**: i18n via `@angular/localize` + `$localize` tagged template literals. Compile-time default locale `de` (German source). English catalogue loaded at runtime via `loadTranslations()` in `main.ts` based on `navigator.language`. Stable `@@id` keys. No locale switcher v1. No new runtime dependency.
**Rationale**: runtime catalogue loading keeps a single build artefact (compatible with `frontend-maven-plugin` + `ng build` pipeline); avoids per-locale bundle explosion of classic i18n; matches Angular 19's recommended pattern. Default `de` matches the project's German-speaking audience and `README.md` convention.
**Alternatives considered**: compile-time per-locale bundles (complicates Maven build, multiple jars); ngx-translate / transloco (third-party dep, different templating); no i18n (rejected — UX requires German copy for six connection states).
**Consequences**: `angular.json` registers `extract-i18n` builder; `main.ts` gains ~10 LOC of catalogue-loading; one runtime fetch of `messages.en.json` for English users, browser-cached. Existing untouched components keep their hardcoded English strings until a future retrofit PR — **explicitly out of scope here**.

## Files to touch

### Backend — new
- `de.seism0saurus.glacier.webservice.cache.CacheEntry`
- `de.seism0saurus.glacier.webservice.cache.PerTagRing`
- `de.seism0saurus.glacier.webservice.cache.MessageCache` (interface)
- `de.seism0saurus.glacier.webservice.cache.MessageCacheImpl`
- `de.seism0saurus.glacier.webservice.cache.EventType` enum
- `de.seism0saurus.glacier.webservice.FallbackController`
- `de.seism0saurus.glacier.webservice.cache.FallbackResponse` DTO
- `de.seism0saurus.glacier.webservice.cache.FallbackRateLimiter` (two-axis token bucket + `evictStaleBuckets()`)
- `de.seism0saurus.glacier.webservice.cache.FallbackSecurityHeadersFilter`
- `de.seism0saurus.glacier.webservice.cache.CacheCapacityException`
- `de.seism0saurus.glacier.webservice.cache.UnknownSubscriptionException`
- `de.seism0saurus.glacier.webservice.cache.FallbackControllerAdvice` (narrow-scoped `@RestControllerAdvice`, flat `{error:"code"}` bodies, scoped to `FallbackController`)
- `de.seism0saurus.glacier.webservice.messaging.messages.SubscriptionRejection`
- `de.seism0saurus.glacier.webservice.messaging.messages.RejectionCode` enum
- `de.seism0saurus.glacier.util.LogScrubber` (JSON-field scrubber for logback)

### Backend — modified
- `StompCallback.java` — call `messageCache.recordThenPublish(...)`; normalise `editedAt` via `Instant.parse(...).toString()` with `DateTimeParseException` → WARN+drop. `SimpMessagingTemplate` dependency removed from this class and moved into `MessageCacheImpl`.
- `SubscriptionManagerImpl.java` — constructor accepts `MessageCache`; calls `provisionHashtag` before starting the virtual thread; catches `CacheCapacityException`.
- `SubscriptionController.java` — maps `CacheCapacityException` to a negative `SubscriptionAckMessage` with `rejection={code: CAP_EXCEEDED, details: {"limit": <config>}}`; never throws past `@SendToUser`.
- `SubscriptionListener.java` — no direct change; an integration test asserts cache eviction on the 5-min timer.
- `InformationController.java` — rewrite `readCookie` to emit `ResponseCookie` (`HttpOnly`, `Secure` from `glacier.cookie.secure`, `SameSite=Lax`, `Path=/`, `Max-Age=2592000`) via `response.addHeader("Set-Cookie", …)`. Add per-IP throttle on the UUID issuance path (120/min, same bucket type; non-blocking Phase 2 fix).
- `SubscriptionAckMessage.java` — new `SubscriptionRejection rejection` field (nullable).
- `GlacierApplication.java` — CORS origins already derive from `glacier.domain` at source; add `https://${glacier.domain}` explicitly to the `/rest/*` allowed-origins list.
- `application.properties` — new keys:
  ```properties
  glacier.fallback.enabled=${GLACIER_FALLBACK_ENABLED:true}
  glacier.fallback.ratelimit.perMinute=${GLACIER_FALLBACK_RATELIMIT_PER_WALLID:30}
  glacier.fallback.ratelimit.perMinutePerIp=${GLACIER_FALLBACK_RATELIMIT_PER_IP:120}
  glacier.cache.size=${GLACIER_CACHE_SIZE:20}
  glacier.cache.maxHashtagsPerPrincipal=${GLACIER_CACHE_MAX_HASHTAGS_PER_PRINCIPAL:10}
  glacier.cache.maxPrincipals=${GLACIER_CACHE_MAX_PRINCIPALS:10000}
  glacier.cookie.secure=${COOKIE_SECURE:true}
  server.forward-headers-strategy=${FORWARD_HEADERS_STRATEGY:NONE}
  ```
- `logback.xml` — configure `CompositeJsonEncoder` to drop `cookie`, `setCookie`, `authorization` fields; define an `AUDIT` logger.

### Frontend — new
- `frontend/src/app/fallback/fallback.service.ts`
- `frontend/src/app/fallback/transport-mode.ts` (enum)
- `frontend/src/app/fallback/delivery-cursor.ts`
- `frontend/src/app/connection-status/connection-status.component.ts`
- `frontend/src/app/connection-status/connection-status.component.html`
- `frontend/src/app/connection-status/connection-status.component.css`
- `frontend/src/app/message-types/subscription-rejection.d.ts`
- `frontend/src/assets/i18n/messages.en.json`

### Frontend — modified
- `subscription.service.ts` — new `ingestCacheEntries(hashtag, entries)`; `MessageQueue.update()` UTC-normalised `editedAt` compare.
- `rx-stomp.config.ts` — `reconnectDelay: 0`.
- `rx-stomp.factory.ts` — expose `connectionState$`.
- `main.ts` — `@angular/localize/init` import + `loadTranslations()` + `navigator.language` detection.
- `angular.json` — register `@angular/build:extract-i18n` builder.
- `subscription-ack-message.d.ts` — add `rejection?: { code: string; details?: Record<string, unknown> }`.
- `hashtag.component.ts` — handle `rejection.code === 'CAP_EXCEEDED'`, remove optimistic chip, show localised `MatSnackBar`.
- `header/header.component.html` — embed `<app-connection-status>`.
- `app.module.ts` — `MatSnackBarModule`, `MatMenuModule`, declare `ConnectionStatusComponent`.
- `app.component.html` — empty-state placeholder and 401-banner slot above `<app-wall>`.
- `frontend/package.json` — add `@axe-core/playwright` dev dep, exact version.

### Infrastructure — new / modified
- `infrastructure/docker-compose.override.killswitch.yaml` (new) — `glacier.environment.GLACIER_FALLBACK_ENABLED: "false"`.
- `infrastructure/docker-compose.override.insecure.yaml` (new) — `glacier.ports: ["127.0.0.1:8081:8080"]` (loopback-only).
- `infrastructure/README.md` — add "TEST FIXTURE ONLY" banner referencing the override files; document `FORWARD_HEADERS_STRATEGY=FRAMEWORK` as mandatory behind traefik.
- `.github/workflows/verify.yml` — two new CI stages running each override via `docker compose -f docker-compose.yaml -f docker-compose.override.<name>.yaml up … playwright --exit-code-from playwright` scoped to the matching Playwright project.

### Tests — new / modified
- **Unit Java**: `PerTagRingTest` (incl. concurrency torture test: 1000 appends × 10 threads + 1000 snapshots; hashtag cap; principal cap; newline/CR/NUL rejection), `MessageCacheImplTest` (exactly-once publish; recordThenPublish no-op when tuple not provisioned; log-hygiene assertion), `FallbackControllerTest` (MockMvc — all validation branches, header contract, 405 on non-GET, reflection assertion that `principal` is never a `@RequestParam`), `FallbackRateLimiterTest` (30/min per wallId; 120/min per IP; evictStaleBuckets; 1000-thread concurrency), `FallbackControllerAdviceTest`, `CookieEmissionTest` (HttpOnly/Secure/SameSite attributes), `CodebaseConstraintTest` (grep guard for `glacier.devmode` refs outside `mastodon/*`). Amendments: `StompCallbackTest` (non-loadable toot → zero `recordThenPublish`; non-opt-in → zero; UTC normalisation of `editedAt`; `DateTimeParseException` → WARN+drop), `SubscriptionManagerImplTest` (provision/evict calls), `SubscriptionControllerTest` (`CAP_EXCEEDED` ack shape).
- **Unit Angular**: `connection-status.component.spec.ts` (per-state DOM/ARIA/focus/reduced-motion), `fallback.service.spec.ts` (3-fail trigger, poll cadence, exponential probe schedule with `jest`/`fakeAsync`-style clock, 401→SessionExpired event, 429→RateLimited event with `Retry-After`, 404→KillSwitched, gap debounce, http-origin → INSECURE, `DeliveryCursor` clamp), `subscription.service.spec.ts` extensions (`ingestCacheEntries` dedup, UTC `editedAt` compare), `hashtag.component.spec.ts` extension (`CAP_REACHED` optimistic rollback).
- **Integration `*IT.java`**: `FallbackControllerIT` (full Spring + wiremock-spring-boot for embed HEAD; 200/204/400/401/429 body + header contract), `FallbackSecurityIT` (cross-principal BOLA: principal A cannot read principal B's hashtag — same 400 response), `FallbackAtomicityIT` (50 parallel `StompCallback` events — STOMP + HTTP snapshots yield identical `statusId` sets; sequences strictly increasing; 10/50 `convertAndSend` failures do not corrupt the cache), `CorsConfigurationIT` (exact origin list; no `*`; `allowCredentials=false`), `CookieEmissionIT` (Set-Cookie header contains `HttpOnly`, `Secure`, `SameSite=Lax`, `Path=/`, `Max-Age=2592000`), `RateLimitHeaderTrustIT` (run twice, `FORWARD_HEADERS_STRATEGY=NONE` and `FRAMEWORK`, both paths verified), `MessageCacheSubscriptionLifecycleIT` (subscribe → events → terminate → HTTP 400; 5-min timer eviction with overridden `glacier.timeouts.client_reconnect`).
- **E2E Playwright (real dockerized Mastodon)**: `fallback.spec.ts` (happy WS, WS-break via traefik middleware, recovery, cache gap, visual regression), `fallback-ux.spec.ts` (six-state indicator screenshots at 3 breakpoints, axe audits, reduced-motion, keyboard path, cookie-privacy `document.cookie` assertion, SR narration via `page.getByRole('status')`). Override-based: `fallback-killswitch.spec.ts` and `fallback-insecure.spec.ts`.

### Jacoco
Thresholds preserved (instruction ≥ 0.45, branch ≥ 0.35). No lowering. New bundle ≈ 400 LOC, fully test-covered by the matrix above.

## Resolved conflicts

**None.** All peer-review items across the two rounds resolved via accept/adapt. No `## ⚡ CONFLICT:` markers raised by any agent.

## Non-blocking Phase 2 fix requests (folded into implementation, not separate decisions)

1. Per-IP throttle on `InformationController#readCookie` (`/rest/wall-id` issuance) — same token-bucket type, separate bucket, default 120/min. Addresses residual T-08 cookie-farming vector.
2. Narrow-scoped `@RestControllerAdvice` returning flat `{error: "code"}` bodies, scoped to `FallbackController`, plus an `IT` assertion that default Spring error MVC is not reachable on `/rest/messages` (closes T-11 stacktrace leakage).
3. `127.0.0.1:8081:8080` binding on the insecure compose override (loopback-only, not `0.0.0.0`).
4. `FallbackRateLimiter.evictStaleBuckets()` documented in Javadoc with unit test.
5. `try/catch DateTimeParseException` around the server-side `Instant.parse` in `StompCallback` (drop + WARN, don't propagate).
6. `SubscriptionRejection.details` hygiene test: iterate `RejectionCode` values, assert no `wallId`, raw hashtag, or IP appear.

## User approval

Date: 2026-04-21
Approval message (verbatim): "1: sounds good. 2: 20-items is a good start. 3: 3 reconnect attempts with increasing timeout. 4: exponential but max 5 minutes. 5: sounds good. 6: make the best decission" (Step 0) followed by "do it" (Phase 1 approval gate, all seven open decisions accepted as recommended by the planners).

Consolidated approvals:
- D-A1 `glacier.cookie.secure` default = **true**.
- D-A2 Cookie attribute change ships **unconditionally in 0.0.9** (no feature flag).
- D-A3 Memory cap defaults **accepted** (`maxPrincipals=10000`, `maxHashtagsPerPrincipal=10`).
- D-A4 Dark-mode future-proofing via CSS custom properties **yes** (no dark theme shipped).
- D-A5 Locale switcher **no** in v1 (browser-locale detection only).
- D-A6 i18n retrofit of existing GDPR/header/footer copy **deferred** (scope guard).
- D-A7 Operator diagnostic UX **out of scope** (rely on Spring Boot logs + `glacier.cache.*` gauges + `glacier.fallback.publish.failures` counter).

## Phase 2 Round 1 clarifications

Appended 2026-04-21 in response to `CLARIFICATION REQUEST` markers from the `devops-infra-engineer` agent.

### C-01 — CI trigger scope for `e2e-killswitch` / `e2e-insecure` stages

**Question**: The current `.github/workflows/verify.yml` trigger is `push: branches: ["*.*.*"]` (version branches). Should the two new override stages run on `main` and arbitrary-branch PRs too, or stay on the version-branch trigger?
**Resolution (2026-04-21, user)**: Keep the existing `*.*.*` trigger. Expand to `main` once the `frontend-designer` has landed the `fallback-killswitch.spec.ts` / `fallback-insecure.spec.ts` specs and they are green on the version branch. Verbatim user message: "1: a".
**Impact**: no `verify.yml` trigger widening in this PR. The two new stages run on the same triggers as the existing `e2e` job.

### C-02 — `insecure` Playwright network topology

**Question**: `infrastructure/docker-compose.override.insecure.yaml` binds port 8081 to `127.0.0.1` (Docker host loopback). The Playwright container runs inside the compose network and cannot reach the host loopback without extra host configuration. How should Playwright reach `http://…:8081`?
**Resolution (2026-04-21, user)**: Add `extra_hosts: ["host.docker.internal:host-gateway"]` to the `playwright` service in the insecure override. The `fallback-insecure.spec.ts` spec navigates to `http://host.docker.internal:8081`. This keeps the binding loopback-only on the host while allowing the Playwright container to traverse the bridge gateway. Verbatim user message: "2: a".
**Impact**: `docker-compose.override.insecure.yaml` gains the `extra_hosts` entry on the `playwright` service. The `frontend-designer` wires `http://host.docker.internal:8081` as the `insecure` Playwright project's `baseURL`.

### C-03 — `RestTemplate` has no connect/read timeout (scope expansion, accepted)

**Question**: `GlacierApplication.restTemplate()` returns `new RestTemplate()` with no connect or read timeout. `StompCallback.isLoadable(...)` issues a synchronous `HEAD` to `tootUrl + "/embed"` on every qualifying toot on the Bigbone virtual thread. A slow or hung remote instance stalls that `(principal, hashtag)` subscription indefinitely, starving *both* the WS stream and the fallback cache simultaneously. Pre-existing (not introduced by this PR) but made more exposed by the fallback feature. Fold into Phase 2 or defer?
**Resolution (2026-04-21, user)**: Fold into Phase 2. Verbatim user message: "3: a".
**Scope expansion approved**:
- `GlacierApplication.restTemplate()` — inject a `SimpleClientHttpRequestFactory` with connect + read timeouts wired from `glacier.embed.connectTimeoutMs` (default 3000) and `glacier.embed.readTimeoutMs` (default 5000).
- `application.properties` — two new keys:
  ```properties
  glacier.embed.connectTimeoutMs=${GLACIER_EMBED_CONNECT_TIMEOUT_MS:3000}
  glacier.embed.readTimeoutMs=${GLACIER_EMBED_READ_TIMEOUT_MS:5000}
  ```
- `StompCallbackTest` — new test: when `RestTemplate.headForHeaders` throws `ResourceAccessException("Read timed out")`, the event is dropped, `recordThenPublish` is **never** called, and the exception does not propagate out of `onEvent`. Owner: `devops-infra-engineer` (agent that raised the finding).
- `StompCallback.isLoadable(...)` — must already treat `RestClientException` (parent of `ResourceAccessException`) as "not loadable" and skip publish. Verify; add a comment tying this behaviour to the timeout path.

## Open risks (accepted)

- Brief double-delivery window during WS↔fallback transitions, absorbed by client `statusId` dedup.
- Up-to-5 s staleness in fallback mode; surfaced by the `FALLBACK` indicator.
- `recordThenPublish` persists cache entry even when STOMP publish throws; exposed via WARN log + `glacier.fallback.publish.failures` counter; no alerting in this PR.
- +~60 s CI time per verify run for the two new override-based Playwright stages (runner-time; wall-time near-zero since the two stages run in parallel with the existing `e2e` job).
- Scope increase: cookie hardening, i18n plumbing, six states instead of three, empty-state polish. All justified in peer review.
- C-03 scope expansion: `RestTemplate` timeout hardening. Pre-existing issue folded into this PR at the user's direction.

## References

- `/home/ulrich.viefhaus/git/seism0saurus/glacier/CLAUDE.md` — testing policy, architecture, conventions (binding).
- `/home/ulrich.viefhaus/git/seism0saurus/glacier/docs/websocket-http-fallback-plan.md` — architect's working notes (archive).
- OWASP Top 10 (2025), OWASP API Security Top 10 (2023), TSS-WEB.
- WCAG 2.2 AA.
- Mastodon API `edited_at` field (ISO-8601 UTC).
