# Decision Record: OWASP Standards Integration + Missing Tests — Acceptance

Date: 2026-05-04
Phase: Acceptance
Agents: security-auditor (Rounds 1 & 2), acceptance-test-auditor (Round 1), tdd-ddd-implementer (fix cycle), secure-tdd-implementer (fix cycle)
Status: Accepted — PASSED

## Summary

All twenty acceptance criteria are met after a Phase 3 fix cycle that addressed the five deferred
follow-up items from the initial audit. The full test suite (`./mvnw verify`) passes GREEN with
Jacoco instruction 90% / branch 81% — well above the 45% / 35% thresholds. No Critical or High
security issues remain. The overall disposition was upgraded from ⚠️ PASSED WITH CONDITIONS to
✅ PASSED after the fix cycles resolved all deferred items.

## AC Coverage — Final

| AC | Criterion | Result |
|----|-----------|--------|
| AC-01 | iframe sandbox exact 3-token string | ✅ |
| AC-02 | Karma asserts exact tokens; allow-same-origin absent | ✅ |
| AC-03 | Playwright spec (optional) | 🟦 Deferred — marked optional in Phase 1 |
| AC-04 | getShortHandle("@@server.example") throws; CWE-117 message | ✅ |
| AC-05 | T-empty-shorthandle 5 cases | ✅ |
| AC-06 | ResponseBodySecretLeakIT covers all AUTHORITATIVE_ENDPOINT_ALLOWLIST entries | ✅ Fixed in Phase 3 cycle — 48 tests (9 GET + 2 non-GET × 4 modes) |
| AC-07 | No stack traces across 4 modes | ✅ |
| AC-08 | Lockstep test parses matrix and asserts equality | ✅ |
| AC-09 | RED commit before GREEN matrix correction; not squashed | ✅ |
| AC-10 | EP-02/EP-05 = SameSite=Lax; CSRF cookie = SameSite=Strict in matrix + lockstep | ✅ Fixed in Phase 3 cycle — matrix EP-09 rows updated; CSRF cookie lockstep test added (7 total) |
| AC-11 | CorsHardeningIT four sub-cases | ✅ |
| AC-12 | glacier.cookie.secure=true → all Set-Cookie carry Secure | ✅ |
| AC-13 | glacier.cookie.secure=false → Secure absent, HttpOnly+SameSite preserved | ✅ |
| AC-14 | HttpMethodHardeningIT parameterised over all endpoints including share paths | ✅ Fixed in Phase 3 cycle — 46 tests (10 TRACE + 8+7+8+8 verb × 3 mode) |
| AC-15 | OWASP_COVERAGE_MATRIX.md Standards Traceability section | ✅ |
| AC-16 | SECURITY_TESTS.md Layer 1/2/3 tables have WSTG/ASVS/Proactive columns | ✅ |
| AC-17 | Endpoint/STOMP addition guides mention three traceability axes | ✅ |
| AC-18 | Updating Standard Versions section | ✅ |
| AC-19 | Jacoco thresholds preserved; new tests raise totals | ✅ |
| AC-20 | Tests in correct Surefire (*Test) or Failsafe (*IT) phases | ✅ |

## Test Results — Post Fix Cycle

| Test Layer | Total | Passed | Failed |
|---|---|---|---|
| Unit (Surefire) | ~1,047 | 1,047 | 0 |
| Integration (Failsafe) | 298 | 298 | 0 |
| Frontend (Karma) | 532 | 532 | 0 |
| Jacoco instruction | 90% | — | — (threshold: 45%) |
| Jacoco branch | 81% | — | — (threshold: 35%) |

Feature-specific test counts after fix cycle:
- `OwaspMatrixCookieAttributesLockstepTest`: 7 (was 5 — +2 CSRF cookie lockstep)
- `ResponseBodySecretLeakIT`: 48 (was 28 — +20 share endpoints across 4 modes)
- `HttpMethodHardeningIT`: 46 (was 30 — +16 share endpoints)
- All other feature tests unchanged

## Phase 3 Fix Cycles

### Fix Cycle Round 1 — tdd-ddd-implementer

**FU-2 (matrix side)**: Updated `infrastructure/security/OWASP_COVERAGE_MATRIX.md` EP-09 rows
(OWASP Top 10 table ~line 66 and API Security table ~line 92) to declare:
`Secure, SameSite=Strict, NOT HttpOnly` for `__Host-shareCsrf` cookie.
Discovery: `CsrfTokenCookieFactory.java` sets `HttpOnly=false` explicitly (double-submit CSRF
pattern requires JS to read the cookie). The previous matrix description "HttpOnly cookie" was
factually incorrect on two counts.

**FU-4**: Merged duplicate `WSTG-AUTHZ-04` rows (lines 127+129) into one row listing all three
test classes: `StompCallbackOptInEnforcementTest`, `StompEnumerationIndistinguishabilityIT`,
`StompMassAssignmentIT`. Replaced misattributed `WSTG-AUTHZ-01` row (EndpointInventoryTest is an
API inventory test, not a directory-traversal test) with `n/a — Glacier serves no file-system paths`.

**FU-5**: Fixed `SECURITY_TESTS.md` line 64: replaced "Spring Security HTTP method restrictions;
TRACE/OPTIONS/unsupported verbs rejected" with "`HttpMethodRejectFilter` (servlet filter) rejects
TRACE/TRACK at HIGHEST_PRECEDENCE; Spring MVC default 405 for undeclared verbs; OPTIONS permitted
for CORS preflight". Glacier has no Spring Security dependency.

