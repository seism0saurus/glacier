# Decision Record: jqwik Fuzz-Test Flake Fix — Acceptance

Date: 2026-05-04
Phase: Acceptance (Phase 3)
Agents: security-auditor (Round 1 + Round 2), acceptance-test-auditor (Round 1)
Status: Accepted — PASSED

## Summary

All twelve security requirements (SR-FUZZ-FIX-01 through SR-FUZZ-FIX-12) are satisfied in the
shipped code. Two conditions identified during Phase 3 auditing were closed by fix commits
`319ea7e` and `94d83db` before sign-off. The two compounding silent-skip bugs are eliminated and
double-locked by structural canary tests and `Statistics.coverage(...)` hard gates.

## Final Test Counts

| Layer | Before | After | Delta |
|-------|--------|-------|-------|
| Surefire (unit) | 1,047 | **1,227** | +180 |
| Failsafe (IT) | 298 | 298 | 0 |
| Jacoco (bundle) | Met | Met | — |
| BUILD | SUCCESS | SUCCESS | — |

+180 unit tests break down as:
- 169 — `ImageProxyUrlBuilderVerifyFuzzCanaryTest` (168 positions + 1 sentinel)
- 11 — New tests in `ImageProxyUrlBuilderVerifyFuzzTest` (9 from Phase 2 + 2 from Phase 3 fix cycle)

## SR Verification (final)

| SR | Severity | Verdict | Evidence |
|----|----------|---------|---------|
| SR-FUZZ-FIX-01 | High | PASS | `Statistics.coverage(≥ 80 % ASSERTED)` hard gate in property body |
| SR-FUZZ-FIX-02 | High | PASS | Zero bare `return;` in any `@Property` body (Round 1 PARTIAL was a mis-classification; corrected in Round 2) |
| SR-FUZZ-FIX-03 | High | PASS | `random.nextInt(token.length())` at line 184 |
| SR-FUZZ-FIX-04 | High | PASS | `mutationIndex > dotIdx` guard at line 213 |
| SR-FUZZ-FIX-05 | Medium | PASS | `dotSeparatorMutationReturnsEmpty()` `@Test` present |
| SR-FUZZ-FIX-06 | Medium | PASS | F-1 fix (`319ea7e`): `+ e` → `+ e.getClass().getSimpleName()` at line 114 |
| SR-FUZZ-FIX-07 | Medium | PASS | `nonMacBranchesReturnEmpty()` — 7 rows (6 from Phase 2 + 7th boundary row from `94d83db`) |
| SR-FUZZ-FIX-08 | Medium | PASS | `expiredTokenReturnsEmpty()` with forged HMAC-valid past-timestamp token |
| SR-FUZZ-FIX-09 | Medium | PASS | Statistics labels ASSERTED_PAYLOAD/MAC/DOT/SKIPPED; ASSERTED_PAYLOAD ≥ 50 % gate |
| SR-FUZZ-FIX-10 | Low | PASS | Class is `*Test.java` (Surefire) |
| SR-FUZZ-FIX-11 | Low | PASS | `grep -r DEV_SECRET src/main/` → zero hits |
| SR-FUZZ-FIX-12 | Low | PASS | F-2 fix (`94d83db`): ArchUnit `noClasses().that().doNotHaveSimpleName("ShareImageProxyUrlBuilder").should().callMethod(hmacSha256)` + modifier reflection gate |

## Phase 3 Findings and Dispositions

### F-1: Raw exception concatenation in `anyNonSignedByteStringReturnsEmpty` (SR-FUZZ-FIX-06)

**Finding**: `ImageProxyUrlBuilderVerifyFuzzTest.java:114` used `+ e` (Throwable.toString()) which
may include the base64-decoding input in jqwik shrink reports, violating ADR-FUZZ-02 / D-13.

**Severity**: Low (theoretical — `verify()` is internally exception-safe; catch block unreachable in practice).

**Disposition**: Fixed — commit `319ea7e`. Replaced `+ e` with `+ e.getClass().getSimpleName()`.

**Security audit Round 2 correction**: Auditor mis-cited line 124–127 as a second leak site; acceptance
auditor confirmed only line 114 was affected. Round 2 cross-review accepted the narrower scope.

---

### F-2: SR-FUZZ-FIX-12 planning-vs-implementation drift — modifier-check vs. call-graph gate (ADR-FUZZ-04)

