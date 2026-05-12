# Decision Record: CSRF Cookie Single Emission Fix — Planning

Date: 2026-05-06
Phase: Planning (Phase 1)
Agents: ddd-tdd-architect (R1 + R2), secure-feature-planner (R1 + R2)
Status: Accepted

## Summary

`CsrfTokenCookieFactory.issueCsrfToken()` and `ShareViewerCookieFactory` both emit two `Set-Cookie`
headers for the same cookie name: once via `response.addCookie()` (no `SameSite` attribute) and once
via `response.addHeader("Set-Cookie", ...)` (with `SameSite`). RFC 6265 §4 leaves duplicate-name
precedence implementation-defined; some browsers honour only the first, silently losing `SameSite`.
The fix replaces both calls with a single `response.addHeader(HttpHeaders.SET_COOKIE,
ResponseCookie.from(...).build().toString())` emission — no `addCookie`, no raw string concatenation.

## Bounded Context

**Share / Public Wall — CSRF Defense subdomain** (`de.seism0saurus.glacier.share.web`).

## Domain Invariants (must hold after the fix)

| ID | Invariant |
|----|-----------|
| I-CSRF-1 | `response.getHeaders("Set-Cookie")` filtered by CSRF cookie name yields exactly 1 entry |
| I-CSRF-2 | The single header contains `SameSite=Strict` |
| I-CSRF-3 | The single header does NOT contain `HttpOnly` (intentional: double-submit JS readability) |
| I-CSRF-4 | Secure mode: name=`__Host-shareCsrf`, `Path=/`, `Secure` present; insecure: `shareCsrf`, `Path=/share`, `Secure` absent |
| I-CSRF-5 | `Max-Age=3600` present |
| I-CSRF-6 | Token: 32-byte `SecureRandom`, Base64URL-no-padding, returned to caller unchanged |
| I-CSRF-7 | No class in `share.web` calls `HttpServletResponse#addCookie` or raw `addHeader("Set-Cookie", ...)` |

## Key Decisions

### ADR-1: Remove `response.addCookie()` — emit one canonical `Set-Cookie` header

**Decision**: Remove the `response.addCookie(cookie)` call from both `CsrfTokenCookieFactory` and
`ShareViewerCookieFactory`. Emit only the `response.addHeader(HttpHeaders.SET_COOKIE, ...)` call.

**Rationale**: Two `Set-Cookie` headers with the same cookie name produce implementation-defined
precedence in browsers and HTTP intermediaries. Some take the first (without `SameSite`), silently
stripping the CSRF defence. A single canonical header is deterministic.

**Alternatives considered**:
- Keep both, set `SameSite` on the `Cookie` API — rejected: Servlet `Cookie` API does not support
  `SameSite` portably; requires container-specific adapters.
- Keep both, overwrite via `setHeader` (singular) — rejected: brittle interaction with other
  middleware that may add Set-Cookie earlier in the chain.

### ADR-2: Use Spring's `ResponseCookie` builder

**Decision**: `ResponseCookie.from(cookieName, token).path(cookiePath).maxAge(Duration.ofSeconds(3600)).sameSite("Strict").secure(secureCookies).httpOnly(false).build()` serialised via `.toString()` into a single `addHeader(HttpHeaders.SET_COOKIE, ...)` call.

**Rationale**: `ResponseCookie` is already on the classpath (spring-web via Spring Boot 3.4), has
first-class `SameSite` support, validates attributes, and produces RFC 6265-conformant serialization.
Raw string concatenation is an anti-pattern (future contributors may forget attributes).

**Important implementation note**: `ResponseCookie.toString()` co-emits `Expires=` alongside
`Max-Age` for HTTP/1.0 proxy compatibility. All tests MUST use substring containment assertions
(`.contains("Max-Age=3600")`), NOT exact string equality. The `Expires=` value drifts per request
and MUST NOT be asserted.

**Alternatives considered**: Custom builder — rejected: reinvents `ResponseCookie`.

### ADR-3: ArchUnit structural gate scoped to full `de.seism0saurus.glacier.share.web` package

