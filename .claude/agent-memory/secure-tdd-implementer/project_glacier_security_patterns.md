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

---

## OWASP Coverage Matrix Completion — WebSocket rate limiting (Phase 2, 2026-04-30)

**Why:** Nine OWASP coverage gaps (G1–G9) were closed by this pipeline. L2 scope owned by secure-tdd-implementer.

### HandshakeRateLimitInterceptor (SR-WS-01, ADR-PT-G5-01)
- `HandshakeInterceptor` implementation registered on `/websocket` AND `/share-view-ws`
- Fixed-window token bucket: `ConcurrentHashMap<String, TokenBucket>` keyed by source IP
- Capacity and max-per-minute from `glacier.security.ws.handshake.max-per-minute` (default 10)
- Rejects with HTTP 429 when bucket exhausted (closes cookie-rotation DoS)
- Fail-open on ANY `Throwable` (SR-WS-04) — consistent with FallbackRateLimiter
- AUDIT event: `ws.handshake.rate_limited ip-hash={}` using `LogScrubber.maskIp(ip)` (SR-LOG-WS-01)
- `@Scheduled` eviction at 5-minute cadence — removes entries idle > 10 minutes

### SubscribeRateLimitInterceptor (SR-WS-02, ADR-PT-G7-01)
- `ChannelInterceptor` on `clientInboundChannel` — registered first in interceptor chain
- Silent drop (return `null`) for over-limit SUBSCRIBE frames — preserves indistinguishability (API6)
- Bucket key: `ip + ":" + principalName` — isolated per (IP, wallId) pair
- AUDIT event: `ws.subscribe.rate_limited ip-hash={} wallid-hash={}` (SR-LOG-WS-01)
- Only intercepts `SimpMessageType.SUBSCRIBE` — all other frame types pass through
- Fail-open on `Throwable`

### WebSocket transport limits (SR-WS-05)
- `configureWebSocketTransport` explicitly calls: `setMessageSizeLimit(65536)`, `setSendBufferSizeLimit(524288)`, `setSendTimeLimit(20000)`
- All three values operator-tunable via env vars (SR-WS-06): `GLACIER_WS_MESSAGE_SIZE_BYTES`, `GLACIER_WS_SEND_BUFFER_BYTES`, `GLACIER_WS_SEND_TIME_MS`

### EndpointInventoryTest (SR-MTX-02, ADR-PT-API5-01)
- `@SpringBootTest(webEnvironment=MOCK)` — Surefire unit test
- Iterates `RequestMappingHandlerMapping.getHandlerMethods()` for live Spring context
- Bidirectional check: undocumented routes fail AND stale allowlist entries fail
- Only checks `/rest/` and `/internal/` paths — excludes Spring framework internals and actuator

### TrivyignoreExpiryTest (SR-CI-03)
- Validates BOTH `.trivyignore` (image layer) AND `.trivyignore-fs` (jar deps)
- Every non-blank, non-comment line must have trailing `# expires: YYYY-MM-DD`
- Past expiry dates cause build failure — forces periodic re-triage

### StompCallbackOptInEnforcementTest (SR-OI-01, SR-OI-02)
- isOptedIn() helper extracted and called at lines 327 (GenericMessage), 516 (StatusCreated), 578 (StatusEdited)
- 6 tests: UT-sec-01 (generic drop), UT-sec-02 (generic pass), UT-sec-03 (domain mismatch), UT-sec-04 (typed StatusCreated), UT-sec-04b (typed StatusEdited), UT-sec-04c (structural regression guard)

### Test counts after this phase
- Surefire: 946 tests, 0 failures
- Failsafe: 184 IT tests, 0 failures
- Total Java: 1130, BUILD SUCCESS

**How to apply:** Any new WebSocket endpoint must be registered with `handshakeRateLimitInterceptor`. Any new HTTP endpoint must be added to `EndpointInventoryTest.AUTHORITATIVE_ENDPOINT_ALLOWLIST` AND `OWASP_COVERAGE_MATRIX.md`. Any new Trivy suppression must carry `# expires: YYYY-MM-DD`.

---

## TD Backlog Bundle — F-7 + OBS-1 (Phase 2 Lane B, 2026-05-01)

**Why:** Technical debt deferred from 2026-04-30 OWASP Matrix Completion acceptance cycle.

### F-7 literal-token assertions (SR-F7-02)
- `HandshakeRateLimitInterceptorTest` UT-WS-RL-04: added `.contains("ws.handshake.rate_limited")` assertion
- `SubscribeRateLimitInterceptorTest` UT-SUBRL-05: added `.contains("ws.subscribe.rate_limited")` assertion
- Both tests were already green; additions tighten the AUDIT contract to pin the literal event token (OWASP A09:2021)

