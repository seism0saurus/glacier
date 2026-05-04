# Decision Record: jqwik Fuzz-Test Flake Fix — Planning

Date: 2026-05-04
Phase: Planning (Phase 1)
Agents: ddd-tdd-architect (Round 1 + Round 2), secure-feature-planner (Round 1 + Round 2)
Status: Accepted

## Summary

Two compounding silent-skip bugs in `ImageProxyUrlBuilderVerifyFuzzTest.anyMutationOfValidTokenReturnsEmpty`
reduce the asserting-tries ratio to ~15 % of the declared 500, creating a false-green for HMAC tamper detection
on the payload prefix of image-proxy tokens. The fix is test-only (plus one visibility change on a private helper)
and is delivered in four commits.

## Root Cause

The signed token is exactly 168 chars long (dotIdx = 124).

- **Bug A** — `@Provide mutationIndices()` returns `Arbitraries.integers().between(0, 299)`. 132 / 300 = 44 % of
  generated indices exceed `token.length()` and silently `return`, bypassing the assertion.
- **Bug B** — `macDecodesIdentically(token, mutatedToken)` compares only the MAC suffix. For any mutation in the
  payload-prefix positions [0, 123], the MAC is byte-identical and the guard silently returns. This skips all 124
  payload-prefix positions — the URL, expiry timestamp, and share-link ID — which are the load-bearing
  HMAC-authenticated fields.

Combined effect: only ~15 % of 500 tries actually execute `assertThat(result).isEmpty()`. A regression that broke
HMAC verification of the payload prefix (e.g. a refactor that changes payload assembly) would ship undetected.

## Security Context

`ShareImageProxyController:88-93` calls `ShareImageProxyUrlBuilder.verify(...)` as the sole HMAC-fail-closed gate
before `ShareImageProxyService.fetch(originalUrl)`. Token forgery is an SSRF pivot (OWASP A10:2021; T-FUZZ-FIX-01/02).
The test is classified SR-TEST-06 / ADR-SHARE-07 — a load-bearing security regression detector.

## Key Decisions

### ADR-FUZZ-01: Drive mutation index from `@ForAll Random`, not fixed-bound `@Provide`

**Decision**: Replace `@ForAll("mutationIndices") int mutationIndex` (fixed `[0, 299]`) with `@ForAll Random random`
and derive the index via `random.nextInt(token.length())` inside the property body.

**Rationale**: The valid mutation range depends on the signed token's length, which depends on `VALID_URL`,
`DUMMY_SHARE_LINK_TOKEN`, and the current epoch. Hard-coding `[0, 299]` required a silent OOB guard to compensate.
Driving the index from a `Random` ForAll keeps shrinking working and ties the bound to the actual token.