**Finding**: ADR-FUZZ-04 promised an ArchUnit `noClasses().that()...callMethod(hmacSha256)` rule; Phase 2
implemented only a reflection modifier-check. The modifier check verifies visibility but cannot detect a future
production caller of the package-private method.

**Severity**: Low (security-auditor Round 2 final; immediate misuse risk bounded by same-package visibility).

**User decision (2026-05-04)**: Option A — add ArchUnit and the call-graph rule.

**Disposition**: Fixed — commit `94d83db`. Added `archunit-junit5:1.3.0` to `pom.xml` test scope and
`hmacSha256IsNotCalledByProductionClasses()` using `ClassFileImporter(DO_NOT_INCLUDE_TESTS)`.

---

### Advisory: uncovered branch — invalid base64 in MAC suffix

**Finding**: `ShareImageProxyUrlBuilder.java:115–118` `catch (IllegalArgumentException)` for
`Base64.decode(providedSig)` had no deterministic boundary row.

**Disposition**: Closed — `94d83db` adds 7th row `Arguments.of("invalid base64 in MAC suffix", "AAAAAAAAAAA.!!!")` to `nonMacBranchRows()`.

## Fix Cycle Commits

| Commit | Subject | Closes |
|--------|---------|--------|
| `319ea7e` | `fix(security): SR-FUZZ-FIX-06 scrub raw exception from Property 1 AssertionError` | F-1 |
| `94d83db` | `feat(security): SR-FUZZ-FIX-12 add ArchUnit call-graph gate + 7th boundary row` | F-2 + advisory |

## Resolved Conflicts (Phase 3)

### SR-FUZZ-FIX-02 reclassification

**security-auditor Round 1**: Scored SR-FUZZ-FIX-02 PARTIAL, conflating the SR (bare `return;`)
with SR-FUZZ-FIX-06 (message discipline).

**acceptance-test-auditor**: Corrected to PASS — Property 1 has zero bare `return;` statements.

**security-auditor Round 2**: Confirmed correction. SR-FUZZ-FIX-02: PASS.

### F-1 scope (line 114 vs. two sites)

**security-auditor Round 1**: Cited both line 114 and lines 124–127 as leak sites.

**acceptance-test-auditor**: Lines 124–127 reference only `s.length()` — no raw input. Single site at line 114.

**security-auditor Round 2**: Confirmed. One site. Severity downgraded to Low.

## User Approval

Date: 2026-05-04
Approval message (verbatim): "1: a, 2:yes"
(1 = F-2 Option A — add ArchUnit; 2 = yes to 7th boundary row)

## Open Risks (none remaining)

All findings from Phase 3 are closed. Residual risk from `hmacSha256` package-private visibility
(17 sibling production classes gain compile-time access) was accepted in Phase 1 (ADR-FUZZ-04) and
is now doubly mitigated by: modifier-check gate (`hmacSha256IsPackagePrivateForTestingOnly`)
+ ArchUnit call-graph gate (`hmacSha256IsNotCalledByProductionClasses`).

## References

- [Phase 1 planning](2026-05-04-planning-fuzz-flake-fix.md)
- [Phase 2 implementation](2026-05-04-implementation-fuzz-flake-fix.md)
- `src/test/java/de/seism0saurus/glacier/share/web/ImageProxyUrlBuilderVerifyFuzzTest.java`
- `src/test/java/de/seism0saurus/glacier/share/web/ImageProxyUrlBuilderVerifyFuzzCanaryTest.java`
- `src/main/java/de/seism0saurus/glacier/share/web/ShareImageProxyUrlBuilder.java`
- [OWASP A02:2021 — Cryptographic Failures](https://owasp.org/Top10/A02_2021-Cryptographic_Failures/), [A09:2021 — Security Logging and Monitoring Failures](https://owasp.org/Top10/A09_2021-Security_Logging_and_Monitoring_Failures/), [A10:2021 — Server-Side Request Forgery](https://owasp.org/Top10/A10_2021-Server-Side_Request_Forgery_(SSRF)/)
- [OWASP ASVS 5.0](https://raw.githubusercontent.com/OWASP/ASVS/refs/heads/v5.0.0/5.0/docs_en/OWASP_Application_Security_Verification_Standard_5.0.0_en.json) V2.9.1 (L1), V8.3.4 (L1)
- [CWE-345: Insufficient Verification of Data Authenticity](https://cwe.mitre.org/data/definitions/345.html)
