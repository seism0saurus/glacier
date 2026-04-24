/**
 * E2E a11y spec for the share-link readonly view (chromium project).
 *
 * Uses @axe-core/playwright for WCAG 2.2 AA automated scan.
 * Also tests keyboard navigation and 200% zoom reflow.
 *
 * Backend endpoints wired in Phase 3. Tests enabled.
 */

import { test, expect } from '@playwright/test';
import AxeBuilder from '@axe-core/playwright';

const SHARE_TEST_URL = process.env['SHARE_TEST_URL'] || '/share/test-share-id-placeholder';

test.describe('Share link — accessibility', () => {

  test('readonly wall has zero critical/serious axe violations', async ({ page }) => {
    await page.goto(SHARE_TEST_URL);
    await expect(page.getByTestId('share-feed')).toHaveAttribute('aria-busy', 'false', {
      timeout: 15_000,
    });

    const results = await new AxeBuilder({ page })
      .withTags(['wcag2a', 'wcag2aa', 'wcag21aa', 'wcag22aa'])
      .analyze();

    const criticalOrSerious = results.violations.filter(
      (v) => v.impact === 'critical' || v.impact === 'serious'
    );
    expect(criticalOrSerious).toEqual([]);
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
