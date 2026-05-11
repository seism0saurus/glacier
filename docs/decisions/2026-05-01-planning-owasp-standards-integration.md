# Decision Record: OWASP Standards Integration + Missing Tests — Planning

Date: 2026-05-01
Phase: Planning
Agents: ddd-tdd-architect (Rounds 1 & 2), secure-feature-planner (Rounds 1 & 2)
Status: Accepted

## Summary

Three standards — OWASP WSTG 4.2, OWASP ASVS 5.0, and OWASP Proactive Controls 2024 — are wired into Glacier's agent files and now drive a gap analysis of `OWASP_COVERAGE_MATRIX.md`, `SECURITY_TESTS.md`, and the codebase. Nine concrete gaps were identified (documentation drift, missing security controls, missing tests). Ten security requirements (SR-NEW-01 through SR-NEW-10) and twenty acceptance criteria (AC-01 through AC-20) were produced and locked across two planning rounds.

## Key Decisions

### ADR-1: Flat traceability tables in OWASP_COVERAGE_MATRIX.md

**Decision**: Add a new `## Standards Traceability` section with three flat sub-tables (WSTG / ASVS 5.0 / Proactive Controls 2024), each with a version-pin row, rather than adding new columns to the existing endpoint matrices.
**Rationale**: The endpoint matrices are already at readability limits (~12 endpoints × ~12 controls). Adding 3 more columns would break 80-char rendering. Flat tables by standard are more readable and easier to maintain independently.
**Alternatives considered**: Columns in existing tables (rejected — readability), separate file (rejected — discoverability).
**Source**: ddd-tdd-architect Round 1 (ADR-1), confirmed by secure-feature-planner Round 1.

### ADR-2: iframe sandbox token set

**Decision**: `toot.component.html` iframe gets `sandbox="allow-scripts allow-popups allow-popups-to-escape-sandbox"`. `allow-same-origin` is explicitly forbidden. `allow-top-navigation` and `allow-forms` are also excluded.
**Rationale**: `allow-scripts` only would silently block `<a target="_blank">` links inside Mastodon embeds. Operators encountering broken click-through links remove the sandbox attribute entirely, defeating the security control. `allow-popups-to-escape-sandbox` prevents the popup from inheriting sandbox restrictions. `allow-same-origin` is excluded because it would give the embed access to its own origin's storage (the threat sandbox is meant to prevent). The `postMessage` height-resize protocol is cross-origin and does not require `allow-same-origin`.
**Alternatives considered**: `allow-scripts` only (rejected — breaks user-visible behavior causing operators to bypass control), `allow-same-origin` (explicitly rejected — XSS escalation path).
**Source**: Conflict resolved in Round 2 — architect accepted secure-feature-planner's position.

### ADR-3: Lockstep test before matrix correction

**Decision**: `OwaspMatrixCookieAttributesLockstepTest` is written FIRST so it fails RED on the current matrix (which incorrectly states `SameSite=Strict` for the `wallId` cookie), then turns GREEN after the matrix is corrected. The two commits must not be squashed.
**Rationale**: The RED commit proves the matrix was lying (documented drift). A single GREEN commit would obscure the fact that a regression prevention test was added.
**Consequences**: Implementers must commit in the correct order: lockstep test first, matrix correction second.
**Source**: secure-feature-planner Round 2 (SR-NEW-04, AC-08/AC-09).

### ADR-4: Canary wallId strategy

**Decision**: `ResponseBodySecretLeakIT` uses a fixed canary wallId `00000000-0000-0000-0000-000000000001` injected via the `wallId` cookie header. `/rest/wall-id` is explicitly allowlisted (it intentionally echoes the UUID for SPA bootstrap). All other endpoints from `EndpointInventoryTest.AUTHORITATIVE_ENDPOINT_ALLOWLIST` must not echo the canary.
**Rationale**: Asserting "response body does not contain the raw wallId" is meaningless if the wallId is generated fresh per test request. A fixed canary makes the assertion deterministic and auditable.
**Source**: secure-feature-planner Round 1 (ADAPT-02), confirmed in Round 2.

### ADR-5: All four operational modes required for error-handling tests

