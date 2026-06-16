/**
 * Accessibility E2E tests for the Hashtag Prune on Removal feature.
 * Tests A1–A5 from the Phase 1 test plan.
 *
 * Project: chromium — axe-core scans are chromium-only per D-20 and the
 * playwright-angular-a11y skill (browser-agnostic engine, 3× cost saving).
 *
 * Requires the full dockerized Mastodon stack (see CLAUDE.md, infrastructure/).
 *
 * A1 — role="feed" present on toot list.
 * A2 — Each toot has role="article".
 * A3 — Live region is role="status" with aria-live="polite".
 * A4 — No axe violations on wall with 3 toots.
 * A5 — No axe violations on empty wall (after cancel-all).
 */

import {expect, test} from '@playwright/test';
import {assertNoWcag22AaViolationsLightAndDark, runAxeOnlyInChromium} from '../helper/a11y';
import {createTextToot} from '../helper/mastodon-client';

const glacier_handle = process.env['GLACIER_HANDLE'] || '@glacier_e2e_test@proxy';

test.describe('A1–A5: Prune a11y (axe-core WCAG 2.2 AA)', () => {

  test.beforeEach(async ({page}) => {
    await page.goto('/');
    await page.evaluate(() => localStorage.clear());
    await page.reload();
  });

  // ---------------------------------------------------------------------------
  // A1: role="feed" present on toot list
  // ---------------------------------------------------------------------------
  test('A1: wall container has role="feed"', async ({page}) => {
    // The feed element should always be present, even with 0 toots
    const feed = page.locator('[role="feed"]');
    await expect(feed).toBeAttached();
    await expect(feed).toHaveAttribute('aria-label', /.+/);  // non-empty aria-label
  });

  // ---------------------------------------------------------------------------
  // A2: Each toot has role="article"
  // ---------------------------------------------------------------------------
  test('A2: each toot is wrapped in role="article"', async ({page}) => {
    // Subscribe and get a toot so we have articles to inspect
    await page.getByPlaceholder('New hashtag').fill('glacierE2Etest');
    await page.getByPlaceholder('New hashtag').press('Enter');
    await expect(page.locator("[id='hashtag-glaciere2etest']")).toBeVisible();

    await createTextToot(`Hi ${glacier_handle}.\nA2 article role test toot.\n#glacierE2Etest`);
    await expect(page.locator('app-toot')).toHaveCount(1);

    // Verify every visible toot is inside a role="article" element
    const articles = page.locator('article[role="article"]');
    const count = await articles.count();
    expect(count).toBeGreaterThan(0);

    for (let i = 0; i < count; i++) {
      const role = await articles.nth(i).getAttribute('role');
      expect(role).toBe('article');
    }
  });

  // ---------------------------------------------------------------------------
  // A3: Live region is role="status" with aria-live="polite"
  // ---------------------------------------------------------------------------
  test('A3: live region has role="status" and aria-live="polite"', async ({page}) => {
    // The live region is always in the DOM (sibling to role="feed")
    const liveRegion = page.locator('[role="status"]');
    await expect(liveRegion).toBeAttached();
    await expect(liveRegion).toHaveAttribute('aria-live', 'polite');
    await expect(liveRegion).toHaveAttribute('aria-atomic', 'true');
  });

  // ---------------------------------------------------------------------------
  // A4: No axe violations on wall with 3 toots
  // ---------------------------------------------------------------------------
  test('A4: no serious/critical axe violations on wall with toots', async ({page, browserName}) => {
    // Subscribe and get 3 toots (one per post — Mastodon deduplication applies)
    await page.getByPlaceholder('New hashtag').fill('glacierE2Etest');
    await page.getByPlaceholder('New hashtag').press('Enter');
    await expect(page.locator("[id='hashtag-glaciere2etest']")).toBeVisible();

    await createTextToot(`Hi ${glacier_handle}.\nA4 axe toot 1.\n#glacierE2Etest`);
    await createTextToot(`Hi ${glacier_handle}.\nA4 axe toot 2.\n#glacierE2Etest`);
    await createTextToot(`Hi ${glacier_handle}.\nA4 axe toot 3.\n#glacierE2Etest`);
    await expect(page.locator('app-toot')).toHaveCount(3);

    // Run axe-core in light and emulated dark (XCUT-07: dual color-scheme gate)
    await runAxeOnlyInChromium(browserName, async () => {
      await assertNoWcag22AaViolationsLightAndDark(page);
    });
  });

  // ---------------------------------------------------------------------------
  // A5: No axe violations on empty wall (after cancel-all)
  // ---------------------------------------------------------------------------
  test('A5: no serious/critical axe violations on empty wall after cancel-all', async ({page, browserName}) => {
    // Subscribe to a hashtag and get a toot
    await page.getByPlaceholder('New hashtag').fill('glacierE2Etest');
    await page.getByPlaceholder('New hashtag').press('Enter');
    await expect(page.locator("[id='hashtag-glaciere2etest']")).toBeVisible();

    await createTextToot(`Hi ${glacier_handle}.\nA5 axe empty wall toot.\n#glacierE2Etest`);
    await expect(page.locator('app-toot')).toHaveCount(1);

    // Cancel all — wall becomes empty
    await page.locator("[id='cancel-all']").click();
    await expect(page.locator("[id='hashtag-glaciere2etest']")).toHaveCount(0);
    await expect(page.locator('app-toot')).toHaveCount(0);

    // Run axe-core in light and emulated dark (XCUT-07: dual color-scheme gate)
    await runAxeOnlyInChromium(browserName, async () => {
      await assertNoWcag22AaViolationsLightAndDark(page);
    });
  });

});
