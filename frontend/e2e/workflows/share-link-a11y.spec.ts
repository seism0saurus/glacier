/**
 * E2E a11y spec for the share-link readonly view (a11y project — chromium only).
 *
 * XCUT-09: Refactored the axe scan to use the shared `assertNoWcag22AaViolations`
 * helper from `helper/a11y.ts` wrapped in `runAxeOnlyInChromium`.  The previous
 * inline AxeBuilder usage ran in all five Playwright projects (3× wasted cost)
 * and duplicated the tag list and impact filter from the shared helper.
 *
 * This file matches the glob pattern `**\/*-a11y.spec.ts` so it is routed
 * exclusively to the
 * `a11y` project (chromium), consistent with D-20 and all other axe scans.
 */

import { test, expect } from '@playwright/test';
import { assertNoWcag22AaViolationsLightAndDark, runAxeOnlyInChromium } from '../helper/a11y';

const SHARE_TEST_URL = process.env['SHARE_TEST_URL'] || '/share/test-share-id-placeholder';

test.describe('Share link — accessibility', () => {

  /**
   * XCUT-09: Replaced inline AxeBuilder + withTags() + manual filter with
   * assertNoWcag22AaViolations() wrapped in runAxeOnlyInChromium().
   * Single source of truth; no longer runs in firefox/webkit/killswitch/insecure.
   */
  test('@a11y readonly wall has zero critical/serious axe violations',
    async ({ page, browserName }) => {
      await page.goto(SHARE_TEST_URL);
      await expect(page.getByTestId('share-feed')).toHaveAttribute('aria-busy', 'false', {
        timeout: 15_000,
      });

      await runAxeOnlyInChromium(browserName, async () => {
        await assertNoWcag22AaViolationsLightAndDark(page);
      });
    });

  test('keyboard navigation: Tab moves through toots and links', async ({ page }) => {
    await page.goto(SHARE_TEST_URL);
    await expect(page.getByTestId('share-feed')).toHaveAttribute('aria-busy', 'false', {
      timeout: 15_000,
    });

    // First focusable element after skip links is the feed or first interactive element
    await page.keyboard.press('Tab');
    const focused = await page.evaluate(() => document.activeElement?.tagName);
    expect(['A', 'BUTTON', 'INPUT', 'DETAILS']).toContain(focused);
  });

  test('200% zoom: no horizontal scroll on 360px viewport', async ({ page }) => {
    // Emulate 360px viewport width at 200% zoom (effective CSS width = 180px)
    await page.setViewportSize({ width: 360, height: 640 });
    await page.evaluate(() => {
      (document.body.style as CSSStyleDeclaration & { zoom: string }).zoom = '200%';
    });
    await page.goto(SHARE_TEST_URL);

    const scrollWidth: number = await page.evaluate(() => document.body.scrollWidth);
    const clientWidth: number = await page.evaluate(() => document.body.clientWidth);
    // Allow a small tolerance (1px) for rounding
    expect(scrollWidth).toBeLessThanOrEqual(clientWidth + 1);
  });

  test('skip links are present and keyboard-reachable', async ({ page }) => {
    await page.goto(SHARE_TEST_URL);

    // First Tab should reach the skip links nav
    await page.keyboard.press('Tab');
    const focused = await page.evaluate(() => document.activeElement?.textContent?.trim());
    expect(focused).toContain('Feed');
  });

  test('toot articles have role=group with aria-labelledby', async ({ page }) => {
    await page.goto(SHARE_TEST_URL);
    await expect(page.getByTestId('share-feed')).toHaveAttribute('aria-busy', 'false', {
      timeout: 15_000,
    });

    const firstArticle = page.locator('article[role="group"]').first();
    await expect(firstArticle).toBeVisible();
    const labelledBy = await firstArticle.getAttribute('aria-labelledby');
    expect(labelledBy).toBeTruthy();
    // The referenced element must exist
    await expect(page.locator(`#${labelledBy}`)).toBeVisible();
  });
});