**Decision**: `ResponseBodySecretLeakIT` and all stack-trace/secret-leak assertions run across all four Glacier operational modes: live, fallback, killswitch, and insecure-transport.
**Rationale**: Error-handling divergence across modes is an established Glacier attack surface per `glacier-fallback-mode-discipline`. A test that only passes in live mode is not accepted.
**Source**: secure-feature-planner Round 1 (ADAPT-03), confirmed in Round 2.

## Gaps Identified

| ID | Gap | File | Status |
|----|-----|------|--------|
| Gap-1 | OWASP_COVERAGE_MATRIX.md has no WSTG / ASVS 5.0 / Proactive Controls references | `infrastructure/security/OWASP_COVERAGE_MATRIX.md` | Addressed by SR-NEW-08 |
| Gap-2 | SECURITY_TESTS.md test classes have no WSTG / ASVS / Proactive references | `infrastructure/security/SECURITY_TESTS.md` | Addressed by SR-NEW-09 |
| Gap-3 | `toot.component.html` renders `<iframe>` with no `sandbox` attribute | `frontend/src/app/toot/toot.component.html` | Addressed by SR-NEW-01 |
| Gap-4 | `StompCallback.getShortHandle("@@server")` returns empty string — `contains("")` passes ALL toots | `src/main/java/de/seism0saurus/glacier/mastodon/StompCallback.java` L182-195 | Addressed by SR-NEW-02 |
| Gap-5 | No test verifies that response bodies do not echo the wallId UUID | New `ResponseBodySecretLeakIT` | Addressed by SR-NEW-03 |
| Gap-6 | Three OWASP_COVERAGE_MATRIX.md cells claim `SameSite=Strict` for `wallId`; actual code uses `SameSite=Lax` | `infrastructure/security/OWASP_COVERAGE_MATRIX.md` L59,62 | Addressed by SR-NEW-04 |
| Gap-7 | Canary allowlist for `/rest/wall-id` must be explicit and tested | `ResponseBodySecretLeakIT` | Addressed by SR-NEW-03 |
| Gap-8 | `CorsConfigurationIT` does not assert: no credentials reflection, no blind origin echo, preflight Max-Age | `src/test/java/.../CorsConfigurationIT.java` (extend) | Addressed by SR-NEW-06 |
| Gap-9 | No IT verifies that `glacier.cookie.secure=true` (default) produces `Secure` flag in `Set-Cookie` | `CookieEmissionIT` extension | Addressed by SR-NEW-07 |

## Security Requirements

| ID | Requirement | Proactive Control | ASVS 5.0 | WSTG 4.2 |
|----|-------------|-------------------|----------|----------|
| SR-NEW-01 | iframe MUST carry `sandbox="allow-scripts allow-popups allow-popups-to-escape-sandbox"` — no `allow-same-origin` | C8 | V50.x (L1) | WSTG-CLNT-09 |
| SR-NEW-02 | `StompCallback.getShortHandle()` MUST throw `IllegalArgumentException` for empty short-handle (covers `@@server`, `@`, `""`, `@@`) | C3 | V2.1 (L1) | WSTG-INPV-01 |
| SR-NEW-03 | No HTTP response body (except `/rest/wall-id`) MAY echo canary wallId `00000000-0000-0000-0000-000000000001` | C9 | V6.2 (L1), V11.x (L1) | WSTG-ERRH-01 |
| SR-NEW-04 | OWASP_COVERAGE_MATRIX.md SameSite values MUST match actual `Set-Cookie` headers; lockstep test prevents drift | C5 | V7.1.1 (L1) | WSTG-SESS-02 |
| SR-NEW-05 | Response bodies in all four modes MUST NOT contain stack traces or internal class names | C3, C9 | V11.1.4 (L1), V14.5 (L1) | WSTG-ERRH-01 |
| SR-NEW-06 | CORS: no `ACAC: true`; no blind origin reflection; preflight `Max-Age` ≥ 600; disallowed origins produce no `ACAO` | C8 | V14.5.x (L1), V3.x (L1) | WSTG-CONF-07 |
| SR-NEW-07 | With `glacier.cookie.secure=true` (default) every `Set-Cookie` MUST carry the `Secure` flag | C5, C7 | V7.1.1 (L1) | WSTG-SESS-02 |
| SR-NEW-08 | OWASP_COVERAGE_MATRIX.md gains `## Standards Traceability` section with WSTG / ASVS 5.0 / Proactive tables | C10 | governance | governance |
| SR-NEW-09 | SECURITY_TESTS.md Layer 1/2/3 tables gain WSTG ID, ASVS shortcode, Proactive Control ID columns | C10 | governance | governance |
| SR-NEW-10 | All undeclared HTTP methods return 405; TRACE/TRACK return 405/501; method-override headers ignored | C8, C1 | V14.5.1 (L1), V3.5 (L1) | WSTG-CONF-06 |

