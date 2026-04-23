---
name: playwright-e2e-patterns
owner: "@seism0saurus"
description: Write or modify Playwright e2e tests for Glacier's workflow and page-component suites that run against the real dockerized Mastodon stack (never mocks). Covers page-object patterns, the four Playwright projects (chromium/firefox/webkit/killswitch/insecure), stable waits on connection-status, test-data discipline, and fallback/killswitch/insecure mode handling. TRIGGER when editing or creating files under frontend/e2e/**/*.spec.ts, page-objects under frontend/e2e/page-components/, playwright.config.ts, docker-compose.override.*.yaml, or when the user mentions Playwright, e2e, page object, workflow test, dockerized Mastodon, killswitch test, fallback test. SKIP for a11y-only tests (playwright-angular-a11y), unit tests (angular-karma-jasmine-testing), or backend tests.
---

# Playwright E2E Patterns for Glacier

Non-negotiable from project memory: **e2e tests run against the real dockerized Mastodon stack, never a mock**. Playwright setup:

- Config: `frontend/playwright.config.ts`
- Five projects: `chromium`, `firefox`, `webkit` (standard suite), `killswitch`, `insecure` (variant-specific)
- Default `BASE_URL`: `http://localhost:8080/` — the Spring Boot jar serving the Angular bundle in front of the Mastodon stack
- `fullyParallel: false`, `workers: 1` — tests assume serial execution, respect this
- Test organization:
  - `frontend/e2e/page-components/` — page-object-style component tests (footer, header, hashtag, legal)
  - `frontend/e2e/workflows/` — end-to-end user flows (subscriptions, toots, clear, fallback, fallback-ux, fallback-killswitch, fallback-insecure)

## Never mock Mastodon in e2e — the rule

The dockerized stack IS the test infrastructure. If a test setup needs a specific Mastodon state (followers, statuses, hashtags), arrange it via the stack's real API, not by intercepting HTTP. Mocking defeats the point: the whole test layer exists to catch integration bugs that unit tests cannot see.

