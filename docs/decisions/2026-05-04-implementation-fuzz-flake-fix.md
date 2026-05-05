# Decision Record: jqwik Fuzz-Test Flake Fix — Implementation

Date: 2026-05-04
Phase: Implementation (Phase 2)
Agents: tdd-ddd-implementer (Commits 1–3), secure-tdd-implementer (Commit 4, integrated by orchestrator)
Status: Accepted

## Summary

Four commits eliminate the two compounding silent-skip bugs in
`ImageProxyUrlBuilderVerifyFuzzTest.anyMutationOfValidTokenReturnsEmpty` and add ten new
deterministic boundary tests. The rewritten property now asserts on ≥ 80 % of all 500 tries
(vs. ~15 % before), enforced by a `Statistics.coverage(...)` hard gate. No production logic
changed beyond relaxing `hmacSha256` to package-private for test-only HMAC forging.

## Commits

| # | Hash | Subject |
|---|------|---------|
| 1 | `2356a96` | `test(security): SR-FUZZ-FIX-03 add canary tests for jqwik flake fix pre-conditions` |
| 2 | `de4e8a8` | `fix(security): SR-FUZZ-FIX-01/02/03/04/09 rewrite anyMutationOfValidTokenReturnsEmpty` |
| 3 | `0f14b62` | `test(security): SR-FUZZ-FIX-02 harden signThenVerifyRoundTripSucceeds — assert not null` |
| 4 | `58aeb87` | `test(security): SR-FUZZ-FIX-05/07/08/12 — boundary tests + hmacSha256 package-private` |

## Files Changed

| File | Change |
|------|--------|
| `src/test/java/de/seism0saurus/glacier/share/web/ImageProxyUrlBuilderVerifyFuzzCanaryTest.java` | **Created** — 169 tests (168 × `@ParameterizedTest` positions + 1 sentinel) |
| `src/test/java/de/seism0saurus/glacier/share/web/ImageProxyUrlBuilderVerifyFuzzTest.java` | **Modified** — property rewritten; 7 new methods added (`expiredTokenReturnsEmpty`, `dotSeparatorMutationReturnsEmpty`, `nonMacBranchesReturnEmpty` ×6, `hmacSha256IsPackagePrivateForTestingOnly`, `nonMacBranchRows`, `forgeSignedToken`) |
| `src/main/java/de/seism0saurus/glacier/share/web/ShareImageProxyUrlBuilder.java` | **Modified** — `hmacSha256` relaxed from `private static` to package-private static + `@VisibleForTesting` comment (ADR-FUZZ-04) |
| `docs/decisions/2026-05-04-planning-fuzz-flake-fix.md` | **Created** — Phase 1 planning decision record |

## Test Results

| Layer | Before | After | Delta |
|-------|--------|-------|-------|
| Unit (Surefire) | 1,047 | **1,225** | +178 |
| Integration (Failsafe) | 298 | 298 | 0 |
| Jacoco — all checks | Met | Met | — |
| BUILD | SUCCESS | SUCCESS | — |

The +178 unit tests break down as:
- 169 — `ImageProxyUrlBuilderVerifyFuzzCanaryTest` (168 positions + 1 sentinel)
- 9 — New JUnit/Parameterized tests in `ImageProxyUrlBuilderVerifyFuzzTest`

## Security Requirements Delivered

| SR | Status | Evidence |
|----|--------|---------|
| SR-FUZZ-FIX-01 | ✅ | `Statistics.coverage(c -> c.checkPattern("ASSERTED.*").percentage(p -> p >= 80))` in property |
| SR-FUZZ-FIX-02 | ✅ | Zero bare `return;` in `@Property` body; OOB guard is `AssertionError` |
| SR-FUZZ-FIX-03 | ✅ | `random.nextInt(token.length())` — runtime-bounded, self-correcting |
| SR-FUZZ-FIX-04 | ✅ | `if (mutationIndex > dotIdx && macDecodesIdentically(...))` — suffix-only skip |
| SR-FUZZ-FIX-05 | ✅ | `dotSeparatorMutationReturnsEmpty()` @Test |
| SR-FUZZ-FIX-06 | ✅ | AssertionError messages use `LogScrubber.hash8(token)` + index + length; no raw token |
| SR-FUZZ-FIX-07 | ✅ | `nonMacBranchesReturnEmpty()` @ParameterizedTest 6 rows |
| SR-FUZZ-FIX-08 | ✅ | `expiredTokenReturnsEmpty()` @Test with forged past-timestamp HMAC-valid token |
| SR-FUZZ-FIX-09 | ✅ | `Statistics.label("mutationOutcome")` distinguishes ASSERTED_PAYLOAD/ASSERTED_MAC/ASSERTED_DOT/SKIPPED; `ASSERTED_PAYLOAD ≥ 50%` coverage gate |
| SR-FUZZ-FIX-10 | ✅ | Test class remains `*Test.java` (Surefire); default Maven profile |
| SR-FUZZ-FIX-11 | ✅ | `grep -r DEV_SECRET src/main/` → zero hits |
| SR-FUZZ-FIX-12 | ✅ | `hmacSha256IsPackagePrivateForTestingOnly()` reflection gate |

## Phase 2 Conflict Resolution

**Conflict**: `secure-tdd-implementer` worktree branched from pre-fix `main`, so its
`anyMutationOfValidTokenReturnsEmpty` snapshot would have reverted Commits 1–3 on merge.

**Resolution (orchestrator, 2026-05-04)**: The four additive methods and the production
visibility change were cherry-picked directly onto the post-Commits-1-3 HEAD using Edit
and the production-file modification. The conflicting imports (which removed `LogScrubber`,
`Statistics`, `Random` that Commits 1–3 added) were resolved by applying only the additive
imports (`Test`, `ParameterizedTest`, `Arguments`, `MethodSource`, `Method`, `Modifier`,
`StandardCharsets`, `Instant`, `Stream`) to the already-fixed file. Both peer agents
confirmed this was the correct mechanical resolution.

## Deviations from Phase 1 Plan

None. All four commits match the planned scope exactly, including the correction to
ADR-FUZZ-04 (package contains 19 production classes, not 2 — noted in the planning doc).

## User Approval

Date: 2026-05-04
Approval message (verbatim): "approve"

## References

- `docs/decisions/2026-05-04-planning-fuzz-flake-fix.md` — Phase 1 planning decision
- `src/test/java/de/seism0saurus/glacier/share/web/ImageProxyUrlBuilderVerifyFuzzTest.java`
- `src/test/java/de/seism0saurus/glacier/share/web/ImageProxyUrlBuilderVerifyFuzzCanaryTest.java`
- `src/main/java/de/seism0saurus/glacier/share/web/ShareImageProxyUrlBuilder.java`
