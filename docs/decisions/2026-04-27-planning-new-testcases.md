# Decision Record: New Testcases — Planning

Date: 2026-04-27
Phase: Planning
Agents: ddd-tdd-architect (×2 rounds), secure-feature-planner (×2 rounds)
Status: Accepted

## Summary

A full test-gap audit of the Glacier codebase identified both coverage gaps (production classes with no test counterpart) and behavioral/contract gaps (missing branches, non-functional properties, and security invariants). The planning phase produced a unified test plan covering all three layers (Java unit+IT, Angular Karma, Playwright e2e) across two implementation lanes. A critical active BOLA vulnerability (R-1) was discovered during planning and is addressed by TDD-driven production code changes alongside the new tests.

## Active Vulnerability Found in Planning

### R-1 — STOMP Topic Hijack (BOLA)
Spring's simple in-memory broker (`enableSimpleBroker("/topic")`) provides no per-principal isolation for `/topic/hashtags/...` destinations. The existing `ShareViewTopicAuthInterceptor.preSend()` explicitly returns message unchanged for any non-`ShareViewerPrincipal` — meaning all `WallPrincipal` SUBSCRIBE frames are unchecked. Any authenticated user who learns another user's `wallId` UUID can subscribe to their full toot stream with no authorisation check and no audit trail.

**Severity**: HIGH. **Likelihood**: MEDIUM (128-bit UUID entropy protects against blind guessing; wallId disclosure via share-link BOLA, XSS, or earlier log leaks converts to active eavesdrop).

**Fix**: New `WallTopicAuthInterceptor` registered in `WebSocketConfiguration.configureClientInboundChannel`, asserting `WallPrincipal.getName().equals(wallIdSegment)` for every SUBSCRIBE under `/topic/hashtags/`. Tests go red first; interceptor makes them green.

## Key Decisions

### ADR-TEST-01: WallTopicAuthInterceptor fixes active BOLA
**Decision**: Introduce `WallTopicAuthInterceptor` mirroring `ShareViewTopicAuthInterceptor`. For every STOMP SUBSCRIBE to `/topic/hashtags/{wallId}/...`, assert the subscriber's `WallPrincipal` name equals the path segment. Reject with ERROR frame and AUDIT event on mismatch.
**Rationale**: Spring simple broker performs no principal-scoping for unqualified `/topic/...` destinations. This is OWASP API1 (BOLA) at the messaging layer. A 30-line interceptor closes it without changing the fan-out model.
**Alternatives considered**: Move publishes to `/user/topic/...` (breaks cache key model), encode HMAC in destination (increases surface area), use Spring Security messaging (no existing Spring Security in project — too much blast radius for one rule).
**Source**: ddd-tdd-architect Round 2, confirmed by secure-feature-planner Round 2.

### ADR-TEST-02: DTO field scan is package-exhaustive, not an allowlist
**Decision**: `DtoFieldScanTest` uses classpath scanning over `de.seism0saurus.glacier.**` for any class whose name ends in `Response`, `Entry`, or `Dto`. Asserts no declared field is named `wallId`, `sharerWallId`, `principal`, `rawWallId`, or `userId`.
**Rationale**: A class-name allowlist silently exempts future DTOs from the check. Package-exhaustive scanning means any new response DTO with a forbidden field breaks the build immediately.
**Alternatives considered**: Opt-in annotation (`@ContainsNoWallId`) — rejected, same defect as allowlist.
**Source**: secure-feature-planner Round 1 CONFLICT → accepted by architect Round 2.

### ADR-TEST-03: connect-src uses explicit hosts, not bare wss:
**Decision**: `ShareSecurityHeadersFilter` must emit `connect-src 'self' wss://share.${glacier.domain} wss://${glacier.domain}`. Bare `wss:` is forbidden. Tests assert the directive string and fail on bare token.
**Rationale**: Bare `wss:` permits the page to open a WebSocket to any TLS-capable host — an XSS finding upstream becomes a full-bandwidth exfiltration channel. Pinning to explicit hosts is cheap and meaningful.
**Source**: secure-feature-planner Round 1 CONFLICT → accepted by architect Round 2.

### ADR-TEST-04: jqwik property-based fuzz for HMAC verify path
**Decision**: `ImageProxyUrlBuilderVerifyFuzzTest` uses jqwik to assert: (a) any non-sign()-produced byte string returns `Optional.empty()` and never throws; (b) any mutation of a valid token returns `Optional.empty()`; (c) sign+verify roundtrip succeeds.
**Rationale**: The verify path has 8+ failure branches reachable by attackers. Property-based testing covers the combinatorial space no enumerated test list can exhaustively reach.
**Source**: secure-feature-planner Round 1, item 1 adaptation.

