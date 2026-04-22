---
name: angular-material-theming
owner: "@seism0saurus"
description: Apply Angular Material 19 (Material Design 3) theming patterns when customizing colors, typography, density, or dark mode in the Glacier frontend. TRIGGER when editing frontend/src/styles.scss, frontend/**/*-theme.scss, files under frontend/src/app/**/*.component.scss that contain theme tokens or Material mixins, or when the user mentions Material colors, dark mode, brand theming, palette, density, typography config, mat.theme, mat-sys tokens, M3, or Material Design 3. SKIP for backend Java/Spring code, non-UI changes, or pure Angular logic without styling concerns.
---

# Angular Material 19 Theming (Material Design 3)

Angular Material 19 is built on **Material Design 3** using a token system based on CSS custom properties (`--mat-sys-*`). The old M2 API (`mat.define-palette()`, `mat.define-light-theme()`, `mat.core-theme()`) is **deprecated** — use the M3 `mat.theme()` mixin.

## Minimum working theme in `frontend/src/styles.scss`

```scss
@use '@angular/material' as mat;

html {
  color-scheme: light dark;  // enables native light-dark() support

  @include mat.theme((
    color: (
      primary: mat.$violet-palette,
      tertiary: mat.$orange-palette,
    ),
    typography: Roboto,
    density: 0,
  ));
}
```

`mat.theme()` emits the full MD3 system-token set (`--mat-sys-primary`, `--mat-sys-on-primary`, `--mat-sys-surface-container`, etc.) plus component base styles. Do **not** also call `mat.core-theme()` or `mat.all-component-themes()` — that duplicates token emissions and produces oversized CSS.

## Dark mode

Prefer **system-preference** via `color-scheme: light dark` + `light-dark()`:

```scss
html { color-scheme: light dark; }

.brand-banner {
  background: light-dark(#eee, #222);
  color: light-dark(#111, #eee);
}
```

Manual override (user toggle):

```scss
html.theme-dark { color-scheme: dark; }
html.theme-light { color-scheme: light; }
```

Avoid `@media (prefers-color-scheme: dark) { /* duplicate theme */ }` — the MD3 tokens already adapt via `color-scheme`.

## Component-level overrides (scoped, never global)

```scss
@use '@angular/material' as mat;

.glacier-toot-card {
  @include mat.card-overrides((
    elevated-container-color: var(--mat-sys-surface-container-high),
    elevated-container-shape: 12px,
  ));
}
```

Always scope overrides under a CSS selector — unscoped they leak into every `<mat-card>` in the app.

## Brand palette

If the brand color doesn't match a prebuilt palette (`$violet-palette`, `$azure-palette`, `$rose-palette`, `$cyan-palette`, `$magenta-palette`, `$orange-palette`, `$spring-green-palette`, `$red-palette`), generate a custom one:

```bash
cd frontend && ng generate @angular/material:m3-theme
```

This writes `_theme-colors.scss` with `$primary-palette`/`$tertiary-palette` you then pass to `mat.theme()`.

## Typography

```scss
@include mat.theme((
  typography: (
    plain-family: '"Inter", system-ui, sans-serif',
    brand-family: '"Inter Display", system-ui, sans-serif',
    bold-weight: 600,
    medium-weight: 500,
  ),
));
```

Consume in app CSS via system tokens:
```scss
.toot-header { font: var(--mat-sys-title-medium); }
.toot-body { font: var(--mat-sys-body-medium); }
```

## Density

`density: 0` (default) = comfortable; `-1` through `-5` increasingly compact. For data-dense lists (timelines, tables) `-2` is a reasonable target. `-5` is minimum — below that Material components break.

## System tokens cheat-sheet (most-used)

| Token | Use |
|---|---|
| `--mat-sys-primary` / `--mat-sys-on-primary` | Primary actions, branded surfaces |
| `--mat-sys-secondary` / `--mat-sys-on-secondary` | Secondary actions |
| `--mat-sys-tertiary` / `--mat-sys-on-tertiary` | Accent/highlight |
| `--mat-sys-surface` / `--mat-sys-on-surface` | Body background/text |
| `--mat-sys-surface-container` / `--mat-sys-surface-container-high` | Card/dialog backgrounds |
| `--mat-sys-on-surface-variant` | Secondary text (still passes AA contrast) |
| `--mat-sys-outline` / `--mat-sys-outline-variant` | Borders, dividers |
| `--mat-sys-error` / `--mat-sys-on-error` | Error states |

All are contrast-paired by design — `--mat-sys-on-X` is the safe foreground color for `--mat-sys-X` background.

## What Claude gets wrong without this skill

- Reaches for **M2 API** (`mat.define-palette`, `mat.define-light-theme`, `mat.core-theme`) — deprecated in v19, won't compose with `mat.theme()`.
- Duplicates token emissions by calling `mat.all-component-themes()` alongside `mat.theme()`.
- Hardcodes hex colors in component SCSS instead of `var(--mat-sys-*)` — breaks dark mode.
- Writes `@media (prefers-color-scheme: dark) { /* full override */ }` instead of relying on `color-scheme: light dark`.
- Sets `density: -6` or `-7` — out of range, Material caps at `-5`.

## References
- Base theme file: `frontend/src/styles.scss`
- M3 token definitions: `node_modules/@angular/material/core/tokens/_m3.scss`
- Official guide: https://material.angular.io/guide/theming
