---
name: playwright-angular-a11y
owner: "@seism0saurus"
description: Write or extend Playwright e2e tests that assert accessibility using @axe-core/playwright in the Glacier frontend test suite. TRIGGER when editing or creating files under frontend/e2e/**/*.spec.ts, modifying frontend/playwright.config.ts, adding a11y-test scaffolding, or when the user asks about Playwright accessibility testing, axe violations, automated a11y checks, @axe-core/playwright, or AxeBuilder. SKIP for Karma/Jasmine unit tests, backend integration tests, or manual a11y review without automation.
---

# Playwright + @axe-core/playwright in Glacier

Glacier's Playwright setup:
- Config: `frontend/playwright.config.ts`
- Five projects: `chromium`, `firefox`, `webkit` (standard suite), `killswitch`, `insecure` (backend-variant-specific)
- Tests live in `frontend/e2e/` (with `fullyParallel: false`, `workers: 1`)
- Default `BASE_URL`: `http://localhost:8080/` — tests run against the dockerized Mastodon stack
- axe binding: `@axe-core/playwright@4.10.2` already installed

## Minimal a11y assertion

```typescript
import { test, expect } from '@playwright/test';
import AxeBuilder from '@axe-core/playwright';

test('@a11y home page has no detectable violations', async ({ page }) => {
  await page.goto('/');
  await page.waitForLoadState('networkidle');

  const results = await new AxeBuilder({ page })
    .withTags(['wcag2a', 'wcag2aa', 'wcag21aa', 'wcag22aa'])
    .analyze();

  expect(results.violations, formatViolations(results.violations)).toEqual([]);
});
```

Tag the test with `@a11y` in the title so it can be filtered in CI.

## Readable violation output

Default violation output is a wall of JSON. Provide a helper:

```typescript
import { Result } from 'axe-core';

export function formatViolations(violations: Result[]): string {
  if (violations.length === 0) return '';
  return '\nA11y violations:\n' + violations.map(v =>
    `  - [${v.impact ?? '?'}] ${v.id}: ${v.help}\n` +
    `    ${v.nodes.length} node(s) affected\n` +
    `    ${v.helpUrl}`
  ).join('\n');
}
```

Place this helper in `frontend/e2e/support/a11y.ts` or similar so every test uses the same formatting.

## Per-route scans

```typescript
import { test, expect } from '@playwright/test';
import AxeBuilder from '@axe-core/playwright';
import { formatViolations } from './support/a11y';

const routes = ['/', '/about', '/impressum'] as const;

for (const path of routes) {
  test(`@a11y ${path} passes axe`, async ({ page }) => {
    await page.goto(path);
    await page.waitForLoadState('networkidle');
    const results = await new AxeBuilder({ page })
      .withTags(['wcag2aa', 'wcag22aa'])
      .analyze();
    expect(results.violations, formatViolations(results.violations)).toEqual([]);
  });
}
```

## Scoping scans

Run axe on a specific region (useful when one area has known-unfixable third-party content):

```typescript
const results = await new AxeBuilder({ page })
  .include('#main-content')
  .exclude('[data-third-party]')
  .analyze();
```

## Excluding violations with justification

When excluding, prefer `disableRules` (rule-level) over `exclude` (selector) — rule-level is narrower. **Always** comment why:

```typescript
const results = await new AxeBuilder({ page })
  // color-contrast skipped on Mastodon-hosted embed — out of our styling control
  .disableRules(['color-contrast'])
  .exclude('iframe[src*="mastodon"]')
  .analyze();
```

Without a comment, exclusions accumulate and nobody knows which were justified.

## Impact thresholds — progressive tightening

During initial rollout, fail only on `serious` + `critical`:

```typescript
const blocking = results.violations.filter(v =>
  v.impact === 'critical' || v.impact === 'serious'
);
expect(blocking, formatViolations(blocking)).toEqual([]);
```

Once the baseline is clean, drop to `moderate`+, then `minor`+. Track `results.violations.length` over time to avoid regression on lower-impact issues.

