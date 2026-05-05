# Decision Record: OWASP Coverage Matrix Completion — Acceptance

Date: 2026-04-30
Phase: Acceptance (Phase 3)
Agents: security-auditor (Round 1 + Round 2 + re-verification), acceptance-test-auditor (Round 1), devops-infra-engineer (SA-F1 fix), secure-tdd-implementer (SA-F2 fix)
Status: Accepted — PASSED

## Summary

The OWASP Coverage Matrix Completion feature passes Phase 3 acceptance. All nine OWASP coverage
gaps (G1–G9) identified in the Phase 1 planning document are closed. All 16 security requirements
(SR-OI-01/02, SR-WS-01..06, SR-LOG-WS-01, SR-CI-01..03, SR-MTX-01/02, SR-MODE-WS-01) are
satisfied. Seven security findings were fixed before sign-off: five found by the acceptance-test-
auditor (F-1..F-4, F-8) and two by the security-auditor (SA-F1, SA-F2). Five low/informational
items are deferred to the TD backlog. The build passes with 949 unit tests + 186 integration tests,
0 failures in any feature-scope test, and Jacoco coverage thresholds met.

## Acceptance Disposition: PASSED

**Date**: 2026-04-30
**User approval message (verbatim)**: "approve" (Phase 3 gate)

## Test Results

| Layer | Tests | Failed | Notes |
|-------|-------|--------|-------|
| Surefire (unit `*Test.java`) | 949 | 0 | +2 from SA-F2 isolation tests; pre-existing jqwik flake `ImageProxyUrlBuilderVerifyFuzzTest` intermittent, unrelated |
| Failsafe (integration `*IT.java`) | 186 | 0 | +1 from SA-F1 `HandshakeForwardedForRespectedIT`; includes 14 new OWASP-feature ITs total |
| Jacoco coverage gate | met | — | instruction ≥ 45%, branch ≥ 35%, bundle-wide |

## Security Findings and Dispositions

### F-1 — FIXED (Critical): SubscribeRateLimitInterceptor per-IP axis dead in production

`SubscribeRateLimitInterceptor.extractIp()` read `REMOTE_ADDR` from STOMP session attributes
but no production code wrote it. All SUBSCRIBE frames keyed into `"unknown:<wallId>"`;
per-IP isolation was completely inert.

**Fix**: Both `PrincipalHandler.determineUser()` and `ShareViewPrincipalHandler.determineUser()`
now write `attributes.put(SubscribeRateLimitInterceptor.REMOTE_ADDR, servletRequest.getRemoteAddr())`
after the existing `SESSION_ID` write, guarded by `request instanceof ServletServerHttpRequest`.
A shared constant on `SubscribeRateLimitInterceptor` eliminates magic-string drift.

**Regression test**: `SubscribeRateLimitProductionPathIT.subscribeRateLimit_throughRealHandshake_populatesIpInAuditLog`
— connects via real `WebSocketStompClient` without manual attribute injection; asserts AUDIT
log contains `ip-hash=` and does NOT contain `ip-hash=null`.

### F-2 — FIXED (High): SR-OI-02 structural guard was behavioral

`StompCallbackOptInEnforcementTest.allEventHandlers_callIsOptedIn_structurally` was behavioral
(asserts `messageCache.recordThenPublish` never called) rather than reflective/structural.
Phase 1 SR-OI-02 mandated "verification via reflection that all paths call `isOptedIn`".

**Fix**: Added `UT-sec-04d` (`allEventHandlerMethods_invokeIsOptedIn_bySourceInspection`) that
reads `StompCallback.java` source text via `StompCallback.class.getProtectionDomain()
.getCodeSource().getLocation()` and asserts each of `processStatusCreatedEvent`,
`processStatusEditedEvent`, and `sendMessage` contains `isOptedIn(` within the method body.

### F-3 — FIXED (Medium): `bucketCapacity` parameter silently ignored

Both interceptors accepted a `bucketCapacity` `@Value`-injected constructor parameter that
was never assigned or used. Corresponding `application.properties` entries were misleading.

**Fix**: Parameter removed from both constructors; matching properties removed from
`application.properties` and `src/test/resources/application.properties`.

