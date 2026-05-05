# Decision Record: New Testcases — Implementation

Date: 2026-04-27
Phase: Implementation
Agents: tdd-ddd-implementer (Round 1 + fix routing), secure-tdd-implementer (Round 1 + fix routing)
Status: Accepted

## Summary

Phase 2 implemented 132 new tests (720 Java unit + 149 Java IT + 519 Angular/Karma = 1,388 total) and four production code fixes. The R-1 BOLA vulnerability is closed by the new `WallTopicAuthInterceptor`. `ShareSecurityHeadersFilter` connect-src was hardened from bare `wss:` to explicit hosts. The `@JsonAlias("edited_at")` production fix resolves silent null on all Mastodon status-update events. All Jacoco thresholds pass.

## Production Code Changes

### `WallTopicAuthInterceptor.java` (new)

**Decision**: Create `WallTopicAuthInterceptor implements ChannelInterceptor` in `webservice.messaging`. For every STOMP SUBSCRIBE to `/topic/hashtags/{wallId}/...`, assert the subscriber's `WallPrincipal` name equals the path segment. Reject with null return + AUDIT event on mismatch.
**Security**: Uses `LogScrubber.hash8()` (SHA-256) for both principal name and destination wallId. Raw destination string never appears in any log — D-13/SR-8 compliant. AUDIT event format: `stomp.subscribe.rejected reason=cross_principal principal-hash8={} destination-wallId-hash8={}`.
**Source**: ADR-TEST-01 (Phase 1), secure-tdd-implementer Round 1, fix routing to correct `String.hashCode()` → `LogScrubber.hash8()`.

### `WebSocketConfiguration.java` (modified)

**Decision**: Surgical addition of `WallTopicAuthInterceptor` to `configureClientInboundChannel`, running before the existing `ShareViewTopicAuthInterceptor`. The existing share-link viewer endpoint, `secureCookies` flag, `ShareViewPrincipalHandler` bean, and dual-endpoint registration were preserved unchanged.
**Rationale**: The worktree had regressed to an older codebase state. Only the interceptor addition was applied.
**Source**: tdd-ddd-implementer Round 2 conflict finding; fix routing.

### `ShareSecurityHeadersFilter.java` (modified)

**Decision**: Replace `connect-src 'self' wss:` with `connect-src 'self' wss://${shareHost} wss://${glacierDomain}`. Domain values injected via `@Value("${glacier.share.host}")` and `@Value("${glacier.domain}")` constructor parameters.
**Rationale**: ADR-TEST-03. Bare `wss:` permits the share-view page to open a WebSocket to any TLS-capable host, turning an XSS finding into a full-bandwidth exfiltration channel.
**Source**: ADR-TEST-03 (Phase 1), secure-tdd-implementer fix routing.

### `StatusUpdatedMessage.java` (modified)

**Decision**: Added `@JsonAlias("edited_at")` to the `editedAt` field.
**Rationale**: TDD-driven fix. `MessagingDtoSerdeTest.statusUpdatedMessage_serializes_editedAt_correctly` went red first. The Mastodon API sends snake_case `"edited_at"` in streaming events; without the alias the field was silently null on all status-update events, breaking edit-notification rendering.
**Source**: tdd-ddd-implementer Round 1.

## New Test Files

### Secure lane (9 new test files)

| File | Tests | Security requirement |
|------|-------|---------------------|
| `webservice/messaging/WallTopicAuthInterceptorTest` | 8 unit | SR-TEST-21; includes `doesNotContain(rawWallId)` (D-13) |
| `webservice/messaging/WallTopicSubscribeIsolationIT` | 2 IT | SR-TEST-22: real STOMP cross-principal isolation |
| `webservice/messaging/PrincipalKeyCrossNamespaceTest` | 9 unit | SR-TEST-09: WallPrincipal ≠ ShareViewerPrincipal |
| `share/web/CspNonceFilterTest` | 5 unit | Nonce uniqueness, URL-safe Base64 |
| `share/web/CsrfTokenCookieFactoryTest` | 7 unit | SR-TEST-03: exactly one Set-Cookie per name |
| `share/web/ShareSecurityHeadersFilterTest` | 8 unit | SR-TEST-04: no bare `wss:`, explicit hosts verified |
| `webservice/DtoFieldScanTest` | 3 unit | SR-TEST-05: package-exhaustive scan (`*Message` suffix added + allowlist) |
| `webservice/S1_WallIdCookieFlagsTest` | 5 unit | SR-TEST-02: HttpOnly, SameSite=Lax, Path=/, MaxAge |
| `webservice/ShareLinkIdInjectionTest` | 17 unit | SR-TEST-17: path traversal, NUL, control chars, overlong |

### Dev lane (6 new + 4 extended test files)

| File | Tests | Area |
|------|-------|------|
| `share/application/ShareLinkServiceImplTest` | 10 unit | Cap enforcement, resolve branches, revoke relay |
| `share/application/NoOpShareLinkServiceTest` | 4 unit | Killswitch stub contracts |
| `webservice/messaging/messages/MessagingDtoSerdeTest` | 10 unit | JSON serde incl. `edited_at` alias regression |
| `webservice/cache/FallbackResponseTest` | 4 unit | CacheEntryView field mapping |
| `webservice/cache/SnapshotTest` | 2 unit | Structural equality, ring-drop gap flag |
| `webservice/SubscriptionControllerCapTest` | 2 unit | CAP_EXCEEDED ack, idempotent subscribe |
| `mastodon/StompCallbackTest` (extended) | +5 unit | genericMessage update/delete, malformed drop, 302/XFO |
| `mastodon/SubscriptionManagerImplTest` (extended) | +1 unit | Per-principal map isolation |
| `frontend/share/guards/share-link.guard.spec.ts` (new) | 5 Karma | Guard allow/redirect branches |
| `frontend/rx-stomp.factory.spec.ts` (new) | 3 Karma | wss/ws scheme, wall-id pre-connect |
| `frontend/subscription.service.spec.ts` (extended) | +3 Karma | Persistence only after ack, corrupt localStorage recovery |
| `frontend/wall/wall.component.spec.ts` (extended) | +2 Karma | aria-live announce, deleted toot removal |