**Decision**: `CsrfCookieEmissionStructureTest.java` (new, Surefire unit) asserts across the full
`share.web` package:
1. No class calls `HttpServletResponse#addCookie` (ArchUnit method-call ban).
2. No class calls `addHeader("Set-Cookie", ...)` with a raw string (ArchUnit method-call ban).
3. Any cookie-emitting class imports `org.springframework.http.ResponseCookie`.
4. **Vacuous-pass canary**: `Class.forName("de.seism0saurus.glacier.share.web.CsrfTokenCookieFactory")`
   succeeds AND the package class set is non-empty — fails RED if class is renamed/deleted (prevents
   the jqwik-silent-skip false-positive pattern).

**Rationale**: Per-factory class scope would leave sibling factories unguarded. Package scope catches
existing and future siblings immediately. Per-factory attribute invariants (I-CSRF-1..7) remain
CSRF-scoped because viewer cookie semantics differ (SameSite=Lax, HttpOnly=true).

### ADR-4: Test assertion shape — filter-by-name, then attribute substrings

**Decision**: All Set-Cookie assertions filter by cookie name first
(`getHeaders("Set-Cookie").stream().filter(h -> h.startsWith("<name>="))`) before asserting
attributes. `anyMatch` over an unfiltered Set-Cookie stream is banned for any attribute
(SameSite, HttpOnly, Secure, Max-Age, Path).

**Rationale**: `anyMatch(contains("SameSite=Strict"))` over the full Set-Cookie list passes whenever
any cookie carries the attribute — including unrelated cookies. This is a false-green anti-pattern
that masked the double-emission bug in CI (threat T-DSC-5). The `extractCsrfSetCookieWithSameSite()`
helper in `OwaspMatrixCookieAttributesLockstepTest` is a formalisation of this anti-pattern and
must be deleted or renamed to the filter-by-name form.

### ADR-5: Two-tier test coverage — unit + MockMvc IT

**Decision**: (a) `CsrfTokenCookieFactoryTest.java` (Surefire) drives the factory directly with
`MockHttpServletResponse` for the full I-CSRF attribute matrix. (b) `CsrfTokenIssuanceIT.java`
(Failsafe, `@WebMvcTest`) drives the share endpoint via the real MockMvc pipeline and asserts
exactly one Set-Cookie header for the CSRF cookie name.

**Rationale**: Unit alone does not exercise Spring's response serialisation. IT alone is too coarse
for the attribute matrix. Two layers: unit for breadth, IT for end-to-end emission count.

## Security Requirements

