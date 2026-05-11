# Decision Record: CSRF Cookie Single Emission Fix — Implementation

Date: 2026-05-06
Phase: Implementation (Phase 2)
Agents: tdd-ddd-implementer (Lane A + Round 2 review), secure-tdd-implementer (Lane B + Round 2 fix)
Status: Accepted

## Summary

6 commits implement the double Set-Cookie emission fix across two sequential lanes plus one Round 2
fix cycle. `CsrfTokenCookieFactory` and `ShareViewerCookieFactory` now each emit exactly one
`Set-Cookie` header via Spring's `ResponseCookie` builder. All 14 SRs satisfied. 1,278 unit +
303 IT tests pass, BUILD SUCCESS, Jacoco thresholds met.

## Files Changed

| File | Lane/Phase | Change |
|------|-----------|--------|
| `src/test/java/de/seism0saurus/glacier/share/web/CsrfTokenCookieFactoryTest.java` | A + Fix | RED canary cases (Commit 1); legacy outer-method `anyMatch` hardened to filter-by-name (fix commit) |
| `src/test/java/de/seism0saurus/glacier/security/OwaspMatrixCookieAttributesLockstepTest.java` | A | `extractCsrfSetCookieWithSameSite()` deleted; CSRF lockstep block redesigned (Commit 2) |
| `src/main/java/de/seism0saurus/glacier/share/web/CsrfTokenCookieFactory.java` | B | Cookie API removed; `ResponseCookie` builder; single `addHeader(HttpHeaders.SET_COOKIE, ...)` emission (Commit 3) |
| `src/main/java/de/seism0saurus/glacier/share/web/ShareViewerCookieFactory.java` | B | Same migration; `SameSite=Lax`, `HttpOnly=true` (Commit 3) |
| `src/test/java/de/seism0saurus/glacier/share/web/CsrfCookieEmissionStructureTest.java` | B (NEW) | ArchUnit + anyMatch ban + vacuous-pass canary (Commit 4) |
| `src/test/java/de/seism0saurus/glacier/share/web/CsrfTokenIssuanceIT.java` | A (NEW) | MockMvc `@WebMvcTest` IT (Commit 5) |

## Production Fix Detail

### CsrfTokenCookieFactory.java

Before (dual emission, RFC 6265 violation):
```java
Cookie cookie = new Cookie(cookieName, token);
cookie.setPath(cookiePath);
cookie.setMaxAge(3600);
cookie.setHttpOnly(false);
if (secureCookies) { cookie.setSecure(true); }
response.addCookie(cookie);                               // Set-Cookie without SameSite
response.addHeader("Set-Cookie",                         // second Set-Cookie with SameSite
    cookieName + "=" + token + "; Path=" + cookiePath
    + "; SameSite=Strict; Max-Age=3600"
    + (secureCookies ? "; Secure" : ""));
```

After (single canonical emission):
```java
ResponseCookie csrfCookie = ResponseCookie.from(cookieName, token)
        .path(cookiePath)
        .maxAge(Duration.ofSeconds(3600))
        .sameSite("Strict")
        .secure(secureCookies)
        .httpOnly(false)       // intentional: double-submit pattern requires JS to read token
        .build();
response.addHeader(HttpHeaders.SET_COOKIE, csrfCookie.toString());
```

Removed: `import jakarta.servlet.http.Cookie;`, `Cookie` constructor block, all `cookie.set*()` calls,
`response.addCookie(cookie)`.

### ShareViewerCookieFactory.java

Was NOT calling `response.addCookie()` — created a `Cookie` object unnecessarily while emitting only
via raw `addHeader("Set-Cookie", ...)` string. Migrated to `ResponseCookie`:
```java
ResponseCookie viewerCookie = ResponseCookie.from(cookieName, viewerId)
        .path(cookiePath)
        .maxAge(Duration.ofSeconds(maxAge))
        .sameSite("Lax")       // viewer identity cookie — top-navigation allowed
        .secure(secureCookies)
        .httpOnly(true)        // XSS theft prevention; JS does not need to read viewer ID
        .build();
response.addHeader(HttpHeaders.SET_COOKIE, viewerCookie.toString());
```