## Test Results

| Suite | Count | Failures |
|-------|-------|----------|
| Java unit (Surefire) | 720 | 0 |
| Java integration (Failsafe) | 149 | 0 |
| Angular/Karma | 519 | 0 |
| **Total** | **1,388** | **0** |

Jacoco instruction ≥ 45% / branch ≥ 35% thresholds: **PASS**.

## Resolved Conflicts

### `WallTopicAuthInterceptor.hash8` — wrong algorithm
**secure-tdd-implementer worktree**: used `String.hashCode()` (32-bit Java hash).
**tdd-ddd-implementer cross-review**: flagged as D-13/SR-8 violation; must use `LogScrubber.hash8()` (SHA-256) to preserve AUDIT log correlation with all other components.
**Resolution (2026-04-27)**: Replaced with `LogScrubber.hash8()`. Removed raw `destination={}` from AUDIT log format. Added `doesNotContain(rawVictimWallId)` assertion to `WallTopicAuthInterceptorTest`.

### Worktree `WebSocketConfiguration` regression
**tdd-ddd-implementer cross-review**: worktree version was an older codebase state that deleted `ShareViewTopicAuthInterceptor`, share-view endpoint, and `secureCookies` flag.
**Resolution (2026-04-27)**: Surgical addition only — `WallTopicAuthInterceptor` added to existing method alongside `shareViewTopicAuthInterceptor`.

### Worktree `PrincipalHandler` empty-string fallback — BOLA bypass
**tdd-ddd-implementer cross-review**: worktree used `wallId = ""` fallback; an empty principal passes the interceptor for any real UUID destination.
**Resolution (2026-04-27)**: Worktree change discarded. Main repo already returns `new WallPrincipal(wallId)` with UUID-generation fallback for missing/invalid cookies.

### `ShareSecurityHeadersFilter` bare `wss:`
**secure-tdd-implementer cross-review**: bare `wss:` is an ADR-TEST-03 violation.
**Resolution (2026-04-27)**: Explicit hosts injected via `@Value`. `ShareSecurityHeadersFilterTest` asserts no bare `wss:` and both explicit WSS hosts present.

### `DtoFieldScanTest` scope gap for `*Message` classes
**secure-tdd-implementer cross-review**: `SubscriptionAckMessage.principal` and `TerminationAckMessage.principal` were unguarded by the scan.
**Resolution (2026-04-27)**: `"Message"` suffix added to scan. Explicit allowlist entries document why `principal` is architecturally required in those two classes (frontend needs wallId to construct topic subscriptions).

## User Approval
Date: 2026-04-27
Approval message (verbatim): "approve"

## Deferred to Phase 3

| Item | Reason |
|------|--------|
| B1–B5 AUDIT logging tests (cookie rejection, rate-limit, cap exhaustion, log scrubber, field names) | Classes in scope; tests not written — deferred, non-blocking |
| H1 CSRF-before-rate-limit ordering IT | FallbackController outside worktree scope |
| H6 Killswitch matrix IT | FallbackController outside worktree scope |
| Rate-limit response shape uniformity IT | Non-blocking coverage gap |
| Concurrency stress tests (viewer counter, TOCTOU race R-2) | Non-blocking; R-2 risk accepted |
| Image proxy tests (cache key isolation, byte cap, content-type bypass) | Non-blocking coverage gap |
| Playwright security specs (cross-wall, killswitch, revoked share, SSRF) | Require full dockerized stack |
| jqwik HMAC fuzz (ADR-TEST-04) | No jqwik dependency yet; MAY-level requirement |
| Replay/revocation tests | Non-blocking coverage gap |

## Open Risks (from Phase 1)

| Risk | Status |
|------|--------|
| R-1 STOMP BOLA (WallPrincipal cross-subscription) | **FIXED** — `WallTopicAuthInterceptor` in production |
| R-2 TOCTOU race in `ShareLinkService.create` | MEDIUM — `CreateShareLinkAtCapacityRaceIT` not yet written |
| R-3 SVG Content-Type spoof | LOW — mitigated by nosniff + CSP |
| R-4 CSRF no server-side expiry | LOW — SameSite=Strict mitigation documented |
| R-5 HMAC tokens survive share-link revocation | Accepted design decision |

## References

- [New Testcases — Planning](2026-04-27-planning-new-testcases.md)
- [OWASP API Security Top 10 (2023)](https://owasp.org/API-Security/editions/2023/en/0x11-t10/) — [API1 — Broken Object Level Authorization](https://owasp.org/API-Security/editions/2023/en/0xa1-broken-object-level-authorization/) (R-1) — closed by `WallTopicAuthInterceptor`
- D-13/SR-8 — log hygiene enforced via `LogScrubber.hash8()`
- ADR-TEST-01 through ADR-TEST-05 — all addressed except ADR-TEST-04 (deferred)
- [New Testcases — Acceptance](2026-04-27-acceptance-new-testcases.md)
