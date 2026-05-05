# Decision Record: Rate-Limiter IT Isolation + SessionId Log Hygiene — Planning

Date: 2026-05-04
Phase: Planning (Phase 1)
Agents: ddd-tdd-architect (Round 1 + Round 2), secure-feature-planner (Round 1 + Round 2)
Status: Accepted

## Summary

Two latent correctness/security defects are addressed in this pipeline:

1. **RL-D-01 (HIGH)**: Three Spring integration tests that mutate singleton Bucket4j token-bucket beans
   (`SubscribeRateLimitProductionPathIT`, `ShareViewRemoteAddrProductionPathIT`,
   `HandshakeForwardedForRespectedIT`) do not reset the application context between test methods,
   allowing bucket state from one method to bleed into the next. The fix is
   `@DirtiesContext(classMode = ClassMode.AFTER_EACH_TEST_METHOD)` on each class.

2. **MED-02 (MEDIUM)**: `SubscriptionController.java` logs raw `headerAccessor.getSessionId()` at
   two null-principal guard sites without applying `LogScrubber.hash8()`, violating D-13 / SR-8.
   The fix hashes the identifier at point of log emission and adds two AUDIT events for SIEM
   visibility.

## Problem Statements

### RL-D-01: Bucket4j singleton state bleeds between test methods

`FallbackRateLimiter` and `HandshakeRateLimiter` are Spring singletons holding token-bucket maps.
Integration tests that set `max-per-minute=1` or `max-per-minute=3` consume tokens across methods
without reset. A flaky "max-per-minute=1" test can spuriously block a method that should succeed,
or spuriously pass because prior-method tokens refill on a background thread at an unpredictable
time.

`ShareLinkViewerCapIT` is intentionally exempt: it calls `ShareLinkViewerCounter.resetCounter()`
in `@BeforeEach`, which is a drain mechanism exposed specifically for testing. This pattern avoids
the 5-second context restart overhead of `@DirtiesContext` and must be documented with a Javadoc
paragraph so future `@Test` authors understand the distinction.

### MED-02: Raw STOMP sessionId reaches log output

`SubscriptionController.java` lines 121–122 (subscribe null-principal path) and 200–201
(unsubscribe null-principal path) use the string template `"... sessionId={}",
headerAccessor.getSessionId()`. STOMP session IDs are short opaque alphanumeric strings — not
UUID-format — so the existing `assertNoRawUuid()` structural gate is vacuous for this leak class.

The `WallTopicAuthInterceptor` (line 119) already emits
`AUDIT.info("stomp.subscribe.rejected reason=cross_principal sessionId-hash=...")`. The controller
null-principal paths have no AUDIT event, creating a gap in wall-side security-event coverage.

## Key Decisions

### ADR-RL-01: Use `@DirtiesContext(AFTER_EACH_TEST_METHOD)` for Bucket4j isolation

**Decision**: Add `@DirtiesContext(classMode = ClassMode.AFTER_EACH_TEST_METHOD)` at class level
to the three affected ITs.

**Rationale**: Bucket4j token buckets are singletons with no `resetBucket()` or equivalent test
hook — unlike `ShareLinkViewerCounter`. The only clean option is context reset. The overhead
(~5 s per method) is acceptable for three small integration test classes.

**Alternatives considered**: (A) Adding a `resetBucket()` test hook to `FallbackRateLimiter` /
`HandshakeRateLimiter` — rejected because it pollutes production code for test convenience and
requires synchronized access to the bucket map. (B) Ordered `@TestMethodOrder` + quota sizing —
rejected as fragile; later additions break ordering.

**Source**: ddd-tdd-architect Round 1; confirmed by secure-feature-planner Round 1.

### ADR-RL-02: Structural gate for @DirtiesContext compliance

**Decision**: Add `RateLimiterItIsolationStructureTest.java` (Surefire unit tier) that uses
reflection to assert `@DirtiesContext(classMode = AFTER_EACH_TEST_METHOD)` is present on all
three IT classes.

**Rationale**: A one-time annotation fix without an enforcement gate will silently regress when a
future contributor removes it while chasing a faster test run. The structural test runs even under
`-P SkipIntegrationTest`, so it catches annotation gaps before the IT suite runs.

**Source**: ddd-tdd-architect Round 1; secure-feature-planner Round 2 confirmed necessity.

### ADR-RL-03: Document drain-vs-@DirtiesContext in ShareLinkViewerCapIT Javadoc