## Acceptance Criteria

**Iframe Sandbox**
- AC-01: `toot.component.html` iframe carries `sandbox="allow-scripts allow-popups allow-popups-to-escape-sandbox"` (exact three-token string)
- AC-02: Karma spec asserts exact sandbox token set; asserts `allow-same-origin`, `allow-top-navigation`, `allow-forms` absent
- AC-03: Playwright spec verifies real Mastodon embed still loads and height-resize postMessage still works

**StompCallback ShortHandle**
- AC-04: `getShortHandle("@@server.example")` throws `IllegalArgumentException`; message does NOT contain raw handle (CWE-117)
- AC-05: `StompCallbackOptInEnforcementTest` extended with T-empty-shorthandle cases for `"@@server"`, `"@"`, `""`, `"@@"`

**Response-Body Secret Leak**
- AC-06: `ResponseBodySecretLeakIT` iterates `EndpointInventoryTest.AUTHORITATIVE_ENDPOINT_ALLOWLIST` with canary cookie; asserts no echo except `/rest/wall-id`
- AC-07: Same IT asserts no stack-trace pattern across all 4 modes

**Matrix Lockstep**
- AC-08: `OwaspMatrixCookieAttributesLockstepTest` parses matrix SameSite tokens; asserts equality against runtime `Set-Cookie`
- AC-09: Test fails RED on main (matrix says Strict, code says Lax); matrix correction turns it GREEN
- AC-10: `EP-02` and `EP-05` cells corrected to `SameSite=Lax`; CSRF cookie cell reads `SameSite=Strict`

**CORS Hardening**
- AC-11: `CorsHardeningIT` covers: allowed-origin preflight → 204 + Max-Age; attacker origin → no ACAO; no ACAC header; only declared methods in Allow

**Cookie Emission**
- AC-12: `CookieEmissionIT` with `glacier.cookie.secure=true` → all Set-Cookie carry Secure flag
- AC-13: With `glacier.cookie.secure=false` → Secure flag absent (insecure-transport mode honesty)

**HTTP Method Hardening**
- AC-14: `HttpMethodHardeningIT` parameterised over EP-01..EP-12: TRACE→405/501; undeclared verb→405 + Allow header; method-override headers ignored

**Standards Traceability**
- AC-15: OWASP_COVERAGE_MATRIX.md `## Standards Traceability` section with version-pin row and three populated sub-tables
- AC-16: SECURITY_TESTS.md Layer 1/2/3 tables have WSTG / ASVS / Proactive columns with at least one ID per row

**Documentation Maintenance**
- AC-17: Endpoint/STOMP addition sections in SECURITY_TESTS.md mention three traceability axes
- AC-18: New `## Updating Standard Versions` section documents re-pinning procedure

**Coverage**
- AC-19: Jacoco thresholds (instruction ≥ 45%, branch ≥ 35%) preserved; new tests raise totals
- AC-20: New tests run in correct Surefire (`*Test`) or Failsafe (`*IT`) phases

## Phase 2 Lane Partition

### tdd-ddd-implementer lane