**Alternatives considered**: Fixed `@IntRange(min=0, max=167)` (brittle; fails silently when URL length changes);
`@BeforeProperty` setting a per-instance bound (lifecycle mismatch with jqwik's `@Provide`).

---

### ADR-FUZZ-02: Assertion failure messages must not echo raw token bytes

**Decision**: AssertionError and `.as(...)` strings in the property body reference only `mutationIndex`,
`token.length()`, and `LogScrubber.hash8(token)` — never the raw token, `signedUrl`, or `mutatedToken`.

**Rationale**: jqwik shrink reports echo `@ForAll` parameter values. The token string embeds the original URL and a
share-link ID. With `DEV_SECRET` as a fixed test constant there is no real key-material risk today, but the pattern
must be established before a future move to a generated test secret (SR-FUZZ-02 chain; OWASP A09 / ASVS V8.3.4 L1).

---

### ADR-FUZZ-03: `macDecodesIdentically` skip restricted to MAC-suffix mutations

**Decision**: Apply the base64url-padding-bit no-op skip only when `mutationIndex > dotIdx`.

**Rationale**: The skip handles the edge case where the last char of the 43-char base64url MAC has 2 unused
padding bits — a mutation that changes a padding bit but not the decoded byte is a genuine no-op. That edge case
can only occur in the MAC suffix. For payload-prefix mutations the helper incorrectly returns `true` (identical
MAC — the MAC was not touched) and the guard fires, skipping the assertion entirely. Restricting the skip to the
suffix removes the over-skip without introducing false negatives.

**Alternatives considered**: Remove skip entirely (would introduce ~1/64 false negatives on MAC-last-char boundary);
test both decoded payloads and MACs (over-engineered).

---

### ADR-FUZZ-04: `hmacSha256` relaxed to package-private for test-side payload forging

**Decision**: Change `ShareImageProxyUrlBuilder.hmacSha256(byte[], String)` from `private static` to package-private
static, annotated `@VisibleForTesting` in comment form. An ArchUnit rule (SR-FUZZ-FIX-12) ensures no other production
class in `share.web` calls this helper.

**Rationale**: `expiredTokenReturnsEmpty()` and two rows of `nonMacBranchesReturnEmpty()` require a properly-signed
token with a synthetic timestamp. Option C (duplicate HMAC logic in a test fixture) creates a crypto-divergence
maintenance pact that is itself a silent-skip risk. Option B (inject `Clock`) couples the fix to a wider
architectural change. Option A (package-private scoping) is the minimum surface change.

**Fact correction from Round 2**: `de.seism0saurus.glacier.share.web` contains 19 production classes, not 2.
The relaxation grants compile-time visibility to 17 sibling classes that do not currently need it. Risk is
bounded because the helper is not `public`. The ArchUnit rule closes the misuse gap at the build gate.

**Alternatives considered**: `Clock` injection (out-of-scope, wider blast radius); test-fixture HMAC duplication
(crypto-divergence pact, rejected).

---

### ADR-FUZZ-05: `Statistics.coverage(...)` as hard build gate, not telemetry

**Decision**: Use jqwik `Statistics.coverage(c -> c.check("ASSERTED").percentage(p -> p >= 80))` and
`c.check("ASSERTED_PAYLOAD").percentage(p -> p >= 50)` as hard property-failure thresholds, not just labels.

**Rationale**: The original flake survived multiple CI runs precisely because no threshold failed. A histogram
without a threshold is observability without an alert — the same pattern that created the false-green recurs
whenever someone adds a well-meaning skip guard. `Statistics.coverage(...)` in jqwik 1.8.4 fails the property
when the floor is missed, making silent-skip regression impossible to ship silently.

**Alternatives considered**: `Statistics.label(...).collect(...)` only (telemetry; no build signal — rejected as
insufficient).

---

## Resolved Conflicts

### CONFLICT: Statistics histogram is insufficient as a regression gate

**secure-feature-planner position**: `Statistics.label(...)` provides no regression signal; a future skip guard
that collapses the asserting-tries ratio will produce no build failure. MUST install hard
`Statistics.coverage(...).percentage(p -> p >= 80)` floor.

**ddd-tdd-architect original position (Round 1)**: `Statistics.label("mutationOutcome").collect(...)` as
observability only, no threshold stated.

**Resolution (2026-05-04)**: Architect accepted. ADR-FUZZ-05 added. `Statistics.coverage(...)` with total ASSERTED
≥ 80 % and ASSERTED_PAYLOAD ≥ 50 % gates are mandatory in Commit 2. *(User approval: "approve", 2026-05-04.)*

---

## Phase 2 Lane Partition

| Agent | Lane | Files owned |
|-------|------|-------------|
| `tdd-ddd-implementer` | Commits 1, 2, 3 — canary class, property rewrite, sister hardening | `ImageProxyUrlBuilderVerifyFuzzCanaryTest.java` (new), `ImageProxyUrlBuilderVerifyFuzzTest.java` |
| `secure-tdd-implementer` | Commit 4 — boundary tests + visibility change + ArchUnit rule | `ShareImageProxyUrlBuilder.java`, `ImageProxyUrlBuilderVerifyFuzzTest.java` (additive only) |

**Coordination point**: `secure-tdd-implementer`'s Commit 4 adds methods to `ImageProxyUrlBuilderVerifyFuzzTest.java`
that call the package-private `hmacSha256`. Both agents must not edit the same methods simultaneously. The
`tdd-ddd-implementer` writes `anyMutationOfValidTokenReturnsEmpty`, `signThenVerifyRoundTripSucceeds`, and the
canary class. The `secure-tdd-implementer` adds `expiredTokenReturnsEmpty`, `dotSeparatorMutationReturnsEmpty`,
and `nonMacBranchesReturnEmpty` as new methods — no overlap.

---

## Security Requirements (all to be verified in Phase 3)

| ID | Severity | Requirement |
|----|---------|-------------|
| SR-FUZZ-FIX-01 | High | `Statistics.coverage` ASSERTED ≥ 80 % hard gate |
| SR-FUZZ-FIX-02 | High | Zero bare `return;` inside any `@Property` body in this file |
| SR-FUZZ-FIX-03 | High | Mutation index derived from `token.length()` at runtime |
| SR-FUZZ-FIX-04 | High | `macDecodesIdentically` called only when `mutationIndex > dotIdx` |
| SR-FUZZ-FIX-05 | Medium | `dotSeparatorMutationReturnsEmpty()` deterministic `@Test` present and passing |
| SR-FUZZ-FIX-06 | Medium | Assertion messages do not echo raw `signedUrl`/`token`/`mutatedToken` |
| SR-FUZZ-FIX-07 | Medium | Six boundary `@Test`/`@ParameterizedTest` rows covering non-MAC `verify()` branches |
| SR-FUZZ-FIX-08 | Medium | `expiredTokenReturnsEmpty()` deterministic `@Test` with forged past-timestamp token |
| SR-FUZZ-FIX-09 | Medium | Statistics histogram distinguishes ASSERTED_PAYLOAD / ASSERTED_MAC / ASSERTED_DOT / SKIPPED; per-section `coverage()` threshold on ASSERTED_PAYLOAD ≥ 50 % |
| SR-FUZZ-FIX-10 | Low | Test class remains `*Test.java` (Surefire); runs under default Maven profile |
| SR-FUZZ-FIX-11 | Low | DEV_SECRET constant has zero references outside fuzz test files |
| SR-FUZZ-FIX-12 | Low | ArchUnit rule preventing production classes other than `ShareImageProxyUrlBuilder` from calling `hmacSha256(...)` |

---

## User Approval

Date: 2026-05-04
Approval message (verbatim): "approve"

---

## Open Risks (accepted)

- **`hmacSha256` package-private in a 19-class package**: 17 sibling production classes gain compile-time visibility.
  Mitigated by ADR-FUZZ-04 rationale, `@VisibleForTesting` comment, and SR-FUZZ-FIX-12 ArchUnit gate. Residual risk
  is that ArchUnit is a test-time check only; an IDE or future `javac` invocation that skips tests would not catch
  misuse. Accepted.

## References

- `docs/decisions/2026-04-22-planning-share-link-qr.md` — ADR-SHARE-07 (HMAC image-proxy token design)
- `docs/decisions/2026-05-01-planning-fuzz-mutation-testing.md` — SR-FUZZ-05 (PITest 70 % kill-rate gate)
- `docs/decisions/2026-05-01-acceptance-fuzz-mutation-testing.md` — original jqwik flake noted as deferred
- `src/test/java/de/seism0saurus/glacier/share/web/ImageProxyUrlBuilderVerifyFuzzTest.java`
- `src/main/java/de/seism0saurus/glacier/share/web/ShareImageProxyUrlBuilder.java`
- OWASP A02:2021 (Cryptographic Failures), A09:2021 (Security Logging), A10:2021 (SSRF)
- ASVS V2.9.1 (L1), V13.2.4 (L1), V8.3.4 (L1)
- CWE-345 (Insufficient Verification of Data Authenticity), CWE-613 (Insufficient Session Expiration)
- WSTG-CRYP-04
