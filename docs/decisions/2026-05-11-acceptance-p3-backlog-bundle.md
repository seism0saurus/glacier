# Decision Record: P3 Backlog Bundle A — Phase 3 Acceptance

Date: 2026-05-11
Phase: Acceptance
Agents: security-auditor (Round 1 + Round 2), acceptance-test-auditor (Round 1 + fix re-verification)
Status: **ACCEPTED — PASSED**

## Summary

P3 Backlog Bundle A is fully accepted. All 24 acceptance criteria verified PASS after two fix
cycles. Four security findings raised in Round 1 (SEC-P3A-01/02/04/05) are all RESOLVED.
Final build: **337 IT, 0 failures, BUILD SUCCESS, Jacoco ≥ 45%/35% met**. No Critical or High
findings remain open. Two deferred items (TD-P3A-DOMAIN-FIX, GlacierDevmodeProperties) are
tracked for Bundle B.

## Acceptance verdict

**PASSED** — User approval received 2026-05-11.

## Evidence

| Dimension | Result |
|---|---|
| Surefire (backend unit) | **0 failures** |
| Failsafe IT (backend integration) | **337/337 PASS — BUILD SUCCESS** |
| Jacoco coverage | **All checks met** (≥ 45% instruction / ≥ 35% branch, bundle-wide) |
| All 24 AC | **PASS** (independently verified by acceptance-test-auditor) |
| Security audit Round 1 | 4 findings raised (SEC-P3A-01/02/04/05) |
| Security audit Round 2 | **PASSED** — all 4 findings RESOLVED; all critical SRs MET |
| FIX REQUEST items issued | **2** (tdd-ddd-implementer, secure-tdd-implementer) — both RESOLVED |

## Verified Acceptance Criteria (AC-P3A-01..24)

| AC | Description | Status | Key evidence |
|---|---|---|---|
| AC-P3A-01 | All 5 `glacier.cookie.secure` consumers remain on `@Value` (GlacierDevmodeProperties deferred) | PASS | grep confirms 5 `@Value("${glacier.cookie.secure}")` hits; `GlacierDevmodeProperties` not created |
| AC-P3A-02 | `mastodon.https` is `Boolean + @NotNull`; `MastodonPropertiesMissingHttpsIT` passes | PASS | `MastodonProperties.java`; IT suite green |
| AC-P3A-03 | `BindFailureValueScrubbingIT` proves exception chain does not contain raw `mastodon.accessToken` | PASS | Sentinel `P3A_SENTINEL_DO_NOT_LEAK` on FAILING field; positive + negative-control tests pass |
| AC-P3A-04 | `MastodonShortHandle.parse()` rejects CRLF, RTL override, null-byte inputs | PASS | `MastodonShortHandleTest` ≥ 19 cases |
| AC-P3A-05 | `MastodonShortHandleVsLegacyParityTest` proves strict superset of legacy `getShortHandle()` | PASS | 4 parameterized cases |
| AC-P3A-06 | `MastodonShortHandleVsLegacyParityTest` covers ≥ 4 parameterized inputs | PASS | `MastodonShortHandleVsLegacyParityTest.java` |
| AC-P3A-07 | `EventTypeMapping` covers all three vocabulary branches | PASS | `EventTypeMappingTest` all 3 branches |
| AC-P3A-08 | `EventTypeMappingExhaustivenessTest` uses reflection to cover every enum constant | PASS | Reflection enumeration over `StompEventType` constants |
| AC-P3A-09 | `EventTypeMappingExclusivityTest` ArchUnit rule prevents co-import; `rule.check()` invoked | PASS | SEC-P3A-02 fix: `rule.check(productionClasses)` at line 149; regression-proof inner-class test |
| AC-P3A-10 | `StompCallback.eventTypeFor()` removed; uses `EventTypeMapping.stompFor()` | PASS | `StompCallback.java:300` delegates to `EventTypeMapping` |
| AC-P3A-11 | `OperatorPropertyKeyMigrationSentinelTest` passes (no camelCase operator key remains) | PASS | Sentinel walks `src/`; 0 camelCase operator key hits |
| AC-P3A-12 | `@SafeOperatorString` rejects all 8 forbidden sequences in `SafeOperatorStringValidatorTest` | PASS | CRLF, null-byte, tab, RTL override, ZWSP, BOM, U+2028, U+2029 |
| AC-P3A-13 | `operatorWebsite` rejects `javascript:`, `data:`, `vbscript:`, `file:` | PASS | `GlacierOperatorPropertiesTest` lines 88–106 |
| AC-P3A-14 | `MastodonProperties` `@Pattern` rejects structural violations (handle, instance) | PASS | `MastodonPropertiesTest`; `MastodonInstanceValidator` unit tests |
| AC-P3A-15 | `GlacierBindHandler` registered as `ConfigurationPropertiesBindHandlerAdvisor` | PASS | `GlacierBindHandler.glacierBindHandlerAdvisor()` returns advisor lambda |
| AC-P3A-16 | `BindFailureValueScrubbingIT.withoutBindHandler_sentinelOnFailingField_leaksInExceptionChain` passes (negative-control) | PASS | Handler removed → sentinel IS present in exception chain (proves handler is load-bearing) |
| AC-P3A-17 | `RessourcesTypoSentinelTest` passes (typo absent from `src/main/java/`) | PASS | Sentinel passes on current codebase |
| AC-P3A-18 | `AdrIndexCompletenessSentinelTest` passes (all ADR files indexed in `docs/decisions/README.md`) | PASS | All `.md` files in `docs/decisions/` referenced in `README.md` |
| AC-P3A-19 | `codeql.yml` cache-key fix — `${{ hashFiles(...) }}` present at line 75 | PASS | Manual inspection: `.github/workflows/codeql.yml:75` confirmed |
| AC-P3A-20 | `GlacierOperatorPropertiesTest` covers `@Size` max constraints for unbounded string fields | PASS | SEC-P3A-05 fix: `@Size` on `name`(256)/`streetAndNumber`(256)/`city`(128)/`country`(128); 5 boundary tests at lines 154–198 incl. at-max-accepted |
| AC-P3A-21 | `MastodonHandleFactoryIT` wired to `MastodonProperties` (Round 2 integration) | PASS | `MastodonHandleFactoryIT.java` uses `ApplicationContextRunner` + `MastodonProperties` |
| AC-P3A-22 | `OwaspMatrixCookieAttributesLockstepTest` + `CookieEmissionIT` pass with `@Import` | PASS | `@Import({MastodonProperties.class, MastodonHandleFactory.class})` + `@MockitoBean GlacierOperatorProperties` |
| AC-P3A-23 | `docs/decisions/README.md` exists and contains ≥ 80 ADR entries | PASS | Hand-curated index, 6 topic groups |
| AC-P3A-24 | `./mvnw verify` (full suite) exits BUILD SUCCESS, 0 failures | PASS | 337 IT, 0 failures, Jacoco thresholds met |

