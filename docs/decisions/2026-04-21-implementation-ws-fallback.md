# Decision Record: WebSocket HTTP Fallback — Implementation

Date: 2026-04-21
Phase: Implementation
Agents: tdd-ddd-implementer, secure-tdd-implementer, devops-infra-engineer, frontend-designer
Status: Accepted

## Summary

The full WebSocket → HTTP short-poll fallback feature was implemented against the frozen Phase 1 plan (`docs/decisions/2026-04-21-planning-ws-fallback.md`, 21 decisions + 8 ADRs + clarifications C-01/C-02/C-03). Four specialist agents delivered Round 1, followed by cross-review (Round 2) that surfaced five FIX REQUESTs (two correctness bugs, three defense-in-depth tests). All five were resolved. The final clean `./mvnw verify` run reports Surefire **264/264**, Failsafe **62/62**, Karma **187/187**, Jacoco ≈ 93% instruction / 85% branch, BUILD SUCCESS.

## Files touched (summary)

### Backend — new
`webservice/cache/`: `CacheEntry`, `PerTagRing(+size())`, `MessageCache`, `MessageCacheImpl`, `Snapshot`, `EventType`, `CacheCapacityException`, `UnknownSubscriptionException`, `FallbackResponse`, `FallbackRateLimiter(+TokenBucket.tryConsume()/refund())`, `FallbackSecurityHeadersFilter`.
`webservice/`: `FallbackController`, `FallbackControllerAdvice`, `FallbackAuthGuard`, `CookieBasedFallbackAuthGuard`, `PassthroughFallbackAuthGuard`.
`util/`: `LogScrubber`.
`webservice/messaging/messages/`: `SubscriptionRejection`, `RejectionCode`.

### Backend — modified
`StompCallback.java`, `SubscriptionManagerImpl.java`, `SubscriptionController.java`, `InformationController.java`, `SubscriptionAckMessage.java`, `GlacierApplication.java`, `application.properties`, `logback.xml`, `pom.xml` (+`spring-boot-starter-actuator`, `micrometer-core`).

### Tests
**Unit (Surefire 264/264)** — full pyramid coverage per CLAUDE.md: `PerTagRingTest`, `MessageCacheImplTest` (incl. 3 gauge tests), `FallbackRateLimiterTest` (incl. atomicity + stale-bucket), `FallbackControllerTest`, `FallbackControllerAdviceTest`, `FallbackSecurityHeadersFilterTest`, `CookieBasedFallbackAuthGuardTest`, `LogScrubberTest`, `LoggingSmokeTest`, `SubscriptionRejectionHygieneTest`, `SubscriptionRejectionTest`, `CodebaseConstraintTest`; plus extensions to `StompCallbackTest` (incl. C-03 timeout paths), `SubscriptionManagerImplTest`, `InformationControllerTest`, `SubscriptionControllerTest`, `CacheEntryTest`.
**Integration (Failsafe 62/62)** — `FallbackControllerIT`, `FallbackSecurityIT` (incl. CORS preflight header assertion), `FallbackAtomicityIT`, `MessageCacheSubscriptionLifecycleIT`, `CorsConfigurationIT`, `CookieEmissionIT`, `RateLimitHeaderTrustIT`, `ActuatorExposureIT` (incl. heapdump/threaddump/loggers 404 assertions).
**Frontend (Karma 187/187)** — `connection-status.component.spec.ts`, `fallback.service.spec.ts`, `delivery-cursor.spec.ts`, extensions to `subscription.service.spec.ts` (incl. JSON-wire contract test), `hashtag.component.spec.ts`, `header.component.spec.ts`, `app.component.spec.ts`.
**Playwright** — 4 new specs (`fallback.spec.ts`, `fallback-ux.spec.ts`, `fallback-killswitch.spec.ts`, `fallback-insecure.spec.ts`). Authored; full-stack execution runs in CI.

### Frontend — new
`app/fallback/{transport-mode.ts, delivery-cursor.ts, fallback.service.ts}`, `app/connection-status/{.ts,.html,.css}`, `assets/i18n/messages.en.json`, `app/message-types/subscription-rejection.d.ts`.

### Frontend — modified
`app.module.ts`, `app.component.{ts,html,css,spec.ts}`, `header.component.html`, `hashtag/hashtag.component.{ts,spec.ts}`, `subscription.service.{ts,spec.ts}`, `rx-stomp.config.ts`, `rx-stomp.factory.ts`, `main.ts` (`@angular/localize` runtime catalogue), `message-types/subscription-ack-message.d.ts`, environments (3 files), `tsconfig.spec.json`, `playwright.config.ts`, `package.json` (+`@axe-core/playwright`).

