# Decision Record: Fuzz & Mutation Testing — Planning

Date: 2026-05-01
Phase: Planning
Agents: ddd-tdd-architect (Round 1 + Round 2), secure-feature-planner (Round 1 + Round 2)
Status: Accepted

## Summary

Add three complementary testing layers to Glacier: PITest mutation testing (Maven profile, 10-class target set, 70% threshold), four jqwik property-based fuzz test classes covering hashtag validation, LogScrubber, ShareLinkId, and the new IframeEmbedPolicy, and frontend fast-check property-based tests for SubscriptionService and MessageQueue. A new production class `IframeEmbedPolicy` is extracted from `StompCallback.isLoadable` to make mutation-testable logic independently addressable.

## Key Decisions

### ADR-FUZZ-01: PITest scope — 10 named classes, 70% threshold
**Decision**: The `mutation` Maven profile targets exactly 10 fully-qualified class names; no wildcards. Minimum mutation score: 70%. Jacoco bundle thresholds (instruction ≥ 45%, branch ≥ 35%) are unchanged.
**Rationale**: Broad wildcard mutation targets produce excessive noise and long runtimes. The 10 classes are all security-relevant pure functions where mutation survival is actionable. Scope-local thresholds avoid penalising framework glue code.
**Alternatives considered**: Full-package wildcard (rejected — too slow, high noise), no threshold (rejected — defeats the quality gate).
**Source**: ddd-tdd-architect Round 1; confirmed secure-feature-planner Round 2.

**Target classes (10 FQNs):**
- `de.seism0saurus.glacier.util.LogScrubber`
- `de.seism0saurus.glacier.share.domain.ShareLinkId`
- `de.seism0saurus.glacier.share.domain.ShareViewerId`
- `de.seism0saurus.glacier.share.domain.SecureRandomTokenGenerator`
- `de.seism0saurus.glacier.webservice.messaging.HashtagFormat`
- `de.seism0saurus.glacier.share.web.ShareImageProxyUrlBuilder`
- `de.seism0saurus.glacier.share.application.DefaultSafeUrlValidator`
- `de.seism0saurus.glacier.mastodon.IframeEmbedPolicy` *(new class — see ADR-FUZZ-05)*
- `de.seism0saurus.glacier.webservice.cache.FallbackRateLimiter`
- `de.seism0saurus.glacier.share.web.ShareRateLimiter`

### ADR-FUZZ-02: STRONGER mutator group — no exclusions
**Decision**: PITest uses ALL mutators (STRONGER group). Surviving equivalents are documented in a mutation baseline document (see SR-FUZZ-13).
**Rationale**: Excluding mutators reduces confidence. Equivalent mutations are a known, tolerable result; they are documented, not suppressed.
**Alternatives considered**: DEFAULTS group only (rejected — misses boundary arithmetic mutations relevant to rate limiters).
**Source**: ddd-tdd-architect Round 1.

### ADR-FUZZ-03: fast-check exact version pin
**Decision**: `fast-check: "3.21.0"` (no `^` caret) added to `frontend/package.json` devDependencies. `package-lock.json` committed.
**Rationale**: Property-based test libraries can change shrink behavior between patch versions, producing non-reproducible failures. Exact pin + committed lockfile guarantees CI determinism.
**Alternatives considered**: Caret range (rejected — supply-chain risk, non-reproducible shrink).
**Source**: secure-feature-planner Round 1; ADR-FUZZ-03.

### ADR-FUZZ-04: Mutation CI paths filter — main and PR-to-main only
**Decision**: The mutation CI job in `build-and-deploy.yml` (and optionally `verify.yml`) runs only on push-to-main and PR-to-main, with a `paths:` filter restricted to the Java source directories containing the 10 target classes.
**Rationale**: PITest takes ~5 minutes for 10 classes. Running on every branch push would slow all branches unnecessarily.
**Alternatives considered**: Every push (rejected — runtime overhead), nightly cron only (rejected — too delayed feedback).
**Source**: ddd-tdd-architect Round 2.

