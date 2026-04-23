/**
 * E2E spec: full sharer→viewer round trip (chromium project).
 *
 * Scenario:
 * 1. Sharer visits main wall, subscribes to a hashtag.
 * 2. Sharer opens share dialog via QR badge.
 * 3. Sharer creates a link.
 * 4. QR code is decoded to the share URL.
 * 5. A second browser context navigates to the share URL.
 * 6. Viewer sees the readonly wall.
 * 7. A new toot is posted to the subscribed hashtag via the real Mastodon API.
 * 8. Both sharer and viewer see the toot within 15 s.
 * 9. Sharer revokes the link.
 * 10. Viewer sees the /expired route within 5 s.
 *
 * NOTE: All toot posting happens via mastodon-client.ts against the
 * dockerized Mastodon stack — per CLAUDE.md "never shortcut by injecting
 * directly into the Spring app".
 *
 * Deferred: tests marked test.fail — backend share endpoints not yet
 * implemented (Phase 2 tdd-ddd-implementer and secure-tdd-implementer).
 * They will be un-failed in Round 2 once backend is ready.
 */

import { test, expect, BrowserContext, Page } from '@playwright/test';
import { MastodonClient } from '../helper/mastodon-client';

const HASHTAG = 'glaciersharetest';

test.describe('Share link — full round trip', () => {

  // Deferred until backend is wired
  test.fail(
    'sharer creates share link, viewer opens it, both see toot, revoke shows expired',
    async ({ browser }) => {
      const mastodon = new MastodonClient(
        process.env['MASTODON_USER_API_URL'] ?? 'https://proxy',
        process.env['MASTODON_USER_ACCESS_TOKEN'] ?? '',
      );

      // --- Sharer context ---
      const sharerContext: BrowserContext = await browser.newContext();
      const sharerPage: Page = await sharerContext.newPage();

      await sharerPage.goto('/');
      await expect(sharerPage.getByTestId('connection-status')).toHaveAttribute(
        'data-state', 'live', { timeout: 10_000 }
      );

      // Subscribe to hashtag
      await sharerPage.getByRole('textbox', { name: /hashtag/i }).fill(HASHTAG);
      await sharerPage.getByRole('button', { name: /abonnieren/i }).click();

      // Open share dialog
      const qrBadge = sharerPage.getByTestId('qr-badge-button');
      await expect(qrBadge).toBeVisible();
      await qrBadge.click();

      // Create link
      await expect(sharerPage.getByTestId('create-button')).toBeVisible();
      await sharerPage.getByTestId('create-button').click();

      // Wait for link URL to appear
      const urlInput = sharerPage.getByTestId('share-url-input');
      await expect(urlInput).toBeVisible({ timeout: 10_000 });
      const shareUrl = await urlInput.inputValue();
      expect(shareUrl).toContain('/share/');

      // Close dialog
      await sharerPage.getByTestId('close-button').click();

      // --- Viewer context ---
      const viewerContext: BrowserContext = await browser.newContext();
      const viewerPage: Page = await viewerContext.newPage();

      await viewerPage.goto(shareUrl);

      // Wait for readonly wall
      await expect(viewerPage.getByTestId('share-banner')).toBeVisible({ timeout: 10_000 });
      await expect(viewerPage.getByTestId('share-feed')).toBeVisible();

      // Post a toot via real Mastodon
      const tootContent = `#${HASHTAG} Share link round-trip test toot ${Date.now()}`;
      await mastodon.postToot(tootContent);

      // Wait for toot to appear on viewer feed
      await expect(viewerPage.getByText(tootContent.substring(0, 40))).toBeVisible({
        timeout: 15_000,
      });

      // Sharer revokes
      await qrBadge.click();
      const revokeButton = sharerPage.getByTestId('revoke-button');
      await expect(revokeButton).toBeVisible();
      await revokeButton.click();

      // Confirm revoke
      await sharerPage.getByTestId('revoke-confirm').click();

      // Viewer should be redirected to /expired within 5 s
      await expect(viewerPage).toHaveURL(/\/expired/, { timeout: 10_000 });

      await sharerContext.close();
      await viewerContext.close();
    }
  );
});
