# Decision Record: P3 Backlog Bundle B — Phase 3 Acceptance

Date: 2026-05-12
Phase: Acceptance
Agents: security-auditor (Round 1 + Round 2), acceptance-test-auditor (Round 1)
Status: **ACCEPTED — PASSED**

## Summary

P3 Backlog Bundle B is fully accepted. All 14 acceptance criteria (13 functional + BUILD)
verified PASS. Security-auditor Round 1 raised 3 medium findings (F-3/F-10/F-14) — all
confirmed deferrable by Round 2 cross-review. No Critical or High findings remain open.
Final build: **345 IT, 0 failures, BUILD SUCCESS, Jacoco ≥ 45%/35% met**.

## Acceptance verdict

**PASSED** — User approval received 2026-05-12.

## Evidence

| Dimension | Result |
|---|---|
| Surefire (backend unit) | **0 failures** |
| Failsafe IT (backend integration) | **345/345 PASS — BUILD SUCCESS** |
| Jacoco coverage | **All checks met** (≥ 45% instruction / ≥ 35% branch, bundle-wide) |
| All 13 ACs | **PASS** (verified by acceptance-test-auditor) |
| Security audit Round 1 | 3 medium findings raised (F-3/F-10/F-14) |
| Security audit Round 2 | **PASSED** — all 3 findings confirmed deferrable; no blockers |
| FIX REQUEST items issued | **0** |

## Verified Acceptance Criteria

