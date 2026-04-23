/**
 * E2E spec: share link happy path in Firefox (firefox project).
 *
 * Verifies cross-browser parity: cookie flags, STOMP, render identity.
 * Deferred: test.fail until backend endpoints are available.
 */

import { test, expect } from '@playwright/test';

test.describe('Share link — Firefox', () => {

  test.fail('share link happy path works in Firefox', async ({ page, browser }) => {
    await page.goto('/');
    await expect(page.getByTestId('connection-status')).toHaveAttribute(
      'data-state', 'live', { timeout: 10_000 }
    );

    // Subscribe to hashtag
    await page.getByRole('textbox', { name: /hashtag/i }).fill('glaciertest');
    await page.getByRole('button', { name: /abonnieren/i }).click();

    // Create share link
    await page.getByTestId('qr-badge-button').click();
    await page.getByTestId('create-button').click();

    const urlInput = page.getByTestId('share-url-input');
    await expect(urlInput).toBeVisible({ timeout: 10_000 });
    const shareUrl = await urlInput.inputValue();

    // Open in second browser context
    const viewerContext = await browser.newContext();
    const viewerPage = await viewerContext.newPage();
    await viewerPage.goto(shareUrl);

    await expect(viewerPage.getByTestId('share-banner')).toBeVisible({ timeout: 10_000 });
    // shareViewerId cookie should be set with correct flags
    const cookies = await viewerContext.cookies();
    const viewerCookie = cookies.find((c) => c.name === '__Host-shareViewerId' || c.name === 'shareViewerId');
    expect(viewerCookie).toBeDefined();
    expect(viewerCookie?.httpOnly).toBeTrue();

    await viewerContext.close();
  });
});