**Decision**: Add a Javadoc paragraph to `ShareLinkViewerCapIT` explaining why `@DirtiesContext`
is NOT used and what obligation that places on future `@Test` method authors.

**Rationale**: Without this documentation the pattern looks inconsistent; future maintainers may
add `@DirtiesContext` (adding unnecessary overhead) or forget to call `resetCounter()` (breaking
isolation).

**Source**: ddd-tdd-architect Round 1.

### ADR-MED-02-01: Hash sessionId at all log-emission sites in SubscriptionController

**Decision**: Replace `headerAccessor.getSessionId()` with `LogScrubber.hash8(headerAccessor.getSessionId())`
at lines 121–122 and 200–201. Rename the structured-log key from `sessionId=` to `sessionId-hash=`
to signal the transformation to log consumers.

**Rationale**: D-13 / SR-8 require that opaque identifiers never reach the log output raw.
`getSessionId()` returns a short alphanumeric that could correlate a session across log lines —
exactly the kind of linkable identifier the rule targets.

**Source**: secure-feature-planner Round 1; ddd-tdd-architect confirmed (no domain-model impact).

### ADR-MED-02-02: Add AUDIT events on null-principal rejection paths

**Decision**: Emit `AUDIT.info("stomp.subscribe.rejected reason=null_principal sessionId-hash={}", ...)` and
`AUDIT.info("stomp.terminate.rejected reason=null_principal sessionId-hash={}", ...)` immediately
after the respective ERROR log lines.

**Rationale**: The wall-side `WallTopicAuthInterceptor:119` already emits
`stomp.subscribe.rejected reason=cross_principal`. Null-principal is an equally anomalous event
(likely a client bug or deliberate bypass attempt) and SIEM/alerting must see it. This closes the
AUDIT coverage gap on two security-boundary paths.

**Naming convention**: verb-form `<object>.<action>.rejected reason=<cause>` matches the existing
wall-side convention over the noun-form proposed in an earlier draft.

**Source**: secure-feature-planner Round 1; ddd-tdd-architect Round 2 confirmed convention match.

### ADR-MED-02-03: Non-UUID canary in behavioral test; extend structural gate

**Decision**: `SubscriptionControllerSessionIdScrubbingTest.java` injects a non-UUID canary
(`"raw-stomp-session-canary-abc123def456"`) rather than a UUID, because `assertNoRawUuid()` is
vacuous for this shape. The `RawWallIdLogHygieneTest.java` structural gate is extended to scan
the full `webservice/` package (not just `SubscriptionController.java`) for bare `getSessionId()`
calls not wrapped by `LogScrubber.hash8(`.

**Rationale**: Closes both immediate and future-regression gaps; single source of truth for the
"no raw sessionId in webservice layer" invariant.

**Source**: secure-feature-planner Round 1 + Round 2.

## Security Requirements

| SR | Description | Severity | Layer |
|----|-------------|----------|-------|
| SR-RL-01 | `@DirtiesContext(AFTER_EACH_TEST_METHOD)` present on all three Bucket4j ITs | High | Test infra |
| SR-RL-02 | Structural gate asserts annotation via reflection; runs under SkipIntegrationTest | High | Surefire |
| SR-RL-03 | Javadoc on `ShareLinkViewerCapIT` documents drain-vs-DirtiesContext obligation | Low | Documentation |
| SR-RL-04 | No introduction of production code changes for Bucket4j isolation (test-only fix) | Medium | Scope guard |
| SR-MED-02-01 | `getSessionId()` wrapped by `LogScrubber.hash8()` at subscribe null-principal site | High | Production |
| SR-MED-02-02 | `getSessionId()` wrapped by `LogScrubber.hash8()` at unsubscribe null-principal site | High | Production |
| SR-MED-02-03 | Structured-log key renamed `sessionId-hash=` at both sites | Medium | Production |
| SR-MED-02-04 | AUDIT event emitted on subscribe null-principal path with `reason=null_principal` | High | Production |
| SR-MED-02-05 | AUDIT event emitted on unsubscribe null-principal path with `reason=null_principal` | High | Production |
| SR-MED-02-06 | Behavioral canary uses non-UUID token; asserts both LOGGER and AUDIT appenders | High | Test |

## Phase 2 Lane Partition

### Lane A — `tdd-ddd-implementer`

