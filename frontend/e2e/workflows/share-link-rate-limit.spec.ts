/**
 * E2E spec: rate limiting on viewer polling (chromium project).
 *
 * Simulates a polling-abuse scenario: viewer sends many rapid requests
 * until a 429 is received, then verifies the i18n'd snackbar/message.
 *
 * Deferred: test.fail — requires backend rate limiting + viewer polling endpoint.
 */

import { test, expect } from '@playwright/test';

test.describe('Share link — rate limiting', () => {

  test.fail('viewer receives i18n snackbar on 429 from polling endpoint', async ({ page }) => {
    // Navigate to a known share link (pre-seeded in the test environment)
    const shareUrl = process.env['SHARE_TEST_URL'] || '/share/test-share-id';
    await page.goto(shareUrl);

    await expect(page.getByTestId('share-feed')).toHaveAttribute(
      'aria-busy', 'false', { timeout: 15_000 }
    );

    // Inject a 429 response for polling requests
    await page.route('**/rest/share/**/messages**', (route) => {
      route.fulfill({ status: 429, body: JSON.stringify({ message: 'rate limited' }) });
    });

    // Trigger polling by waiting for the next poll cycle
    // (viewer is already polling at 5s intervals)
    await page.waitForTimeout(6000);

    // Expect a visible rate-limit message or snackbar in German (source language)
    const snackbar = page.getByText(/Zu viele Anfragen/);
    await expect(snackbar).toBeVisible({ timeout: 10_000 });
  });
});