If a test absolutely needs a stubbed response (for a very narrow failure-mode scenario — e.g., forcing a 500 from a specific Mastodon endpoint that can't be triggered otherwise), document why in the test file and gate it to a specific project.

## Page-object pattern (existing Glacier convention)

`page-components/` mirrors component-level concerns; `workflows/` stitches them together.

Page-object skeleton:
```typescript
import { Page, Locator, expect } from '@playwright/test';

export class HashtagPage {
  readonly input: Locator;
  readonly submit: Locator;
  readonly chips: Locator;

  constructor(private page: Page) {
    this.input = page.getByRole('textbox', { name: /hashtag/i });
    this.submit = page.getByRole('button', { name: /abonnieren/i });  // DE source!
    this.chips = page.getByTestId('hashtag-chip');
  }

  async goto() {
    await this.page.goto('/');
  }

  async subscribeTo(hashtag: string) {
    await this.input.fill(hashtag);
    await this.submit.click();
    await expect(this.chips.filter({ hasText: hashtag })).toBeVisible();
  }
}
```

Use `getByRole`, `getByLabel`, `getByText` — user-facing locators — over CSS selectors. They double as a11y verification.

## Stable waits on `connection-status`

Glacier's WebSocket state transitions (live → fallback → killswitch → offline) are visible via the `connection-status` component. Tests that depend on WebSocket state should wait explicitly:

```typescript
await expect(page.getByTestId('connection-status'))
    .toHaveAttribute('data-state', 'live', { timeout: 10_000 });

// Then perform actions that assume live streaming
```

`data-state` values match the frontend's mode enum. Don't rely on CSS classes or visible labels — they may change with i18n.

**Never `page.waitForTimeout(2000)`** as a substitute for stable-state waits. It's the #1 source of flakiness. Always wait for a specific DOM condition.

## The five Playwright projects — which to use when

| Project | testIgnore/testMatch | Purpose | Compose override |
|---|---|---|---|
| `chromium` | ignores `fallback-killswitch.spec.ts`, `fallback-insecure.spec.ts` | Standard flows | default |
| `firefox` / `webkit` | same ignores | Cross-browser parity | default |
| `killswitch` | matches only `fallback-killswitch.spec.ts` | Backend has `GLACIER_FALLBACK_ENABLED=false` | `docker-compose.override.killswitch.yaml` |
| `insecure` | matches only `fallback-insecure.spec.ts` | Backend on HTTP 8081 (plain, no TLS) | `docker-compose.override.insecure.yaml` |

Place each test in its intended project:
- `fallback-killswitch.spec.ts` → **must** be in `killswitch` project (otherwise `GLACIER_FALLBACK_ENABLED=true` default breaks the test).
- `fallback-insecure.spec.ts` → **must** be in `insecure` project (base URL differs).
- Default `fallback.spec.ts`, `fallback-ux.spec.ts` → live in chromium, verify the normal-mode fallback-to-polling transition.

Run a specific project:
```bash
cd frontend
PLAYWRIGHT_PROJECT=killswitch npx playwright test
```

## Env-dependent base URLs

```typescript
const baseURL = process.env['BASE_URL'] ?? 'http://localhost:8080/';
```

`playwright.config.ts` defaults this already. Don't hardcode URLs in spec files — they break when CI uses a different stack.

## Fixture-based setup for shared state

```typescript
import { test as base } from '@playwright/test';

type GlacierFixtures = {
  authenticatedPage: Page;
};

export const test = base.extend<GlacierFixtures>({
  authenticatedPage: async ({ page }, use) => {
    await page.goto('/');
    // perform login against dockerized Mastodon
    await use(page);
  },
});
```

For tests that need a specific Mastodon state (statuses queued, hashtags subscribed), the fixture should arrange the state via the real Mastodon API, not by DOM manipulation.

## Data cleanup between tests

With `fullyParallel: false` and `workers: 1`, tests run serially. Still: each test should leave the system in a known state (or arrange its own). Use `test.beforeEach` / `afterEach` for cleanup:

```typescript
test.afterEach(async ({ page }) => {
  // Clear localStorage, cookies, subscriptions from the clear.spec.ts workflow
  await page.goto('/');
  await page.evaluate(() => localStorage.clear());
});
```

Glacier has a dedicated `clear.spec.ts` workflow — reference its patterns.

## Handling network conditions

Playwright can simulate offline:
```typescript
await context.setOffline(true);
await expect(page.getByTestId('connection-status')).toHaveAttribute('data-state', 'offline');
await context.setOffline(false);
```

Useful for fallback-mode tests. But prefer the dedicated compose overrides (killswitch/insecure) for the deeper backend-state scenarios.

## Traces and screenshots

Default config:
```typescript
use: {
  screenshot: 'only-on-failure',
  trace: 'on-first-retry',
}
```

After a test failure, open the trace viewer:
```bash
npx playwright show-trace test-results/.../trace.zip
```

The HTML report (`playwright-report/`) is automatically generated (`open: 'never'`). For CI, use `--reporter=dot` for terse output; locally `--reporter=line` for readability (already configured via `process.env['CI']` detection).

## i18n — tests match German source

Just like unit tests (see `angular-karma-jasmine-testing`), Playwright tests run against the built Angular bundle which uses **German source**. If the tests run in a browser locale other than English (no `messages.<locale>.json` catalog loaded), the visible text is German.

```typescript
await expect(page.getByRole('button', { name: 'Abonnieren' })).toBeVisible();  // DE
```

If you test the English catalog specifically, load it by setting a browser locale context that triggers the runtime catalog load.

## Workflows vs page-components separation

- `page-components/`: verify a specific component renders and behaves correctly in isolation (header, footer, legal page, hashtag input).
- `workflows/`: stitch multiple components into user journeys (subscribe to hashtag → see toots arrive → unsubscribe → verify clean state).

New tests: decide which directory based on scope. Component isolation tests in `workflows/` are a smell.

## What Claude gets wrong without this skill

- Mocks Mastodon in e2e tests → violates the "never mock in e2e" rule, hides integration bugs.
- Uses `page.waitForTimeout(2000)` instead of waiting for a state indicator → flaky.
- Places `fallback-killswitch.spec.ts` in the `chromium` project → test runs without the `GLACIER_FALLBACK_ENABLED=false` override, backend still has fallback enabled, assertion fails.
- Hardcodes `http://localhost:8080/` in spec files → breaks in CI.
- Asserts English text that requires the runtime catalog — tests don't load it.
- Ignores trace output after failure → lost diagnostic information.
- Puts component-isolation tests in `workflows/` → wrong granularity, misleading name.

## References
- Config: `frontend/playwright.config.ts`
- Existing page objects: `frontend/e2e/page-components/` (footer, header, hashtag, legal, page)
- Existing workflows: `frontend/e2e/workflows/` (subscriptions, toots, clear, fallback*, fallback-ux, fallback-killswitch, fallback-insecure)
- Compose overrides: `infrastructure/docker-compose.override.killswitch.yaml`, `infrastructure/docker-compose.override.insecure.yaml`
- No-mock rule: project memory `feedback_testing_pyramid.md`
- A11y complement: `playwright-angular-a11y` skill
