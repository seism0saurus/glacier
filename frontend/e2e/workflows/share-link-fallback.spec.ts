/**
 * E2E spec: share link with WebSocket forced off (chromium project).
 *
 * Simulates WebSocket unavailability: viewer switches to HTTP polling.
 * Toot posted via Mastodon should appear on readonly wall within poll interval.
 *
 * Backend endpoints wired in Phase 3. Tests enabled.
 */

import { test, expect } from '@playwright/test';
import { MastodonClient } from '../helper/mastodon-client';

test.describe('Share link — fallback polling', () => {

  test('viewer receives toots via HTTP polling when WS is unavailable', async ({ browser }) => {
    const mastodon = new MastodonClient(
      process.env['MASTODON_USER_API_URL'] ?? 'https://proxy',
      process.env['MASTODON_USER_ACCESS_TOKEN'] ?? '',
    );

    const sharerContext = await browser.newContext();
    const sharerPage = await sharerContext.newPage();

    await sharerPage.goto('/');
    await sharerPage.getByRole('textbox', { name: /hashtag/i }).fill('glacierfallback');
    await sharerPage.getByRole('button', { name: /abonnieren/i }).click();

    await sharerPage.getByTestId('qr-badge-button').click();
    await sharerPage.getByTestId('create-button').click();

    const urlInput = sharerPage.getByTestId('share-url-input');
    await expect(urlInput).toBeVisible({ timeout: 10_000 });
    const shareUrl = await urlInput.inputValue();

    // Viewer context with WebSocket blocked
    const viewerContext = await browser.newContext();
    // Block WebSocket connections to force fallback polling
    await viewerContext.route('**/share-view-ws', (route) => route.abort());

    const viewerPage = await viewerContext.newPage();
    await viewerPage.goto(shareUrl);

    // Should fall through to HTTP polling — wait for feed to load
    await expect(viewerPage.getByTestId('share-feed')).toHaveAttribute(
      'aria-busy', 'false', { timeout: 20_000 }
    );

    // Post a fresh toot
    const content = `#glacierfallback Fallback polling test ${Date.now()}`;
    await mastodon.postToot(content);

    // Toot should appear within 2x poll interval (10 s buffer)
    await expect(viewerPage.getByText(content.substring(0, 30))).toBeVisible({
      timeout: 15_000,
    });

    await sharerContext.close();
    await viewerContext.close();
  });
});