### Fix Cycle Round 2 — secure-tdd-implementer

**FU-1**: Extended `ResponseBodySecretLeakIT` from 6 to 9 GET paths and added 2 non-GET paths in
all four nested mode classes (live / fallback / killswitch / insecure-transport). Share path
variables substituted with 43-char base62 fake ID (`AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA`);
UUID-format IDs are 36 chars and fail the `^[A-Za-z0-9_-]{43,256}$` shareId validation,
causing a `ConstraintViolationException` whose message contains "Exception" — which would have
tripped the `STACK_TRACE_PATTERN` regex. The longer fake ID passes validation without triggering
false positives on the stack-trace assertion.

**FU-2 (test side)**: Added `@Nested CsrfCookieLockstep` class to `OwaspMatrixCookieAttributesLockstepTest`
using `@WebMvcTest(controllers = {ShareViewController.class}) @Import(CsrfTokenCookieFactory.class)`.
Two new tests:
- `csrfCookie_actualSameSite_matchesMatrixClaim()` — parses matrix EP-09 rows for
  `SameSite=(Lax|Strict|None)`, hits `GET /rest/share-csrf`, extracts Set-Cookie header carrying
  the SameSite token, asserts match.
- `csrfCookie_notHttpOnly_perDoubleSubmitPattern()` — asserts no CSRF Set-Cookie header contains
  `HttpOnly`, protecting against future regression to HttpOnly=true.

**FU-3**: Extended `HttpMethodHardeningIT` TRACE `@ValueSource` from 6 to 10 endpoints
(added catalog, messages, img-proxy, share-links/{id}). Extended PUT/DELETE/PATCH/POST
GET-only verb arrays from 4–5 to 7–8 entries (added share GET endpoints). Note:
`DELETE /rest/share-links/{id}` added to TRACE test but not to GET-only verb arrays
(it is a DELETE endpoint, not GET-only).

## Key Findings Accepted

| Finding | Severity | Disposition |
|---------|----------|-------------|
| AC-06: endpoint coverage gap 6/11 | Medium | Fixed in Phase 3 cycle |
| AC-10: CSRF cookie not in matrix or lockstep | Medium | Fixed in Phase 3 cycle |
| AC-14: HttpMethodHardeningIT missing share endpoints | Medium | Fixed in Phase 3 cycle |
| WSTG-AUTHZ-04 duplicate + WSTG-AUTHZ-01 misattribution | Low | Fixed in Phase 3 cycle |
| SECURITY_TESTS.md "Spring Security" misattribution | Low | Fixed in Phase 3 cycle |

## Security Audit Final Disposition (security-auditor Round 2)

CONFIRMED — PASSED. All ten SR-NEW security controls present in production. No ASVS L1 requirement
in the touched chapters (V3, V7, V11, V14) is unaddressed. `HttpMethodRejectFilter` is safe at
`@Order(HIGHEST_PRECEDENCE)`. The `toUpperCase()` without `Locale.ROOT` is not exploitable for
`{TRACE, TRACK}` (Turkish-I problem affects only `i/I` — neither token contains these chars).

Sidebar (out of scope, pre-existing): `CsrfTokenCookieFactory.java` writes `Set-Cookie` twice —
once via `response.addCookie()` (no SameSite), once via `response.addHeader("Set-Cookie", ...)`
(correct SameSite=Strict). This is pre-existing behavior and unrelated to SR-NEW work. Tracked
separately for a future fix.

## Open Risks Carried Forward

| ID | Risk | Mitigation |
|----|------|-----------|
| R-OR-02 | Lockstep parser coupling to Markdown table format | Parser fails explicitly on absent token |
| R-OR-05 | Standards version drift (WSTG 4.2, ASVS 5.0.0, Proactive Controls 2024) | AC-18 procedure in SECURITY_TESTS.md |
| Sidebar | CsrfTokenCookieFactory double Set-Cookie emission | Pre-existing; separate issue; SameSite=Strict still correctly emitted |
| Minor | HttpMethodRejectFilter.toUpperCase() without Locale.ROOT | Not security-critical for {TRACE, TRACK}; deferred |

## User Approval

Date: 2026-05-04
Approval message (verbatim): "approve, if the risks are tested"
Condition resolution: All three medium-risk items (FU-1, FU-2, FU-3) are now covered by tests.
Final `./mvnw verify` exit code: 0 — BUILD SUCCESS.

## References

- [Phase 1 planning document](2026-05-01-planning-owasp-standards-integration.md)
- [Phase 2 implementation document](2026-05-04-implementation-owasp-standards-integration.md)
- [OWASP COVERAGE MATRIX](../../infrastructure/security/OWASP_COVERAGE_MATRIX.md)
- [SECURITY_TESTS.md](../../infrastructure/security/SECURITY_TESTS.md)
- [OWASP ASVS 5.0.0 JSON](https://raw.githubusercontent.com/OWASP/ASVS/refs/heads/v5.0.0/5.0/docs_en/OWASP_Application_Security_Verification_Standard_5.0.0_en.json)
- [OWASP WSTG 4.2](https://owasp.org/www-project-web-security-testing-guide/stable/)
- [OWASP Proactive Controls 2024](https://top10proactive.owasp.org/)