Note: `ShareViewerCookieFactory` has a private method named `addCookie(HttpServletResponse, ...)` —
this is an internal helper, NOT a call to `jakarta.servlet.http.HttpServletResponse#addCookie(Cookie)`.
The ArchUnit bytecode gate confirms the distinction.

## Test Results

| Layer | Before | After | Delta |
|-------|--------|-------|-------|
| Unit (Surefire) | 1,269 | **1,278** | +9 |
| Integration (Failsafe) | 298 | **303** | +5 |
| Jacoco | Met | Met | — |
| BUILD | SUCCESS | SUCCESS | — |

+9 unit tests:
- 4 — RED canary (single-emission count assertions, `CsrfTokenCookieFactoryTest$SingleSetCookieEmission`)
- 5 — `CsrfCookieEmissionStructureTest` (ArchUnit gate + vacuous canary + anyMatch ban)

+5 IT tests:
- 5 — `CsrfTokenIssuanceIT` (`@WebMvcTest` secure mode × 3, insecure mode × 2)

## Security Requirements Delivered

| SR | Status | Key Evidence |
|----|--------|-------------|
| SR-CSRF-01 | ✅ | `.sameSite("Strict")` at `CsrfTokenCookieFactory.java:74`; I-CSRF-2 unit + IT |
| SR-CSRF-02 | ✅ | RED canary filter-by-name `hasSize(1)`; IT counterpart |
| SR-CSRF-03 | ✅ | `.httpOnly(false)` at line 76; `doesNotContain("httponly")` in unit + IT |
| SR-CSRF-04 | ✅ | `cookieName = secureCookies ? "__Host-shareCsrf" : "shareCsrf"`; `Path=/` in secure mode |
| SR-CSRF-05 | ✅ | `.secure(secureCookies)` at line 75; `contains("Secure")` secure / `doesNotContain` insecure |
| SR-CSRF-06 | ✅ | `.maxAge(Duration.ofSeconds(3600))`; `contains("Max-Age=3600")` in unit + IT |
| SR-CSRF-07 | ✅ | `SecureRandom` + 32-byte generation unchanged |
| SR-CSRF-08 | ✅ | Token inserted directly into `ResponseCookie.from(cookieName, token)`; no re-encoding; substring tests |
| SR-CSRF-09 | ✅ | `CsrfCookieEmissionStructureTest`: ArchUnit package-wide `addCookie` ban + raw-string ban + `ResponseCookie` import requirement; vacuous-pass canary |
| SR-CSRF-10 | ✅ | Zero new log lines — confirmed by grep; no `LOGGER.*` in fix diff |
| SR-CSRF-11 | ✅ | IT files: `anyMatch` regex gate in `CsrfCookieEmissionStructureTest`; unit outer tests: 4 legacy methods hardened to filter-by-name in fix commit `9066fe8` |
| SR-CSRF-12 | ✅ | `ShareViewerCookieFactory` migrated (`SameSite=Lax`, `HttpOnly=true`); ArchUnit gate covers full `share.web` package |
| SR-CSRF-13 | ✅ | `extractCsrfSetCookieWithSameSite()` deleted; lockstep block redesigned with filter-by-name + `size==1` |
| SR-CSRF-14 | ✅ | `CsrfTokenIssuanceIT.java` 5/5 GREEN — `@WebMvcTest` end-to-end single-emission assertion |

## Round 2 Cross-Review Findings

| Item | Disposition |
|------|-------------|
| `ShareViewerCookieFactory.addCookie(...)` private method — NOT a servlet `addCookie` call (A1) | Informational — ArchUnit bytecode rule correctly distinguishes; no action |
| SR-CSRF-11 gate scoped to `*IT.java` only — 4 legacy `anyMatch` in outer `CsrfTokenCookieFactoryTest` unprotected (A2) | **Fixed** — `9066fe8` hardened all 4 to filter-by-name + `hasSize(1)` |
| `insecureMode_cookiePath_isSharePath` used `Path=/` as fallback (A3) | **Fixed incidentally** in `9066fe8` — assertion now pins `Path=/share` precisely |
| `setCookieHeader_hasPositiveMaxAge` checked only `Max-Age=` (any value) (A4) | **Fixed incidentally** in `9066fe8` — assertion now pins `Max-Age=3600` |

