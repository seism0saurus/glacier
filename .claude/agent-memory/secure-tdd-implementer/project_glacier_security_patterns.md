---
name: Glacier security implementation patterns
description: Security patterns established during Phase 3 fix cycle — log hygiene, BOLA fix, AUDIT logging, CORS origin gating, Spring profile isolation, cache eviction
type: project
---

## Phase 3 fix cycle security patterns (completed 2026-04-22)

**Why:** Phase 3 security audit (security-auditor) identified 5 vulnerabilities in the ws-fallback feature; these patterns were implemented as fixes.

### D-13/SR-8 Log hygiene
- Raw `wallId` UUIDs must NEVER appear in log output
- Use `LogScrubber.hash8(principal)` for all principal references in LOGGER calls
- Use `LogScrubber.hashtagLen(hashtag)` for all hashtag references
- `LogScrubber.containsRawUuid(msg)` is used in test assertions to verify no UUID leaks
- Exception messages that previously echoed raw principal/hashtag were sanitized (e.g. `SubscriptionManagerImpl.terminateSubscription`)
- `headerAccessor` must never be logged directly (may contain cookies with wallId)

### BOLA fix in PrincipalHandler (FIX B)
- `PrincipalHandler.determineUser()` now generates `UUID.randomUUID().toString()` for missing/empty/short wallId cookies
- `MIN_WALL_ID_LENGTH = 32` — mirrors `CookieBasedFallbackAuthGuard.MIN_WALL_ID_LENGTH` for symmetric HTTP/WS validation
- Every validation failure emits an AUDIT event via `LoggerFactory.getLogger("AUDIT")`
- The AUDIT logger (`"AUDIT"` string key) is the channel for: `websocket.auth.fail`, `fallback.ratelimit.hit`, `fallback.auth.fail`, `cache.capacity.exhausted`

### AUDIT logger pattern
- `private static final Logger AUDIT = LoggerFactory.getLogger("AUDIT");`
- `AUDIT.info("cache.capacity.exhausted principal-hash={} limit={} axis=hashtags-per-principal", ...)`
- `AUDIT.info("websocket.auth.fail reason={} sessionId={}", ...)`
- AUDIT events must never contain raw principals, IPs, or cookie values

### WebSocket CORS origin gating (FIX D)
- `WebSocketConfiguration` injects `@Value("${glacier.cookie.secure:true}") boolean cookieSecure`
- `http://localhost:8080` is only included in allowed origins when `cookieSecure=false` (dev only)
- Production default: `cookieSecure=true` → origins: `["http://localhost:4200", "https://<domain>"]`
- Dev: `cookieSecure=false` → origins: `["http://localhost:4200", "http://localhost:8080", "https://<domain>"]`

### PassthroughFallbackAuthGuard profile isolation (FIX E)
- `@Profile("test")` added to restrict to test Spring profile only
- Production and integration-test contexts that don't activate `test` profile cannot load this bean
- Tests needing passthrough guard: activate `@ActiveProfiles("test")` or use `@MockBean FallbackAuthGuard`

### SubscriptionListener + MessageCache coupling
- `SubscriptionListener` now injects `MessageCache` directly (ADR-05, D-11)
- On disconnect timer expiry: calls BOTH `subscriptionManager.terminateAllSubscriptions(principal)` AND `messageCache.evictPrincipal(principal)`
- This handles fallback-mode-only clients (cache provisioned but no active Bigbone stream)
- `messageCache.evictPrincipal()` is idempotent — safe to call even if subscription manager already evicted

### MongoDB password fix (F-05)
- `spring.data.mongodb.password=securepwd` (hardcoded) → `spring.data.mongodb.password=${MONGODB_PASSWORD}`
- All MongoDB connection params now use env vars: `MONGODB_USERNAME`, `MONGODB_PASSWORD`, `MONGODB_DATABASE`, `MONGODB_HOST`, `MONGODB_PORT`

### Test counts after Phase 3 fixes
- Surefire: 296 tests, 0 failures
- Failsafe: 71 IT tests, 0 failures
- Jacoco: all bundle thresholds met (instruction ≥ 45%, branch ≥ 35%)

**How to apply:** Consult these patterns for any new LOGGER calls, WebSocket changes, Spring bean profile decisions, or MongoDB credential changes in this codebase.
