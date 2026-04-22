---
name: angular-i18n-localize
owner: "@seism0saurus"
description: Handle i18n in Glacier's Angular frontend using @angular/localize with the project's custom runtime JSON-catalog pattern. German is the source language. TRIGGER when adding or modifying user-visible strings in Angular templates (*.html) or TS code, editing frontend/src/assets/i18n/messages*.json, using $localize or i18n= attributes, or when the user mentions translation, i18n, localize, Übersetzung, messages.en.json, mehrsprachig. SKIP for backend string handling (Java/Spring), log messages, or non-UI strings.
---

# i18n in Glacier (runtime $localize catalog, German source)

## Glacier's i18n architecture — READ THIS FIRST

**German is the compile-time source language.** Other locales (currently English) are loaded **at runtime** from `frontend/src/assets/i18n/messages.<locale>.json` via a custom loader in `frontend/src/main.ts`.

The standard Angular AOT `--localize` extraction pipeline is **not** used. Do **not** propose `ng extract-i18n` or add `i18n`/`localize` config to `angular.json` without explicit approval — those are architectural changes, not a bugfix.

Contract:
- Source code contains German strings as default text.
- Every string is marked with `$localize` (TS) or `i18n=` (HTML).
- Every string has an **explicit custom ID** (`@@id`) matching a key in `messages.en.json`.
- At runtime, `main.ts` fetches the catalog and the `@angular/localize` runtime replaces marked strings with translations.

## Marking strings — HTML templates

```html
<!-- format: i18n="meaning|description@@explicit-id" -->
<span i18n="session.expired.banner@@session.expired.banner">
  Ihre Sitzung ist abgelaufen.
</span>

<!-- Attribute values: prefix with i18n- -->
<button i18n-aria-label="@@dialog.close.aria"
        aria-label="Dialog schließen">…</button>

<!-- title, placeholder, alt, etc. -->
<img i18n-alt="@@avatar.alt" alt="Profilbild" [src]="avatarUrl">
```

Rules:
- **Explicit `@@id` is mandatory.** Without it, Angular generates a hash from the source text — any edit to the German source invalidates all existing translations.
- The `meaning|description` part before `@@` is optional (often left empty for Glacier). Use it if the same string has different meanings in different contexts that deserve different translations.

## Marking strings — TypeScript

```typescript
const msg = $localize`:gap.snackbar.message@@gap.snackbar.message:Einige ältere Toots könnten fehlen.`;
```

Format: `` $localize`:meaning|description@@explicit-id:default-text` ``

The trailing `:default-text` is what users see when no catalog is loaded. For Glacier, this is always the German source text.

### Interpolation

```typescript
const msg = $localize`:rate.limited.retry.popover@@rate.limited.retry.popover:Nächster Versuch in ${retryAfterSeconds}s`;
```

In the catalog JSON, interpolations are represented with placeholder tokens. Inspect `messages.en.json` for the exact placeholder convention in use before adding a new interpolated key.

## Adding a new string — checklist

1. Write the string in German as the source-code default, with an explicit `@@id`:
   - Template: `<span i18n="@@feature.area.key">Deutscher Text</span>`
   - TS: `` $localize`:@@feature.area.key:Deutscher Text` ``
2. Add the corresponding key to `frontend/src/assets/i18n/messages.en.json` with the English translation.
3. If interpolating, match the placeholder format used elsewhere in the catalog.
4. Never ship a change that adds an `@@id` in source without adding it to the catalog — the EN user would see untranslated German.

## Key naming convention (as established in Glacier's catalog)

Dotted, domain-grouped:
- `connection.websocket.label`, `connection.probing.detail`, `connection.fallback.label`
- `gap.snackbar.message`, `gap.snackbar.dismiss`
- `rate.limited.snackbar`, `rate.limited.retry.popover`
- `session.expired.banner`, `session.expired.reload`

Follow the existing grouping. Don't invent flat keys (`closeButton`) or route-based keys (`page.home.title`) if the semantic grouping differs from "feature.area.element".

## ICU plurals & selects

For count-dependent strings (always an issue with "0/1/N items"):

```typescript
$localize`:@@timeline.unread.count:{count, plural, =0 {Keine ungelesenen Toots} one {Ein ungelesener Toot} other {{{count}} ungelesene Toots}}`;
```

Template form:
```html
<span i18n="@@timeline.unread.count">
  {count, plural, =0 {Keine ungelesenen Toots} one {Ein ungelesener Toot} other {{{count}} ungelesene Toots}}
</span>
```

ICU categories relevant for Glacier's current locales (de + en): `=0`, `one`, `other` are enough. Languages like Russian or Polish need `few`/`many`/`zero` — add them when those locales arrive, not preemptively.

Select (gender, status, etc.):
```html
{status, select, online {Online} offline {Offline} away {Abwesend}}
```

## Text-expansion planning

German source is often 15-25% longer than English. For English translations this is not a layout issue (they usually shrink). **But** future locales (es, fr, pt) tend to match or exceed German length — design layouts that tolerate +30% expansion without wrapping badly or truncating.

## Dates, numbers, currencies

Use Angular pipes — they follow the current `LOCALE_ID`:

```html
{{ tootDate | date:'medium' }}
{{ followerCount | number }}
{{ boostCount | number:'1.0-0' }}
```

Glacier's runtime loader should configure `LOCALE_ID` based on detected browser locale. Do not hardcode `de-DE` or `en-US` at a component level.

## Extracting new keys — manual workflow

Glacier does **not** run `ng extract-i18n`. Adding a new translatable string is a two-file change:

1. Source file (`.ts` or `.html`) — add `$localize` / `i18n="@@id"`.
2. `frontend/src/assets/i18n/messages.en.json` — add key + English value.

**Improvement opportunity**: a CI lint that greps `@@id` occurrences across source files, diffs against `messages.en.json` keys, and fails on mismatches. Not yet in place (2026-04).

## What Claude gets wrong without this skill

- Proposes `ng extract-i18n` and `--localize` AOT build config — doesn't match Glacier's runtime-loader setup.
- Forgets the explicit `@@id` — falls back to hash-based IDs that silently break translation matching whenever the source text changes.
- Adds an `@@id` in source but forgets to update `messages.en.json` — EN users see untranslated German.
- Hardcodes German strings in TS files for "internal" messages that are actually user-visible.
- Uses `ngx-translate`'s `| translate` pipe syntax — Glacier does not depend on `ngx-translate`.
- Invents new key namespaces instead of following the existing dotted-domain convention.

## References
- Runtime loader: `frontend/src/main.ts` (fetch + locale setup, approx. lines 30–40)
- English catalog: `frontend/src/assets/i18n/messages.en.json`
- Example usages in-repo:
  - Templates: `frontend/src/app/app.component.html`, `frontend/src/app/connection-status/connection-status.component.html`
  - TS: `frontend/src/app/app.component.ts` (multiple `$localize` calls around lines 100–125)
- `@angular/localize` docs: https://angular.dev/guide/i18n/localize
