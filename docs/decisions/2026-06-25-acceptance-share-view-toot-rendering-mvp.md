# Decision Record: Share-View Live Toot-Rendering Pipeline (MVP) — Acceptance

Date: 2026-06-25
Phase: Acceptance
Agents: security-auditor, acceptance-test-auditor (Round 1 + Round 2 cross-review)
Status: Accepted — **PASSED WITH CONDITIONS**

## Post-acceptance update (2026-06-26)

- **Condition 1 (live-relay e2e CI gate) — DONE**: `share-wall-roundtrip.spec.ts` is un-`fixme`'d and
  wired into `verify.yml` as the `share-https` matrix variant; validated against the live stack.
- **Condition 2 (SR-CSP-01 recapture) — RESOLVED**: root cause was the critical-CSS-inlining
  `onload` handler, not JIT; fixed via `inlineCritical=false` (+ `platformBrowser` hardening). See
  `2026-06-24-sr-csp-01-follow-up.md`.
- **Accepted residual F-5 (FLAW-3 fallback renders blank) — RESOLVED**: a new `ShareTootCache`
  (per-(sharerWallId, hashtag) `Status` ring) backs catalog `initialToots` and `/messages` fallback,
  rendered to per-link `ReadonlyTootView`. Verified by unit (`ShareTootCacheTest`) + IT
  (`ShareViewControllerIT`, `ShareViewStompRelayRenderTest`) + a forced-fallback e2e (WS blocked,
  share-https). The remaining residual (F-3 image-proxy bearer surface) is unchanged (held).

## Summary

The MVP that wires the share-view live toot-rendering pipeline passed acceptance with
conditions. The full test pyramid for the feature is green and every security/functional
requirement maps to a passing, run-set test. One merge-blocking build failure (an ADR-index
sentinel, not a code defect) was found and fixed during acceptance. No Critical or High
security findings. The end-to-end live render is **not** proven by the default `./mvnw verify`
(only the seams are) — the true live gate is the `test.fixme` Playwright e2e, deferred to a
pre-merge CI run per the Phase 1 accepted risk.

## Disposition: PASSED WITH CONDITIONS

### Test results (executed by acceptance-test-auditor)
- Backend `./mvnw verify`: 1806 tests; initial 1 failure = `AdrIndexCompletenessSentinelTest`
  (implementation decision doc missing from `docs/decisions/README.md`). **Fixed** (index row
  added; sentinel re-run `1/0`). Jacoco instruction/branch gates met. Now green.
- Frontend Karma: 800 passing, 1 skipped (e2e `test.fixme`), 0 failures.
- Requirement→test coverage: all 10 requirements covered by real asserting tests in the run set
  (SR-RENDER-01/02/03, SR-CAT-01/02, SR-SUB-01/02, SR-LOG-01, FLAW-2/B2, FLAW-1, ADR-RENDER-03).

### Security audit findings + dispositions (no Critical/High)
| ID | Title | Severity | Disposition |
|----|-------|----------|-------------|
| F-1 | SR-CSP-01 `require-trusted-types-for` violation not located | Low | defer (no injectable HTML on share route; defence-in-depth only) |
| F-2 | B2 lenient-JSON parse of federation content | Info | accept (gates precede relay; output sanitized via `renderForView`; bounded) |
| F-3 | Image proxy is a bearer surface (no per-fetch authz) | Low | accept (ADR-SHARE-07/09; per-link render closes cross-link publish) |
| F-4 | Redundant double JSON parse per generic event | Info | accept |
| F-5 | FLAW-3 fallback renders blank | Low | defer (`/messages` not patched; documented; availability-only) |
| F-6 | SR-CSP-01 follow-up doc references Angular 19 (now 20) | Low | tracked follow-up → frontend-designer (non-gating) |

### Fix cycles
- **Merge blocker** (`AdrIndexCompletenessSentinelTest`): fixed by the orchestrator — added the
  `docs/decisions/README.md` row for `2026-06-24-implementation-share-view-toot-rendering-mvp.md`
  (decision-doc index maintenance, no code change). Both auditors confirmed the fix is
  security-neutral. Re-verified green.
- **F-6**: classified by both auditors as a non-gating tracked follow-up; not run as a fix cycle.

## Conditions (accepted by the user at the Phase 3 gate)
1. **Hard pre-merge gate (CI)**: the live-relay Playwright e2e
   (`frontend/e2e/workflows/share-wall-roundtrip.spec.ts`, currently `test.fixme`) must run
   **green** before merge. The default `verify` proves only the unit/IT seams — there is no
   automated proof of the end-to-end live render locally. Requires the deferred HTTPS-infra in
   `infrastructure-content.tar.gz` (out of this MVP's scope).
2. **Tracked follow-up (non-gating, Low)**: redo the SR-CSP-01 runtime CSP-violation capture on
   the Angular 20 build and update `docs/decisions/2026-06-24-sr-csp-01-follow-up.md`.
3. **Accepted residuals**: FLAW-3 (fallback renders blank — needs ring-buffer `Status` caching,
   a future increment); image proxy is a documented bearer surface.

## Resolved Conflicts
None at acceptance. The two Round-1 C2-wording conflicts were resolved in Phase 1; the Phase 3
audits cross-reference and agree (acceptance accepts all security findings; security confirms the
acceptance disposition undermines none). No `## ⚡ CONFLICT:` raised in Phase 3.

## User Approval
Date: 2026-06-25
Approval message (verbatim): "approve"
(Phase 3 gate presented the disposition PASSED WITH CONDITIONS with the three conditions above.)

## Open Risks (explicitly accepted)
- No automated end-to-end live-render proof in local `verify` — gated to CI live-relay e2e.
- Fallback mode renders blank (FLAW-3) until `Status` history caching lands.
- SR-CSP-01 runtime verification pending on Angular 20.
All documented; none has confidentiality/integrity impact.

## References
- Planning (MVP): `docs/decisions/2026-06-24-planning-share-view-toot-rendering-mvp.md`
- Implementation (MVP): `docs/decisions/2026-06-24-implementation-share-view-toot-rendering-mvp.md`
- Parent plan: `docs/decisions/2026-06-24-planning-share-view-toot-rendering.md`
- SR-CSP-01 follow-up: `docs/decisions/2026-06-24-sr-csp-01-follow-up.md`
- e2e (test.fixme, CI gate): `frontend/e2e/workflows/share-wall-roundtrip.spec.ts`