### F-4 — FIXED (Medium): IT-sec-07b tautological assertion

`WebSocketFrameSizeLimitIT.subscribeFrame_above64KB_isRejectedOrDisconnected` ended with
`assertThat(true).isTrue()` — unfalsifiable regardless of whether `setMessageSizeLimit` was
configured.

**Fix**: Replaced with `assertThat(errorOrDisconnect.get()).isTrue()`. Spring's
`setMessageSizeLimit` causes the transport to close the session on receipt of an oversize
frame, setting the atomic boolean via `handleTransportError`.

### F-8 — FIXED (Medium): IT-sec-04 mass-assignment guard skippable

`StompMassAssignmentIT.subscribeFrame_withInjectedPrincipalField_doesNotOverridePrincipal`
wrapped its core assertion in `if (ack.getPrincipal() != null)` — silently skipped when the
ack DTO carried a null principal.

**Fix**: Guard removed; `assertThat(ack.getPrincipal()).isEqualTo(cookieWallId)` is now
unconditional.

### SA-F1 — FIXED (High): `FORWARD_HEADERS_STRATEGY` not wired in production compose (security-auditor)

`application.properties` defaulted `server.forward-headers-strategy` to `NONE`. In production behind
Traefik, `request.getRemoteAddr()` returned Traefik's container IP for every connection — collapsing
per-IP rate-limit isolation to a single shared bucket. Cookie-rotation DoS protection was inert.
Phase 1 planning doc incorrectly claimed the setting was "already set". `HandshakeRateLimitInterceptor`
Javadoc referenced the wrong strategy value (`NATIVE`) and wrong Spring class (`ForwardedHeaderTransformer`,
reactive-stack) for a servlet-stack app.

**Fix**: Added `FORWARD_HEADERS_STRATEGY: "FRAMEWORK"` to the glacier service env in
`infrastructure/docker-compose.yaml:164` (production only — override test-fixture files intentionally
excluded, no proxy in their path). Corrected `HandshakeRateLimitInterceptor.java` Javadoc. Updated
SR-WS-03 in planning doc. Clarified comments in both override files. Added
`HandshakeForwardedForRespectedIT` (IT-sec-FH-01): boots with `FRAMEWORK` via `@TestPropertySource`,
injects `X-Forwarded-For: 1.2.3.4`, asserts AUDIT log contains `1.2.3.xxx`.

### SA-F2 — FIXED (Medium): Per-key bucket isolation not regression-tested (security-auditor)

Both `HandshakeRateLimitInterceptorTest` and `SubscribeRateLimitInterceptorTest` used a single
test-key constant throughout. A refactor collapsing all keys to a single bucket would pass the
existing tests silently.

**Fix**: Added `UT-WS-RL-06: differentSourceIps_haveIndependentBuckets()` (capacity=1, exhausts
IP_A, asserts IP_B still allowed) and `UT-SUBRL-05-ISO: differentIpOrPrincipal_haveIndependentBuckets()`
(covers both IP and principal axes of the composite key). Mutation guard: changing bucket key to
constant string fails both tests.

### F-5 — DEFERRED (Low): IT-sec-07c log-leak race with `Thread.sleep(200)`

Fixed sleep before log-leak assertion; real leak could flush after the window. No false
positives observed. Defer: replace with Awaitility-based stability poll.

### F-6 — DEFERRED (Low): EndpointInventoryTest does not enforce matrix lockstep

Adding a new `/rest/` endpoint to the allowlist does not force a matrix update.
Defer: add matrix-row assertion to `EndpointInventoryTest`.

### F-7 — DEFERRED (Informational): OWASP matrix audit-event name drift

Matrix references `WS_HANDSHAKE_RATE_LIMITED`; code emits `ws.handshake.rate_limited`.
Defer: cosmetic docs fix.

### F-9 — DEFERRED (Informational): Trivy `trivyignores` parameter spelling

Input name verified correct for `aquasecurity/trivy-action@0.30.0`. Defer: add sentinel
suppression entry to confirm the mechanism is active.

## Non-Blocking Observation (security-auditor Round 2)