## Asserting focus behavior (axe can't see this)

axe verifies structural a11y. Keyboard/focus behavior needs explicit assertions:

```typescript
test('@a11y dialog traps focus', async ({ page }) => {
  await page.goto('/');
  await page.getByRole('button', { name: 'Einstellungen' }).click();
  await expect(page.getByRole('dialog')).toBeVisible();

  // Focus must be inside the dialog
  const insideDialog = await page.evaluate(() =>
    !!document.activeElement?.closest('[role="dialog"]'));
  expect(insideDialog).toBe(true);

  // Tab through — focus must not escape the dialog
  for (let i = 0; i < 10; i++) {
    await page.keyboard.press('Tab');
  }
  const stillInside = await page.evaluate(() =>
    !!document.activeElement?.closest('[role="dialog"]'));
  expect(stillInside).toBe(true);

  // Escape must close and return focus to the trigger
  await page.keyboard.press('Escape');
  await expect(page.getByRole('dialog')).toBeHidden();
  await expect(page.getByRole('button', { name: 'Einstellungen' })).toBeFocused();
});
```

## Keyboard-reachability test

```typescript
test('@a11y main CTA reachable by keyboard', async ({ page }) => {
  await page.goto('/');
  for (let i = 0; i < 30; i++) {
    await page.keyboard.press('Tab');
    const focused = await page.evaluate(() => ({
      tag: document.activeElement?.tagName,
      text: document.activeElement?.textContent?.trim(),
    }));
    if (focused.text === 'Abonnieren') return;
  }
  throw new Error('Subscribe button not reachable within 30 Tabs');
});
```

## Waiting for stable state before scanning

Glacier has streaming UI (WebSocket → fallback → killswitch). Don't scan a loading state — axe will see transient issues. Wait for a stable indicator:

```typescript
await expect(page.getByTestId('connection-status'))
  .toHaveAttribute('data-state', /^(live|fallback)$/);
// Only now scan — state has stabilized
const results = await new AxeBuilder({ page }).analyze();
```

## Project-specific considerations

- **Run a11y tests only in `chromium`** — running in firefox + webkit + chromium triples cost with marginal additional signal. The axe rule engine is browser-agnostic.
- **Avoid `killswitch`/`insecure` projects for a11y** — those test backend fallbacks and don't exercise different UI paths.
- **Static routes first** (`/`, `/about`) — no Mastodon stack dependency. Add authenticated-route a11y tests only once dockerized backend fixtures are reliable.

## CI integration

Add an a11y-specific npm script for fast feedback:

```json
// frontend/package.json (sketch)
"scripts": {
  "e2e:a11y": "playwright test --project=chromium --grep @a11y"
}
```

Run locally:
```bash
cd frontend && npm run e2e:a11y
```

## What Claude gets wrong without this skill

- Calls `.analyze()` without `.withTags([...])` → scans all axe rules including experimental/best-practice ones that may not be achievable, producing noise.
- Uses `toHaveLength(0)` instead of `toEqual([])` with a message → failure output is unhelpful.
- Forgets `waitForLoadState('networkidle')` or a stable-state assertion → axe scans a half-rendered page.
- Proposes `pa11y`, `Lighthouse`, or `puppeteer`-based a11y tooling → ignores that `@axe-core/playwright` is already installed and matches the existing test stack.
- Runs a11y tests across all five Playwright projects → 5× cost, same findings.
- Exclusions without comments → entropy over time, no reviewer knows what's been silenced.

## References
- Playwright config: `frontend/playwright.config.ts`
- Existing e2e tests: `frontend/e2e/*.spec.ts` — follow their style for imports, test naming, baseURL usage
- @axe-core/playwright docs: https://github.com/dequelabs/axe-core-npm/tree/develop/packages/playwright
- axe-core rule reference: https://github.com/dequelabs/axe-core/blob/develop/doc/rule-descriptions.md