- `OWASP_COVERAGE_MATRIX.md`: `## Standards Traceability` section addition (SR-NEW-08, AC-15)
- `OWASP_COVERAGE_MATRIX.md`: SameSite correction for wallId rows (AC-09/AC-10) — only AFTER secure lane writes RED lockstep test
- `SECURITY_TESTS.md`: column expansion with WSTG/ASVS/Proactive IDs (SR-NEW-09, AC-16)
- `SECURITY_TESTS.md`: `## Updating Standard Versions` section + maintenance updates (AC-17/AC-18)
- `StompCallback.java` (lines 182-195): empty-shortHandle rejection (SR-NEW-02, AC-04)
- `StompCallbackOptInEnforcementTest.java`: empty-shortHandle test cases (AC-05)
- `toot.component.spec.ts`: Karma sandbox token-set assertion (AC-02)

### secure-tdd-implementer lane

- `toot.component.html`: add `sandbox="allow-scripts allow-popups allow-popups-to-escape-sandbox"` (SR-NEW-01, AC-01)
- `ResponseBodySecretLeakIT.java` (new): canary wallId + stack-trace regex across all 4 modes (SR-NEW-03/SR-NEW-05, AC-06/AC-07)
- `OwaspMatrixCookieAttributesLockstepTest.java` (new): parses matrix, asserts cookie attributes RED first (SR-NEW-04, AC-08/AC-09) — MUST be committed before doc-lane matrix correction
- `CookieEmissionIT.java` (extend): both `glacier.cookie.secure=true` and `=false` sub-cases (SR-NEW-07, AC-12/AC-13)
- `HttpMethodHardeningIT.java` (new): TRACE/TRACK + method-override hardening (SR-NEW-10, AC-14)
- `CorsHardeningIT.java` (new): four CORS sub-cases (SR-NEW-06, AC-11)
- Optional `security-iframe-sandbox.spec.ts` (Playwright): user-visible verification of sandbox behavior (AC-03/AC-20)

### Cross-lane handoff ordering

1. secure lane writes `OwaspMatrixCookieAttributesLockstepTest` FIRST (RED on main)
2. doc lane corrects matrix SECOND (turns GREEN)
3. Two commits must NOT be squashed

## Resolved Conflicts

### iframe sandbox token set

**ddd-tdd-architect**: `sandbox="allow-scripts"` only.
**secure-feature-planner**: `sandbox="allow-scripts allow-popups allow-popups-to-escape-sandbox"` — `allow-scripts` only silently breaks click-through links in Mastodon embeds, causing operators to remove the attribute entirely, defeating the security control.
**Resolution (2026-05-02)**: secure-feature-planner's position accepted. Architect accepted in Round 2.

## Open Risks

| ID | Risk | Mitigation |
|----|------|-----------|
| R-OR-01 | ASVS 5.0 V50 chapter shortcodes from late-draft; published final may differ | Implementers must verify against live JSON before citing |
| R-OR-02 | `OwaspMatrixCookieAttributesLockstepTest` parses Markdown; brittle to table format changes | Parse only well-anchored tokens; fail explicitly on ambiguity |
| R-OR-03 | Insecure-transport mode test fixture may not exist for `CookieEmissionIT` | Verify [`glacier-fallback-mode-discipline`](../../.claude/skills/glacier-fallback-mode-discipline.md) skill; create fixture if needed |
| R-OR-04 | Canary `00000000-0000-0000-0000-000000000001` could collide with deterministic UUID generation | Document canary in SECURITY_TESTS.md; probability vanishingly small |
| R-OR-05 | Standards version drift (WSTG, ASVS, Proactive) requires quarterly review | AC-18 documents procedure; not automated |

## User Approval

Date: 2026-05-02
Approval message (verbatim): "approve"

## References

- [OWASP Web Security Testing Guide (WSTG) 4.2](https://owasp.org/www-project-web-security-testing-guide/stable/)
- [OWASP ASVS 5.0 JSON](https://raw.githubusercontent.com/OWASP/ASVS/refs/heads/v5.0.0/5.0/docs_en/OWASP_Application_Security_Verification_Standard_5.0.0_en.json)
- [OWASP Top 10 Proactive Controls 2024](https://top10proactive.owasp.org/)
- [infrastructure/security/OWASP_COVERAGE_MATRIX.md](../../infrastructure/security/OWASP_COVERAGE_MATRIX.md)
- [infrastructure/security/SECURITY_TESTS.md](../../infrastructure/security/SECURITY_TESTS.md)
