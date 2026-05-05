# Decision Record: Rate-Limiter IT Isolation + SessionId Log Hygiene — Implementation

Date: 2026-05-04
Phase: Implementation (Phase 2)
Agents: tdd-ddd-implementer (Lane A), secure-tdd-implementer (Lane B + Round 2)
Status: Accepted

## Summary

Six unit tests (+6 delta) and zero integration test changes implement two independent correctness/security fixes:
1. Three Bucket4j integration tests now reset the Spring context between test methods via `@DirtiesContext(classMode = AFTER_EACH_TEST_METHOD)`, backed by a Surefire reflection gate.
2. `SubscriptionController` null-principal paths now hash the STOMP session ID via `LogScrubber.hash8()` and emit AUDIT events following the wall-side `stomp.*.rejected reason=*` convention.

No production logic changed beyond the `SubscriptionController` log-hygiene fix. No Bucket4j production code was modified.

## Files Changed

| File | Change |
|------|--------|
| `src/test/java/de/seism0saurus/glacier/security/SubscribeRateLimitProductionPathIT.java` | `@DirtiesContext(classMode = AFTER_EACH_TEST_METHOD)` added |
| `src/test/java/de/seism0saurus/glacier/security/ShareViewRemoteAddrProductionPathIT.java` | Same |
| `src/test/java/de/seism0saurus/glacier/security/HandshakeForwardedForRespectedIT.java` | Same |
| `src/test/java/de/seism0saurus/glacier/webservice/messaging/ShareLinkViewerCapIT.java` | Javadoc paragraph: drain-vs-@DirtiesContext obligation |
| `src/test/java/de/seism0saurus/glacier/security/RateLimiterItIsolationStructureTest.java` | **Created** — Surefire reflection gate, 3 assertions |
| `src/main/java/de/seism0saurus/glacier/webservice/SubscriptionController.java` | `sessionId=` → `sessionId-hash=` + `hash8()` at 2 sites; 2 AUDIT events added; typo fixed |
| `src/test/java/de/seism0saurus/glacier/webservice/SubscriptionControllerSessionIdScrubbingTest.java` | **Created** — non-UUID canary, dual ListAppender, 9 assertions × 2 tests |
| `src/test/java/de/seism0saurus/glacier/mastodon/RawWallIdLogHygieneTest.java` | `allWebserviceGetSessionIdCallsAreHashed()` structural scan added (16th test) |

## Test Results

| Layer | Before | After | Delta |
|-------|--------|-------|-------|
| Unit (Surefire) | 1,227 | **1,233** | +6 |
| Integration (Failsafe) | 298 | 298 | 0 |
| Jacoco | Met | Met | — |
| BUILD | SUCCESS | SUCCESS | — |

+6 unit tests:
- 3 — `RateLimiterItIsolationStructureTest` (one per covered IT class)
- 2 — `SubscriptionControllerSessionIdScrubbingTest` (subscribe + unsubscribe paths)
- 1 — `allWebserviceGetSessionIdCallsAreHashed()` in `RawWallIdLogHygieneTest`

## Security Requirements Delivered

| SR | Status | Evidence |
|----|--------|---------|
| SR-RL-01 | ✅ | `@DirtiesContext(classMode = AFTER_EACH_TEST_METHOD)` on all three Bucket4j ITs |
| SR-RL-02 | ✅ | `RateLimiterItIsolationStructureTest` — 3 reflection assertions run under SkipIntegrationTest |
| SR-RL-03 | ✅ | `ShareLinkViewerCapIT` Javadoc: drain-vs-@DirtiesContext obligation statement |
| SR-RL-04 | ✅ | Zero production code changes for Bucket4j isolation |
| SR-MED-02-01 | ✅ | `hash8(getSessionId())` at subscribe null-principal site |
| SR-MED-02-02 | ✅ | `hash8(getSessionId())` at unsubscribe null-principal site |
| SR-MED-02-03 | ✅ | Key renamed `sessionId-hash=` at both sites |
| SR-MED-02-04 | ✅ | `AUDIT.info("stomp.subscribe.rejected reason=null_principal ...")` on subscribe path |
| SR-MED-02-05 | ✅ | `AUDIT.info("stomp.terminate.rejected reason=null_principal ...")` on unsubscribe path |
| SR-MED-02-06 | ✅ | Non-UUID canary; dual ListAppender; hash value `8c18abc2` confirmed in output (silent-deletion guard added in Round 2) |

## Round 2 Cross-Review Improvements

| Item | Disposition |
|------|-------------|
| Structural scan scope to `mastodon/` (S1) | Deferred — runtime canaries already cover those paths |
| Typo `"unsubscibe"` → `"unsubscribe"` in SubscriptionController line 206 (S2) | Fixed |
| Assertion-7 counting note (S3) | No action — confirmed correct |
| Silent-deletion guard: `contains(LogScrubber.hash8(CANARY_SESSION_ID))` (S4) | Accepted + implemented in both test methods |

## Deviations from Phase 1 Plan

None. All deliverables match the Phase 1 decision doc exactly.

## User Approval

Date: 2026-05-04
Approval message (verbatim): "approve"

## References

- `docs/decisions/2026-05-04-planning-dirtiescontext-sessionid-hygiene.md` — Phase 1 planning
- `src/main/java/de/seism0saurus/glacier/webservice/SubscriptionController.java`
- `src/test/java/de/seism0saurus/glacier/security/RateLimiterItIsolationStructureTest.java`
- `src/test/java/de/seism0saurus/glacier/webservice/SubscriptionControllerSessionIdScrubbingTest.java`
- `src/test/java/de/seism0saurus/glacier/mastodon/RawWallIdLogHygieneTest.java`
- D-13 / SR-8: opaque identifier log-hygiene requirement
- OWASP A09:2021 (Security Logging and Monitoring Failures)