## Security Findings

### All 4 Round 1 findings RESOLVED.

| Finding | Description | Disposition |
|---|---|---|
| **SEC-P3A-01** | `GlacierBindHandler.onFailure` lacked `BindValidationException` branch (PRIMARY leak path for Jakarta Validation constraint failures; `BindValidationException extends RuntimeException`, NOT `BindException`) | **RESOLVED** — `BindValidationException` branch added FIRST at `GlacierBindHandler.java:137` with `null` cause (mandatory: preserving cause re-exposes `FieldError.rejectedValue` via `getCause().toString()`) |
| **SEC-P3A-02** | `EventTypeMappingExclusivityTest.buildExclusivityRule()` defined the rule but never called `rule.check(productionClasses)` — vacuous test always passed | **RESOLVED** — `rule.check(productionClasses)` invoked at line 149; regression-proof test (`exclusivityRule_catchesDeliberateViolation`) uses deliberate-violator inner class + `because()` clause pinning |
| **SEC-P3A-04** | `BindFailureValueScrubbingIT` placed sentinel on VALID field — positive test passed regardless of whether handler scrubbed anything | **RESOLVED** — sentinel `P3A_SENTINEL_DO_NOT_LEAK` on FAILING field `mastodon.instance`; negative-control test (`withoutBindHandler_sentinelOnFailingField_leaksInExceptionChain`) proves handler is load-bearing |
| **SEC-P3A-05** | `GlacierOperatorProperties` `name`, `streetAndNumber`, `city`, `country` had no `@Size` cap — unbounded strings served unauthenticated via `/rest/operator` | **RESOLVED** — `@Size(max=256)` on `name` + `streetAndNumber`; `@Size(max=128)` on `city` + `country`; 5 boundary tests in `GlacierOperatorPropertiesTest` |

### Security improvements delivered by this bundle

| Area | Before | After |
|---|---|---|
| Config startup failure shape | Silent NPE or raw value in `BindException` chain | `BindValidationException` → `ScrubbedBindException` (count-only message, `null` cause — raw value unreachable); `BindException` → `ScrubbedBindException` (scrubbed value, original as cause) |
| Operator string length | Unbounded — any length stored and served | `@Size` caps at 256/128 chars; enforced at startup, tested with at-boundary proof |
| EventType translation authority | Duplicate `StompCallback.eventTypeFor()` + `EventTypeMapping.stompFor()` (dead letter risk) | Single authority: `EventTypeMapping.stompFor()`; ArchUnit exclusivity gate with load-bearing regression proof |
| Mastodon handle/instance validation | Raw `@Value` — no structural validation, no safe typing | `@Pattern` + `@MastodonInstanceValidator`; `MastodonShortHandle` record with `toString()` never exposing `full`/`server`; `parse()` strict superset of legacy |

## Security Requirements Coverage (SR-P3A-01..22)

All 22 SRs MET or DEFERRED-BY-DESIGN (SR-P3A-05 → Bundle B atomic migration of `glacier.cookie.secure`).

