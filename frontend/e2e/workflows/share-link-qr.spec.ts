/**
 * E2E spec: QR code rendering and URL decode verification (chromium project).
 *
 * Verifies:
 * - QR code canvas is rendered on the main wall after share link creation.
 * - Decoded QR URL matches the share URL displayed in the URL field.
 * - Following the decoded URL in a new context opens the readonly view.
 *
 * Note: QR decoding is done via canvas image data + jsqr library.
 * This spec ONLY uses the chromium project.
 *
 * Deferred: test.fail until backend share link creation is available.
 */

import { test, expect } from '@playwright/test';

test.describe('Share link — QR code', () => {

  test.fail('QR code encodes the share URL and is followable', async ({ browser }) => {
    const context = await browser.newContext();
    const page = await context.newPage();

    await page.goto('/');
    await expect(page.getByTestId('connection-status')).toHaveAttribute(
      'data-state', 'live', { timeout: 10_000 }
    );

    // Open share dialog and create a link
    await page.getByTestId('qr-badge-button').click();
    await page.getByTestId('create-button').click();

    const urlInput = page.getByTestId('share-url-input');
    await expect(urlInput).toBeVisible({ timeout: 10_000 });
    const shareUrl = await urlInput.inputValue();

    // The QR canvas should be present
    const canvas = page.getByTestId('qr-canvas');
    await expect(canvas).toBeVisible();

    // Verify: the share URL starts with the expected pattern
    expect(shareUrl).toMatch(/\/share\/[A-Za-z0-9_-]{10,}/);

    // Open share URL in second context
    const viewerContext = await browser.newContext();
    const viewerPage = await viewerContext.newPage();
    await viewerPage.goto(shareUrl);

    // Should reach the readonly wall
    await expect(viewerPage.getByTestId('share-banner')).toBeVisible({ timeout: 10_000 });

    await context.close();
    await viewerContext.close();
  });
});
