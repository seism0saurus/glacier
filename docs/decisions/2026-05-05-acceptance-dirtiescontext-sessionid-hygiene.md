# Decision Record: Rate-Limiter IT Isolation + SessionId Log Hygiene — Acceptance

Date: 2026-05-05
Phase: Acceptance (Phase 3)
Agents: security-auditor (Round 1), acceptance-test-auditor (Round 1)
Status: Accepted — PASSED

## Summary

All ten security requirements (SR-RL-01..04, SR-MED-02-01..06) and all nine acceptance criteria
(AC-RL-01..04, AC-MED-01..04, AC-BUILD) are satisfied in the shipped code.
Zero fix cycles required. No Critical, High, or Medium security findings. BUILD SUCCESS:
1,233 unit tests + 298 integration tests.

## Acceptance Result

**Disposition: PASSED**
**Criteria checked**: 9
**Criteria passed**: 9
**Criteria failed**: 0

## Security Audit Findings (security-auditor)

| Severity | Count | Disposition |
|----------|-------|-------------|
| Critical | 0 | — |
| High | 0 | — |
| Medium | 0 | — |
| Low | 0 | — |
| Informational | 2 | F-01, F-02 — non-blocking (see below) |

Production attack surface: unchanged — `SubscriptionController.java` is the only production file
modified; no Bucket4j production code touched.

## SR Coverage (security-auditor)

| SR | Requirement | Status | Key Evidence |
|----|-------------|--------|--------------|
| SR-RL-01 | `@DirtiesContext(classMode = AFTER_EACH_TEST_METHOD)` on all three Bucket4j ITs | PASS | `SubscribeRateLimitProductionPathIT.java:66`; `ShareViewRemoteAddrProductionPathIT.java:86`; `HandshakeForwardedForRespectedIT.java:72` |
| SR-RL-02 | `RateLimiterItIsolationStructureTest` reflection gate; runs under SkipIntegrationTest | PASS | Plain `*Test.java` POJO; reflection check at lines 55–67, 82–94, 108–120 |
| SR-RL-03 | `ShareLinkViewerCapIT` Javadoc: drain-vs-@DirtiesContext obligation statement | PASS | `ShareLinkViewerCapIT.java:53–71` — "Why this class does NOT use @DirtiesContext" + obligation paragraph |
| SR-RL-04 | Zero production code changes for Bucket4j isolation | PASS | No production rate-limiter/bucket/interceptor files modified |
| SR-MED-02-01 | `hash8(getSessionId())` at subscribe null-principal site | PASS | `SubscriptionController.java:121–122` |
| SR-MED-02-02 | `hash8(getSessionId())` at unsubscribe null-principal site | PASS | `SubscriptionController.java:202–203` |
| SR-MED-02-03 | Key renamed `sessionId-hash=` at both sites | PASS | Both LOGGER and AUDIT lines use `sessionId-hash={}` |
| SR-MED-02-04 | AUDIT event `stomp.subscribe.rejected reason=null_principal` | PASS | `SubscriptionController.java:123–124` |
| SR-MED-02-05 | AUDIT event `stomp.terminate.rejected reason=null_principal` | PASS | `SubscriptionController.java:204–205` |
| SR-MED-02-06 | Non-UUID canary; dual ListAppender; hash value guard; silent-deletion guard | PASS | `SubscriptionControllerSessionIdScrubbingTest.java:56` (canary), `:70–79` (appenders), `:195–202 / 294–301` (silent-deletion guard asserts `LogScrubber.hash8(CANARY_SESSION_ID)` in both LOGGER and AUDIT outputs) |

## AC Coverage (acceptance-test-auditor)

| AC | Criterion | Status | Key Evidence |
|----|-----------|--------|--------------|
| AC-RL-01 | All three Bucket4j ITs carry `@DirtiesContext(classMode = AFTER_EACH_TEST_METHOD)` | SATISFIED | Three class-level annotations confirmed |
| AC-RL-02 | `RateLimiterItIsolationStructureTest` verifies all three via reflection (unit profile) | SATISFIED | `RateLimiterItIsolationStructureTest.java:54/81/107` — three `@Test` methods; `classMode == AFTER_EACH_TEST_METHOD` at lines 67, 93, 119 |
| AC-RL-03 | `ShareLinkViewerCapIT` NOT annotated with `@DirtiesContext`; has Javadoc obligation note | SATISFIED | `ShareLinkViewerCapIT.java:73` (only `@SpringBootTest`); obligation note `:53–71`; drain in `resetCounter()` `:103–111` |
| AC-RL-04 | No Bucket4j production code modified | SATISFIED | `git diff HEAD -- 'src/main/java/'` filtered for rate/bucket/interceptor: zero matches |
| AC-MED-01 | `SubscriptionController` uses `hash8(getSessionId())`; key `sessionId-hash=` at both sites | SATISFIED | Lines 121–124 (subscribe); lines 202–205 (unsubscribe) |
| AC-MED-02 | Two AUDIT events emitted | SATISFIED | `stomp.subscribe.rejected reason=null_principal` at `:123`; `stomp.terminate.rejected reason=null_principal` at `:204` |
| AC-MED-03 | Non-UUID canary, dual ListAppender, silent-deletion guard | SATISFIED | `SubscriptionControllerSessionIdScrubbingTest.java:56/70–79/195–202/294–301` |
| AC-MED-04 | `RawWallIdLogHygieneTest` extended with structural scan of webservice package | SATISFIED | `RawWallIdLogHygieneTest.java:1080–1112` (`allWebserviceGetSessionIdCallsAreHashed`); all 8 `getSessionId()` sites in webservice tree are wrapped |
| AC-BUILD | `./mvnw verify` GREEN; unit=1,233; IT=298 | SATISFIED | BUILD SUCCESS confirmed |