**Files/modules owned:**
- `src/test/java/de/seism0saurus/glacier/security/SubscribeRateLimitProductionPathIT.java` — add `@DirtiesContext(classMode = ClassMode.AFTER_EACH_TEST_METHOD)`
- `src/test/java/de/seism0saurus/glacier/security/ShareViewRemoteAddrProductionPathIT.java` — add `@DirtiesContext(classMode = ClassMode.AFTER_EACH_TEST_METHOD)`
- `src/test/java/de/seism0saurus/glacier/security/HandshakeForwardedForRespectedIT.java` — add `@DirtiesContext(classMode = ClassMode.AFTER_EACH_TEST_METHOD)`
- `src/test/java/de/seism0saurus/glacier/webservice/messaging/ShareLinkViewerCapIT.java` — add Javadoc paragraph (ADR-RL-03)
- `src/test/java/de/seism0saurus/glacier/security/RateLimiterItIsolationStructureTest.java` — **create**: Surefire structural test asserting `@DirtiesContext(classMode = AFTER_EACH_TEST_METHOD)` on all three ITs via reflection

### Lane B — `secure-tdd-implementer`

**Files/modules owned:**
- `src/main/java/de/seism0saurus/glacier/webservice/SubscriptionController.java` — lines ~121–122 and ~200–201: apply `LogScrubber.hash8()`, rename key, add AUDIT events
- `src/test/java/de/seism0saurus/glacier/webservice/SubscriptionControllerSessionIdScrubbingTest.java` — **create**: non-UUID canary, dual `ListAppender`, 7 assertions covering SR-MED-02-01 through SR-MED-02-06
- `src/test/java/de/seism0saurus/glacier/mastodon/RawWallIdLogHygieneTest.java` — extend structural scan to full `webservice/` package

**Exact code for subscribe null-principal site (lines ~121–122 of `SubscriptionController.java`):**
```java
LOGGER.error("Someone tried to subscribe without a principal. This is not supported. sessionId-hash={}",
        LogScrubber.hash8(headerAccessor.getSessionId()));
AUDIT.info("stomp.subscribe.rejected reason=null_principal sessionId-hash={}",
        LogScrubber.hash8(headerAccessor.getSessionId()));
```

**Exact code for unsubscribe null-principal site (lines ~200–201):**
```java
LOGGER.error("Someone tried to unsubscribe without a principal. This is not supported. sessionId-hash={}",
        LogScrubber.hash8(headerAccessor.getSessionId()));
AUDIT.info("stomp.terminate.rejected reason=null_principal sessionId-hash={}",
        LogScrubber.hash8(headerAccessor.getSessionId()));
```

## Resolved Conflicts

None. Both agents were in full agreement across both rounds. The only refinement was the AUDIT
event naming convention (verb-form over noun-form), resolved by mutual agreement and the
precedent in `WallTopicAuthInterceptor:119`.

## User Approval

Date: 2026-05-04
Approval message (verbatim): "approve"

## Open Risks

- **@DirtiesContext overhead**: 3 affected ITs × ~5 s context restart = ~15 s added to the IT
  suite runtime. Accepted — these are small classes and isolation correctness outweighs CI time.
- **Structural gate coverage**: `RateLimiterItIsolationStructureTest` covers only the three known
  affected classes. Future Bucket4j IT classes that do NOT call `resetBucket()` will need manual
  addition to the gate's class list. Accepted as low risk given the rarity of new Bucket4j ITs.

## References

- `docs/decisions/2026-04-28-acceptance-pentest-findings.md` — origin of MED-02 finding
- `src/main/java/de/seism0saurus/glacier/webservice/SubscriptionController.java`
- `src/test/java/de/seism0saurus/glacier/security/SubscribeRateLimitProductionPathIT.java`
- `src/test/java/de/seism0saurus/glacier/security/ShareViewRemoteAddrProductionPathIT.java`
- `src/test/java/de/seism0saurus/glacier/security/HandshakeForwardedForRespectedIT.java`
- `src/test/java/de/seism0saurus/glacier/webservice/messaging/ShareLinkViewerCapIT.java`
- `src/test/java/de/seism0saurus/glacier/mastodon/RawWallIdLogHygieneTest.java`
- D-13 / SR-8: opaque identifier log-hygiene requirement
- OWASP A09:2021 (Security Logging and Monitoring Failures)
- Glacier structured-logging skill: `glacier-structured-logging-logback`
- Spring testing skill: `spring-boot-testing-patterns`
