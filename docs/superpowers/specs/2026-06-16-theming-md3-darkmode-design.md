# Design Spec: MD3 Token Theming + System Dark Mode

Date: 2026-06-16
Topic: Frontend theming migration (audit finding XCUT-07)
Status: Approved (design) — pending implementation plan

## Problem

Glacier's Angular frontend uses the legacy prebuilt Material theme
(`@angular/material/prebuilt-themes/deeppurple-amber.css`) plus hardcoded hex colors
(`styles.css` and 10 component CSS files). Consequences (from the 2026-06-16 a11y/UX audit, XCUT-07):

- No MD3 system tokens (`--mat-sys-*`) are defined by the prebuilt theme, so component focus rings
  written as `var(--mat-sys-primary, #6200ee)` fall back to a hardcoded purple — contrast passes only
  by luck of the fallback, not by design.
- No dark mode: no `color-scheme`, no `prefers-color-scheme` path, no `light-dark()`. Users with a dark
  OS preference get a fixed pale-cyan page (glare for light-sensitive users).
- Contrast is incidental (hardcoded hex) rather than guaranteed by a token system.

**Goal:** migrate to an MD3 `mat.theme()` system-token theme, drive surfaces/text/borders/focus/links from
`--mat-sys-*` tokens, and add a system-triggered dark mode — with WCAG 2.2 AA contrast guaranteed by the
token values (and verified by automated axe scans in both schemes), while preserving Glacier's brand identity.

## Decisions (from brainstorming)

1. **Palette direction — seeded MD3 palette, brand surfaces preserved.** Generate the MD3 tonal palette from
   a teal seed (systematic, contrast-guaranteed roles), then override the surface/background tokens to keep
   Glacier's signature pale-cyan (light) and deep-teal-tinted (dark) look. Glacier still looks like Glacier.
2. **Dark-mode trigger — system-only.** Driven purely by `prefers-color-scheme` via `color-scheme: light dark`
   + `light-dark()`. No UI toggle, no persistence, no JS. (A manual toggle is an explicit future follow-up.)
3. **Dark aesthetic — deep teal-tinted surfaces** (e.g. `#0e2329` background / `#15323a` containers,
   `#d6eaed` on-surface), not neutral grays — so the brand carries into dark mode.
4. **Scope — Approach 2: theme infra + full component token migration + dark variants for semantic states.**
   Not minimal (#1 leaves dark mode broken inside components) and not a full one-off-grey sweep (#3,
   diminishing returns). Incidental decorative greys are left alone.

## Architecture

### 1. Theme infrastructure
- Rename `frontend/src/styles.css` → `frontend/src/styles.scss`. Update both `styles` arrays in
  `frontend/angular.json` (the `build` target and the `test` target) to reference `styles.scss`. Angular CLI
  bundles `sass` (binary already present in `node_modules/.bin/sass`; Angular Material 19.2 in use).
- New SCSS partial `frontend/src/_glacier-theme.scss` defining the teal-seeded palette
  (primary = brand teal, tertiary = brand cyan; seed hue derived from `#3293a3`/`#123342`).
- `styles.scss`:
  ```scss
  @use '@angular/material' as mat;
  @use 'glacier-theme' as glacier;
  html {
    color-scheme: light dark;
    @include mat.theme((
      color: (theme-type: color-scheme, primary: glacier.$primary, tertiary: glacier.$tertiary),
      typography: Roboto,
      density: 0,
    ));
    /* Brand-surface overrides (keep Glacier identity, not MD3 neutrals) */
    --mat-sys-background: light-dark(#d3ecf0, #0e2329);
    --mat-sys-surface: light-dark(#d3ecf0, #0e2329);
    --mat-sys-surface-container: light-dark(#ffffff, #15323a);
    --mat-sys-on-surface: light-dark(#123342, #d6eaed);
    --mat-sys-outline: light-dark(/* derived */, /* derived */);
    /* …only the surface/outline family is overridden; primary/secondary/error stay seeded */
  }
  body { background: var(--mat-sys-background); color: var(--mat-sys-on-surface); margin: 0; }
  a { color: var(--mat-sys-primary); }
  a:hover { color: color-mix(in srgb, var(--mat-sys-primary) 80%, var(--mat-sys-on-surface)); }
  ```
  `theme-type: color-scheme` is the Material 19 feature that emits BOTH light and dark `--mat-sys-*` token
  values, switched automatically by `color-scheme: light dark` + `prefers-color-scheme`. Verify the exact
  Material 19.2 API during implementation (the `mat.theme()` map keys / `m3` palette helpers).
- The `@font-face` (juggerRock) and other non-color global rules are carried over unchanged.

### 2. Component token migration (the 10 `*.component.css`)
Replace hardcoded hex with tokens:
- Surfaces `#d3ecf0`/`#f5f5f5`/`#fafafa` → `var(--mat-sys-surface)` / `var(--mat-sys-surface-container)`.
- Text `#123342`/`#666`/`#374151`/`#333` → `var(--mat-sys-on-surface)` / `var(--mat-sys-on-surface-variant)`.
- Borders `#9cc`/`#ccc`/`#ddd` → `var(--mat-sys-outline)` / `var(--mat-sys-outline-variant)`.
- Brand-accent buttons `#3293a3`/`#3daabb`/`#4acad7` → `var(--mat-sys-primary)` (+ `color-mix` for hover;
  this also subsumes the SHELL-09 hover-contrast fix already shipped).
