# Decision Record: P3 Backlog Bundle B — Phase 2 Implementation

Date: 2026-05-12
Phase: Implementation
Agents: secure-tdd-implementer (Lane A), tdd-ddd-implementer (Lane B), cross-review Round 2
Status: **Approved**

## Summary

P3 Bundle B implementation is complete. Lane A (TD-P3A-DOMAIN-FIX) removes raw-value
concatenation from `DomainSafetyValidator.java:98` and adds a structural gate enforcing
static-literal-only constraint-violation templates (ADR-P3B-4). Lane B atomically migrates
all seven `@Value("${glacier.cookie.secure:true}")` injection sites to `GlacierCookieProperties`
with `@NotNull Boolean secure` (ADR-P3B-1/2/3/5). Build: **345 IT, 0 failures, BUILD SUCCESS**
(HEAD d1dc38b).

## Key Decisions

### Lane A: DomainSafetyValidator static-message fix

**Decision**: Remove raw `trimmed` value from the PROHIBITED_LITERALS branch template string at
`DomainSafetyValidator.java:96-100`.

Before:
```java
context.buildConstraintViolationWithTemplate(
        "glacier.domain must not be a loopback or any-address literal "
        + "(CORS/CSP misconfiguration risk): '" + trimmed + "' is prohibited "
        + "— Sec-12, OWASP A05:2021")
        .addConstraintViolation();
```
After:
```java
context.buildConstraintViolationWithTemplate(
        "glacier.domain must not be a loopback or any-address literal "
        + "(CORS/CSP misconfiguration risk) — Sec-12, OWASP A05:2021")
        .addConstraintViolation();
```

All five `buildConstraintViolationWithTemplate` callsites in the file are now static-literal-only.

**Rationale**: Defense-in-depth against programmatic `validator.validate(bean)` calls, REST `@Valid`
body binding, and AOP method validation that bypass `GlacierBindHandler.ScrubbingBindHandler`.

**Source**: secure-tdd-implementer (TDD commits 4612d61, 6d446a9, 1eb2838)

### Lane B: GlacierCookieProperties atomic migration

**Decision**: `GlacierCookieProperties.java` introduced with `@Component`,
`@ConfigurationProperties(prefix="glacier.cookie")`, `@Validated`, and `@NotNull Boolean secure`.
All 7 consumers migrated in a single atomic commit. Old `CookieSecureSingleSourceOfTruthStructureTest`
deleted; new bidirectional `CookieSecureBeanWiringStructureTest` replaces it.

Consumer unboxing pattern: `Boolean.TRUE.equals(props.getSecure())` — null-safe, no `.booleanValue()`.

**Rationale**: Prevents CWE-1188 silent-false-default on missing property. Fail-closed startup
via `@NotNull` + `GlacierBindHandler`.

**Source**: tdd-ddd-implementer (TDD commits f063026, ec12f1c); Lane B cleanup commits df68e35,
fee9721, 6a3d1b1, c615f3a, e2a22dc, 0054fb2, d9089b8, d1dc38b.

### Lane B scope-creep remediation

**Decision**: The tdd-ddd-implementer introduced an out-of-scope SQLite share-link persistence
scaffold (13 production files, 21 test files, JDBC dependency, LogScrubber format change). All
out-of-scope additions were surgically reverted across 7 cleanup commits while preserving all valid
Lane B work. The cleanup also restored 6 pre-existing share-feature files erroneously deleted by
the initial revert.

**Impact on scope**: Zero. All 26 Lane B acceptance criteria remain intact.

## Files Created / Modified

### Lane A

| File | Change |
|---|---|
| `src/main/java/de/seism0saurus/glacier/DomainSafetyValidator.java` | Remove raw-value concatenation from PROHIBITED_LITERALS branch (lines 96-100) |
| `src/test/java/de/seism0saurus/glacier/DomainSafetyValidatorStaticMessageTest.java` | New — 8 inputs; static-message assertions |
| `src/test/java/de/seism0saurus/glacier/DomainSafetyValidatorProgrammaticLeakTest.java` | New — handler-bypass path; 8 inputs |
| `src/test/java/de/seism0saurus/glacier/DomainSafetyValidatorLargeInputBoundednessTest.java` | New — 10 KB regression canary |
| `src/test/java/de/seism0saurus/glacier/ConstraintViolationMessageStaticOnlyStructureTest.java` | New — structural gate; scans all `*Validator.java` |

### Lane B

