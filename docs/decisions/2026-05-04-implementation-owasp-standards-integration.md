# Decision Record: OWASP Standards Integration + Missing Tests — Implementation

Date: 2026-05-04
Phase: Implementation
Agents: tdd-ddd-implementer (Rounds 1 & 2), secure-tdd-implementer (Rounds 1 & 2), 3 documentation fix cycles
Status: Accepted

## Summary

All ten security requirements (SR-NEW-01 through SR-NEW-10) from the Phase 1 plan were implemented. Two new production files were added (iframe sandbox attribute, `HttpMethodRejectFilter`), eight test files were created or extended, and both security documentation files were updated with verified ASVS 5.0.0 shortcodes, WSTG 4.2 IDs, and Proactive Controls traceability. Three documentation fix cycles resolved ASVS level inaccuracies and WSTG ID mismatches discovered during cross-review.

## New Production Files

| File | Change | SR |
|------|--------|----|
| `frontend/src/app/toot/toot.component.html` | Added `sandbox="allow-scripts allow-popups allow-popups-to-escape-sandbox"` to iframe — no `allow-same-origin` | SR-NEW-01 |
| `src/main/java/de/seism0saurus/glacier/webservice/HttpMethodRejectFilter.java` | New `@Order(HIGHEST_PRECEDENCE)` servlet filter rejecting TRACE/TRACK with 405 + `Allow:` header | SR-NEW-10 |

## New and Extended Test Files

| File | Type | Tests | SR |
|------|------|-------|----|
| `OwaspMatrixCookieAttributesLockstepTest.java` | Surefire unit | 5 | SR-NEW-04 |
| `ResponseBodySecretLeakIT.java` | Failsafe IT | 28 | SR-NEW-03, SR-NEW-05 |
| `CookieEmissionIT.java` (extended) | Failsafe IT | +15 | SR-NEW-07 |
| `HttpMethodHardeningIT.java` | Failsafe IT | 30 | SR-NEW-10 |
| `HttpMethodRejectFilterTest.java` | Surefire unit | 16 | SR-NEW-10 |
| `CorsHardeningIT.java` | Failsafe IT | 13 | SR-NEW-06 |
| `StompCallbackOptInEnforcementTest.java` (extended) | Surefire unit | +5 | SR-NEW-02 |
| `frontend/src/app/toot/toot.component.spec.ts` (extended) | Karma unit | +2 | SR-NEW-01 |

## Documentation Changes

| File | Changes |
|------|---------|
| `infrastructure/security/OWASP_COVERAGE_MATRIX.md` | 3 SameSite cells corrected (Strict→Lax for wallId rows EP-02/EP-05); `## Standards Traceability` section added (WSTG/ASVS 5.0.0/Proactive tables, version pin `ASVS 5.0.0 | WSTG 4.2 | Proactive Controls 2024`); WSTG labels corrected (SESS-04 description, INPV-01→AUTHZ-04, CONF-04→ATHN-03); ASVS levels corrected (V16.5.1 L1→L2, V4.1.4 L3 restored) |
| `infrastructure/security/SECURITY_TESTS.md` | 3 new traceability columns (WSTG ID, ASVS shortcode, Proactive Control) in all Layer tables; new test classes added; `## Updating Standard Versions` section added; endpoint/STOMP addition guides updated with three traceability axes |

## Key Implementation Decisions

### HttpMethodRejectFilter required as new production class

**Requirement**: SR-NEW-10 (AC-14) — TRACE/TRACK must return 405 or 501.
**Discovery**: Spring MVC without Spring Security returns HTTP 200 for TRACE requests. `HttpMethodHardeningIT` confirmed RED immediately.
**Implementation**: `HttpMethodRejectFilter.java` with `@Order(HIGHEST_PRECEDENCE)` — intercepts before CORS, authentication, or any handler. Rejects TRACE/TRACK (case-insensitive) with 405 + `Allow:` header.
**STOMP impact**: None — WebSocket upgrade always uses `GET` per RFC 6455 §4.1; `{TRACE, TRACK}` rejection set is disjoint from all upgrade path methods.
**Test**: `HttpMethodRejectFilterTest` (16 unit) + `HttpMethodHardeningIT` (30 IT). All GREEN.
**ASVS**: V3.5.3 (L1), V4.1.4 (L3) | **WSTG**: WSTG-CONF-06 | **Proactive**: C8, C1

### StompCallback guard pre-existing — no production change needed

