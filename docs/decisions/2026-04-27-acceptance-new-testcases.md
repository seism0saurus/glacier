# Decision Record: New Testcases — Acceptance

Date: 2026-04-27
Phase: Acceptance
Agents: security-auditor (Round 1 + fix routing), acceptance-test-auditor (Round 1 + fix routing)
Status: **PASSED**

## Summary

Phase 3 acceptance audit validated all 1,410 tests (731 Java unit + 157 Java IT + 522 Angular/Karma), confirmed 0 failures, Jacoco thresholds pass, and all Phase 1 security requirements are addressed. Seven fix-cycle items were resolved before sign-off. The feature pipeline for "Find and build useful new testcases" is complete.

## Acceptance Disposition: PASSED

All requirements from Phase 1 planning (`docs/decisions/2026-04-27-planning-new-testcases.md`) are met. No Critical or High findings remain open.

## Final Test Counts

| Suite | Count | Failures |
|-------|-------|----------|
| Java unit (Surefire) | 731 | 0 |
| Java integration (Failsafe) | 157 | 0 |
| Angular/Karma | 522 | 0 |
| **Total** | **1,410** | **0** |

Jacoco instruction ≥ 45% / branch ≥ 35% thresholds: **PASS**.

## Security Findings and Dispositions

### Fixed in fix cycles (before sign-off)

| ID | Finding | Severity | Resolution |
|----|---------|----------|-----------|
| F-3 | `ShareSecurityHeadersFilter` domain values not validated at startup — misconfigured hostname could inject extra CSP directives | HIGH | `@PostConstruct validateDomains()` added; regex `^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)*(:[0-9]{1,5})?$` rejects injection payloads; startup fails fast on bad config |
| F-8/SR-TEST-22 | `WallTopicSubscribeIsolationIT` asserted only that a cross-principal SUBSCRIBE was dropped — did not verify own-topic SUBSCRIBE succeeds with a real message published | MEDIUM | Test strengthened: publishes via `SimpMessagingTemplate`, asserts message received on own-topic; cross-principal frame still silently dropped |
| F-9 | `WebSocketConfigurationTest` had no assertion that `WallTopicAuthInterceptor` is registered | MEDIUM | Added `configureClientInboundChannel_registersWallTopicAuthInterceptor` with `ArgumentCaptor<ChannelInterceptor>` |
| SR-TEST-07 | Rate-limit 429 response shape not asserted across all endpoints | MEDIUM | `RateLimitResponseShapeUniformityIT` covers `/rest/wall-id`, `/rest/subscribe`, `/rest/share/*` — asserts status 429 + `Retry-After` header on all three |
| SR-TEST-23 | Angular `subscription.service.spec.ts` missing STOMP reconnect-resubscribe and localStorage corruption recovery tests | MEDIUM | 3 new tests added: reconnect triggers resubscribe, corrupt localStorage recovered gracefully, subscriptions not persisted until server ack |
| F-10/SR-TEST-01 | CSRF-before-rate-limit ordering not integration-tested (MUST requirement from Phase 1) | MEDIUM | `ShareLinkCsrfRateLimitOrderingIT` written: CSRF failure does not consume a rate-limit token; valid CSRF + bad body does consume |
| SR-TEST-06 | jqwik HMAC fuzz test deferred from Phase 2 | LOW | `net.jqwik:jqwik:1.8.4` added to `pom.xml`; `ImageProxyUrlBuilderVerifyFuzzTest` covers 3 property-based scenarios × 500 tries |

### R-2 TOCTOU race — real production bug fixed

`ShareLinkServiceImpl.create()` had a check-then-act race: concurrent callers could overshoot the per-sharer cap. This was not a test gap — it was a real production bug discovered during the fix cycle.

**Fix**: Added `ConcurrentHashMap<String, ReentrantLock> sharerLocks` for per-sharer locking around the cap check + save sequence. `CreateShareLinkAtCapacityRaceIT` (10 concurrent callers at cap boundary) went red first, confirmed green after fix.

### Accepted residual risks

