---
name: ws-fallback Phase 3 fix context
description: Context for Phase 3 fix cycle on Glacier ws-fallback feature — three frontend fixes applied April 2026
type: project
---

Phase 3 fix cycle for Glacier ws-fallback feature (2026-04-22).

FIX A: Wired `environment.allowPlaintext` into the INSECURE gate in `fallback.service.ts`.
- The production build INSECURE gate now checks `environment.production && !environment.allowPlaintext && protocol === 'http:'`
- environment.production.ts has `allowPlaintext: false`, development has `true`
- 3 new tests added to `fallback.service.spec.ts`

FIX B: Added missing `cap.reached.snackbar.no.limit` i18n key to `messages.en.json`.
- Key added with value `"Hashtag limit reached. '{tag}' was not added."`
- Catalog completeness regression test added to `hashtag.component.spec.ts` using `fetch('/assets/i18n/messages.en.json')`
- Also implemented Phase 2 CAP_EXCEEDED rollback in `hashtag.component.ts` (was missing from working tree)

FIX C: Added WCAG 2.2 AA tag scoping and 3 missing state audits to e2e specs.
- Created `frontend/e2e/helper/a11y.ts` with `assertNoWcag22AaViolations` and `runAxeOnlyInChromium` helpers
- Updated `fallback-ux.spec.ts` to add PROBING and OFFLINE state axe audits
- Added KILLSWITCHED axe audit to `fallback-killswitch.spec.ts`
- All axe scans now use `.withTags(['wcag2a','wcag2aa','wcag21aa','wcag22aa'])`

**Why:** Phase 3 acceptance audit returned PASS WITH CONDITIONS — these were the conditions.
**How to apply:** Reference `docs/decisions/2026-04-21-planning-ws-fallback.md` D-14/D-18/D-20 for context.