**OBS-1 (Low)**: `ShareViewPrincipalHandler.determineUser()` now writes `REMOTE_ADDR` at
handshake time (F-1 fix), but no IT exercises the `/share-view-ws` endpoint end-to-end to
confirm the attribute is visible to `SubscribeRateLimitInterceptor`. The F-1 production-path
IT covers `/websocket` only. Deferred to TD backlog; does not block merge.

## Security Requirements — Final Status

| SR | Requirement | Status |
|----|-------------|--------|
| SR-OI-01 | `isOptedIn()` called in all 3 StompCallback event handlers | PASS |
| SR-OI-02 | UT-sec-04d structural source-text gate | PASS (F-2 fixed) |
| SR-WS-01 | HandshakeRateLimitInterceptor on /websocket + /share-view-ws; HTTP 429 on breach | PASS |
| SR-WS-02 | SubscribeRateLimitInterceptor; silent drop; AUDIT event | PASS |
| SR-WS-03 | Source IP via REMOTE_ADDR populated at handshake + `FORWARD_HEADERS_STRATEGY=FRAMEWORK` in production compose | PASS (F-1 + SA-F1 fixed) |
| SR-WS-04 | Both interceptors fail-open on Throwable | PASS |
| SR-WS-05 | Explicit setMessageSizeLimit/setSendBufferSizeLimit/setSendTimeLimit | PASS |
| SR-WS-06 | Rate-limit thresholds operator-tunable | PASS (F-3 fixed) |
| SR-LOG-WS-01 | AUDIT events use LogScrubber.maskIp(ip) for bare IPs | PASS |
| SR-CI-01 | Trivy fs scan targets jar; fails on HIGH/CRITICAL not in .trivyignore-fs | PASS |
| SR-CI-02 | docker manifest inspect + jar existence preconditions | PASS |
| SR-CI-03 | TrivyignoreExpiryTest validates # expires: on both ignore files | PASS |
| SR-MTX-01 | OWASP_COVERAGE_MATRIX.md zero blank cells (240 cells, 0 blank) | PASS |
| SR-MTX-02 | EndpointInventoryTest allowlist is authoritative | PASS |
| SR-MODE-WS-01 | Test classes declare mode applicability in class Javadoc | PASS |

## Accepted Residual Risks

| ID | Risk | Rationale |
|----|------|-----------|
| AR-WS-01 | Rate-limit buckets in-memory (single-instance only) | Glacier currently single-instance; revisit before any HA deployment |
| AR-WS-02 | Source IP trusted from X-Forwarded-For via single Traefik proxy | Internal cluster bypass out of threat model; consistent with FallbackRateLimiter |
| AR-WS-03 | Known wallId allows hashtag-topic probing at rate-limited rate | 122-bit UUID + 50-hashtag cap limits information gain; no sensitive data exposed |

## TD Backlog Items

| Item | Deferred from |
|------|--------------|
| Replace `Thread.sleep(200)` in IT-sec-07c with Awaitility poll | F-5 |
| Enforce matrix lockstep in EndpointInventoryTest | F-6 |
| Fix OWASP matrix audit-event name drift (`ws.handshake.rate_limited`) | F-7 |
| Add sentinel suppression to `.trivyignore-fs` to verify `trivyignores` parameter | F-9 |
| Add IT covering `/share-view-ws` REMOTE_ADDR production path | OBS-1 |
| Fix `ImageProxyUrlBuilderVerifyFuzzTest.anyMutationOfValidTokenReturnsEmpty` jqwik flake (pre-existing, HMAC URL signing collision at mutation index ~167) | SA-3 |

## References

- [Phase 1 Planning Document](2026-04-30-planning-owasp-matrix-completion.md)
- [Phase 2 Implementation Document](2026-04-30-implementation-owasp-matrix-completion.md)
- [OWASP_COVERAGE_MATRIX.md](../../infrastructure/security/OWASP_COVERAGE_MATRIX.md)
- [SECURITY_TESTS.md](../../infrastructure/security/SECURITY_TESTS.md)
- [glacier-pentest-automator agent](../../.claude/agents/glacier-pentest-automator.md)