### ADR-TEST-05: Log field naming harmonised to -hash8 suffix
**Decision**: All AUDIT log entries that emit hashed IDs use the canonical suffix `-hash8=` (e.g., `wallId-hash8=`, `viewerId-hash8=`, `shareId-hash8=`, `url-hash8=`). `B5_LogFieldNameConsistencyTest` captures a `ListAppender` and asserts no key matching `.*Id$` appears without the `-hash8` suffix.
**Rationale**: Current codebase has inconsistent naming (`viewerId-hash` vs `wallId-hash8`). Canonical convention prevents future drift and makes log queries deterministic.
**Source**: secure-feature-planner Round 1, item 5 adaptation.

## Phase 2 Lane Partition

### secure-tdd-implementer lane (44 test classes/specs + 6 production code fixes)

Production code changes (TDD-driven — tests go red first):
1. New `WallTopicAuthInterceptor` (fixes R-1 BOLA)
2. Tighten `ShareSecurityHeadersFilter.connectSrc` to explicit hosts
3. Harmonise log field names to `-hash8` suffix across all AUDIT events
4. Fix CSRF cookie dual-emission (exactly one `Set-Cookie` per cookie name)
5. Add AUDIT event for cross-principal SUBSCRIBE rejection
6. Add TOCTOU guard in `ShareLinkServiceImpl.create` (if `CreateShareLinkAtCapacityRaceIT` goes red)

Test classes:
- **Crypto**: `ImageProxyUrlBuilderVerifyFuzzTest` (jqwik), `SafeUrlValidatorEdgeTest`, `HashtagPatternUnicodeStressTest`
- **Log hygiene**: `B1_AuditLoggingForCookieRejectionTest`, `B2_AuditLoggingForRateLimitTest`, `B3_AuditLoggingForCapExhaustionTest`, `B4_LogScrubberInvariantTest`, `B5_LogFieldNameConsistencyTest`
- **Cookie/CSRF**: `S1_WallIdCookieFlagsTest`, `S1_CsrfCookieDualEmissionTest`, `S1_ShareViewerCookieMaxAgeBoundsTest`
- **STOMP isolation**: `WallTopicAuthInterceptorTest`, `WallTopicSubscribeIsolationIT`, `TerminateOtherPrincipalSubscriptionTest`, `StompUnauthenticatedConnectAttemptIT`, `StompOriginAllowlistIT`, `ShareViewPrincipalHandlerEdgeTest`
- **DTO leakage**: `DtoFieldScanTest`
- **CSP/headers**: `ShareSecurityHeadersFilterTest`, `SecurityHeaderApplicabilityMatrixIT`, `CorsCredentialsHandlingTest`, `ErrorBodyHygieneIT`
- **Rate limit/ordering**: `H1_CsrfBeforeRateLimitOrderingIT`, `RateLimitResponseShapeUniformityIT`, `H6_KillswitchMatrixIT`, `XForwardedForSpoofingIT`
- **Image proxy**: `HostHeaderInjectionViaProxyTest`, `ImgProxyContentTypeBypassTest`, `ImgProxyByteCapBoundaryTest`, `ImgProxyCachePoisoningTest`, `A6_ImageProxyCacheKeyIsolationTest`, `H10c_SafeUrlValidatorEgressIT`
- **Concurrency**: `ViewerCounterRaceConditionStressTest`, `CreateShareLinkAtCapacityRaceIT`
- **Replay/revocation**: `RevokedShareLinkSubscribeReplayTest`, `CsrfTokenExpiryReplayTest`, `RevokeShareLinkAuthorizationTest`
- **Input injection**: `ShareLinkIdInjectionTest`
- **Playwright (security)**: `security-r1-cross-wall.spec.ts`, `security-killswitch-matrix.spec.ts`, `security-share-revoked.spec.ts`, `security-image-proxy-ssrf.spec.ts`
- **Karma (security)**: share-view component STOMP topic isolation cases
- **CspNonceFilter**: `CspNonceFilterTest` + extend `CspOnShareRouteIT`
- **CsrfTokenCookieFactory**: `CsrfTokenCookieFactoryTest`
- **ShareImageProxyService**: `ShareImageProxyServiceTest`, `ShareImageProxyControllerTest`
- **ShareLinkCapPolicy**: `ShareLinkCapPolicyTest`
- **PrincipalKey**: `PrincipalKeyCrossNamespaceTest`

### tdd-ddd-implementer lane (9 test classes/specs)

- **Subscription lifecycle**: `SubscriptionControllerCapTest`, `SubscriptionListenerReconnectIT`, `SubscriptionManagerImplLifecycleTest`
- **Message routing**: `StompCallbackEventDispatchTest`, `MessageCacheImplReplayOrderingTest`
- **Domain/service**: `InformationControllerCookieIssuanceTest`, `ShareLinkServiceImplTest`, `NoOpShareLinkServiceTest`, `MessagingDtoSerdeTest`
- **Angular Karma**: extend `subscription.service.spec.ts`, `wall.component.spec.ts`; new `share-link.guard.spec.ts`, `rx-stomp.factory.spec.ts`
- **Playwright (functional)**: extend `toots.spec.ts` (hashtag-prune regression H8); `reconnect-gap.spec.ts` (H9)