### ADR-FUZZ-05: IframeEmbedPolicy — List<String> signature (RESOLVED CONFLICT)
**Decision**: `IframeEmbedPolicy.isEmbeddable(@Nullable List<String> xFrameOptions, @Nullable List<String> csp, String domain)` — `List<String>` for both header parameters, not `String`.
**Rationale**: Three critical invariants require `List<String>`:
1. RFC-7034 CSP `frame-ancestors` — multiple values are a list; joining to a single String loses per-value semantics.
2. `X-Frame-Options` exact-match must be per-element (`DENY`, `SAMEORIGIN`) — String concatenation breaks this.
3. TD-4/ADR-TD4-01: `LogScrubber.xfoSummary(List<String>)` is the CWE-117 log-injection guard. Flattening `List<String>` to `String` at the API boundary silently disables this guard.
**Alternatives considered**: `String`-typed parameters (rejected — silently breaks CWE-117 guard and RFC-7034 semantics).
**Source**: Conflict raised by secure-feature-planner Round 2; architect accepted in Round 2.

### ADR-FUZZ-06: jqwik tests in default Surefire — no separate profile
**Decision**: All four jqwik fuzz test classes (`*FuzzTest.java`) run as standard Surefire unit tests. No separate Maven profile.
**Rationale**: jqwik is already on the classpath (version 1.8.4). Separating fuzz tests into a profile adds friction without benefit; trial counts (200–500) are fast enough for CI.
**Source**: ddd-tdd-architect Round 1.

## New Files

### Production
- `src/main/java/de/seism0saurus/glacier/mastodon/IframeEmbedPolicy.java`
  — `final` class, package-private constructor, extracted from `StompCallback.isLoadable`
  — `StompCallback` delegates to it; existing behavior byte-for-byte identical

### Test (backend — Surefire)
- `src/test/java/de/seism0saurus/glacier/webservice/messaging/messages/SubscriptionMessageHashtagFuzzTest.java`
- `src/test/java/de/seism0saurus/glacier/util/LogScrubberFuzzTest.java`
- `src/test/java/de/seism0saurus/glacier/share/domain/ShareLinkIdFuzzTest.java`
- `src/test/java/de/seism0saurus/glacier/mastodon/IframeEmbedPolicyFuzzTest.java`
- `src/test/java/de/seism0saurus/glacier/mastodon/IframeEmbedPolicyTest.java` (5 branch-coverage unit tests)

### Test (frontend — Karma)
- `frontend/src/app/subscription.service.fuzz.spec.ts`
- `frontend/src/app/model/message-queue.fuzz.spec.ts`

## Security Requirements

| ID | Severity | Requirement |
|---|---|---|
| SR-FUZZ-01 | CRITICAL | `IframeEmbedPolicy` MUST use `List<String>` parameters — breaking this silently disables the CWE-117 log-injection guard |
| SR-FUZZ-02 | CRITICAL | Fuzz test shrink reports must not echo raw secrets or raw arbitrary inputs |
| SR-FUZZ-03 | HIGH | Fuzz tests MUST attach `ListAppender` and assert no log line contains raw arbitrary input |
| SR-FUZZ-04 | HIGH | Property inputs MUST include log-injection canaries: CRLF, U+202E, BOM, U+2028, ANSI escapes, 10 KB+ strings |
| SR-FUZZ-05 | HIGH | PITest gate is non-bypassable; separate `mutation` profile; 70% threshold enforced at CI |
| SR-FUZZ-06 | HIGH | Mutation CI job must NOT have production secrets (ACCESS_KEY, etc.) in env |
| SR-FUZZ-07 | MEDIUM | fast-check exact version pin + `npm audit` step in CI |
| SR-FUZZ-08 | MEDIUM | Rate limiter fail-policy invariants (deny on error) covered by PITest target |
| SR-FUZZ-09 | MEDIUM | `IframeEmbedPolicy` is `final` with private/package-private constructor |
| SR-FUZZ-10 | MEDIUM | `.gitignore` includes `target/jqwik-database/` and `target/pit-reports/` |
| SR-FUZZ-11 | MEDIUM | `SubscriptionService` fuzz validates against `HashtagFormat.PATTERN` parity |
| SR-FUZZ-12 | MEDIUM | `MessageQueueValidator` fuzz includes prototype-pollution canaries + queue-size bound |
| SR-FUZZ-13 | LOW-MEDIUM | Mutation-survivor equivalents documented in decision doc after Phase 2 |
| SR-FUZZ-14 | HIGH | `IframeEmbedPolicy` has 5 branch-coverage unit tests + jqwik property |
| SR-FUZZ-15 | LOW | PITest `<targetClasses>` lists exactly 10 FQNs, no wildcards |
| SR-FUZZ-16 | HIGH | Reflection regression gate: test asserts `IframeEmbedPolicy.isEmbeddable` second param is `List`, not `String` |
| SR-FUZZ-17 | MEDIUM | Golden-vector corpus ≥ 30 vectors committed before extraction refactor (byte-for-byte parity proof) |

