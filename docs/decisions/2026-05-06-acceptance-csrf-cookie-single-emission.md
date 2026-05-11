# Decision Record: CSRF Cookie Single Emission Fix — Acceptance

Date: 2026-05-06
Phase: Acceptance (Phase 3)
Agents: security-auditor (R1 + R2), acceptance-test-auditor (R1)
Status: Accepted — PASSED

## Summary

All 11 acceptance criteria (AC-CSRF-01..10 + AC-BUILD) are satisfied. Zero fix cycles required.
Both auditors returned PASS independently with no conflicts. The `ResponseCookie` single-emission
fix, `ShareViewerCookieFactory` migration, ArchUnit structural gate, lockstep redesign, and MockMvc
IT are all correctly implemented and tested. BUILD SUCCESS: 1,278 unit tests + 303 IT.

## Acceptance Result

**Disposition: PASSED**
**Criteria checked**: 11
**Criteria passed**: 11
**Criteria failed**: 0
**Fix cycles**: 0

## AC Coverage (acceptance-test-auditor)

| AC | Status | Key Evidence |
|----|--------|-------------|
| AC-CSRF-01 | PASS | Single `addHeader(HttpHeaders.SET_COOKIE, ...)` at `CsrfTokenCookieFactory.java:80`; `SingleSetCookieEmission.issueCsrfToken_emitsExactlyOneSetCookieHeader_inSecureMode` (`CsrfTokenCookieFactoryTest.java:238`) + IT counterpart (`CsrfTokenIssuanceIT.java:107`); both modes confirmed |
| AC-CSRF-02 | PASS | `.sameSite("Strict")` at `CsrfTokenCookieFactory.java:74`; asserted in `CsrfTokenCookieFactoryTest.java:65, 321, 371` and `CsrfTokenIssuanceIT.java:164, 321` |
| AC-CSRF-03 | PASS | `.httpOnly(false)` at `CsrfTokenCookieFactory.java:76`; `doesNotContain("httponly")` in `CsrfTokenCookieFactoryTest.java:73-89, 327, 376` and `CsrfTokenIssuanceIT.java:170, 326` |
| AC-CSRF-04 | PASS | Name=`__Host-shareCsrf`, `Path=/`, `Secure` at `CsrfTokenCookieFactory.java:57,63,75`; triple-asserted in `CsrfTokenCookieFactoryTest.java:111, 332-335` and `CsrfTokenIssuanceIT.java:175,178` |
| AC-CSRF-05 | PASS | Name=`shareCsrf`, `Path=/share`, no `Secure` at `CsrfTokenCookieFactory.java:57,63`; asserted in `CsrfTokenCookieFactoryTest.java:129, 381, 384` and `CsrfTokenIssuanceIT.java:331, 334` |
| AC-CSRF-06 | PASS | `.maxAge(Duration.ofSeconds(3600))` at `CsrfTokenCookieFactory.java:73`; `contains("Max-Age=3600")` in `CsrfTokenCookieFactoryTest.java:184, 340, 389` and `CsrfTokenIssuanceIT.java:184, 339` |
| AC-CSRF-07 | PASS | `ShareViewerCookieFactory.java:84-93` — `ResponseCookie`, `.sameSite("Lax")`, `.httpOnly(true)`, single `addHeader`; ArchUnit package-wide gate enforces single-emission structurally |
| AC-CSRF-08 | PASS | `CsrfCookieEmissionStructureTest.java` 5/5 GREEN: vacuous-pass canary (`Class.forName` + non-empty package), ArchUnit `addCookie` ban, raw-string ban, `ResponseCookie` import requirement, `anyMatch` regex ban |
| AC-CSRF-09 | PASS | `OwaspMatrixCookieAttributesLockstepTest.java$CsrfCookieLockstep:394-400, 438-459` — filter-by-name + `hasSize(1)`; `extractCsrfSetCookieWithSameSite()` confirmed deleted (grep returns zero matches); 7/7 GREEN |
| AC-CSRF-10 | PASS | `CsrfTokenIssuanceIT.java` — `@WebMvcTest(ShareViewController.class)` + `@Import(CsrfTokenCookieFactory.class)`; 5/5 GREEN; real Spring MVC pipeline exercises `ResponseCookie.toString()` serialisation including `Expires=` co-emission |
| AC-BUILD | PASS | 1,278 unit tests + 303 integration tests — BUILD SUCCESS; Jacoco thresholds met |

## SR Coverage (security-auditor)

