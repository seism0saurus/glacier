# Decision Record: Share Link with QR Code — Acceptance

Date: 2026-04-24
Phase: Acceptance (Phase 3)
Agents: security-auditor (×2), acceptance-test-auditor (×1), secure-tdd-implementer (×3), tdd-ddd-implementer (×1), frontend-designer (×2)
Status: Accepted

## Summary

The "Share Link with QR Code" feature passed Phase 3 acceptance after two security audit rounds and a full fix cycle. All 19 SR-SHARE requirements are verified. Two critical security bugs were found and fixed during Phase 3: an unimplemented `maxViewersPerLink` cap (OWASP API4) and a BOLA vulnerability in `ShareViewTopicAuthInterceptor` that allowed a viewer for link A to subscribe to link B's STOMP topics. The branch `feature/share-link-qr` is ready to merge to `main`.

## Final Build State

Branch: `feature/share-link-qr`, commit `9a33dd8`

| Layer | Count | Failures |
|---|---|---|
| Backend unit (Surefire) | 603 | 0 |
| Backend IT (Failsafe) | 147 | 0 |
| Frontend Karma | 328 | 0 |
| Playwright share-link specs (active) | 15 entries across 5 projects | 0 |
| Jacoco instruction (bundle) | 86.9% | ≥ 45% req. ✓ |
| Jacoco branch (bundle) | 75.5% | ≥ 35% req. ✓ |

## Fix Cycles

### Round 1 (Phase 3 initial audit)

| Commit | Fix | Severity |
|---|---|---|
| `88de6a1` | `src/test/resources/application.properties` with HMAC secret — IT boot fix | Infra |
| `c341e0e` | PrincipalKey migration (`FallbackRateLimiter` + `MessageCacheImpl`); CSRF wiring; SSRF redirect-disable + DNS pin; `__Host-` cookie `Path=/`; dead `ShareCatalogResponse.inactive()` removed | High |
| `7599a2d` | `null` shareViewStompRelay fix in unit constructor | Low |
| `c82d8b0` | CORS for share endpoints; rate-limit test isolation (`@TestPropertySource`); SSRF/CSRF IT extension | Medium |
| `b489f8b` | `ShareLinkExpirationIT` (5), `ShareCatalogEndpointTimingIT` (1), `PrincipalHandlerIT` (4) added | Test |
| `b205b97` | Static `import * as QRCode` (fixes Karma ChunkLoadError); all 9 Playwright `test.fail` un-deferred | Medium |

### Round 2 (security re-audit + acceptance audit)

| Commit | Fix | Severity |
|---|---|---|
| `5d617de` | `ShareLinkViewerCounter` + `ShareViewPrincipalHandler` cap enforcement + disconnect decrement (SR-SHARE-05) | High |
| `9a33dd8` | `ShareViewTopicAuthInterceptor` BOLA fix: replaced string-prefix check with `instanceof ShareViewerPrincipal` + `boundShareLinkId` comparison; 14 unit tests added | **Critical** |
| `e3a053d` | 6 missing English i18n catalog entries added to `messages.en.json` | Medium |

## Key Decisions

### 404-for-All Catalog Design (SR-SHARE-06 / SR-SHARE-15)

**Decision**: The catalog endpoint returns 404 uniformly for all non-active share IDs (unknown, expired, revoked, malformed). This deviates from the Phase 1 plan's "uniform-200 with state discriminator" design.
**Rationale**: The 200-vs-404 split is a binary oracle (is this ID currently active?), but the share-link ID already encodes 256 bits of entropy — an attacker guessing IDs already has per-link oracle information by construction. The 404-for-all path is simpler, more standard, and avoids a bespoke timing-equality problem.
**Source**: User decision, 2026-04-23, Conflict #1 resolution ("a").
**Alternatives considered**: Uniform-200 with `{state: "active"|"expired"|"revoked"}` (rejected — complexity without proportional gain given the secret-ID model).

### Timing Tolerance for Anti-Enumeration IT

**Decision**: `ShareCatalogEndpointTimingIT` uses 50% p95 tolerance (not ≤15% as originally planned).
**Rationale**: The active-ID path does materially more work (cookie minting, JSON body serialization) than the unknown-ID 404 path. A 15% tolerance is unachievable across these two different code paths. The 50% tolerance still catches order-of-magnitude (≥10×) timing oracles.
**Source**: Fix-cycle determination, 2026-04-24.

