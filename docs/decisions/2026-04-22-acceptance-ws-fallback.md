# Decision Record: WebSocket HTTP Fallback — Acceptance

Date: 2026-04-22
Phase: Acceptance
Agents: security-auditor, acceptance-test-auditor
Status: Accepted — **PASSED**

## Summary

The `ws-fallback` feature completes the Glacier `/feature` pipeline with a **PASSED** disposition after one fix cycle. Phase 3 Round 1 surfaced 12 security findings (F-01..F-12) and 4 acceptance-criteria gaps (`cap.reached.snackbar.no.limit` i18n key, `cache.capacity.exhausted` AUDIT event, `MessageCacheSubscriptionLifecycleIT` 5-min timer, axe-core WCAG tags + three missing state audits). Round 2 produced one inter-auditor conflict on F-12 which was resolved by the security-auditor conceding to the acceptance-auditor's argument (reflection into private timeout fields proves "setter was called", not "socket honours the value under a hung peer"). The user chose the comprehensive fix scope (Option C — all 13 findings). Four Phase 2 agents executed disjoint lanes in parallel; both auditors re-verified and returned PASS with no conditions. Authoritative `./mvnw verify`: Surefire **296** / Failsafe **71** / Karma **181** / Jacoco **93.3% instruction / 87.8% branch** / BUILD SUCCESS.

## Disposition: PASSED

Both Phase 3 auditors returned PASS independently, with no remaining conflicts between them, after re-verifying each fix against the production code and its regression test.

### Security-auditor verdict (verbatim extract)

> "**PASS.** All blocker findings (F-01, F-02, F-03) are closed with code fixes and regression tests; all non-blocker findings with FIX REQUESTs attached are closed; the F-12 conflict resolution produced a real socket-level IT with honest scope documentation; the `SubscriptionListener` follow-on change is a net security positive. Residual items accepted without condition: F-09 `anonymiseIp` left in `InformationController` (tech debt, no live defect), and the Karma −6 regression (frontend-quality concern outside this audit's scope). No remaining security-auditor conflicts with Phase 2 outputs or with the acceptance auditor."

### Acceptance-test-auditor verdict (verbatim extract)

> "**PASS.** All 12 security-auditor findings and all 4 acceptance-auditor additions have executable, test-pyramid-appropriate coverage. Jacoco moved in the right direction (instruction +0.6%, branch +2.1% — an increase despite adding code, which indicates the new tests are pulling coverage with them). The Karma count drop is explained and semantically inert. No new acceptance-criterion gap surfaced. The `/feature` pipeline Phase 3 gate can close."

## Findings dispositions