### Infra / CI
`infrastructure/docker-compose.override.killswitch.yaml` (new), `infrastructure/docker-compose.override.insecure.yaml` (new), `infrastructure/README.md` (TEST FIXTURE banner + `FORWARD_HEADERS_STRATEGY=FRAMEWORK` operator note), `.github/workflows/verify.yml` (+`e2e-killswitch`, +`e2e-insecure` jobs with structural checks for `PLAYWRIGHT_PROJECT` and port bindings).

### Documentation
`CLAUDE.md` updated with `JAVA_HOME` per-developer note and `.claude/settings.local.json` guidance. `README.md` Java 21→23 cosmetic fix.

## Key Decisions (Phase 2 record)

### D-P2-01 — `CacheEntry` wire shape pinned both sides (FIX #1)
**Decision**: Backend `FallbackResponse.CacheEntryView.statusId` serialises to JSON key `"id"` via `@JsonProperty("id")`; frontend `CacheEntry.id` matches. Pinned by `FallbackControllerIT` (backend) and a `JSON.parse`-driven test in `subscription.service.spec.ts` (frontend).
**Rationale**: Round 1 frontend-designer mirrored the Java field name `statusId` rather than the serialised key `"id"`. At runtime every `entry.statusId` read `undefined`, silently breaking dedup and DELETE semantics in the fallback path. Round 2 tdd-ddd-implementer cross-review caught it.
**Source**: FIX REQUEST #1 (tdd-ddd-implementer Round 2 → frontend-designer; closed).

### D-P2-02 — D-11 Micrometer gauges wired (FIX #2)
**Decision**: `MessageCacheImpl` constructor registers `glacier.cache.principals.count` (reads `store.size()`) and `glacier.cache.entries.total` (sums `PerTagRing::size`). Counter `glacier.fallback.publish.failures` was already present.
**Rationale**: D-11 explicitly mandated these gauges; Round 1 only emitted the counter. Gauges are the primary operational visibility for the 10 000-principal / 10×20 entry caps.
**Source**: FIX REQUEST #2 (tdd-ddd-implementer Round 2 self-owned; closed).

### D-P2-03 — `TokenBucket` TOCTOU fix via atomic `tryConsume()`/`refund()` (FIX #3)
**Decision**: `TokenBucket.tryConsume()` holds the `ReentrantLock` across the full check-and-decrement; `refund()` returns a token on the wallId-then-IP-axis rejection path under the same lock. `check()` and `checkIpOnly()` call `tryConsume()` exclusively. Split `hasToken()`/`consume()` retained as dead code; deletion deferred as tech debt.
**Rationale**: Round 1 `FallbackRateLimiterTest.check_1000ConcurrentThreads_noDataCorruption` was intermittently flaky — 1/261 failure observed on the first Round 2 verify run. Root cause was a classic TOCTOU between `hasToken()` and `consume()` under 1000-thread burst contention. Option A (atomic primitive) taken per Round 2 recommendation.
**Source**: FIX REQUEST #3 (tdd-ddd-implementer Round 2 → secure-tdd-implementer; closed). Verified deterministic across three consecutive clean runs.

### D-P2-04 — `PLAYWRIGHT_PROJECT` forwarded into playwright container (FIX #4)
**Decision**: Both `docker-compose.override.killswitch.yaml` and `.override.insecure.yaml` declare `PLAYWRIGHT_PROJECT` in the `playwright` service `environment:` block (hardcoded to `killswitch` / `insecure` respectively). The two CI structural checks now grep the merged compose output for the expected value and fail early if absent.
**Rationale**: Round 2 devops-infra-engineer cross-review discovered that `PLAYWRIGHT_PROJECT` in the GitHub Actions step `env:` is never forwarded into a container spawned by `docker compose up` unless the compose file's service-level `environment:` block explicitly declares it. Without this fix, both `e2e-killswitch` and `e2e-insecure` jobs would run all Playwright projects, cross-contaminating the two scoped jobs and producing misleading pass/fail signals.
**Source**: FIX REQUEST #4 (devops-infra-engineer Round 2 self-owned; closed). Verified via `docker compose config` for both overrides.

### D-P2-05 — Actuator endpoint 404 coverage extended to high-risk set (FIX #5)
**Decision**: `ActuatorExposureIT` adds three explicit 404 assertions for `/internal/actuator/heapdump`, `/threaddump`, `/loggers` alongside the existing `env`, `beans`, `configprops`, `mappings` tests.
**Rationale**: `management.endpoints.web.exposure.include=health,info` already blocks these at the framework level, but explicit tests pin the invariant so a future property drift that adds them to the include list would fail CI. OWASP A05 defense-in-depth; heapdump in particular exposes raw wallId / cookie values.
**Source**: FIX REQUEST #5 (secure-tdd-implementer Round 2 → tdd-ddd-implementer; closed).