## Security Requirements

| ID | Statement | Level |
|----|-----------|-------|
| SR-TEST-01 | CSRF check happens before rate-limit; token not consumed on CSRF failure | MUST |
| SR-TEST-02 | Cookie flags (Secure, HttpOnly, SameSite, Path, MaxAge) tested × 2 transport modes | MUST |
| SR-TEST-03 | Exactly one Set-Cookie header per cookie name | MUST |
| SR-TEST-04 | connect-src explicit hosts; no bare wss: token | MUST |
| SR-TEST-05 | Package-exhaustive DTO field scan for forbidden field names | MUST |
| SR-TEST-06 | jqwik property fuzz for HMAC verify path | MUST |
| SR-TEST-07 | All 429 responses: uniform shape + Retry-After | MUST |
| SR-TEST-08 | Log field naming canonical (-hash8 suffix) | MUST |
| SR-TEST-09 | PrincipalKey cross-namespace isolation in rate-limiter and cache | MUST |
| SR-TEST-10 | Viewer SUBSCRIBE isolation — cannot access other share link's topic | MUST |
| SR-TEST-11 | SafeUrlValidator rejects all alternate-IP-form representations | MUST |
| SR-TEST-12 | Killswitch mode matrix (catalog OK, polling 404, STOMP OK) | MUST |
| SR-TEST-13 | Revocation: SUBSCRIBE rejected, STOMP closed, poll returns 404 | MUST |
| SR-TEST-14 | Concurrency stress: viewer counter + service.create TOCTOU | MUST |
| SR-TEST-15 | Error bodies contain no stack traces, raw IDs, internal paths | MUST |
| SR-TEST-16 | __Host- prefix cookies require Path=/ | MUST |
| SR-TEST-17 | ShareLinkId injection: path traversal, NUL, overlong, control chars | SHOULD |
| SR-TEST-18 | Timing equivalence smoke: CSRF-fail vs rate-limited within 2× p95 | SHOULD |
| SR-TEST-19 | X-Forwarded-For honored only with FRAMEWORK strategy | SHOULD |
| SR-TEST-20 | Image proxy magic-number sniff (MAY) | MAY |
| SR-TEST-21 | WallPrincipal cannot subscribe to another WallPrincipal's topic (R-1) | MUST |
| SR-TEST-22 | Principal binding survives reconnect — WallTopicAuthInterceptor re-validates | MUST |
| SR-TEST-23 | Client-side Karma regression: Angular never subscribes to /topic/hashtags/{otherId} | MUST |

## Resolved Conflicts

### connect-src bare wss: vs explicit hosts
**secure-feature-planner**: bare `wss:` allows WS to any Internet host; pin to explicit domain hosts.
**ddd-tdd-architect**: accepted in full.
**Resolution (2026-04-27)**: explicit hosts required; test asserts bare `wss:` token absent.

### DTO field scan allowlist vs package-exhaustive
**secure-feature-planner**: allowlist silently exempts future DTOs; use package scan.
**ddd-tdd-architect**: accepted in full.
**Resolution (2026-04-27)**: package-exhaustive scan over `*Response`/`*Entry`/`*Dto`.

## User Approval
Date: 2026-04-27
Approval message (verbatim): "approve"

## Open Risks

| Risk | Severity | Status |
|------|----------|--------|
| R-1 STOMP BOLA — WallPrincipal cross-subscription | HIGH | Fixed by WallTopicAuthInterceptor (TDD-driven in Phase 2) |
| R-2 TOCTOU race in ShareLinkService.create | MEDIUM | CreateShareLinkAtCapacityRaceIT will reveal; fix in Phase 2 if red |
| R-3 SVG via Content-Type spoof by hostile upstream | MEDIUM | Mitigated by nosniff + CSP; magic-number check is MAY |
| R-4 CSRF no server-side expiry | LOW | Substantially mitigated by SameSite=Strict; documented and pinned |
| R-5 HMAC tokens survive share-link revocation | MEDIUM | Accepted design decision; documented explicitly |

## References

- [OWASP API Security Top 10 (2023)](https://owasp.org/API-Security/editions/2023/en/0x11-t10/) — [API1 — Broken Object Level Authorization](https://owasp.org/API-Security/editions/2023/en/0xa1-broken-object-level-authorization/) (R-1)
- [`glacier-fallback-mode-discipline`](../../.claude/skills/glacier-fallback-mode-discipline.md) skill — three-mode invariant coverage
- [`glacier-structured-logging-logback`](../../.claude/skills/glacier-structured-logging-logback.md) skill — D-13/SR-8 log hygiene
- [Share Link QR — Acceptance](2026-04-24-acceptance-share-link-qr.md)
- [Hashtag Prune — Acceptance](2026-04-24-acceptance-hashtag-prune.md)
- [New Testcases — Implementation](2026-04-27-implementation-new-testcases.md)
- [New Testcases — Acceptance](2026-04-27-acceptance-new-testcases.md)