- Focus rings already `var(--mat-sys-primary, …)` — the fallback becomes moot once the token is real; keep
  the token, the hardcoded `#6200ee` fallback may remain as a harmless safety net.

**Semantic state colors** (NOT `--mat-sys-*` — they are domain semantic palettes) get explicit `light-dark()`
pairs, keeping today's tuned light values verbatim and adding axe-verified dark counterparts:
- `connection-status.component.css`: live / fallback / killswitch / insecure / probing / offline states — each
  has a documented ≥7:1 light value (preserve exactly) + a new dark value (target ≥7:1, min ≥4.5:1).
- Toot / media notice palettes (`#fee2e2`/`#7f1d1d` red, `#dcfce7`/`#166534` green, `#fef9c3`/`#713f12`
  amber, etc.) in the relevant component CSS — same `light-dark()` treatment.

Incidental one-off decorative greys with no brand/accessibility role may stay hardcoded (Approach 2 boundary).

### 3. Dark-mode mechanism
Pure CSS: `color-scheme: light dark` on `html` + `light-dark(<light>, <dark>)` in every color declaration +
Material's dual token emission. No JavaScript, no `.dark-theme` class, no toggle, no `localStorage`. The
OS/browser `prefers-color-scheme` is the single source of truth. Existing `prefers-reduced-motion` handling
(NoopAnimationDriver, app.module.ts) is untouched.

## Testing (per the non-negotiable testing policy)

- **e2e / axe (primary contrast guarantee):** extend every `frontend/e2e/workflows/*-a11y.spec.ts` axe scan
  to run a second time under `page.emulateMedia({ colorScheme: 'dark' })`, so axe enforces WCAG 1.4.3 /
  1.4.11 contrast in BOTH light and dark across all covered surfaces (wall, connection indicator states,
  readonly viewer, header, footer, GDPR dialog, share dialog, expired page — the coverage added by XCUT-08).
  Factor the light+dark loop into the shared `helper/a11y.ts` if clean.
- **Karma:** templates/TS are unchanged, so component specs are largely unaffected. Adjust any spec that
  asserts a literal color value. Add a small smoke spec asserting the global theme exposes the key
  `--mat-sys-*` tokens (e.g. `getComputedStyle(document.documentElement).getPropertyValue('--mat-sys-primary')`
  is non-empty) so a broken theme include fails fast.
- **Local verification limits:** the dockerized axe e2e runs in CI (local stack has the known
  toot-delivery flakiness). Locally: build the frontend (`ng build`) to confirm SCSS compiles and the bundle
  is produced, and statically check the chosen dark hex values against WCAG ratios before relying on CI axe.

## Files touched (estimate ~12–14)
- `frontend/src/styles.css` → `frontend/src/styles.scss` (rename + rewrite)
- `frontend/src/_glacier-theme.scss` (new — palette partial)
- `frontend/angular.json` (2 `styles` references)
- `frontend/src/app/**/**.component.css` (10 files: app, header, footer, migration-banner, wall, hashtag,
  connection-status, gdpr, + any others with color rules)
- `frontend/e2e/helper/a11y.ts` and `frontend/e2e/workflows/*-a11y.spec.ts` (dark-scheme scans)
- Possibly a Karma theme-smoke spec

No template or component-logic changes beyond test tweaks. Theming only; no behavioral change.

## Risks & mitigations
| Risk | Mitigation |
|---|---|
| Light-mode visual regression | Token light-side values mapped to today's exact hex; `ng build` + axe-light scans confirm parity. |
| connection-status documented contrast drift | Light values preserved verbatim; dark values axe-verified ≥4.5:1 (target ≥7:1). |
| `light-dark()` browser support | All evergreen browsers (Chrome 123+/FF 120+/Safari 17.5+); Playwright chromium/firefox/webkit all support it. |
| Material 19.2 `mat.theme()` dual-emission API uncertainty | Verify exact `theme-type: color-scheme` + palette-helper API against installed `@angular/material` during implementation before broad changes. |
| SCSS migration breaks the build | `angular.json` updated atomically with the rename; `ng build` gate. |

## Out of scope (explicit)
- Manual light/dark toggle + persistence (system-only chosen; future follow-up).
- Full one-off decorative-grey tokenization (Approach 3).
- Any non-color visual redesign (spacing, typography scale beyond Roboto default, component restructuring).
- GDPR German legal authoring (separate deferred item).

## References
- Audit: `docs/decisions/2026-06-16-a11y-ux-audit.md` (XCUT-07, also subsumes SHELL-09 hover-contrast).
- Skill: `angular-material-theming` (project-authoritative MD3 theming playbook).
- [Angular Material theming](https://material.angular.io/guide/theming) · [MDN `light-dark()`](https://developer.mozilla.org/en-US/docs/Web/CSS/color_value/light-dark)
- [WCAG 2.2 SC 1.4.3 / 1.4.11](https://www.w3.org/TR/WCAG22/)