## Commit History

| SHA | Description |
|-----|-------------|
| `aa0f754` | Lane A — RED canary: single-emission assertions (FAIL on old code) |
| `3b8d4ec` | Lane A — Lockstep redesign: `extractCsrfSetCookieWithSameSite()` deleted; filter-by-name + size==1 |
| `36e2a55` | Lane B — Production fix: `ResponseCookie` migration for both CSRF + viewer cookie factories |
| `bb25be4` | Lane B — `CsrfCookieEmissionStructureTest`: ArchUnit gate + vacuous-pass canary + anyMatch ban |
| `613af40` | Lane A — `CsrfTokenIssuanceIT`: MockMvc pipeline IT, 5 tests |
| `9066fe8` | Fix — SR-CSRF-11: harden 4 legacy `anyMatch` outer tests to filter-by-name pattern |

## Deviations from Phase 1 Plan

- **`ShareViewerCookieFactory` was not actually calling `addCookie()`**: Pre-flight inventory (Lane A) revealed the viewer factory created a `Cookie` object but emitted only via raw `addHeader`. The fix still applied the `ResponseCookie` migration correctly — the semantic outcome (no raw-string emission, ArchUnit gate clean) matches the plan intent.
- **SR-CSRF-11 scope gap found in Round 2**: Gate scoped to IT files only. Resolved via Option A (harden legacy tests) rather than Option B (extend gate scope). Both agents agreed Option A is more robust.

## User Approval

Date: 2026-05-06
Approval message (verbatim): "approve"

## Open Risks

| ID | Risk | Mitigation |
|----|------|-----------|
| OR-CSRF-01 | `Expires=` co-emitted by `ResponseCookie` | Substring-containment tests; `Expires` never asserted — safe |
| OR-CSRF-02 | Insecure-mode test case symmetric coverage | Both modes tested in `SingleSetCookieEmission` + IT; explicit |
| OR-CSRF-03 | SR-CSRF-10 no-Logger structural gate (Gap 4) | Zero log lines confirmed by code review; structural enforcement deferred |

## References

- [Phase 1 planning](2026-05-06-planning-csrf-cookie-single-emission.md)
- `src/main/java/de/seism0saurus/glacier/share/web/CsrfTokenCookieFactory.java` (fix site)
- `src/main/java/de/seism0saurus/glacier/share/web/ShareViewerCookieFactory.java` (migrated)
- `src/test/java/de/seism0saurus/glacier/share/web/CsrfTokenCookieFactoryTest.java`
- `src/test/java/de/seism0saurus/glacier/share/web/CsrfCookieEmissionStructureTest.java`
- `src/test/java/de/seism0saurus/glacier/share/web/CsrfTokenIssuanceIT.java`
- `src/test/java/de/seism0saurus/glacier/security/OwaspMatrixCookieAttributesLockstepTest.java`
- [RFC 6265](https://www.rfc-editor.org/rfc/rfc6265) §4, [RFC 6265bis](https://www.rfc-editor.org/rfc/rfc6265bis) §4.1.3
- [OWASP CSRF Prevention Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/Cross-Site_Request_Forgery_Prevention_Cheat_Sheet.html), [OWASP A05:2021 — Security Misconfiguration](https://owasp.org/Top10/A05_2021-Security_Misconfiguration/)
- [CWE-625: Permissive Regular Expression](https://cwe.mitre.org/data/definitions/625.html), [CWE-697: Incorrect Comparison](https://cwe.mitre.org/data/definitions/697.html), [CWE-1004: Sensitive Cookie Without HttpOnly Flag](https://cwe.mitre.org/data/definitions/1004.html), [CWE-1188: Initialization of a Resource with an Insecure Default](https://cwe.mitre.org/data/definitions/1188.html)