| SR | Requirement | Standard | Verifying test/control |
|----|-------------|----------|------------------------|
| SR-CSRF-01 | Set-Cookie MUST carry `SameSite=Strict` | [OWASP CSRF Prevention Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/Cross-Site_Request_Forgery_Prevention_Cheat_Sheet.html) | I-CSRF-2 unit + IT |
| SR-CSRF-02 | Exactly one Set-Cookie header per call | [RFC 6265](https://www.rfc-editor.org/rfc/rfc6265) §4 | RED canary + IT |
| SR-CSRF-03 | `HttpOnly` MUST be absent (intentional exception) | [CWE-1004](https://cwe.mitre.org/data/definitions/1004.html) — exception: double-submit | I-CSRF-3 unit + IT |
| SR-CSRF-04 | `__Host-` prefix semantics in secure mode | [RFC 6265bis](https://www.rfc-editor.org/rfc/rfc6265bis) §4.1.3 | I-CSRF-4 unit |
| SR-CSRF-05 | `Secure` present in secure mode; absent in insecure | [RFC 6265](https://www.rfc-editor.org/rfc/rfc6265) §4 | I-CSRF-4 unit (both branches) |
| SR-CSRF-06 | `Max-Age=3600` present | [OWASP ASVS V7](https://raw.githubusercontent.com/OWASP/ASVS/refs/heads/v5.0.0/5.0/docs_en/OWASP_Application_Security_Verification_Standard_5.0.0_en.json) Session Management | I-CSRF-5 unit + IT |
| SR-CSRF-07 | Token entropy ≥ 256 bits (32-byte SecureRandom) | [NIST SP 800-63B](https://csrc.nist.gov/publications/detail/sp/800-63b/final) | Code path preserved unchanged |
| SR-CSRF-08 | `ResponseCookie.toString()` MUST NOT transform token value | [CWE-116](https://cwe.mitre.org/data/definitions/116.html) | Substring test: `contains(token)` |
| SR-CSRF-09 | ArchUnit gate MUST prevent Cookie API re-introduction | [CWE-1188](https://cwe.mitre.org/data/definitions/1188.html) — insecure default | `CsrfCookieEmissionStructureTest` |
| SR-CSRF-10 | Zero new log lines capturing token or Set-Cookie content | D-13 / SR-8 (glacier-structured-logging) | Code review + recommended no-Logger gate |
| SR-CSRF-11 | Ban `anyMatch` over unfiltered Set-Cookie stream for any attribute | [CWE-697](https://cwe.mitre.org/data/definitions/697.html) — incorrect comparison | Regex gate in `CsrfCookieEmissionStructureTest` |
| SR-CSRF-12 | Sibling-factory inventory; gate covers full `share.web` package | [OWASP A05:2021](https://owasp.org/Top10/A05_2021-Security_Misconfiguration/) — Misconfiguration | Package-scoped ArchUnit + `ShareViewerCookieFactory` fix |
| SR-CSRF-13 | Rewrite `CsrfCookieLockstep`; delete `extractCsrfSetCookieWithSameSite()` | T-DSC-5 false-green threat | Lockstep redesign in Commit 2 |
| SR-CSRF-14 | MockMvc-pipeline IT asserting single emission on real Spring response | [OWASP ASVS 5.0](https://raw.githubusercontent.com/OWASP/ASVS/refs/heads/v5.0.0/5.0/docs_en/OWASP_Application_Security_Verification_Standard_5.0.0_en.json) V3.4 | `CsrfTokenIssuanceIT.java` |

## Phase 2 Lane Partition

### Lane A — `tdd-ddd-implementer`

1. **Pre-flight inventory** (no commit — documented in PR description):
   - All `share.web` classes touching `Cookie`/`addCookie`/raw `Set-Cookie`.
   - All call sites of `extractCsrfSetCookieWithSameSite()` and any attribute-scan cousins.
2. **Commit 1** — RED canary: `CsrfTokenCookieFactoryTest` new cases asserting size==1 (MUST FAIL RED on current code).
3. **Commit 2** — Lockstep test redesign: `OwaspMatrixCookieAttributesLockstepTest` — delete `extractCsrfSetCookieWithSameSite()` (if CSRF-only) or rename to `extractSetCookieByName(name)` + migrate all call sites; rewrite CSRF lockstep block to filter-by-name + size==1.
4. **Commit 4** — `CsrfTokenIssuanceIT.java` (Failsafe `@WebMvcTest`).

### Lane B — `secure-tdd-implementer`

1. **Commit 2** — Production fix: `CsrfTokenCookieFactory.java` (ResponseCookie, remove Cookie import, remove addCookie) + `ShareViewerCookieFactory.java` (same migration, SameSite=Lax, HttpOnly=true preserved).
2. **Commit 3** — `CsrfCookieEmissionStructureTest.java` (new): vacuous-pass canary + ArchUnit package-wide addCookie ban + ArchUnit addHeader ban + ResponseCookie import requirement + anyMatch regex gate over test sources.

### Sequential ordering
1. Lane A pre-flight inventory → determines whether siblings beyond `ShareViewerCookieFactory` also need fixing.
2. Lane A Commit 1 (RED canary).
3. Lane B Commit 2 (production fix, canary turns GREEN) + Lane A Commit 2 (lockstep redesign, co-located with fix).
4. Lane B Commit 3 (structural gate).
5. Lane A Commit 4 (IT).
6. Joint Round 2 review: `./mvnw verify` green; all SRs evidenced.

## Implementation Checklist

| Commit | File | Change |
|--------|------|--------|
| 1 | `CsrfTokenCookieFactoryTest.java` | New canary cases (filter-by-name + size==1); MUST FAIL RED |
| 2 | `CsrfTokenCookieFactory.java` | Remove Cookie block + addCookie; replace with ResponseCookie builder; remove Cookie import |
| 2 | `ShareViewerCookieFactory.java` | Same migration; SameSite=Lax, HttpOnly=true |
| 2 | `OwaspMatrixCookieAttributesLockstepTest.java` | Delete/migrate helper; redesign CSRF lockstep block |
| 3 | `CsrfCookieEmissionStructureTest.java` (NEW) | Vacuous-pass canary + ArchUnit bans + anyMatch regex gate |
| 4 | `CsrfTokenIssuanceIT.java` (NEW) | MockMvc IT asserting single emission + attribute matrix |

Files NOT modified: `ShareCsrfGuard.java`, `OWASP_COVERAGE_MATRIX.md` (EP-09 already correct), any frontend CSRF reader (cookie name/value semantics unchanged).

## Resolved Conflicts

### Conflict 1: `ShareViewerCookieFactory` scope ("if any" vs. explicit)
**secure-feature-planner**: `ShareViewerCookieFactory` lines 73/82 confirmed to exhibit the same anti-pattern; conditional "if any" framing risks out-of-scope decision in Phase 2.
**ddd-tdd-architect**: "if any" was deliberately conditional pending pre-flight inventory.
**Resolution** (2026-05-06): Accept security-feature-planner position — `ShareViewerCookieFactory` named explicitly in Commit 2; same `ResponseCookie` migration; SameSite=Lax, HttpOnly=true preserved per viewer semantics.

### Conflict 2: `anyMatch` ban scope (SameSite only vs. all attributes)
**secure-feature-planner**: `OwaspMatrixCookieAttributesLockstepTest` line 442-444 uses `anyMatch(contains("httponly"))` — same anti-pattern, different attribute; regex ban must cover all attributes.
**ddd-tdd-architect**: ADR-3 wording "anyMatch pattern ban" left regex specification to Phase 2.
**Resolution** (2026-05-06): Accept security-feature-planner position — semantic ban on any `anyMatch` over `getHeaders("Set-Cookie").stream()` without a preceding `.filter(h -> h.startsWith(<name>=))`; applies to all attributes.

## User Approval

Date: 2026-05-06
Approval message (verbatim): "approve"

## Open Risks

| ID | Risk | Rationale |
|----|------|-----------|
| OR-CSRF-01 | `Expires=` co-emission by `ResponseCookie` | HTTP/1.0 compatibility attribute; tests use substring containment, never assert `Expires` value — safe |
| OR-CSRF-02 | Insecure-mode explicit test case (Gap 3) | `secureCookies=false` branch is tested; explicit symmetric test case recommended in Phase 2 but not mandated |
| OR-CSRF-03 | SR-CSRF-10 no-Logger gate (Gap 4) | "No log lines" enforced by code review and `glacier-structured-logging-logback` convention; structural gate possible in Commit 3 |

## References

- [OWASP Standards Integration — Acceptance](2026-05-04-acceptance-owasp-standards-integration.md) — original sidebar (lines 120-131)
- `src/main/java/de/seism0saurus/glacier/share/web/CsrfTokenCookieFactory.java`
- `src/main/java/de/seism0saurus/glacier/share/web/ShareViewerCookieFactory.java`
- `src/test/java/de/seism0saurus/glacier/webservice/OwaspMatrixCookieAttributesLockstepTest.java`
- [RFC 6265](https://www.rfc-editor.org/rfc/rfc6265) §4 (duplicate Set-Cookie precedence)
- [RFC 6265bis](https://www.rfc-editor.org/rfc/rfc6265bis) §4.1.3 (`__Host-` prefix)
- [OWASP CSRF Prevention Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/Cross-Site_Request_Forgery_Prevention_Cheat_Sheet.html)
- [OWASP A05:2021 — Security Misconfiguration](https://owasp.org/Top10/A05_2021-Security_Misconfiguration/)
- [CWE-625: Permissive Regular Expression](https://cwe.mitre.org/data/definitions/625.html), [CWE-697: Incorrect Comparison](https://cwe.mitre.org/data/definitions/697.html), [CWE-1004: Sensitive Cookie Without HttpOnly Flag](https://cwe.mitre.org/data/definitions/1004.html), [CWE-1188: Initialization of a Resource with an Insecure Default](https://cwe.mitre.org/data/definitions/1188.html)
- [OWASP ASVS 5.0](https://raw.githubusercontent.com/OWASP/ASVS/refs/heads/v5.0.0/5.0/docs_en/OWASP_Application_Security_Verification_Standard_5.0.0_en.json) V3.4 (CSRF token cookie attributes)
- [`spring-security-hardening`](../../.claude/skills/spring-security-hardening.md) skill
- [`spring-boot-testing-patterns`](../../.claude/skills/spring-boot-testing-patterns.md) skill
- [`glacier-structured-logging-logback`](../../.claude/skills/glacier-structured-logging-logback.md) skill
