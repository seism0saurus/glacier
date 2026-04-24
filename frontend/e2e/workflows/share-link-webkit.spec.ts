/**
 * E2E spec: share link happy path in WebKit/Safari (webkit project).
 *
 * Focuses on WebKit's stricter SameSite=Strict behaviour and cookie handling.
 * Backend endpoints wired in Phase 3. Tests enabled.
 */

import { test, expect } from '@playwright/test';

test.describe('Share link — WebKit', () => {

  test('share link flow works in WebKit with SameSite cookie behaviour', async ({ page, browser }) => {
    await page.goto('/');
    await expect(page.getByTestId('connection-status')).toHaveAttribute(
      'data-state', 'live', { timeout: 10_000 }
    );

    await page.getByRole('textbox', { name: /hashtag/i }).fill('glaciertest');
    await page.getByRole('button', { name: /abonnieren/i }).click();

    await page.getByTestId('qr-badge-button').click();
    await page.getByTestId('create-button').click();

    const urlInput = page.getByTestId('share-url-input');
    await expect(urlInput).toBeVisible({ timeout: 10_000 });
    const shareUrl = await urlInput.inputValue();

    // Navigate viewer in new context (tests SameSite isolation)
    const viewerContext = await browser.newContext();
    const viewerPage = await viewerContext.newPage();
    await viewerPage.goto(shareUrl);

    // Readonly wall should be visible
    await expect(viewerPage.getByTestId('share-banner')).toBeVisible({ timeout: 10_000 });
    await expect(viewerPage.getByTestId('share-feed')).toBeVisible();

    await viewerContext.close();
  });
});