| # | Finding | Severity | Disposition | Fix anchor | Regression test |
|---|---|---|---|---|---|
| F-01 | D-11 killswitch did not stop cache writes | High | Fixed | `MessageCacheImpl.java:94,124-138,187-191` — `fallbackEnabled` gates `recordThenPublish` ring-write and `provisionHashtag`; STOMP fan-out preserved in kill-switch branch | `MessageCacheImplTest#recordThenPublish_killswitchOff_isNoOp_returnNullAndDoesNotWriteToStore`, `…_stompFanoutPreservedButNoCacheWrite`, `provisionHashtag_killswitchOff_isNoOp` |
| F-02 | D-13 log hygiene: raw wallId logged on 4 production paths | High | Fixed | `StompCallback.java`, `SubscriptionManagerImpl.java`, `SubscriptionController.java`, `SubscriptionListener.java` — every principal log arg now routed through `LogScrubber.hash8`; `IllegalArgumentException` messages in `terminateSubscription` no longer echo raw values | `RawWallIdLogHygieneTest.java` — 8 methods with `ListAppender` per target class |
| F-03 | BOLA / cross-principal leak via unvalidated WebSocket principal | High | Fixed | `PrincipalHandler.java:82-102` — missing/null/blank/short wallIds yield fresh `UUID.randomUUID().toString()`; `MIN_WALL_ID_LENGTH=32` mirrors HTTP guard; emits `AUDIT.info("websocket.auth.fail reason=... sessionId=...")` | `PrincipalHandlerTest` (unit branches) + `WebSocketPrincipalValidationIT` × 5 — two-session tests assert `p1.getName() != p2.getName()` |
| F-04 | WS `setAllowedOrigins` permitted `http://localhost:8080` in prod | Medium | Fixed | `WebSocketConfiguration.java:70-75` — origin gated on `cookieSecure` constructor arg; TODO removed | `WebSocketConfigurationOriginTest` × 3 |
| F-05 | MongoDB config with hardcoded password | Low (downgraded from Medium — Mongo starter commented out in `pom.xml`) | Fixed | `application.properties` — lines 70–76 deleted | `CodebaseConstraintTest#assertNoHardcodedPasswords_inApplicationProperties` — regex lint excludes `${...}` env-var forms |
| F-06 | `recordThenPublish` WARN + counter on publish failure | Medium | Accepted (no change required) | Already correctly redacted per D-13 / D-P2-04 | `FallbackAtomicityIT` (existing) |
| F-07 | `statusId`/`hashtag` raw in log fields | Info | Accepted per D-13 explicit permission | — | — |
| F-08 | `environment.allowPlaintext` dead code | Low | Fixed | `fallback.service.ts:175` — INSECURE gate reads `environment.production && !environment.allowPlaintext && window.location.protocol === 'http:'` | 3 new Karma tests in `fallback.service.spec.ts` |
| F-09 | 4 SHA-256 hash-truncation copies | Info | Fixed with residual | `FallbackController.hashWallId8`, `CookieBasedFallbackAuthGuard.hashWallId`, `MessageCacheImpl.hashPrincipal` deleted; all call sites route through `LogScrubber.hash8` | `LogScrubberTest` — blank-input parametrized, legacy-truncation for known UUIDs, concrete SHA-256 pin `"70c8ebbb"`. **Residual**: `InformationController.anonymiseIp` is a 5th copy missed by this scope; accepted as tech debt (security-auditor: "no live defect"). |
| F-10 | Shared `ipBuckets` between `/rest/wall-id` and `/rest/messages` | Low | Accepted | Bounded impact at 120/min | — |
| F-11 | `PassthroughFallbackAuthGuard` registered as prod `@Component` | Info | Fixed | `@Profile("test")` added | `PassthroughFallbackAuthGuardProfileTest` via `ApplicationContextRunner` |
| F-12 | No IT for C-03 `RestTemplate` timeouts against a real socket | Low | Fixed (conflict-resolved: security-auditor flipped ACCEPT → FIX) | `EmbedTimeoutIT.java` — WireMock `withFixedDelay(10_000)` against `readTimeoutMs=500`; `@Timeout(10)` sentinel; `<2000ms` wall-clock bound; falsification check documented in class Javadoc. **Accepted limitation**: connect-timeout scenario not reliably reproducible in-process (kernel backlog); reflection-based `RestTemplateRedirectTest` pins configured value. | 2 IT tests |
| + | `cache.capacity.exhausted` AUDIT event missing (D-13 list item never emitted) | Medium | Fixed | `SubscriptionController.java:43,107` — declares `AUDIT` logger; catch block emits `cache.capacity.exhausted principal-hash={} limit={} axis=hashtags-per-principal` | `SubscriptionControllerTest#subscribe_cacheCapacityExceeded_emitsAuditEvent` |
| + | Missing `cap.reached.snackbar.no.limit` i18n catalog key | Low | Fixed | `messages.en.json` — key added | 3 catalog-completeness Karma tests in `hashtag.component.spec.ts:262-300` using `fetch('/assets/i18n/messages.en.json')` |
| + | ADR-05 `MessageCacheSubscriptionLifecycleIT` 5-min timer not implemented | Low | Fixed | `MessageCacheDisconnectTimerIT.java` — `@SpringBootTest` + `glacier.timeouts.client_reconnect=500ms` + Awaitility + `ApplicationEventPublisher.publishEvent(SessionDisconnectEvent)` | 2 scenarios: eviction after timeout; cache preserved on reconnect within timeout |
| + | axe-core `.analyze()` missing WCAG tags; PROBING/OFFLINE/KILLSWITCHED not audited | Low | Fixed | `frontend/e2e/helper/a11y.ts` — `WCAG_22_AA_TAGS`, `assertNoWcag22AaViolations`, `runAxeOnlyInChromium`. `fallback-ux.spec.ts` audits 4 states; `fallback-killswitch.spec.ts:130` adds KILLSWITCHED; `fallback-insecure.spec.ts:99` migrated to helper | Playwright specs in chromium/killswitch/insecure projects (run in CI only) |

## Unplanned production change made during the fix cycle

`SubscriptionManagerImpl.terminateAllSubscriptions` previously returned early when the subscription map was empty, skipping `evictPrincipal`. The `tdd-ddd-implementer` found this while implementing `MessageCacheDisconnectTimerIT` (because fallback-only clients never populated the subscription map but did provision the cache). The fix makes eviction unconditional on the 5-min disconnect timer.

**Security-auditor re-verification**: *"No new security concern. `evictPrincipal` is idempotent (`store.remove(principal)`). The closure captures `event.getUser().getName()` for the eviction key — necessary, and the raw value never reaches a log line (principal-hash is captured separately into `principalHash` at line 183). This closes a genuine availability gap for fallback-only clients without expanding the auth surface."*

## Resolved Conflicts

### F-12 disposition

**security-auditor (Round 1)**: *"ACCEPT. `RestTemplateRedirectTest` + `StompCallbackTest` are sufficient."*

**acceptance-test-auditor (Round 1)**: *"My position: a `MockWebServer`-driven IT that times out the real `RestTemplate` should exist. Reflecting into private timeout fields proves the configuration is set, not that the timeout actually fires."*

**Resolution** (Round 2, auto-resolved between auditors): security-auditor flipped to FIX REQUEST. *"The auditor's argument is load-bearing: reflection into `connectTimeout`/`readTimeout` proves the setter was called, not that the socket honours the value under a hung-peer scenario. The C-03 threat model is a remote Mastodon instance that accepts the TCP connect but never sends bytes — exactly what a reflection test cannot verify."*