| Risk | Status | Rationale |
|------|--------|-----------|
| R-3 SVG Content-Type spoof | LOW — accepted | Mitigated by `nosniff` header + CSP; no SVG upload surface in scope |
| R-4 CSRF no server-side expiry | LOW — accepted | `SameSite=Strict` is the primary mitigation; documented design decision |
| R-5 HMAC tokens survive share-link revocation | Accepted design decision | Revocation invalidates the DB record; HMAC still verifies cryptographically but the service layer rejects revoked links |
| R-2 TOCTOU race | **FIXED** — `ConcurrentHashMap<String, ReentrantLock>` in production | `CreateShareLinkAtCapacityRaceIT` confirms cap is never exceeded under 10-thread concurrency |
| Playwright security specs (cross-wall, killswitch, revoked share, SSRF) | Deferred | Require full dockerized Mastodon stack; non-blocking for this feature |

## Production Code Changes (complete list)

| File | Change | Security requirement |
|------|--------|---------------------|
| `WallTopicAuthInterceptor.java` (new) | STOMP SUBSCRIBE isolation — R-1 BOLA fix | ADR-TEST-01, SR-TEST-21 |
| `WebSocketConfiguration.java` (modified) | Registers `WallTopicAuthInterceptor` before `ShareViewTopicAuthInterceptor` | ADR-TEST-01 |
| `ShareSecurityHeadersFilter.java` (modified) | Explicit-host `connect-src`, `@PostConstruct validateDomains()` | ADR-TEST-03, F-3 |
| `StatusUpdatedMessage.java` (modified) | `@JsonAlias("edited_at")` | TDD-driven fix |
| `ShareLinkServiceImpl.java` (modified) | Per-sharer `ReentrantLock` for cap atomicity | R-2 TOCTOU |
| `pom.xml` (modified) | `net.jqwik:jqwik:1.8.4` test dependency | SR-TEST-06 |

## New and Extended Test Files (complete list)

### Secure lane

| File | Tests | Requirement |
|------|-------|-------------|
| `WallTopicAuthInterceptorTest` | 8 unit | SR-TEST-21, SR-TEST-08 (canonical field names), D-13 (`doesNotContain(rawWallId)`) |
| `WallTopicSubscribeIsolationIT` | 3 IT | SR-TEST-22: cross-principal dropped + own-topic succeeds with real message |
| `PrincipalKeyCrossNamespaceTest` | 9 unit | SR-TEST-09: WallPrincipal ≠ ShareViewerPrincipal |
| `CspNonceFilterTest` | 5 unit | Nonce uniqueness, URL-safe Base64 |
| `CsrfTokenCookieFactoryTest` | 7 unit | SR-TEST-03: exactly one Set-Cookie per name |
| `ShareSecurityHeadersFilterTest` | 8 unit | SR-TEST-04, F-3: no bare `wss:`, explicit hosts verified, bad hostname rejected at startup |
| `DtoFieldScanTest` | 3 unit | SR-TEST-05: package-exhaustive scan, `*Message` suffix, allowlist for principal fields |
| `S1_WallIdCookieFlagsTest` | 5 unit | SR-TEST-02: HttpOnly, SameSite=Lax, Path=/, MaxAge, cardinality=1 |
| `ShareLinkIdInjectionTest` | 17 unit | SR-TEST-17: path traversal, NUL, control chars, overlong |
| `ShareLinkCsrfRateLimitOrderingIT` | 2 IT | SR-TEST-01: CSRF fail ≠ rate-limit token consumed |
| `RateLimitResponseShapeUniformityIT` | 3 IT | SR-TEST-07: 429 + Retry-After on all rate-limited endpoints |
| `CreateShareLinkAtCapacityRaceIT` | 1 IT | R-2: 10 concurrent callers at cap, count ≤ cap always |
| `ImageProxyUrlBuilderVerifyFuzzTest` | 3 property | SR-TEST-06: jqwik HMAC fuzz, 500 tries per property |
| `WebSocketConfigurationTest` (extended) | +1 unit | F-9: WallTopicAuthInterceptor registration verified |
| `CookieEmissionIT` (extended) | +1 IT | SR-TEST-03: cardinality assertion for wallId Set-Cookie |

### Dev lane

