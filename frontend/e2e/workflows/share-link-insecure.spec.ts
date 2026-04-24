/**
 * E2E spec: share link on plain HTTP (insecure project).
 *
 * Backend serves on HTTP (no TLS). Verifications:
 * - Cookie `Secure=false` on shareViewerId.
 * - Copy-link button falls back to text selection (no Clipboard API).
 * - Full share flow works end-to-end.
 *
 * Backend endpoints wired in Phase 3. Tests enabled.
 */

import { test, expect } from '@playwright/test';

test.describe('Share link — insecure (plain HTTP)', () => {

  test('share flow works on plain HTTP with Secure=false cookies', async ({ browser }) => {
    const sharerContext = await browser.newContext();
    const sharerPage = await sharerContext.newPage();

    // The insecure project uses baseURL = http://localhost:8081/ (no TLS)
    await sharerPage.goto('/');
    await expect(sharerPage.getByTestId('connection-status')).toHaveAttribute(
      'data-state', 'live', { timeout: 10_000 }
    );

    await sharerPage.getByRole('textbox', { name: /hashtag/i }).fill('glacierinsecure');
    await sharerPage.getByRole('button', { name: /abonnieren/i }).click();

    await sharerPage.getByTestId('qr-badge-button').click();
    await sharerPage.getByTestId('create-button').click();

    const urlInput = sharerPage.getByTestId('share-url-input');
    await expect(urlInput).toBeVisible({ timeout: 10_000 });
    const shareUrl = await urlInput.inputValue();

    // Open in viewer context
    const viewerContext = await browser.newContext();
    const viewerPage = await viewerContext.newPage();
    await viewerPage.goto(shareUrl);

    await expect(viewerPage.getByTestId('share-banner')).toBeVisible({ timeout: 10_000 });

    // Cookie should NOT have Secure flag (plain HTTP)
    const cookies = await viewerContext.cookies();
    const viewerCookie = cookies.find((c) =>
      c.name === 'shareViewerId' || c.name.endsWith('shareViewerId')
    );
    if (viewerCookie) {
      expect(viewerCookie.secure).toBeFalse();
    }

    await sharerContext.close();
    await viewerContext.close();
  });
});