Delivered by `devops-infra-engineer` as `EmbedTimeoutIT`. Acceptance-auditor withdrew the conflict in the re-verify round.

### F-05 lane bleed (parallel execution side-effect)

`secure-tdd-implementer` (outside its lane) modified `application.properties` to replace the Mongo password literal with `${MONGODB_PASSWORD}`; `devops-infra-engineer` (in its lane) deleted the entire dead Mongo block. The merged final state is **deletion** (verified via `grep -i "mongodb\|password" src/main/resources/application.properties` → empty), which aligns with the user's Option C scope. No user decision required — the orchestrator observed the final state and confirmed it was the intended outcome.

## Final test roll-up (authoritative — orchestrator's own `./mvnw verify` run)

| Layer | Phase 2 baseline | Post-fix | Delta | Threshold |
|---|---|---|---|---|
| Surefire (unit) | 264 | 296 | +32 | — |
| Failsafe (integration) | 62 | 71 | +9 | — |
| Karma (frontend unit) | 187 (claimed) | **181** (authoritative) | −6 (see note) | — |
| Jacoco — instruction | 92.7% | 93.3% | +0.6 | ≥ 45% |
| Jacoco — branch | 85.7% | 87.8% | +2.1 | ≥ 35% |
| `./mvnw verify` | — | BUILD SUCCESS | — | — |

### Karma baseline correction

The Phase 2 decision doc recorded 187/187 Karma pass, but `frontend-designer` found at the start of the fix cycle that the Phase 2 frontend state was not compilable (missing `@angular/localize` runtime + `@axe-core/playwright` deps; `app.module.ts` missing Material module imports). After installing deps and wiring modules (the "restoration" step), 181/181 pass. Acceptance-auditor per-file `describe/it` counting confirms **every D-NN Phase 1 decision mapped to Karma is still pinned by an executable test** (`D-04`/`D-05`/`D-06`/`D-14`/`D-15`/`D-16`/`D-17`/`D-P2-01`). Conclusion: 181 is the authoritative baseline; the 187 was optimistic. No regression in semantic coverage.

## Open Risks (accepted — unchanged from Phase 2 re-review)

1. `processStatusEditedEvent` (StreamEvent path) still has no `isLoadable` check. Pre-existing, explicitly deferred to a follow-up PR. Acceptance-auditor recommended a "regression-documentation" test that currently passes but would flip to a meaningful assertion when the fix lands; not required for acceptance.
2. Logback `JsonLayout` cannot mechanically drop `cookie`/`setCookie`/`authorization` fields. Enforcement is convention-based + `RawWallIdLogHygieneTest` (F-02 closed this for the 4 touched files). Full-codebase enforcement remains partly convention.
3. Jacoco exec-file race under back-to-back local `./mvnw verify` runs without `clean`. CI is unaffected.
4. `@angular/localize` runtime adds one fetch of `messages.en.json`. Accepted at planning.
5. Brief double-delivery window at WS↔fallback transition, absorbed by `statusId` dedup.
6. `recordThenPublish` persists cache entry even when STOMP publish throws; WARN + `glacier.fallback.publish.failures` counter in place, no alerting in this PR.
7. +~60 s CI runner wall-clock for the two new override-based Playwright stages (parallelised).

### Residual flags surfaced in Phase 3 but accepted without FIX REQUEST

- **F-09 leftover**: `InformationController.anonymiseIp` is a 5th copy of the hash-truncation/IP-masking helper pattern that F-09's scope missed. Security-auditor: *"non-security tech debt, flag for next cleanup round."*
- **Killswitch STOMP-fan-out preservation pin**: the invariant "live WS clients still get toots in killswitch mode" is currently asserted inside `MessageCacheImplTest#recordThenPublish_killswitchOff_stompFanoutPreservedButNoCacheWrite` alongside other assertions. Acceptance-auditor recommendation: *"extract as its own narrowly-named test for discoverability. Not a blocker."*
- **Karma 187 → 181 baseline correction**: Phase 2 decision doc should be annotated (or this Phase 3 doc serves as the correction-of-record).

## User Approval

Date: 2026-04-22
Approval message (verbatim): *"i approve. pump version. but dont commit"*

Post-approval action: version bumped 0.0.8 → 0.0.9 via `push_version.sh 0.0.9 0.0.10` (pom.xml, frontend/package.json, .github/dependabot.yaml target-branch). Changes intentionally not committed — left for the user to review and commit.

## References

- `docs/decisions/2026-04-21-planning-ws-fallback.md` — Phase 1 plan (21 decisions + 8 ADRs + C-01/C-02/C-03).
- `docs/decisions/2026-04-21-implementation-ws-fallback.md` — Phase 2 implementation record.
- `CLAUDE.md` — testing policy, architecture, conventions (binding).
- OWASP Top 10 (2025), OWASP API Security Top 10 (2023), TSS-WEB.
- WCAG 2.2 AA.
- `.claude/skills/glacier-fallback-mode-discipline.md`, `glacier-structured-logging-logback.md`, `angular-i18n-localize.md` (mandatory skills consulted throughout the three phases).