### OBS-1 ShareViewRemoteAddrProductionPathIT (IT-sec-SV-RL-01)
- New IT at `src/test/java/de/seism0saurus/glacier/security/ShareViewRemoteAddrProductionPathIT.java`
- Mirrors `SubscribeRateLimitProductionPathIT` but connects to `/share-view-ws?shareLinkId=<id>` instead of `/websocket`
- Seeds share link via `@Autowired InMemoryShareLinkRepository` direct injection (NOT via POST /rest/share-links — that endpoint is rate-limited by ShareRateLimiter, ADR-5)
- Must use `glacier.cookie.secure=false` so cookie name is `shareViewerId` (not `__Host-shareViewerId`) — plain HTTP test server
- Viewer ID value: `sv_` + 43 URL-safe base64 chars = 46 chars minimum (isValidShareViewerId requirement)
- Share link ID: 43-char URL-safe base64 string, passed as `?shareLinkId=` query param
- Topic destinations: `/topic/share/{shareLinkId}/creation{i}` — required by ShareViewTopicAuthInterceptor
- Interceptor order: subscribeRateLimitInterceptor first → wallTopicAuthInterceptor → shareViewTopicAuthInterceptor
  - Frames 4-5 (above threshold=3) are silently dropped by rate limiter BEFORE auth interceptor runs
  - This guarantees rate-limit AUDIT events are emitted even if auth would otherwise reject those frames
- Asserts: ws.subscribe.rate_limited event present, ip-hash= present, ip-hash=null absent
- Passed green on first run (existing production code already correct: F-1 fix from OWASP cycle populates REMOTE_ADDR)

### Test counts after this phase
- Surefire: same (no new unit tests)
- Failsafe: 187 IT tests, 0 failures (+1 new OBS-1 IT)
- BUILD SUCCESS, all Jacoco thresholds met

**How to apply:** Any new /share-view-ws IT must set `glacier.cookie.secure=false` and pass `shareViewerId` (not `__Host-shareViewerId`) as the cookie header. Always seed share links via `InMemoryShareLinkRepository` injection rather than the rate-limited REST endpoint.

---

## OWASP Standards Integration — new security controls (Phase 2, 2026-05-01/04)

**Why:** Gap analysis against WSTG 4.2, ASVS 5.0, Proactive Controls 2024 found 9 gaps.

### iframe sandbox (SR-NEW-01, ADR-2)
- `toot.component.html` iframe now has `sandbox="allow-scripts allow-popups allow-popups-to-escape-sandbox"`
- `allow-same-origin` is intentionally absent — it would give the embed access to the embedding origin's storage
- Mastodon embed's height-resize postMessage protocol is cross-origin and does NOT require allow-same-origin

### OwaspMatrixCookieAttributesLockstepTest (SR-NEW-04, ADR-3)
- Parses OWASP_COVERAGE_MATRIX.md for SameSite claims on table rows containing "wallId"
- Hits GET /rest/wall-id with MockMvc and compares actual Set-Cookie header against matrix claim
- MUST be committed RED before the doc lane corrects the matrix (two commits must not be squashed)
- wallId cookie uses SameSite=Lax (code truth) — matrix was wrongly claiming SameSite=Strict
- `__Host-shareCsrf` cookie uses SameSite=Strict (correct, no change)

### HttpMethodRejectFilter (SR-NEW-10)
- `src/main/java/de/seism0saurus/glacier/webservice/HttpMethodRejectFilter.java`
- `@Order(HIGHEST_PRECEDENCE)` — runs before CORS, auth, any filter
- Rejects TRACE and TRACK with 405 + Allow header; all other methods pass through
- Without Spring Security, TRACE returns 200 by default in Spring MVC — this filter prevents header echo

### ResponseBodySecretLeakIT (SR-NEW-03/05, ADR-4/ADR-5)
- Canary wallId: `00000000-0000-0000-0000-000000000001` (fixed, never generated by randomUUID)
- Allowlisted: `/rest/wall-id` intentionally echoes the UUID (SPA bootstrap)
- Stack-trace regex: `Exception|Throwable|java\.\w+\.|de\.seism0saurus\.|at \w[\w.$]*\(.*\.java:\d+\)`
- Runs in 4 nested classes: LiveMode, FallbackMode, KillswitchMode, InsecureTransportMode
- Killswitch mode needs `hashtag` param on /rest/messages — Spring MVC validates @RequestParam before controller kill-switch check

### CorsHardeningIT (SR-NEW-06)
- Case A: allowed origin preflight → 200/204 + ACAO + Max-Age ≥ 600 (Spring default = 1800)
- Case B: attacker origin → ACAO completely absent (not echoed)
- Case C: no ACAC: true on /rest/messages, /rest/operator, /rest/wall-id (main CORS configurer)
- Case D: ACAO is exact origin, never wildcard
- NOTE: /rest/share-links and /rest/share-csrf intentionally have allowCredentials(true) — this is by design for wallId cookie cross-origin; these endpoints are OUT OF SCOPE for Case C

### CookieEmissionIT extensions (SR-NEW-07, AC-12/AC-13)
- AC-12: class-level glacier.cookie.secure=true → Secure flag confirmed present
- AC-13: new nested InsecureTransportCookieMode class with glacier.cookie.secure=false → Secure absent, HttpOnly+SameSite=Lax still present
- Nested @WebMvcTest classes require @MockitoBean (not @MockBean) for the inner class's dependencies

### Test counts after this phase
- Surefire: 1040 tests before doc lane correction (1 intentional RED failure)
- After doc lane matrix correction: 1040 tests, 0 failures expected
- Failsafe: new ITs: ResponseBodySecretLeakIT (28 tests), HttpMethodHardeningIT (30), CorsHardeningIT (13)

**How to apply:** When adding new endpoints, add them to AUTHORITATIVE_ENDPOINT_ALLOWLIST AND the matrix. When parsing Markdown tables in tests, filter to lines starting with `|` only. TRACE blocking is now at the filter layer — do not rely on Spring MVC or reverse proxy alone.