**Discovery**: `StompCallback.getShortHandle()` already contained the empty-shortHandle guard (`IllegalArgumentException("The mastodon handle has an empty local part")`) from a previous session. The exception message does not echo user input (CWE-117 compliant).
**Decision**: No production code change. New tests (AC-05) added to confirm all five attack variants (`"@@server.example"`, `"@@"`, `"@"`, `""`, CWE-117 message check) throw correctly.
**ASVS**: V2.1 (L1) | **WSTG**: WSTG-INPV-01 | **Proactive**: C3

### ADR-3 commit ordering confirmed

`OwaspMatrixCookieAttributesLockstepTest` committed RED first (failing on `SameSite=Strict` claim), then matrix correction committed GREEN. The two commits were NOT squashed, preserving the audit trail that the matrix was wrong.

### CorsHardeningIT scope exception for share endpoints

`/rest/share-links` and `/rest/share-csrf` intentionally use `allowCredentials(true)` in `ShareLinkControllerConfig` for cross-origin wallId cookie transmission. The "no ACAC" assertion (Case C) is scoped to main wall endpoints only. Documented in `CorsHardeningIT.java` class-level Javadoc.

### ASVS 5.0.0 shortcode verification

The plan cited non-existent `V50.x` for iframe sandbox. Fetching the ASVS 5.0.0 JSON revealed:
- `V3.5.3` (HTTP method restrictions) and `V3.4.6` (CSP/frame-ancestors/clickjacking) ARE correct ASVS 5.0.0 shortcodes — different content from 4.0.3 despite same number.
- Actual corrections applied: `V3.2.1 → V16.5.1` (error response confidentiality), `V16.5.1 (L1) → (L2)`, `V4.1.4 (L1) → (L3)` (level corrections per JSON).
- WSTG corrections: `WSTG-CONF-04 → WSTG-ATHN-03` for rate-limiting tests; `WSTG-INPV-01 → WSTG-AUTHZ-04` for opt-in enforcement.

## Test Results

- **Backend (Surefire + Failsafe)**: All tests pass; Jacoco thresholds met (instruction ≥ 45%, branch ≥ 35%)
- **Frontend (Karma)**: 532 tests GREEN
- **Lockstep test** (`OwaspMatrixCookieAttributesLockstepTest`): GREEN after matrix correction; confirmed GREEN through all three documentation fix cycles

## Resolved Conflicts

### ASVS Version Label vs. Shortcodes

**secure-tdd-implementer**: ASVS version declared `5.0` but some shortcodes appeared to be from 4.0.3 chapter layout. Raised as a blocking conflict.
**tdd-ddd-implementer**: Used shortcodes as verified against the ASVS 5.0.0 JSON.
**User resolution (2026-05-04)**: Option B — full ASVS 5.0.0 migration. JSON verified; shortcodes corrected across three fix cycles (see "ASVS 5.0.0 shortcode verification" above).

## Deviations from Phase 1 Plan

| Deviation | Disposition |
|-----------|-------------|
| StompCallback guard pre-existing | Accepted — AC-04/AC-05 satisfied by tests; no production change needed |
| ASVS V50.x (non-existent in plan) → verified via JSON | R-OR-01 materialized and resolved; correct ASVS 5.0.0 shortcodes now in place |
| `HttpMethodRejectFilter` new production file (not in plan) | Required by Spring MVC behavior; adds a production security control demanded by SR-NEW-10 |

## Open Risks Remaining

| ID | Risk | Status |
|----|------|--------|
| R-OR-02 | Lockstep parser coupling to Markdown table format | Mitigated — parser fails explicitly on ambiguity |
| R-OR-05 | Standards version drift (WSTG, ASVS, Proactive) | Mitigated — `## Updating Standard Versions` procedure in SECURITY_TESTS.md |
| Minor | `Locale.ROOT` missing from `HttpMethodRejectFilter.toUpperCase()` | Deferred — non-security-critical for current method set {TRACE, TRACK} |

## User Approval

Date: 2026-05-04
Approval message (verbatim): "approve"

## References

- [Phase 1 planning document](2026-05-01-planning-owasp-standards-integration.md)
- [OWASP ASVS 5.0.0 JSON](https://raw.githubusercontent.com/OWASP/ASVS/refs/heads/v5.0.0/5.0/docs_en/OWASP_Application_Security_Verification_Standard_5.0.0_en.json)
- [OWASP WSTG 4.2](https://owasp.org/www-project-web-security-testing-guide/stable/)
- [OWASP Proactive Controls 2024](https://top10proactive.owasp.org/)
- [infrastructure/security/OWASP_COVERAGE_MATRIX.md](../../infrastructure/security/OWASP_COVERAGE_MATRIX.md)
- [infrastructure/security/SECURITY_TESTS.md](../../infrastructure/security/SECURITY_TESTS.md)
