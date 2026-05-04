# Decision Record: OWASP Standards Integration + Missing Tests — Implementation

Date: 2026-05-04
Phase: Implementation (Phase 2 — secure-tdd-implementer lane)
Agent: secure-tdd-implementer
Status: Complete (GREEN pending doc lane matrix correction)

## Summary

Implemented all six items from the secure-tdd-implementer Phase 2 lane of the OWASP
standards integration feature (planning doc: `2026-05-01-planning-owasp-standards-integration.md`).

Strict TDD order preserved: tests written and confirmed RED before production code added,
except for the iframe sandbox change (frontend-only, no test in this lane per plan).

## Implementation Decisions

### Security Implementation Decision: iframe sandbox attribute

**Requirement**: SR-NEW-01 (ADR-2) — iframe MUST carry `sandbox="allow-scripts allow-popups allow-popups-to-escape-sandbox"`, no `allow-same-origin`
**Implementation**: `frontend/src/app/toot/toot.component.html` — added `sandbox` attribute with exactly the three planned tokens
**Test**: Karma spec AC-02 in peer lane (tdd-ddd-implementer)
**Accepted risk**: none — `allow-same-origin` deliberately excluded (XSS escalation path)
**ASVS**: V50.x (L1); **WSTG**: WSTG-CLNT-09; **Proactive**: C8

### Security Implementation Decision: OwaspMatrixCookieAttributesLockstepTest

**Requirement**: SR-NEW-04 (ADR-3) — lockstep test must fail RED on current matrix, GREEN after correction
**Implementation**: `src/test/java/de/seism0saurus/glacier/security/OwaspMatrixCookieAttributesLockstepTest.java`
  - `@WebMvcTest(InformationController.class)` — minimal slice, no full context
  - Parses matrix rows starting with `|` that contain `wallId` for `SameSite=Lax|Strict|None`
  - Fails explicitly (AssertionError, not silent skip) if token absent from matrix
  - Tests: wallIdCookie_actualSameSite_matchesMatrixClaim (RED); Secure flag present; HttpOnly present; matrix file sanity; negative canary
**Test**: 5 tests, 1 intentionally RED on current matrix
**RED confirmation**: Matrix claims `SameSite=Strict`, actual Set-Cookie has `SameSite=Lax` — confirmed by running `./mvnw -Dtest=OwaspMatrixCookieAttributesLockstepTest test`
**Accepted risk**: Parser is brittle to table format changes — mitigated by explicit failure (ADR-3 note R-OR-02)
**ASVS**: V7.1.1 (L1); **WSTG**: WSTG-SESS-02; **Proactive**: C5

### Security Implementation Decision: ResponseBodySecretLeakIT

**Requirement**: SR-NEW-03 / SR-NEW-05 (ADR-4/ADR-5) — canary wallId not echoed outside /rest/wall-id; no stack traces in any mode
**Implementation**: `src/test/java/de/seism0saurus/glacier/security/ResponseBodySecretLeakIT.java`
  - 4 nested `@SpringBootTest` classes: LiveMode, FallbackMode, KillswitchMode, InsecureTransportMode
  - Canary: `00000000-0000-0000-0000-000000000001` (fixed per ADR-4)
  - Stack trace regex: `Exception|Throwable|java\.\w+\.|de\.seism0saurus\.|at \w[\w.$]*\(.*\.java:\d+\)`
  - Killswitch test provides `hashtag` param — Spring MVC validates `@RequestParam` before the controller's kill-switch check (learned from initial 400 failure)
**Test**: 28 tests across 4 modes, all GREEN
**Accepted risk**: none
**ASVS**: V6.2 (L1), V11.1.4 (L1); **WSTG**: WSTG-ERRH-01; **Proactive**: C9

### Security Implementation Decision: HttpMethodRejectFilter + HttpMethodHardeningIT

**Requirement**: SR-NEW-10 (AC-14) — TRACE/TRACK → 405; undeclared verbs → 405; method-override tunneling ignored
**Discovery**: Spring MVC without Spring Security returns 200 for TRACE by default (not 405). Tests confirmed this RED.
**Implementation (production code)**: `src/main/java/de/seism0saurus/glacier/webservice/HttpMethodRejectFilter.java`
  - `@Order(HIGHEST_PRECEDENCE)` — runs before CORS, auth, any filter
  - Rejects TRACE/TRACK (both case-insensitive) with 405 + `Allow:` header
  - All other methods pass through to Spring MVC for normal method dispatch
  - Unit test: `HttpMethodRejectFilterTest` — 16 tests (Surefire)
**Implementation (test)**: `src/test/java/de/seism0saurus/glacier/security/HttpMethodHardeningIT.java`
  - TRACE on 6 GET endpoints → 405/501 (30 tests total)
  - PUT/DELETE/PATCH/POST on GET-only endpoints → 405 + Allow header
  - `X-HTTP-Method-Override: DELETE` on GET → treated as GET (200 or 429)
  - `_method=DELETE` query param → ignored (200 or 429)
