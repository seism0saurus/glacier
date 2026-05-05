# Decision Record: Fuzz & Mutation Testing — Implementation

Date: 2026-05-01
Phase: Implementation
Agents: devops-infra-engineer (Lane 1 + Round 2), tdd-ddd-implementer (Lane 2), secure-tdd-implementer (Lane 3)
Status: Accepted

## Summary

All three implementation lanes delivered: PITest `mutation` Maven profile with 10-class target set and 70% threshold; five new jqwik fuzz/unit test classes covering `IframeEmbedPolicy`, `LogScrubber`, `ShareLinkId`, and `SubscriptionMessage` validation; two fast-check Angular frontend fuzz spec files; and a new `IframeEmbedPolicy` production class extracted from `StompCallback.isLoadable`. Full `./mvnw verify` passes in 8:25 min (187 IT tests, 0 failures; Jacoco thresholds met).

## Key Decisions

### Lane 1 (devops-infra-engineer): Infrastructure

**PITest Maven profile** (`pom.xml`):
- Plugin: `pitest-maven:1.19.1` + `pitest-junit5-plugin:1.2.2`
- Profile id: `mutation`
- Exactly 10 target FQNs (no wildcards — ADR-FUZZ-01 / SR-FUZZ-15)
- Mutators: ALL (STRONGER group — ADR-FUZZ-02)
- Threshold: 70% (ADR-FUZZ-01 / SR-FUZZ-05)
- Output: HTML + XML to `target/pit-reports/` (`timestampedReports=false`)

**CI job** (`.github/workflows/build-and-deploy.yml`):
- Job `mutation` added after `test`, not in `publish-image`'s `needs:` (ADR-FUZZ-04)
- Paths filter: pure bash `git diff --name-only HEAD~1 HEAD` (no `dorny/paths-filter` — supply-chain risk resolved in Round 2)
- Filter scope: `src/(main|test)/java/de/seism0saurus/glacier/(util|share|webservice/(messaging|cache)|mastodon)/` and `pom.xml`
- No production secrets in env (SR-FUZZ-06)
- Artifacts: PITest HTML+XML uploaded via `upload-artifact@v4`

**Supporting files**:
- `.gitignore`: `target/jqwik-database/` and `target/pit-reports/` entries added
- `frontend/package.json`: `"fast-check": "3.21.0"` (exact pin, no caret — ADR-FUZZ-03)
- `frontend/package-lock.json`: committed at 3.21.0

### Lane 2 (tdd-ddd-implementer): Domain logic + tests

**`IframeEmbedPolicy.java`** (new, `de.seism0saurus.glacier.mastodon`):
- `final` class, private constructor (SR-FUZZ-09)
- Single public method: `isEmbeddable(@Nullable List<String> xFrameOptions, @Nullable List<String> csp, String domain)`
- Logic extracted verbatim from `StompCallback.isLoadable`; behavior byte-for-byte identical
- `LogScrubber.xfoSummary(List<String>)` CWE-117 guard preserved (TD-4/ADR-TD4-01)

**`StompCallback.java`** (modified):
- `isLoadable` replaced with 4-line delegation to `IframeEmbedPolicy.isEmbeddable`
- `Stream` import removed (no longer needed)

**`StompCallbackTest.java`** (modified):
- `getTestLogAppender()` now attaches to both `StompCallback` and `IframeEmbedPolicy` loggers

**`src/test/resources/iframe-embed-policy-golden-vectors.json`** (new):
- 34 vectors capturing pre-extraction `StompCallback.isLoadable` behavior
- Used as `@ParameterizedTest` regression guard (SR-FUZZ-17)

**New test files (Lane 2)**:
- `IframeEmbedPolicyTest.java`: 5 branch-coverage `@Test` + parametrized golden-vector test
- `SubscriptionMessageHashtagFuzzTest.java`: 2 `@Property` — invalid hashtag rejected, valid accepted
- `ShareLinkIdFuzzTest.java`: 3 `@Property` (500/300/500 tries) — short rejected, invalid charset rejected, valid roundtrips
- `frontend/src/app/subscription.service.fuzz.spec.ts`: ~5 fast-check properties
- `frontend/src/app/model/message-queue.fuzz.spec.ts`: ~7 fast-check properties (includes prototype-pollution canaries)

### Lane 3 (secure-tdd-implementer): Security fuzz tests