| AC | Description | Status | Key evidence |
|---|---|---|---|
| AC-P3B-A1 | `DomainSafetyValidatorStaticMessageTest` — ≥6 inputs; `getMessage()` ≠ raw input; equals static literal | PASS | 8 inputs; canary keyword "loopback" present; `getMessageTemplate()` regression guard |
| AC-P3B-A1b | `DomainSafetyValidatorProgrammaticLeakTest` — bypasses `GlacierBindHandler`; no violation message contains raw value | PASS | `Validation.buildDefaultValidatorFactory()` direct; 8 inputs |
| AC-P3B-A1c | `DomainSafetyValidatorLargeInputBoundednessTest` — static message length < 300 chars | PASS | 3 test methods; `"localhost"` (prohibited) triggers violation; length asserted |
| AC-P3B-A4 | `ConstraintViolationMessageStaticOnlyStructureTest` — scans all `*Validator.java`; fails on identifier concatenation | PASS | 4-line window; pattern `\+\s+[A-Za-z_$]`; currently catches all violations |
| AC-P3B-A-FIX | `DomainSafetyValidator.java:96-100` PROHIBITED_LITERALS branch — static literal only | PASS | `trimmed` variable absent; all 5 `buildConstraintViolationWithTemplate` callsites confirmed static |
| AC-P3B-B1 | `GlacierCookiePropertiesBindIT` — Spring slice; true/false bind; missing/empty/non-boolean fail-closed | PASS | `ApplicationContextRunner`; 5 test methods; fail-closed (no `@NotNull` bypass via defaults) |
| AC-P3B-B2 | `BindFailureValueScrubbingIT.CookieSecureBooleanBindFailures` — 3 cases; no raw value in chain | PASS | Missing/empty/non-boolean; chain traversal asserts `doesNotContain` raw value |
| AC-P3B-B3 | `GlacierCookiePropertiesNotNullValidationTest` — `secure=null` → `@NotNull` violation | PASS | `Validator.validate(bean)` direct; null → violation; TRUE/FALSE → no violations |
| AC-P3B-B4 | `CookieSecureBeanWiringStructureTest` — bidirectional: all 7 reference bean AND zero `@Value` remain | PASS | Positive + negative assertions; `CookieSecureSingleSourceOfTruthStructureTest` deleted in same commit |
| AC-P3B-B4a | `CookieSecureConsumerUnboxingStructureTest` — zero `.getSecure().booleanValue()` in any of 7 consumer files | PASS | All 7 consumer filenames scanned; pattern absent |
| AC-P3B-B5 | `GlacierConfigurationPropertiesFieldDisjointnessTest` — all `@ConfigurationProperties` beans have disjoint key sets | PASS | Source-scan + reflection; camelCase → kebab-case; no collision across 6+ beans incl. co-prefix `glacier.share.*` |
| AC-P3B-B6 | `StartupSanityChecker.java` mixed-state JavaDoc marker | PASS | Lines 13–18, 53–58: documents `glacier.cookie.secure` → bean; `glacier.devmode` + `mastodon.https` remain on `@Value` per SR-9/D-14 |
| AC-P3B-GREP | `grep "@Value.*glacier.cookie.secure"` → 0; `grep "glacier.devmode"` in webservice/util → 0 code hits | PASS | Security-auditor SR-P3B-11 verified; 3 JavaDoc references in webservice/* are non-code |
| AC-P3B-BUILD | `./mvnw verify` — BUILD SUCCESS, 345 IT, 0 failures, Jacoco thresholds met | PASS | Confirmed by independent re-run after auditor's truncated background run |

## Security Findings

### All 3 Round 1 findings DEFERRED (not resolved — deferral confirmed by Round 2).

| Finding | Description | Disposition |
|---|---|---|
| **F-3** | `ConstraintViolationMessageStaticOnlyStructureTest` regex `\\+\\s+[A-Za-z_$]` misses `+identifier` (no whitespace). No current validator uses this pattern. | **DEFERRED** — regex tightening to `\\+\\s*` planned as post-merge follow-up |
| **F-10** | `CookieSecureBooleanBindFailures.nonBooleanCookieSecure_doesNotLeakRawValue` lacks paired negative-control test proving `GlacierBindHandler` BRANCH 2 is load-bearing | **DEFERRED** — BRANCH 1 negative-control (`mastodon.instance` sentinel) proves handler lifecycle; positive assertion sufficient per AC |
| **F-14** | `CookieSecureBeanWiringStructureTest` and `CookieSecureConsumerUnboxingStructureTest` hardcode `CONSUMER_FILENAMES` set — future consumers silently escape | **DEFERRED** — 7 current consumers fully covered; global-scan complement planned post-merge |

### Security improvements delivered by this bundle

| Area | Before | After |
|---|---|---|
| `DomainSafetyValidator` constraint-violation template | Raw `trimmed` value concatenated at line 98 (PROHIBITED_LITERALS branch) | Static literal only — 5 callsites; A4 structural gate enforces invariant going forward |
| `glacier.cookie.secure` configuration surface | 7 scattered `@Value("${glacier.cookie.secure:true}")` with primitive `boolean` silent-default | Single `GlacierCookieProperties` bean with `@NotNull Boolean`; `Boolean.TRUE.equals(getSecure())` idiom at all 7 consumers; missing property → startup failure |
| Configuration bean inventory | No disjointness gate; future prefix collisions possible | `GlacierConfigurationPropertiesFieldDisjointnessTest` ratchets all `@ConfigurationProperties` beans (6+) against field-key collision |

## Security Requirements Coverage (SR-P3B-01..12)

All SRs MET or DEFERRED-BY-DESIGN.

Critical SRs:
- **SR-P3B-01**: MET — static literal in all 5 callsites; A4 structural gate
- **SR-P3B-03**: MET — `Boolean + @NotNull`; primitive `boolean` absent
- **SR-P3B-04**: MET — `Boolean.TRUE.equals(getSecure())` confirmed in all 7 consumers
- **SR-P3B-05**: MET — bidirectional structural test; old ratchet deleted atomically

## Known Gaps (accepted, do not block)

| Gap | Severity | Rationale |
|---|---|---|
| **F-3 follow-up**: regex `\\+\\s+` instead of `\\+\\s*` in structural gate | Low | No current validator uses `+identifier` (no space). Java style enforces whitespace. Tracked. |
| **F-10 follow-up**: BRANCH 2 negative-control missing in `BindFailureValueScrubbingIT` | Medium | BRANCH 1 negative-control (`mastodon.instance` sentinel) proves handler is load-bearing for Jakarta-validation path. BRANCH 2 positive assertion met. Tracked. |
| **F-14 follow-up**: hardcoded `CONSUMER_FILENAMES` set | Medium | 7 consumers verified correct today. Global-scan complement planned. Tracked. |
| **TD-P3B-DOMAIN-UNICODE**: U+202E/U+200B/U+FEFF/U+2028/U+2029 not blocked by `DomainSafetyValidator` | Low | Operator-controlled config; separate deferred ticket per ADR-P3B-6. |
| **StartupSanityChecker mixed-state constructor** | Low | `glacier.devmode` on `@Value` (SR-9/D-14 fence); documented by B6 JavaDoc. |

## Fix Cycles

None. No fix cycles were required — both Phase 3 agents confirmed no Critical or High blocking findings.

## User Approval

Date: 2026-05-12
Approval message (verbatim): "approve"

## Final sign-off

P3 Backlog Bundle B is hereby **CLOSED — PASSED**. Both deferred items from Bundle A are
implemented, tested, and accepted. The bundle improves the security posture of Glacier in
two areas: (1) defense-in-depth against constraint-violation template injection
(`DomainSafetyValidator` static-literal fix + A4 structural gate), and (2) typed,
fail-closed configuration for the Secure-cookie flag (`GlacierCookieProperties` with
`@NotNull Boolean` replacing 7 scattered `@Value` injection sites).

Three follow-up items remain open (all Medium severity, all post-merge):

| Item | Owner |
|---|---|
| F-3: tighten regex to `\\+\\s*` + positive-control fixture | secure-tdd-implementer |
| F-10: add `withoutBindHandler_nonBooleanCookieSecure_leaksRawValue` negative-control | secure-tdd-implementer |
| F-14: replace hardcoded `CONSUMER_FILENAMES` with dynamic scan | tdd-ddd-implementer |

## References

- Planning: `docs/decisions/2026-05-11-planning-p3-bundle-b.md`
- Implementation: `docs/decisions/2026-05-12-implementation-p3-bundle-b.md`
- Bundle A acceptance: `docs/decisions/2026-05-11-acceptance-p3-backlog-bundle.md`
- Quality Review acceptance: `docs/decisions/2026-05-08-acceptance-quality-review.md`
- [CWE-532: Insertion of Sensitive Information into Log File](https://cwe.mitre.org/data/definitions/532.html)
- [CWE-1188: Insecure Default Initialization of Resource](https://cwe.mitre.org/data/definitions/1188.html)
- [OWASP Top 10 (2025)](https://owasp.org/www-project-top-ten/) — A02, A05
- [OWASP API Security Top 10 (2023)](https://owasp.org/API-Security/editions/2023/en/0x11-t10/) — API8, API9
- [ASVS V7.3.1 (L1)](https://raw.githubusercontent.com/OWASP/ASVS/refs/heads/v5.0.0/5.0/docs_en/OWASP_Application_Security_Verification_Standard_5.0.0_en.json), V14.1.1 (L1)