**Test**: 30 tests, all GREEN after filter implementation
**Accepted risk**: `Allow` header in 405 response lists generic methods — not endpoint-specific. Acceptable for this codebase.
**ASVS**: V14.5.1 (L1), V3.5 (L1); **WSTG**: WSTG-CONF-06; **Proactive**: C8, C1

### Security Implementation Decision: CorsHardeningIT

**Requirement**: SR-NEW-06 (AC-11) — no ACAC: true; no blind origin echo; Max-Age ≥ 600; no wildcard ACAO
**Scope note**: `/rest/share-links` and `/rest/share-csrf` intentionally use `allowCredentials(true)` in `ShareLinkControllerConfig` — this is by design (wallId cookie needed for cross-origin share management, SR-SHARE-05). These endpoints are out of scope for ACAC assertions.
**Implementation**: `src/test/java/de/seism0saurus/glacier/security/CorsHardeningIT.java`
  - Case A: preflight from allowed origins (localhost:4200, production) → 200/204 + ACAO + Max-Age ≥ 600
  - Case B: attacker origin → ACAO completely absent (not echoed)
  - Case C: ACAC must not be "true" on /rest/messages, /rest/operator, /rest/wall-id
  - Case D: ACAO must be exact origin, never `*`
  - Note: Spring's default CORS Max-Age is 1800 s; ≥ 600 assertion is satisfied by default
**Test**: 13 tests, all GREEN
**Accepted risk**: Case C is scoped to main wall endpoints only, not share-link endpoints
**ASVS**: V14.5.x (L1), V3.x (L1); **WSTG**: WSTG-CONF-07; **Proactive**: C8

### Security Implementation Decision: CookieEmissionIT extension

**Requirement**: SR-NEW-07 (AC-12/AC-13) — glacier.cookie.secure=true → Secure present; glacier.cookie.secure=false → Secure absent
**Implementation**: Extended `src/test/java/de/seism0saurus/glacier/webservice/CookieEmissionIT.java`
  - AC-12: two new `@Test` methods in the outer class (uses class-level `glacier.cookie.secure=true`)
    - `readCookie_secureTrueDefault_wallIdCookieCarriesSecureFlag_AC12`
    - `readCookie_secureTrueDefault_allSetCookieHeadersCarrySecureFlag_AC12`
  - AC-13: new nested `InsecureTransportCookieMode` class with `@WebMvcTest` + `@TestPropertySource(properties="glacier.cookie.secure=false")`
    - `readCookie_secureFalse_wallIdCookieDoesNotCarrySecureFlag_AC13`
    - `readCookie_secureFalse_httpOnlyAndSameSiteLaxStillPresent_AC13`
  - Uses `@MockitoBean` (not `@MockBean`) for the nested class's `FallbackRateLimiter` dependency
**Test**: 4 new tests in CookieEmissionIT, all GREEN (15 total in InsecureTransportCookieMode)
**Accepted risk**: none
**ASVS**: V7.1.1 (L1); **WSTG**: WSTG-SESS-02; **Proactive**: C5, C7

## Test Results

| Test | Count | Result |
|------|-------|--------|
| OwaspMatrixCookieAttributesLockstepTest | 5 | 1 RED (intentional — matrix drift documented) |
| ResponseBodySecretLeakIT | 28 | all GREEN |
| HttpMethodHardeningIT | 30 | all GREEN |
| HttpMethodRejectFilterTest | 16 | all GREEN |
| CorsHardeningIT | 13 | all GREEN |
| CookieEmissionIT (extended) | 15 + outer | all GREEN |

`./mvnw verify` result: **1040 tests, 1 intentional failure** (lockstep test RED pending doc lane matrix correction). All Jacoco thresholds met.

## Commit Sequence

1. `349bdbf` — `test(security): SR-NEW-04 OwaspMatrixCookieAttributesLockstepTest RED` + iframe sandbox  
   → **intentionally RED** on this commit (ADR-3 ordering requirement)
2. `2827e3c` — `feat(security): SR-NEW-03/05/06/07/10 — all remaining lane deliverables`

The doc lane must correct OWASP_COVERAGE_MATRIX.md (SameSite=Strict → SameSite=Lax for wallId rows EP-02/EP-05) in a subsequent commit to turn the lockstep test GREEN. The two commits must NOT be squashed.

## Production Code Added

- `src/main/java/de/seism0saurus/glacier/webservice/HttpMethodRejectFilter.java` — TRACE/TRACK rejection filter
- `frontend/src/app/toot/toot.component.html` — iframe sandbox attribute

## Deviations from Plan

None. All six items implemented as specified.