**`LogScrubberFuzzTest.java`** (new):
- 9 `@Property` × 200 tries:
  - `hash8_neverReturnsRawUuidPattern`
  - `maskIp_neverExposesLastOctet`
  - `hashtagLen_neverThrows_outputIsLengthOnly`
  - `safeEventName_neverThrows_outputIsAllowlisted`
  - `xfoSummary_neverExposesRawValues` (regex `xfo-values=\d+ xfo-totallen=\d+`)
  - `urlHostHash_arbitraryUrls_neverThrows`
  - `urlHostHash_httpsNoPort_equalsExplicitPort443`
  - `urlHostHash_httpNoPort_equalsExplicitPort80`
  - `urlHostHash_httpsPort443_differsFromHttpPort80`
- All properties attach `ListAppender` and assert no raw input leaks (SR-FUZZ-03)

**`IframeEmbedPolicyFuzzTest.java`** (new):
- 7 `@Property` + 1 reflection `@Test`:
  - `isEmbeddable_secondParameter_mustBeListNotString` (SR-FUZZ-16 reflection gate)
  - `isEmbeddable_arbitraryXfoValues_neverThrows`
  - `isEmbeddable_arbitraryCspValues_neverThrows`
  - `isEmbeddable_logInjectionCanariesInXfo_neverLeakRawValues`
  - `isEmbeddable_logInjectionCanariesInCsp_neverLeakRawValues`
  - `isEmbeddable_wildcardCspAlwaysAllows`
  - `isEmbeddable_denyXfoNoFrameAncestors_alwaysDenies`
  - `isEmbeddable_cspTakesPrecedenceOverDenyXfo` (added Round 2 — CSP Level 3 precedence)
- Log-injection canaries: CRLF, U+202E, BOM, U+2028, ANSI red, 10 KB+ string (SR-FUZZ-04)
- `@Provide logInjectionCanaries()` restricted to 6 fixed canaries only; no `Arbitraries.strings()` mix (false-positive false alarm fixed)

## Resolved Conflicts

### Supply-chain risk: `dorny/paths-filter@v3`
**devops Round 1**: Used `dorny/paths-filter@v3` (mutable tag, third-party action).
**devops Round 2**: Identified supply-chain risk — project convention is SHA-pinned third-party actions.
**Resolution (2026-05-01)**: Replaced with pure bash `git diff --name-only HEAD~1 HEAD` producing identical `java=true/false` output. No external dependency.

### Log-assertion false positives in `neverThrows` properties
**secure Round 1**: `neverThrows` properties included `assertNoRawListValuesInLogs`, which failed when common English words in generated strings matched static `IframeEmbedPolicy` log messages (e.g., "server", "header").
**Resolution (2026-05-01)**: Removed log-leak assertions from `neverThrows` properties; log-injection coverage preserved in dedicated canary properties (3+4). No CWE-117 gap.

## Test Results

| Layer | Count | Result |
|-------|-------|--------|
| Surefire unit (backend) | included in verify | All pass |
| Failsafe integration (backend) | 187 | 0 failures |
| Jacoco coverage | instruction ≥45%, branch ≥35% | All checks met |
| Karma unit (frontend) | 492 | 0 failures (standalone run) |

`./mvnw verify` total time: **08:25 min** — BUILD SUCCESS (2026-05-01T21:43:56+02:00)

## SR-FUZZ-13: Mutation Survivor Equivalents

To be documented after the first successful PITest CI run. Expected ~2–4 equivalent survivors in:
- `LogScrubber` boundary arithmetic (`hashtagLen`, `maskIp` digit bounds)
- `FallbackRateLimiter` boundary comparisons

## User Approval
Date: 2026-05-01
Approval message (verbatim): "approve"

## Open Risks
- PITest runtime ~5 min per CI run; paths filter gates unnecessary runs
- Surviving equivalent mutations (SR-FUZZ-13) to be documented post first PITest run
- fast-check pinned at 3.21.0; Dependabot will surface patch bumps for human review

## References
- `docs/decisions/2026-05-01-planning-fuzz-mutation-testing.md` — Phase 1 decision
- TD-4/ADR-TD4-01: `LogScrubber.xfoSummary(List<String>)` CWE-117 guard
- D-13/SR-8: Log hygiene discipline
- ADR-FUZZ-05: `IframeEmbedPolicy` must use `List<String>` parameters
