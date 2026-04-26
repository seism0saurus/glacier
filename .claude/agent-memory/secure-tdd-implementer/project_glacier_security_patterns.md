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

---

## Hashtag Prune on Removal — security patterns (Phase 2, 2026-04-24)

**Why:** GitHub issue #29 — prune-on-removal feature introduces localStorage restore path and viewer-side STOMP surface.

### MessageQueueValidator (SR-PRUNE-01, SR-PRUNE-02, SR-PRUNE-12)
- `validateMessageQueue(raw: unknown): WallMessage[] | null` in `frontend/src/app/model/message-queue-validator.ts`
- Returns null (never throws) on ANY malformed input — null, undefined, primitive, array, wrong schema version
- Rejects reserved prototype-pollution keys (`__proto__`, `constructor`, `prototype`) in BOTH item object keys AND hashtag string values
- Uses `Object.keys(obj)` (own enumerable keys only) for key enumeration — prevents prototype chain leakage
- Enforces `v === WallMessageSchemaVersion` (numeric equality, not string)
- Enforces per-item: `id.length <= 100`, `url.length <= 512`, `hashtags.length >= 1`, each `hashtag.length <= 100`
- Entire-queue discard on ANY single item failure (no partial acceptance)
- No JSON.parse, no eval, no Function() inside the validator — caller already parsed

### MessageQueue.restore() — now uses full validator
- `subscription.service.ts MessageQueue.restore()` calls `validateMessageQueue(parsed)` instead of a basic `v` field check
- Previous code: basic `parsed.v !== WallMessageSchemaVersion` check — insufficient (no prototype-pollution protection)
- Current code: delegates entirely to `validateMessageQueue`; null result = discard + reset + warn log

### ReadonlyWallService guard-rail (SR-PRUNE-09)
- `frontend/src/app/share/services/readonly-wall.service.ts`
- `pruneByHashtag(_hashtag: string): PruneResult` — explicit no-op, returns `{ removed: [], remaining: [] }`
- `pruneByHashtags(_hashtags: string[]): PruneResult` — explicit no-op
- `hashtags[]` is set ONLY from the catalog HTTP response (`initialize()` → subscribe) — never from STOMP frames or `handleToot()`
- These no-ops prevent a viewer from being tricked into clearing a sharer's cache via crafted WebSocket termination frames
- The existing localStorage-isolation invariant (verified by existing tests) means these no-ops also implicitly never touch localStorage

### i18n — German source language (messages.de.json)
- Created `frontend/src/assets/i18n/messages.de.json` with all 8 new keys from the Phase 1 plan plus all pre-existing keys
- German is the source language in Glacier; `messages.en.json` is the English catalog (loaded at runtime)
- The angular-i18n-localize skill documents this catalog architecture

### Frontend Karma test count
- Baseline before Phase 2: 386
- After tdd-ddd-implementer lane: 386 (unchanged in this session's start)
- After secure-tdd-implementer lane (Phase 2): 434 (+48 new tests)
  - message-queue-validator.spec.ts: ~39 new tests (U-SEC-01, U-SEC-02, U-SEC-12 + SR-PRUNE-02 coverage)
  - readonly-wall.service.spec.ts: ~9 new tests (SR-PRUNE-09 guard-rail)
- After Phase 3 fix cycle (FIND-P3-SEC-4 + FIND-P3-SEC-7/AC-11): 501 (+32 new tests from 469)
  - message-queue-validator.spec.ts: +32 new tests (U-SEC-14 URL scheme + validateHashtagsList)

---

## Phase 3 fix cycle — FIND-P3-SEC-4 and FIND-P3-SEC-7/AC-11 (2026-04-24)

**Why:** Security-auditor and acceptance-test-auditor identified two remaining defects before Phase 3 sign-off.

### FIND-P3-SEC-4: validateHashtagsList (localStorage hashtags key)
- `validateHashtagsList(raw: unknown): string[] | null` exported from `frontend/src/app/model/message-queue-validator.ts`
- Guards the `hashtags` localStorage key before any entry is forwarded as a STOMP subscription request
- Rejects: non-array, arrays > 100 elements, non-string elements, empty strings, strings > 100 chars
- Rejects reserved prototype-pollution names (`__proto__`, `constructor`, `prototype`) — SR-PRUNE-02 extended
- Rejects strings that fail the normalised Mastodon hashtag charset: `/^[a-z0-9_\p{L}\p{N}]+$/u`
  - This blocks `<`, `>`, `/`, `\`, `"`, `` ` ``, space, and other injection characters
- `subscription.service.ts` constructor now calls `validateHashtagsList(rawHashtags) ?? []` instead of using `JSON.parse` result directly
- `RESERVED_KEYS` Set is shared across both validators (defined once at module level)

### FIND-P3-SEC-7 / AC-11: URL scheme allowlist in validateItem
- Added inside `validateItem()` after the `url.length` check
- Uses `new URL(url)` with try/catch — rejects malformed URLs that cannot be parsed
- Allowlist: `https:` and `http:` only (`http:` permitted for dev/test Mastodon instances)
- Rejects: `javascript:`, `data:`, `vbscript:`, scheme-relative `//`, any other protocol
- Rationale: `bypassSecurityTrustResourceUrl` (used by the iframe renderer) disables Angular's built-in URL sanitiser — this allowlist is the last client-side defence (OWASP A03, AC-11)
- The entire-queue discard semantics of `validateItem` means one bad URL discards the whole queue (SR-PRUNE-01)

**How to apply:** Any new URL field added to WallMessage must also be validated with the http/https scheme check inside `validateItem`. Any new localStorage key containing strings must use `validateHashtagsList` or a comparable validator before consuming the values.