| SR | Status | Evidence |
|----|--------|---------|
| SR-CSRF-01 | PASS | `.sameSite("Strict")` at `CsrfTokenCookieFactory.java:74`; 6 assertion sites |
| SR-CSRF-02 | PASS | Single `addHeader(HttpHeaders.SET_COOKIE, ...)` call; UT canary + IT both assert `hasSize(1)` |
| SR-CSRF-03 | PASS | `.httpOnly(false)` intentional; `doesNotContain("httponly")` tested in unit + IT + lockstep |
| SR-CSRF-04 | PASS | `__Host-` prefix + `Path=/` + `Secure` in secure mode; RFC 6265bis §4.1.3 satisfied |
| SR-CSRF-05 | PASS | `.secure(secureCookies)` conditional; both modes explicitly tested |
| SR-CSRF-06 | PASS | `Duration.ofSeconds(3600)`; `contains("Max-Age=3600")` hardened from "any Max-Age" in fix commit |
| SR-CSRF-07 | PASS | `TOKEN_BYTES = 32`, `SecureRandom.nextBytes()` — 256-bit entropy unchanged |
| SR-CSRF-08 | PASS | URL-safe Base64 chars are RFC 6265 cookie-octet legal; no re-encoding by `ResponseCookie.toString()` |
| SR-CSRF-09 | PASS | ArchUnit 5-gate suite + vacuous-pass canary in `CsrfCookieEmissionStructureTest` |
| SR-CSRF-10 | PASS | Zero new log lines — confirmed by grep on diff |
| SR-CSRF-11 | PASS | IT gate in `CsrfCookieEmissionStructureTest`; 4 outer unit tests hardened to filter-by-name in `9066fe8` |
| SR-CSRF-12 | PASS | `ShareViewerCookieFactory` migrated; package-wide ArchUnit gate covers all `share.web` classes |
| SR-CSRF-13 | PASS | Helper deleted; lockstep redesigned with filter-by-name + `hasSize(1)` |
| SR-CSRF-14 | PASS | `CsrfTokenIssuanceIT.java` 5/5 GREEN |

## Security Findings

| ID | Severity | Location | Description | Disposition |
|----|----------|----------|-------------|-------------|
| F-INFO-1 | Informational | `CsrfCookieEmissionStructureTest.java:82` | Vacuous-pass canary pins only `CsrfTokenCookieFactory` by name; `ShareViewerCookieFactory` covered only by package-non-empty check | Accepted — follow-up R1 tracked |
| F-INFO-2 | Informational | `CsrfCookieEmissionStructureTest.java:276` | `anyMatch` regex ban scoped to `*IT.java` only (by design; unit-level count invariant enforced by `SingleSetCookieEmission` nested class) | Accepted by design — documented |

## Fix Cycles

None. Both auditors returned PASS on first round; no `## FIX REQUEST →` markers raised.

## Test Results

| Suite | Tests | Failures | Errors | Skipped |
|-------|-------|----------|--------|---------|
| Surefire (unit) | 1,278 | 0 | 0 | 0 |
| Failsafe (IT) | 303 | 0 | 0 | 0 |
| Jacoco | — | — | threshold pass | — |

## User Approval

Date: 2026-05-06
Approval message (verbatim): "approve"

## Open Risks

| ID | Risk | Rationale |
|----|------|-----------|
| OR-CSRF-01 | `Expires=` co-emission by `ResponseCookie` | HTTP/1.0 compatibility; tests use substring containment — safe |
| OR-CSRF-03 | SR-CSRF-10 no-Logger structural gate deferred | Zero log lines confirmed by code review; structural ArchUnit enforcement optional |

## Follow-Up Items (non-blocking, tracked)

| ID | Item | Effort |
|----|------|--------|
| FU-R1 | Add `Class.forName("...ShareViewerCookieFactory")` to vacuous-pass canary | 2 lines |
| FU-R2 | Add direct `hasSize(1)` filter-by-name assertion in `ShareViewerCookieFactoryTest` | ~10 lines |
| FU-R3 | Add insecure-mode nested class to `OwaspMatrixCookieAttributesLockstepTest$CsrfCookieLockstep` | ~40 lines |

## References

- [Planning doc](2026-05-06-planning-csrf-cookie-single-emission.md)
- [Implementation doc](2026-05-06-implementation-csrf-cookie-single-emission.md)
- `src/main/java/de/seism0saurus/glacier/share/web/CsrfTokenCookieFactory.java`
- `src/main/java/de/seism0saurus/glacier/share/web/ShareViewerCookieFactory.java`
- `src/test/java/de/seism0saurus/glacier/share/web/CsrfTokenCookieFactoryTest.java`
- `src/test/java/de/seism0saurus/glacier/share/web/CsrfCookieEmissionStructureTest.java`
- `src/test/java/de/seism0saurus/glacier/share/web/CsrfTokenIssuanceIT.java`
- `src/test/java/de/seism0saurus/glacier/security/OwaspMatrixCookieAttributesLockstepTest.java`
- [RFC 6265](https://www.rfc-editor.org/rfc/rfc6265) §4, [RFC 6265bis](https://www.rfc-editor.org/rfc/rfc6265bis) §4.1.3
- [OWASP CSRF Prevention Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/Cross-Site_Request_Forgery_Prevention_Cheat_Sheet.html)
- [OWASP A01:2021 — Broken Access Control](https://owasp.org/Top10/A01_2021-Broken_Access_Control/), [A02:2021 — Cryptographic Failures](https://owasp.org/Top10/A02_2021-Cryptographic_Failures/)
- [OWASP ASVS 5.0](https://raw.githubusercontent.com/OWASP/ASVS/refs/heads/v5.0.0/5.0/docs_en/OWASP_Application_Security_Verification_Standard_5.0.0_en.json) V7.1.1 (L1), V7.4.1 (L1)
- [WSTG-SESS — Session Management Testing](https://owasp.org/www-project-web-security-testing-guide/stable/4-Web_Application_Security_Testing/06-Session_Management_Testing/) (WSTG-SESS-02)
- [CWE-1004: Sensitive Cookie Without HttpOnly Flag](https://cwe.mitre.org/data/definitions/1004.html), [CWE-1188: Initialization of a Resource with an Insecure Default](https://cwe.mitre.org/data/definitions/1188.html)
- [`spring-security-hardening`](../../.claude/skills/spring-security-hardening.md) skill
- [`spring-boot-testing-patterns`](../../.claude/skills/spring-boot-testing-patterns.md) skill