## Phase 2 Lane Partition (sequential per project conventions)

1. **devops-infra-engineer**: `pom.xml` PITest profile, `build-and-deploy.yml` mutation CI job, `.gitignore` entries, `package.json` fast-check dep
2. **tdd-ddd-implementer**: `IframeEmbedPolicy` extraction (with golden-vector corpus + `StompCallback` delegation), `IframeEmbedPolicyTest` (5 unit tests), `SubscriptionMessageHashtagFuzzTest`, `ShareLinkIdFuzzTest`, fast-check Angular specs
3. **secure-tdd-implementer**: `LogScrubberFuzzTest` (with log-injection canaries + `ListAppender`), `IframeEmbedPolicyFuzzTest` (including reflection regression gate SR-FUZZ-16)
4. **devops-infra-engineer round 2** (if threshold calibration needed after initial PITest run)

## Resolved Conflicts

### ADR-FUZZ-05: IframeEmbedPolicy signature
**ddd-tdd-architect (Round 1)**: Proposed `String`-typed parameters `isEmbeddable(String xFrameOptions, String csp, String domain)` to simplify the API.
**secure-feature-planner (Round 2)**: Identified that `String` parameters silently break three critical invariants: RFC-7034 CSP list semantics, per-element XFO exact-match, and `LogScrubber.xfoSummary(List<String>)` CWE-117 guard from TD-4/ADR-TD4-01.
**Resolution (2026-05-01)**: ddd-tdd-architect accepted `List<String>` parameters in Round 2. Decision codified as ADR-FUZZ-05. Pitfall documented in agent memory for future reviewers.

## User Approval
Date: 2026-05-01
Approval message (verbatim): "approve"

## Open Risks
- PITest runtime ~5 minutes for 10 classes; `paths:` filter mitigates scope creep
- Surviving mutations at 70% threshold will appear; ~2–4 equivalents expected in `LogScrubber` boundary arithmetic — to be documented per SR-FUZZ-13 after Phase 2
- fast-check pinned to `3.21.0` — Dependabot (already configured) will surface patch updates for human review

## References
- TD-4/ADR-TD4-01: `LogScrubber.xfoSummary(List<String>)` — CWE-117 guard decision record
- D-13/SR-8: Log hygiene discipline (raw wallId/IP/hashtag must never reach JSON logs)
- `src/test/java/de/seism0saurus/glacier/share/web/ImageProxyUrlBuilderVerifyFuzzTest.java` — existing jqwik template
- [`spring-boot-testing-patterns`](../../.claude/skills/spring-boot-testing-patterns.md) — Surefire/Failsafe conventions
- [`glacier-structured-logging-logback`](../../.claude/skills/glacier-structured-logging-logback.md) — D-13/SR-8 enforcement
