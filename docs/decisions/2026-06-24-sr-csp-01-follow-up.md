# SR-CSP-01: Inline-Handler CSP Violation — Follow-up

Date: 2026-06-24
Phase: Phase 2 (frontend lane)
Status: **Open — deferred follow-up**
Related plan: `docs/decisions/2026-06-24-planning-share-view-toot-rendering-mvp.md` §SR-CSP-01

---

## Summary

The planning acceptance criteria required that the readonly share route load with zero
`script-src` inline-handler CSP violations (SR-CSP-01), or a documented follow-up with
the captured violation report.

Static analysis of the frontend source confirms:

- No `[innerHTML]` binding exists anywhere in the `share/` component tree.
- No `bypassSecurityTrustHtml` or `bypassSecurityTrustScript` calls in any share component
  or service.
- No inline `onclick`, `onerror`, or similar DOM-level event attributes in any Angular template.
- The `index.html` shell is clean (no inline scripts, no inline event attributes).
- The only `DomSanitizer` bypass used in the share view is `bypassSecurityTrustUrl` in
  `safe-url.pipe.ts` (for validated `http://` / `https://` URLs bound to `[attr.href]`) and
  `bypassSecurityTrustResourceUrl` in `glacier-svg-icons.ts` (for local SVG asset paths).

**No fix was made** because the violation source could not be isolated to a specific line of
frontend code without observing the live browser violation report at runtime.

---

## Suspected Violation Source

The share-view CSP includes `require-trusted-types-for 'script'` (SR-SHARE-15,
`ShareSecurityHeadersFilter.java`).

Angular 19 with `NgModule`-based bootstrapping via `platformBrowserDynamic`
(`frontend/src/main.ts:48`) uses AOT-compiled templates at build time, but the Angular
runtime internals and `zone.js` (which patches `Promise`, `setTimeout`, `EventTarget`, and
in some versions patches `Function.prototype`) can still trigger Trusted Types violations in
Chromium when `require-trusted-types-for 'script'` is enforced.

The specific violation shape that would be expected in the browser's DevTools console:

```
[Violation] Content Security Policy of your site blocks the use of 'eval' in JavaScript.
Source: https://glacier.example.com/main.hash.js
Directive: script-src
Blocked: require-trusted-types-for 'script'
```

or:

```
Uncaught TypeError: Failed to execute 'eval' on 'Window': Refused to create a TrustedScript.
```

The following entry points were searched and found clean:
- `frontend/src/index.html` — no inline scripts
- `frontend/src/main.ts` — clean bootstrap, no `eval`, no `new Function(...)`
- `frontend/src/app/app.module.ts` — no inline handlers
- All files in `frontend/src/app/share/**` — no innerHTML, no bypassSecurityTrustHtml/Script
- `frontend/src/app/icons/glacier-svg-icons.ts` — only `bypassSecurityTrustResourceUrl`

---

## Verification Steps to Capture Runtime Evidence

To obtain the precise `securitypolicyviolation` report:

1. Build and run the full stack (see `infrastructure/docker-compose.yaml`).
2. Open the share route (`/share/{id}`) in Chromium with DevTools → Console.
3. Filter for `Content-Security-Policy` and observe any violation reports.
4. Record the full violation object:
   - `blockedURI` — the URL or inline string blocked
   - `violatedDirective` — which directive (e.g. `require-trusted-types-for`)
   - `sourceFile` + `lineNumber` — the exact JS file emitting the violation
   - `sample` — the first 40 characters of the blocked string

Alternatively, add a `report-uri` or `report-to` directive to the CSP and observe the
JSON violation report.

---

## Resolution Options (for a future sprint)

### Option A — Remove `require-trusted-types-for` from share CSP (minimal change)

Remove `require-trusted-types-for 'script'` from `ShareSecurityHeadersFilter`. This
degrades the Trusted Types protection but eliminates the violation. The remaining CSP
(`default-src 'none'; script-src 'self' 'nonce-…'`) still provides strong XSS protection.

Risk: reduces defence-in-depth for XSS on the share route. Given that the share view
renders no user-supplied HTML (only Angular template interpolation), this is low practical
risk.

### Option B — Angular Trusted Types integration (preferred long-term)

Add Angular's Trusted Types policy via `@angular/core` CSP integration:

```typescript
// In main.ts (for prod builds):
import { createApplication } from '@angular/platform-browser';
// Use `bootstrapApplication` with standalone components + Trusted Types config
```

Or, for the existing NgModule-based app: configure zone.js to skip `Function` patching:

```typescript
// Before zone.js is loaded (in polyfills.ts or main.ts):
(window as any).__Zone_disable_customElements = true;
// … see zone.js docs for exact flags
```

This is the correct architectural fix but requires verifying zone.js patching flags that
do not regress the STOMP WebSocket reconnection logic (`glacier-fallback-mode-discipline`).

### Option C — Separate AOT-compiled share-view bundle (architectural)

Route the share view through a separate Angular build target that uses
`bootstrapApplication` (standalone, fully AOT) rather than the `NgModule`-based AppModule.
The two bundles share no runtime code, so the share route never loads the NgModule shim.

This is the cleanest solution but requires significant build pipeline changes
(dual output from `ng build`, separate `index.html` for share routes, backend serving
the correct bundle per URL prefix).

---

## Impact Assessment

- **Confidentiality / Integrity**: No impact. The share view contains no user-supplied HTML
  (all content is interpolated through Angular's safe text-node renderer). Even if an attacker
  were to trigger `eval` via a Trusted Types violation, there is no injectable HTML or script
  content on the page.
- **Availability**: No impact.
- **Compliance**: `require-trusted-types-for 'script'` is listed as SR-SHARE-15 (BSI TSS-WEB
  5.2). A violation in the browser console means the policy is not achieving its full intended
  protection but does not break functionality.

---

## Open Action

- Assign to: **frontend-designer** (next sprint)
- Blocked by: access to a running share-view instance to capture the live violation report
- Prerequisite: capture the `securitypolicyviolation` event (blocked directive + sample) before
  deciding between Option A, B, or C