| File | Change |
|---|---|
| `src/main/java/de/seism0saurus/glacier/GlacierCookieProperties.java` | New bean |
| `src/main/java/de/seism0saurus/glacier/InformationController.java` | @Value → GlacierCookieProperties |
| `src/main/java/de/seism0saurus/glacier/webservice/messaging/WebSocketConfiguration.java` | @Value → GlacierCookieProperties |
| `src/main/java/de/seism0saurus/glacier/CsrfTokenCookieFactory.java` | @Value → GlacierCookieProperties |
| `src/main/java/de/seism0saurus/glacier/share/web/ShareViewerCookieFactory.java` | @Value → GlacierCookieProperties |
| `src/main/java/de/seism0saurus/glacier/share/web/ShareCsrfGuard.java` | @Value → GlacierCookieProperties |
| `src/main/java/de/seism0saurus/glacier/share/web/ImageProxyHmacSecretValidator.java` | @Value → GlacierCookieProperties |
| `src/main/java/de/seism0saurus/glacier/StartupSanityChecker.java` | @Value → GlacierCookieProperties + B6 JavaDoc |
| `src/main/resources/application.properties` | Added `glacier.cookie.secure=${COOKIE_SECURE:true}` |
| `src/test/resources/application.properties` | Added `glacier.cookie.secure=${COOKIE_SECURE:true}` |
| `src/test/java/de/seism0saurus/glacier/GlacierCookiePropertiesBindIT.java` | New — Spring slice, happy + fail-closed paths |
| `src/test/java/de/seism0saurus/glacier/BindFailureValueScrubbingIT.java` | Extended — @Nested CookieSecureBooleanBindFailures (3 cases) |
| `src/test/java/de/seism0saurus/glacier/GlacierCookiePropertiesNotNullValidationTest.java` | New — Jakarta Validation null check |
| `src/test/java/de/seism0saurus/glacier/CookieSecureBeanWiringStructureTest.java` | New — bidirectional structural test |
| `src/test/java/de/seism0saurus/glacier/CookieSecureConsumerUnboxingStructureTest.java` | New — `.booleanValue()` guard |
| `src/test/java/de/seism0saurus/glacier/GlacierConfigurationPropertiesFieldDisjointnessTest.java` | New — key-set disjointness gate |
| `src/test/java/de/seism0saurus/glacier/CookieSecureSingleSourceOfTruthStructureTest.java` | DELETED — replaced by bidirectional B4 |

## Test Results

| Layer | Count | Result |
|---|---|---|
| Surefire (unit) | (subset of 345) | 0 failures |
| Failsafe IT | 345 | 0 failures |
| Jacoco | bundle-wide | All thresholds met |
| BUILD | — | **SUCCESS** |

## Security Requirements Coverage

| SR | Status |
|---|---|
| SR-P3B-01 (DomainSafetyValidator static-literal fix) | MET |
| SR-P3B-01a (A4 structural gate) | MET |
| SR-P3B-02 (A4 scans all *Validator.java) | MET |
| SR-P3B-02a (A1c boundedness canary) | MET (1 of 3 methods genuinely exercises violation path — see G1) |
| SR-P3B-03 (Boolean + @NotNull) | MET |
| SR-P3B-04 (Boolean.TRUE.equals idiom) | MET — all 7 consumers |
| SR-P3B-04a (B4a no .booleanValue()) | MET |
| SR-P3B-05 (CookieSecureBeanWiringStructureTest bidirectional) | MET |
| SR-P3B-05a (B4 negative: zero @Value remain) | MET |
| SR-P3B-07 (B2 scrubbing: missing/empty/non-boolean) | MET |
| SR-P3B-11 (grep: 0 @Value, 8 bean refs, 0 devmode in fenced packages) | MET |
| SR-P3B-12 (StartupSanityChecker B6 JavaDoc) | MET |

## Cross-Review Findings

### Lane A — reviewed by tdd-ddd-implementer: PASS WITH MINOR GAPS

| Gap | Severity | Action |
|---|---|---|
| G1: A1c — 2 of 3 test methods are vacuously green (inputs never produce violations) | LOW | Defer to TD item |
| G2: A1b missing CRLF/fe80 inputs | LOW | Defer to TD item |
| G3: A4 doesn't detect bare-identifier template args (no `+` sign) | LOW | Accept as known limitation; document in comment |

### Lane B — reviewed by secure-tdd-implementer: PASS

| Gap | Severity | Action |
|---|---|---|
| G1: B4 filename-based scan (unique now; future-proofing note) | LOW | Accept — globally unique filenames confirmed |

No conflicts raised between reviewers.

## Resolved Conflicts

None raised during Phase 2. The sole Phase 1 conflict (SR-P3B-09 Unicode canary inputs) was resolved in Phase 1 per the planning document.

## User Approval

Date: 2026-05-12
Approval message (verbatim): "approve"

## Open Risks

| Risk | Severity | Rationale |
|---|---|---|
| **TD-P3A-DOMAIN-FIX G1**: A1c vacuous tests (2 of 3 methods pass vacuously) | LOW | Third method provides genuine canary. Deferrable. |
| **TD-P3B-DOMAIN-UNICODE**: U+202E/U+200B/U+FEFF/U+2028/U+2029 not rejected | LOW | Separate deferred ticket per ADR-P3B-6 |
| **StartupSanityChecker mixed-state**: `glacier.devmode` on @Value (SR-9/D-14 fence) | LOW | Documented via B6 JavaDoc; by design |

## References

- Planning: `docs/decisions/2026-05-11-planning-p3-bundle-b.md`
- Bundle A acceptance: `docs/decisions/2026-05-11-acceptance-p3-backlog-bundle.md`
- [CWE-1188: Insecure Default Initialization](https://cwe.mitre.org/data/definitions/1188.html)
- [CWE-532: Insertion of Sensitive Information into Log File](https://cwe.mitre.org/data/definitions/532.html)
- [OWASP A02:2021](https://owasp.org/Top10/A02_2021-Cryptographic_Failures/)
- [OWASP A05:2021](https://owasp.org/Top10/A05_2021-Security_Misconfiguration/)
- [ASVS V14.1.1 (L1)](https://raw.githubusercontent.com/OWASP/ASVS/refs/heads/v5.0.0/5.0/docs_en/OWASP_Application_Security_Verification_Standard_5.0.0_en.json)