Critical SRs:
- **SR-P3A-01**: MET (ConstraintValidators + `GlacierBindHandler` both scrub; expanded scope documented)
- **SR-P3A-03**: MET (`mastodon.https` `Boolean + @NotNull`; fail-on-missing preserved)
- **SR-P3A-06**: MET (`MastodonShortHandle.parse()` strict superset verified)
- **SR-P3A-11**: MET (`javascript:`, `data:`, `vbscript:`, `file:` rejected)
- **SR-P3A-12**: MET (all 8 `@SafeOperatorString` forbidden sequences rejected)
- **SR-P3A-13**: MET (`mastodon.handle` `@Pattern` uses `\A`/`\z` anchors)

## Known Gaps (accepted, do not block)

| Gap | Severity | Rationale |
|---|---|---|
| **TD-P3A-DOMAIN-FIX**: `DomainSafetyValidator.java:98` raw-value concatenation in error message | Low | Mitigated: `mastodon.instance` uses separate `MastodonInstanceValidator`. Not reachable from external untrusted input today. Tracked for Bundle B. |
| **GlacierDevmodeProperties**: `glacier.cookie.secure` consumers on `@Value` | Low | Atomic migration deferred per ADR-P3A-1 / SR-P3A-05. Non-regression confirmed by AC-P3A-01. |
| **SR-P3A-01 scope documentation**: Original wording covered ConstraintValidators only; expanded to include `BindHandler` (primary leak path) | Observation | No functional change needed. Documentation refresh in Phase 5 ADR pass. |

## Fix Cycles

### Fix Cycle 1 — tdd-ddd-implementer (SEC-P3A-02)
**Issue**: `EventTypeMappingExclusivityTest` rule defined but `rule.check()` never invoked.
**Fix**: Added `buildExclusivityRule().check(productionClasses)` at line 149; added `exclusivityRule_catchesDeliberateViolation` regression-proof test with `UnauthorisedTranslatorViolation` inner class and `because("ADR-P3A-4")` pinning.
**Verified RESOLVED**: Security-auditor Round 2 confirmed at `EventTypeMappingExclusivityTest.java:149`.

### Fix Cycle 2 — secure-tdd-implementer (SEC-P3A-01, SEC-P3A-04, SEC-P3A-05)
**Issues**:
1. SEC-P3A-01: `GlacierBindHandler.onFailure` missing `BindValidationException` branch.
2. SEC-P3A-04: `BindFailureValueScrubbingIT` sentinel on VALID field; no negative-control.
3. SEC-P3A-05: `GlacierOperatorProperties` unbounded string fields.
**Fixes**:
1. `BindValidationException` branch added FIRST (before `BindException`) with mandatory `null` cause.
2. Sentinel relocated to `mastodon.instance` (FAILING field); `withoutBindHandler_sentinelOnFailingField_leaksInExceptionChain` negative-control test added.
3. `@Size(max=256)` on `name` + `streetAndNumber`; `@Size(max=128)` on `city` + `country`; 5 boundary tests.
**Verified RESOLVED**: Security-auditor Round 2 confirmed all three. BUILD SUCCESS: 337 IT, 0 failures.

## User Approval

Date: 2026-05-11
Approval message (verbatim): "approve"

## Final sign-off

P3 Backlog Bundle A is hereby **CLOSED — PASSED**. All seven bundle items are implemented,
tested, and accepted. The bundle improves the security posture of Glacier in four areas:
(1) startup-time config validation with scrubbed failure messages, (2) typed Mastodon handle
with safe `toString()`, (3) length-capped operator strings served unauthenticated, and
(4) exclusive EventType translation authority with a load-bearing ArchUnit gate.

Two deferred items remain open:

| Item | Status |
|---|---|
| TD-P3A-DOMAIN-FIX (`DomainSafetyValidator.java:98`) | Deferred to Bundle B |
| GlacierDevmodeProperties `glacier.cookie.secure` atomic migration | Deferred to Bundle B |

## References

- Planning: `docs/decisions/2026-05-10-planning-p3-backlog-bundle.md`
- Implementation: `docs/decisions/2026-05-11-implementation-p3-backlog-bundle.md`
- SubscriptionService split acceptance: `docs/decisions/2026-05-10-acceptance-subscription-service-split.md`
- Quality Review acceptance: `docs/decisions/2026-05-08-acceptance-quality-review.md`
- [OWASP Top 10 (2025)](https://owasp.org/www-project-top-ten/) — A02, A03, A05, A09
- [OWASP API Security Top 10 (2023)](https://owasp.org/API-Security/editions/2023/en/0x11-t10/) — API4
- [CWE-532: Insertion of Sensitive Information into Log File](https://cwe.mitre.org/data/definitions/532.html)
- [ASVS V7.3.1 (L1)](https://raw.githubusercontent.com/OWASP/ASVS/refs/heads/v5.0.0/5.0/docs_en/OWASP_Application_Security_Verification_Standard_5.0.0_en.json), V9.1.1 (L1)