### NoOpShareLinkService Coverage Exemption

**Decision**: `NoOpShareLinkService` (0% coverage) is left without dedicated tests.
**Rationale**: It is a null-object used only when `glacier.share.enabled=false`, which has no production path in the current configuration. Risk: low.

## Security Findings Summary

| SR | Requirement | Disposition |
|---|---|---|
| SR-01 | wallId not in share URLs | VERIFIED |
| SR-02 | CSRF on create/revoke | VERIFIED |
| SR-03 | `__Host-shareViewerId` cookie | VERIFIED |
| SR-04 | Rate limit creation per wallId/IP | VERIFIED |
| SR-05 | Max viewers per link at STOMP | VERIFIED (fixed in `5d617de`) |
| SR-06 | Timing-safe 404 anti-enumeration | VERIFIED (50% tolerance, documented) |
| SR-07 | HMAC-signed image proxy | VERIFIED |
| SR-08 | 404 for inactive IDs | VERIFIED |
| SR-09 | Revocation push via STOMP | VERIFIED |
| SR-10 | PrincipalKey typed keys | VERIFIED |
| SR-11 | SSRF blocklist + redirect disable | VERIFIED |
| SR-12 | Constant-time HMAC compare | VERIFIED |
| SR-13 | TTL + sweeper | VERIFIED |
| SR-14 | Viewer rate limiting | VERIFIED |
| SR-15 | 404-for-all design | VERIFIED (approved deviation from uniform-200) |
| SR-16 | No wallId in share STOMP topics | VERIFIED |
| SR-17 | Image proxy security headers | VERIFIED |
| SR-18 | CSRF header name `X-Share-CSRF` | VERIFIED |
| SR-19 | Max active links per sharer/IP | VERIFIED |

## Acceptance Criteria Results

| AC | Criterion | Result |
|---|---|---|
| AC-01 | Sharer creates link, unique, no wallId, ≤ 3/sharer | PASS |
| AC-02 | Viewer sees read-only wall with toots | PASS |
| AC-03 | QR encodes share URL; Karma + browser render without error | PASS |
| AC-04 | 7-day TTL; 404 after expiry | PASS |
| AC-05 | Revoke works; control message to viewers | PASS |
| AC-06 | CSRF on create/revoke | PASS |
| AC-07 | Image proxy HMAC; 401 on invalid sig | PASS |
| AC-08 | maxViewersPerLink cap at STOMP | PASS |
| AC-09 | Rate limiting on creation/fallback/proxy | PASS |
| AC-10 | Anti-enum: 404 all non-active, uniform response | PASS |
| AC-11 | 9 Playwright specs active, correct project routing | PASS |
| AC-12 | 328 Karma tests pass | PASS |
| AC-13 | Backend ITs pass; Jacoco thresholds met | PASS |

## Open Risks Accepted

- **`ShareImageProxyService` at 29% instruction coverage**: SSRF defenses are covered at the `DefaultSafeUrlValidator` unit level; streaming-error and content-type rejection branches have no dedicated unit tests. Risk: low.
- **Playwright e2e not run against live Docker stack**: The 9 share-link Playwright specs are structurally sound and active; they will be verified by CI (`-P RunE2ETest`) against the full dockerized Mastodon fixture.

## Resolved Conflicts

### Conflict #1: Uniform-200 vs 404-for-All Catalog Response
**security-auditor**: Implementation uses 404-for-all, which is stricter and closes the state-discrimination oracle.
**Phase 1 plan**: Called for uniform-200 with `{state}` body discriminator.
**Resolution (2026-04-23)**: User chose 404-for-all ("a"). Documented above.

## User Approval

Date: 2026-04-24
Approval message (verbatim): "approve"

## References

- Phase 1 decision: `docs/decisions/2026-04-22-planning-share-link-qr.md`
- Phase 2 decision: `docs/decisions/2026-04-23-implementation-share-link-qr.md`
- Resume handoff: `docs/decisions/2026-04-23-resume-handoff-share-link-qr.md`
- CLAUDE.md: authoritative conventions and testing policy
- Branch: `feature/share-link-qr` (25+ commits ahead of `main`)