| File | Tests | Area |
|------|-------|------|
| `ShareLinkServiceImplTest` | 10 unit | Cap enforcement, resolve branches, revoke relay |
| `NoOpShareLinkServiceTest` | 4 unit | Killswitch stub contracts |
| `MessagingDtoSerdeTest` | 10 unit | JSON serde incl. `edited_at` alias regression |
| `FallbackResponseTest` | 4 unit | CacheEntryView field mapping |
| `SnapshotTest` | 2 unit | Structural equality, ring-drop gap flag |
| `SubscriptionControllerCapTest` | 2 unit | CAP_EXCEEDED ack, idempotent subscribe |
| `StompCallbackTest` (extended) | +5 unit | genericMessage update/delete, malformed drop, 302/XFO |
| `SubscriptionManagerImplTest` (extended) | +1 unit | Per-principal map isolation |

### Angular / Karma

| File | Tests | Area |
|------|-------|------|
| `share-link.guard.spec.ts` (new) | 5 | Guard allow/redirect branches |
| `rx-stomp.factory.spec.ts` (new) | 3 | wss/ws scheme, wall-id pre-connect |
| `subscription.service.spec.ts` (extended) | +6 | Persistence only after ack, corrupt localStorage recovery, reconnect-resubscribe (SR-TEST-23) |
| `wall.component.spec.ts` (extended) | +2 | aria-live announce, deleted toot removal |

## Phase 1 ADR Coverage

| ADR | Requirement | Status |
|-----|-------------|--------|
| ADR-TEST-01 | STOMP BOLA isolation test | **DONE** — `WallTopicAuthInterceptorTest` + `WallTopicSubscribeIsolationIT` |
| ADR-TEST-02 | DTO field scan | **DONE** — `DtoFieldScanTest` (package-exhaustive, `*Message` suffix) |
| ADR-TEST-03 | Explicit-host connect-src | **DONE** — `ShareSecurityHeadersFilterTest` |
| ADR-TEST-04 | HMAC fuzz (MAY) | **DONE** — `ImageProxyUrlBuilderVerifyFuzzTest` (jqwik) |
| ADR-TEST-05 | Rate-limit uniformity | **DONE** — `RateLimitResponseShapeUniformityIT` |

## Resolved Conflicts (from Phase 2)

### `WallTopicAuthInterceptor.hash8` — wrong algorithm
**secure-tdd-implementer**: used `String.hashCode()` (32-bit Java hash).
**tdd-ddd-implementer**: flagged as D-13/SR-8 violation.
**Resolution**: `LogScrubber.hash8()` (SHA-256). Raw `destination={}` removed from AUDIT format. `doesNotContain(rawVictimWallId)` assertion added.

### Worktree codebase regression
**tdd-ddd-implementer cross-review**: secure worktree was forked from an older codebase state, deleting `ShareViewTopicAuthInterceptor` and other share infrastructure.
**Resolution**: Surgical addition only — worktree changes discarded except net-new files; all modifications applied to main's richer codebase.

### `PrincipalHandler` empty-string fallback — BOLA bypass
**tdd-ddd-implementer cross-review**: worktree used `wallId = ""` fallback allowing any UUID topic subscription.
**Resolution**: Worktree change discarded. Main repo already has UUID-generation fallback with AUDIT logging.

### `DtoFieldScanTest` scope gap for `*Message` classes
**secure-tdd-implementer cross-review**: `SubscriptionAckMessage.principal` and `TerminationAckMessage.principal` were unguarded.
**Resolution**: `"Message"` suffix added to scan; explicit allowlist entries document why `principal` is architecturally required in those two DTOs.

## User Approval
Date: 2026-04-27
Approval message (verbatim): "ok,, approve"

## References

- [New Testcases — Planning](2026-04-27-planning-new-testcases.md)
- [New Testcases — Implementation](2026-04-27-implementation-new-testcases.md)
- [OWASP API Security Top 10 (2023)](https://owasp.org/API-Security/editions/2023/en/0x11-t10/) — [API1 — Broken Object Level Authorization](https://owasp.org/API-Security/editions/2023/en/0xa1-broken-object-level-authorization/) (R-1) — closed by `WallTopicAuthInterceptor`
- [OWASP API Security Top 10 (2023)](https://owasp.org/API-Security/editions/2023/en/0x11-t10/) — [API3 — Excessive Data Exposure](https://owasp.org/API-Security/editions/2023/en/0xa3-excessive-data-exposure/) — closed by `DtoFieldScanTest`
- D-13/SR-8 — log hygiene enforced via `LogScrubber.hash8()`
- ADR-TEST-01 through ADR-TEST-05 — all addressed
- jqwik property-based testing: `net.jqwik:jqwik:1.8.4`
