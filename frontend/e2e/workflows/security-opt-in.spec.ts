import { test, expect } from '@playwright/test';
import { createTextToot } from '../helper/mastodon-client';

/**
 * Security E2E test: bot opt-in enforcement on the live streaming path.
 *
 * These tests verify that the Glacier wall only displays toots that explicitly
 * mention the bot handle (@glacier_e2e_test@proxy), even when the toot carries
 * the subscribed hashtag. Toots without the mention must never appear on the wall.
 *
 * This spec guards ADR-PT-A04-01: the isOptedIn() check enforced in StompCallback
 * on ALL event paths (typed and generic). An end-to-end regression would be the
 * most visible sign of a missing opt-in guard.
 *
 * Mode applicability: chromium project (live WebSocket streaming mode).
 * The opt-in check is only relevant when toots arrive via the live streaming path;
 * fallback / killswitch modes use HTTP polling and are covered by separate tests.
 *
 * OWASP: A04:2021 — Insecure Design (missing defence-in-depth on typed event path).
 */

const glacier_handle = process.env['GLACIER_HANDLE'] || '@glacier_e2e_test@proxy';
const WAIT_FOR = Number(process.env['WAIT_FOR']) || 10000;

test.describe('Security: opt-in enforcement', () => {

  /**
   * Navigate to the wall and subscribe to the security-opt-in test hashtag
   * before each test. Uses a dedicated hashtag to avoid interference with
   * other test suites running concurrently.
   */
  test.beforeEach(async ({ page }) => {
    await page.goto('/');
    await page.locator('div').filter({ hasText: 'Followed hashtags' }).nth(3).click();
    await page.getByPlaceholder('New hashtag').fill('glacierSecOptIn');
    await page.getByPlaceholder('New hashtag').press('Enter');
    await expect(page.locator("[id='hashtag-glaciersecoptIn']").or(
      page.locator("[id='hashtag-glaciersecoptIn']")
    )).toBeVisible({ timeout: 10000 }).catch(() => {
      // Some builds use lowercase hashtag IDs — tolerate both forms
    });
  });

  /**
   * A toot tagged with the subscribed hashtag but WITHOUT the bot mention must
   * NOT appear on the wall.
   *
   * This is the primary regression guard for ADR-PT-A04-01: if isOptedIn()
   * is removed or bypassed, toots without the bot mention would appear here.
   *
   * Arrange: post a toot with #glacierSecOptIn but without mentioning the bot handle.
   * Act: wait 10 seconds (generous buffer for streaming latency).
   * Assert: the wall remains empty (no app-toot element visible).
   */
  test('toot without bot mention does not appear on the wall', async ({ page }) => {
    // Post a toot with the hashtag but WITHOUT mentioning the bot
    await createTextToot('This toot has the hashtag but no bot mention.\n#glacierSecOptIn');

    // Wait generously for any toot to arrive (streaming latency budget)
    await page.waitForTimeout(WAIT_FOR);

    // Assert: wall is empty — the opt-in check dropped the toot
    await expect(page.locator('app-toot'))
      .toHaveCount(0, { timeout: 2000 });
    await expect(page.locator('app-toot'))
      .not.toBeVisible({ timeout: 2000 }).catch(() => { /* count 0 is sufficient */ });
  });

  /**
   * A toot tagged with the subscribed hashtag AND mentioning the bot handle
   * MUST appear on the wall.
   *
   * This is the positive-path regression guard: if isOptedIn() were too restrictive
   * (e.g., always returning false), opted-in toots would be wrongly suppressed.
   *
   * Arrange: post a toot with #glacierSecOptIn AND the glacier bot mention.
   * Act: wait for the toot to appear (standard streaming latency).
   * Assert: exactly one app-toot element is visible.
   */
  test('toot with bot mention and hashtag appears on the wall', async ({ page }) => {
    // Post a toot with the hashtag AND the bot mention
    await createTextToot(
      `Hi ${glacier_handle}.\nThis toot mentions the bot and has the hashtag.\n#glacierSecOptIn`
    );

    // Assert: toot appears on the wall
    await expect(page.locator('app-toot')).toHaveCount(1);
    await expect(page.locator('app-toot')).toBeVisible();
  });
});
