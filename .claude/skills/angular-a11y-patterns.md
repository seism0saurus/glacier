---
name: angular-a11y-patterns
owner: "@seism0saurus"
description: Apply WCAG 2.2 AA patterns and Angular CDK a11y primitives when writing or modifying Angular components in the Glacier frontend. TRIGGER when creating or editing Angular templates (*.component.html) or components (*.component.ts), adding dialogs, menus, autocomplete, or custom interactive widgets, fixing axe-core or Lighthouse accessibility findings, or when the user mentions ARIA, screen reader, keyboard navigation, focus management, a11y, accessibility, barrierefreiheit. SKIP for pure backend Java code under src/main/java, build/CI config, or non-UI concerns.
---

# Angular A11y Patterns (WCAG 2.2 AA + Angular CDK a11y)

Glacier is a public-facing Mastodon client — **EAA/BFSG compliance applies from 2025-06-28**. Target is WCAG 2.2 Level AA. Every change to user-facing code should be verifiable against `@axe-core/playwright` (see the `playwright-angular-a11y` skill).

## CDK a11y primitives (`@angular/cdk/a11y`)

| Primitive | Use for |
|---|---|
| `cdkTrapFocus` (+ `cdkTrapFocusAutoCapture`) | Custom dialogs/overlays (MatDialog already handles this) |
| `LiveAnnouncer` (service) | Announce dynamic content changes to screen readers |
| `FocusMonitor` / `cdkMonitorFocus` | React to focus origin (keyboard vs mouse vs programmatic) |
| `FocusKeyManager` / `ActiveDescendantKeyManager` | Arrow-key navigation in custom lists/menus |
| `InteractivityChecker` | Query if an element is focusable/tabbable |
| `HighContrastModeDetector` | Detect Windows high-contrast mode, adjust styles |

## Icon-only buttons

`<mat-icon>` is decorative by default — a button containing only an icon has no accessible name unless you add one:

```html
<!-- Bad: screen reader announces "button" with no context -->
<button mat-icon-button (click)="close()"><mat-icon>close</mat-icon></button>

<!-- Good: aria-label on the button, aria-hidden on decorative icon -->
<button mat-icon-button
        i18n-aria-label="@@dialog.close.aria"
        aria-label="Dialog schließen"
        (click)="close()">
  <mat-icon aria-hidden="true">close</mat-icon>
</button>
```

For Glacier's i18n pattern, use `i18n-aria-label="@@id"` (see `angular-i18n-localize` skill).

## Dialogs & modals

**`MatDialog`** handles focus trap + focus restore automatically. Just open it via the service and the built-in template.

**Custom overlays** built on CDK Overlay (without MatDialog) need manual wiring:

```html
<div cdkTrapFocus [cdkTrapFocusAutoCapture]="true"
     role="dialog" aria-modal="true"
     [attr.aria-labelledby]="titleId">
  <h2 [id]="titleId" i18n="@@custom.dialog.title">Titel</h2>
  <!-- content -->
</div>
```

Required combination: `role="dialog"` + `aria-modal="true"` + `aria-labelledby` referencing the visible title's ID.

## Live region announcements

Dynamic content changes (snackbars, async completion, rate-limit messages, reconnect events) must be announced:

```typescript
import { LiveAnnouncer } from '@angular/cdk/a11y';
import { inject } from '@angular/core';

private announcer = inject(LiveAnnouncer);

onRateLimited(retryAfter: number) {
  this.announcer.announce(
    $localize`:rate.limited.announce@@rate.limited.announce:Zu viele Anfragen. Nächster Versuch in ${retryAfter} Sekunden.`,
    'polite'
  );
}
```

- `'polite'` (default): queued after current screen reader output. Use for most updates.
- `'assertive'`: interrupts immediately. Reserve for errors that block user action.

Glacier's `app.component.ts` already uses this pattern for snackbar announcements — follow that precedent.

## Form fields & errors

```html
<mat-form-field>
  <mat-label i18n="@@profile.name.label">Name</mat-label>
  <input matInput formControlName="name"
         [attr.aria-invalid]="nameCtrl.invalid && nameCtrl.touched"
         aria-describedby="name-error"/>
  @if (nameCtrl.hasError('required') && nameCtrl.touched) {
    <mat-error id="name-error" i18n="@@profile.name.error.required">
      Name ist erforderlich.
    </mat-error>
  }
</mat-form-field>
```