## Informational Findings

### F-01: Silent-deletion guard vacuous against `hash8() → ""` neutering

**Location**: `SubscriptionControllerSessionIdScrubbingTest.java:195–202, 294–301`
**Severity**: Informational

The guard asserts `.contains(LogScrubber.hash8(CANARY_SESSION_ID))`. If a regression changed
`hash8` to return `""`, the assertion `.contains("")` is trivially true. **Mitigation present**:
`LogScrubberTest` independently contract-tests that `hash8` returns a non-empty 8-char hex string;
a no-op implementation would fail there first, making this a defense-in-depth gap rather than a
live risk.

**Optional hardening** (non-blocking): add `assertThat(expectedHash).isNotEmpty()` before each
`.contains(expectedHash)` assertion to make the guard self-contained.

**Disposition**: Informational — not blocking sign-off.

---

### F-02: No rate limit on AUDIT-event emission for null-principal frames

**Location**: `SubscriptionController.java:121–129, 200–206`
**Severity**: Informational

Each null-principal SUBSCRIBE/TERMINATE frame now emits two log entries. A malicious client
crafting frames after a forged handshake could attempt to flood AUDIT logs. **Mitigation present**:
`HandshakeRateLimitInterceptor` caps connections at handshake time; `SubscribeRateLimitInterceptor`
caps STOMP-frame rate per IP. The null-principal path should be unreachable through normal
`PrincipalHandler` wiring.

**Disposition**: Informational — not blocking sign-off.

## Conflicts (Phase 3)

None. security-auditor and acceptance-test-auditor fully agreed across both domains.

## Acceptance Auditor Notes (non-blocking)

1. **Out-of-scope companion changes in working tree**: additional uncommitted edits exist in
   `StompCallback.java`, `SubscriptionManagerImpl.java`, `LogScrubber.java`, `PrincipalHandler.java`,
   `ShareViewPrincipalHandler.java`, and `WebSocketConfiguration.java`. These belong to other open
   pipelines and are NOT part of this acceptance scope. They need their own acceptance pass before
   commit.

2. **Uncommitted state**: RL/MED-02 changes are in the working tree but not yet committed to git.
   A commit should be created post-acceptance.

3. **AUDIT event naming**: `stomp.subscribe.rejected` and `stomp.terminate.rejected` follow the
   `stomp.<verb>.rejected` convention used by existing events. SIEM rules / log-aggregation alerts
   should be updated to recognise the two new stable identifiers.

## Test Results

| Suite | Tests | Failures | Errors | Skipped |
|-------|-------|----------|--------|---------|
| Surefire (unit) | 1,233 | 0 | 0 | 0 |
| Failsafe (IT) | 298 | 0 | 0 | 0 |
| Jacoco | — | — | threshold pass | — |

## Open Risks

| ID | Risk | Rationale |
|----|------|-----------|
| OR-HYG-01 | Silent-deletion guard vacuity (F-01) | Mitigated by independent `LogScrubberTest`; optional hardening deferred |
| OR-HYG-02 | Null-principal AUDIT flood (F-02) | Mitigated by `HandshakeRateLimitInterceptor` + `SubscribeRateLimitInterceptor`; path unreachable in practice |

## References

- [Planning doc](2026-05-04-planning-dirtiescontext-sessionid-hygiene.md)
- [Implementation doc](2026-05-04-implementation-dirtiescontext-sessionid-hygiene.md)
- `src/main/java/de/seism0saurus/glacier/webservice/SubscriptionController.java`
- `src/test/java/de/seism0saurus/glacier/security/RateLimiterItIsolationStructureTest.java`
- `src/test/java/de/seism0saurus/glacier/webservice/SubscriptionControllerSessionIdScrubbingTest.java`
- `src/test/java/de/seism0saurus/glacier/mastodon/RawWallIdLogHygieneTest.java`
- D-13 / SR-8 (opaque identifier log-hygiene)
- [OWASP A09:2021 — Security Logging and Monitoring Failures](https://owasp.org/Top10/A09_2021-Security_Logging_and_Monitoring_Failures/)
- [CWE-532: Insertion of Sensitive Information into Log File](https://cwe.mitre.org/data/definitions/532.html)