## Clarifications (Phase 1 plan amendments)

All three Phase 1 clarifications remain as recorded in `docs/decisions/2026-04-21-planning-ws-fallback.md`:

- **C-01** — CI trigger scope stays at `push: branches: ["*.*.*"]`; expansion to `main` deferred.
- **C-02** — `playwright.extra_hosts: ["host.docker.internal:host-gateway"]`; `fallback-insecure.spec.ts` navigates to `http://host.docker.internal:8081`.
- **C-03** — Scope expansion: `RestTemplate` connect/read timeouts (3 s / 5 s defaults); new `glacier.embed.*` properties; `StompCallback` wraps both `headForHeaders` call sites in `RestClientException` catches.

## Resolved Conflicts

None. No `## ⚡ CONFLICT:` markers were raised across Round 1 or Round 2. Five FIX REQUESTs issued and all closed (see Key Decisions above).

## Final test roll-up (clean `./mvnw verify`)

| Layer | Count | Status |
|---|---|---|
| Surefire (unit) | 264 / 264 | PASS |
| Failsafe (integration) | 62 / 62 | PASS |
| Karma (frontend unit, Chrome Headless 147) | 187 / 187 | PASS |
| Jacoco — instruction | ~93 % | PASS (threshold 45 %) |
| Jacoco — branch | ~85 % | PASS (threshold 35 %) |
| Playwright E2E | not run locally | authored; runs in CI against real docker stack |
| `docker compose config` (killswitch override) | merged playwright env contains `PLAYWRIGHT_PROJECT: killswitch`; no port 8081 | VERIFIED |
| `docker compose config` (insecure override) | merged playwright env contains `PLAYWRIGHT_PROJECT: insecure`; glacier port `127.0.0.1:8081:8080`; `extra_hosts: host.docker.internal=host-gateway`; `COOKIE_SECURE: false` | VERIFIED |

## User Approval

Date: 2026-04-21
Approval message (verbatim): "I approve"

## Open Risks (accepted)

1. **No `@SpringBootTest` IT** that drives a real `HEAD`/embed HTTP call through `RestTemplate` to confirm C-03 timeout values end-to-end. Call-site behaviour covered by unit tests with mocked `ResourceAccessException`. *Disposition*: flagged for Phase 3 security-auditor.
2. **Pre-existing `processStatusEditedEvent` (StreamEvent path)** has no loadability check. Pre-dates this PR; devops flagged as out of scope. *Disposition*: tracked for a follow-up PR.
3. **Logback `JsonLayout`** cannot mechanically drop `cookie`/`setCookie`/`authorization` fields. Enforcement is convention-based + `LoggingSmokeTest`. *Disposition*: accepted at planning (see D-13 and logback.xml comment).
4. **Jacoco exec file race** under back-to-back local `./mvnw verify` runs without `clean`. Not a CI risk (fresh checkout per job). *Disposition*: CLAUDE.md note recommending `clean verify` for local runs.
5. **Runtime `@angular/localize`** adds one fetch of `messages.en.json`; no new npm runtime dep. *Disposition*: accepted at planning (D-18).
6. **Three in-class SHA-256 hash-truncation copies** (`LogScrubber.hash8`, `FallbackController.hashWallId8`, `CookieBasedFallbackAuthGuard.hashWallId`). Not a security issue; tech debt. *Disposition*: follow-up cleanup PR.
7. **Brief double-delivery window during WS↔fallback transitions**, absorbed by client `statusId` dedup. *Disposition*: accepted at planning.
8. **`recordThenPublish` persists cache entry even when STOMP publish throws** — exposed via WARN log + `glacier.fallback.publish.failures` counter. No alerting in this PR. *Disposition*: accepted at planning.
9. **+~60 s CI runner time per verify run** for the two new override-based Playwright stages (wall-time near-zero since they run in parallel with `e2e`). *Disposition*: accepted at planning.

## References

- `docs/decisions/2026-04-21-planning-ws-fallback.md` — frozen Phase 1 plan (21 decisions + 8 ADRs + C-01/C-02/C-03).
- `CLAUDE.md` — testing policy, architecture, conventions (binding).
- Round 1 and Round 2 agent reports (archived in pipeline transcript).
- OWASP Top 10 (2025), OWASP API Security Top 10 (2023), TSS-WEB.
- WCAG 2.2 AA.