`mat-form-field` + `matInput` wires most ARIA links automatically. Custom form controls need explicit `aria-describedby` + error element `id`.

For full reactive-forms UX (when to show errors, `updateOn` choices), see `angular-reactive-forms-ux` skill.

## Keyboard navigation — the non-negotiable baseline

- Native `<button>`, `<a href>`, `<input>` get keyboard support for free. **Never** `<div (click)>` as a substitute — it breaks Tab/Enter/Space.
- Arrow-key nav in custom lists or menus: `FocusKeyManager`.
- Page entry point for keyboard users — skip-link as first focusable element:

```html
<a class="skip-link" href="#main"
   i18n="@@a11y.skip.main">Zum Hauptinhalt springen</a>
<!-- … header/nav … -->
<main id="main" tabindex="-1">…</main>
```

```scss
.skip-link {
  position: absolute;
  top: -40px;
  &:focus { top: 0; }  /* visible only when focused */
}
```

## Focus-visible styling — never strip without replacement

```scss
// Bad — breaks keyboard navigation for everyone
:focus { outline: none; }

// Good — hide for mouse, keep for keyboard
:focus-visible {
  outline: 2px solid var(--mat-sys-primary);
  outline-offset: 2px;
}
```

## Color contrast

Pull from MD3 tokens — they are contrast-paired:
- Body text on background: `color: var(--mat-sys-on-surface)` on `background: var(--mat-sys-surface)` → ≥ 4.5:1
- De-emphasized text: `var(--mat-sys-on-surface-variant)` → still AA-compliant
- Links/interactive: never below 4.5:1; for small text 4.5:1, large text (18pt+ or 14pt+bold) 3:1 is OK.

Do not hand-mix grays for interactive states — always use the system tokens.

## Semantic HTML — use the right element

| Avoid | Prefer |
|---|---|
| `<div (click)>` | `<button type="button">` |
| `<span role="link">` | `<a href>` (or `<a [routerLink]>`) |
| `<div>Abschnitt</div>` as heading | `<h1>`–`<h6>` matching document outline |
| `<ul>` with non-`<li>` children | only `<li>` inside `<ul>`/`<ol>` |
| `<table>` for layout | CSS Grid / Flexbox |
| `<img>` without `alt` | `<img alt="…">` (empty `alt=""` for decorative) |

## Dynamic state — announce changes, not just render them

A visually updated `<div>` is invisible to screen readers unless it's a live region:

```html
<div [attr.role]="isLoading ? null : 'status'" aria-live="polite">
  @if (isLoading) {
    <!-- spinner -->
  } @else {
    <span i18n="@@search.results.count">
      {count, plural, =0 {Keine Treffer} one {1 Treffer} other {{{count}} Treffer}}
    </span>
  }
</div>
```

Or use `LiveAnnouncer` for ephemeral announcements (don't need persistent text in DOM).

## Check before considering a11y done

```bash
cd frontend && npx playwright test --project=chromium --grep @a11y
```

(Requires e2e tests tagged `@a11y` using `@axe-core/playwright` — see `playwright-angular-a11y` skill.)

## What Claude gets wrong without this skill

- Uses `<div (click)>` or `<span role="button">` where `<button>` belongs.
- Adds `role="dialog"` without `aria-modal="true"` or `aria-labelledby`.
- Hardcodes `outline: none` without adding `:focus-visible` styling.
- Forgets `aria-label` / `aria-labelledby` on icon-only buttons.
- Uses `alert()` or `console.log` for errors meant to be surfaced to users — invisible to screen readers.
- Mixes `role="alert"` with dismissable notifications — they fire on every DOM insertion; prefer `LiveAnnouncer`.

## References
- CDK a11y API: https://material.angular.io/cdk/a11y
- WCAG 2.2: https://www.w3.org/TR/WCAG22/
- Glacier live-announcement precedent: `frontend/src/app/app.component.ts` (LiveAnnouncer usage)
