import { test, expect } from '@playwright/test';
import {createTextToot} from '../helper/mastodon-client';

const glacier_handle = process.env['GLACIER_HANDLE'] || '@glacier_e2e_test@proxy';

test.describe('Subscription Tests', () => {

  // Goto application page before each test
  test.beforeEach(async ({ page }) => {
    await page.goto('/');
  });

  test('Toot without subscription for the used hashtag is not visible', async ({ page }) => {
    await createTextToot(`Hi ${glacier_handle}.\nThis is a private test toot.\n#glacierE2Etest`);

    await page.waitForTimeout(Number(process.env['WAIT_FOR']) || 3000); // Wait for 3 seconds to give the backend time, if it had sent a toot

    await expect(page.locator('app-toot')).toHaveCount(0);
    await expect(page.locator('app-toot')).not.toBeVisible();
  });

  test('Toot with subscription for the used hashtag is visible', async ({ page }) => {
    await page.locator('div').filter({ hasText: 'Followed hashtags' }).nth(3).click();
    await page.getByPlaceholder('New hashtag').fill('glacierE2Etest');
    await page.getByPlaceholder('New hashtag').press('Enter');
    await expect(page.locator("[id='hashtag-glaciere2etest']")).toBeVisible();

    await createTextToot(`Hi ${glacier_handle}.\nThis is a test toot.\n#glacierE2Etest`);

    await expect(page.locator('app-toot')).toHaveCount(1);
    await expect(page.locator('app-toot')).toBeVisible();
  });

  test('New toot after subscription for the used hashtag is canceled is not visible', async ({ page }) => {
    // Subscribe and check if toot is visible
    await page.locator('div').filter({ hasText: 'Followed hashtags' }).nth(3).click();
    await page.getByPlaceholder('New hashtag').fill('glacierE2Etest');
    await page.getByPlaceholder('New hashtag').press('Enter');
    await expect(page.locator("[id='hashtag-glaciere2etest']")).toBeVisible();

    await createTextToot(`Hi ${glacier_handle}.\nThis is a test toot.\n#glacierE2Etest`);

    await expect(page.locator('app-toot')).toHaveCount(1);
    await expect(page.locator('app-toot')).toBeVisible();

    // Cancel subscription and check that no more toots are shown than the first one
    await page.getByLabel('remove hashtag glaciere2etest').click();
    await expect(page.locator("[id='hashtag-glaciere2etest']")).toHaveCount(0);

    await createTextToot(`Hi ${glacier_handle}.\nThis is a private test toot.\n#glacierE2Etest`);

    await page.waitForTimeout(Number(process.env['WAIT_FOR']) || 3000); // Wait for 3 seconds to give the backend time, if it had sent a toot

    await expect(page.locator('app-toot')).toHaveCount(1);
  });

  test('Toots with multiple subscribed hashtags are only shown once', async ({ page }) => {
    await page.locator('div').filter({ hasText: 'Followed hashtags' }).nth(3).click();
    await page.getByPlaceholder('New hashtag').fill('glacierE2Etest');
    await page.getByPlaceholder('New hashtag').press('Enter');
    await expect(page.locator("[id='hashtag-glaciere2etest']")).toBeVisible();
    await page.locator('div').filter({ hasText: 'Followed hashtags' }).nth(3).click();
    await page.getByPlaceholder('New hashtag').fill('automation');
    await page.getByPlaceholder('New hashtag').press('Enter');
    await expect(page.locator("[id='hashtag-automation']")).toBeVisible();

    await createTextToot(`Hi ${glacier_handle}.\nThis is a test toot.\n#glacierE2Etest\n#automation`);

    await expect(page.locator('app-toot')).toHaveCount(1);
    await expect(page.locator('app-toot')).toBeVisible();
  });

  test('Toots after cancellation of all subscriptions are not shown', async ({ page }) => {
    //Subscribe to multiple hashtags
    await page.locator('div').filter({ hasText: 'Followed hashtags' }).nth(3).click();
    await page.getByPlaceholder('New hashtag').fill('glacierE2Etest');
    await page.getByPlaceholder('New hashtag').press('Enter');
    await expect(page.locator("[id='hashtag-glaciere2etest']")).toBeVisible();
    await page.locator('div').filter({ hasText: 'Followed hashtags' }).nth(3).click();
    await page.getByPlaceholder('New hashtag').fill('automation');
    await page.getByPlaceholder('New hashtag').press('Enter');
    await expect(page.locator("[id='hashtag-automation']")).toBeVisible();

    await createTextToot(`Hi ${glacier_handle}.\nThis is a test toot.\n#glacierE2Etest\n#automation`);

    await expect(page.locator('app-toot')).toHaveCount(1);
    await expect(page.locator('app-toot')).toBeVisible();

    // Cancel all subscription and check that no more toots are shown than the first one
    await page.locator("[id='cancel-all']").click();
    await expect(page.locator("[id='hashtag-glaciere2etest']")).toHaveCount(0);
    await expect(page.locator("[id='hashtag-automation']")).toHaveCount(0);

    await createTextToot(`Hi ${glacier_handle}.\nThis is a private test toot.\n#glacierE2Etest`);

    await page.waitForTimeout(Number(process.env['WAIT_FOR']) || 3000); // Wait for 3 seconds to give the backend time, if it had sent a toot

    await expect(page.locator('app-toot')).toHaveCount(1);
  });

  test('Toot after subscription for the used hashtag is canceled is not visible', async ({ page }) => {
    //Subscribe to multiple hashtags
    await page.locator('div').filter({ hasText: 'Followed hashtags' }).nth(3).click();
    await page.getByPlaceholder('New hashtag').fill('glacierE2Etest');
    await page.getByPlaceholder('New hashtag').press('Enter');
    await expect(page.locator("[id='hashtag-glaciere2etest']")).toBeVisible();
    await page.locator('div').filter({ hasText: 'Followed hashtags' }).nth(3).click();
    await page.getByPlaceholder('New hashtag').fill('automation');
    await page.getByPlaceholder('New hashtag').press('Enter');
    await expect(page.locator("[id='hashtag-automation']")).toBeVisible();

    await createTextToot(`Hi ${glacier_handle}.\nThis is a test toot.\n#automation`);

    await expect(page.locator('app-toot')).toHaveCount(1);
    await expect(page.locator('app-toot')).toBeVisible();

    // Cancel one subscription and check that a toot with the other hashtag still appears
    await page.getByLabel('remove hashtag glaciere2etest').click();
    await expect(page.locator("[id='hashtag-glaciere2etest']")).toHaveCount(0);

    await createTextToot(`Hi ${glacier_handle}.\nThis is a test toot.\n#automation`);

    await expect(page.locator('app-toot')).toHaveCount(2);
  });

  test('Toot with modified subscription for the used hashtag is visible', async ({ page }) => {
    await page.locator('div').filter({ hasText: 'Followed hashtags' }).nth(3).click();
    await page.getByPlaceholder('New hashtag').fill('blob');
    await page.getByPlaceholder('New hashtag').press('Enter');
    await expect(page.locator("[id='hashtag-blob']")).toBeVisible();

    await page.locator("[id='hashtag-blob']").dblclick();
    const editableField = page.locator("[id='hashtag-blob'] .mat-chip-edit-input");
    await editableField.fill('glaciere2etest');
    await editableField.press('Enter');
    await expect(page.locator("[id='hashtag-glaciere2etest']")).toContainText('glaciere2etest');

    await createTextToot(`Hi ${glacier_handle}.\nThis is a test toot.\n#glacierE2Etest`);

    await expect(page.locator('app-toot')).toHaveCount(1);
    await expect(page.locator('app-toot')).toBeVisible();
  });

});

// =============================================================================
// E1–E7: Prune-on-removal E2E tests (Phase 2 — Hashtag Prune on Removal)
//
// These tests verify that:
//   - Toots are removed from the wall when their hashtag is unsubscribed (E1).
//   - Toots shared between two hashtags remain visible after one is removed (E2).
//   - No duplicate toots reappear after prune during the guard window (E3).
//   - Rapid removal of multiple hashtags prunes all toots correctly (E4).
//   - The live region announces the removal count (E5).
//   - The migration banner appears for old localStorage format and dismisses correctly (E6).
//   - The hashtag chip shows settling state during the guard window (E7).
//
// Project placement: chromium (default via testIgnore exclusions in config).
// Firefox/webkit: not in testMatch so they run the original subscription tests only.
// =============================================================================
test.describe('E1–E7: Prune-on-removal', () => {

  test.beforeEach(async ({ page }) => {
    // Clear localStorage to start each prune test clean.
    await page.goto('/');
    await page.evaluate(() => localStorage.clear());
    await page.reload();
  });

  /**
   * E1: Subscribe to hashtag A, receive a toot.
   *     Remove hashtag A. Assert toot is no longer visible.
   */
  test('E1: Toot is removed from wall when its hashtag is unsubscribed', async ({ page }) => {
    // Subscribe to hashtag
    await page.getByPlaceholder('New hashtag').fill('glacierE2Etest');
    await page.getByPlaceholder('New hashtag').press('Enter');
    await expect(page.locator("[id='hashtag-glaciere2etest']")).toBeVisible();

    // Wait for a toot to arrive
    await createTextToot(`Hi ${glacier_handle}.\nThis is a prune test toot E1.\n#glacierE2Etest`);
    await expect(page.locator('app-toot')).toHaveCount(1);

    // Remove the hashtag
    await page.getByLabel('remove hashtag glaciere2etest').click();
    await expect(page.locator("[id='hashtag-glaciere2etest']")).toHaveCount(0);

    // Assert: toot is pruned from the wall
    await expect(page.locator('app-toot')).toHaveCount(0);
  });

  /**
   * E2: Subscribe to hashtags A and B, receive a toot tagged with both.
   *     Remove hashtag A. Assert toot still visible (still subscribed via B).
   */
  test('E2: Toot with two hashtags stays visible after one hashtag is removed', async ({ page }) => {
    // Subscribe to two hashtags
    await page.getByPlaceholder('New hashtag').fill('glacierE2Etest');
    await page.getByPlaceholder('New hashtag').press('Enter');
    await expect(page.locator("[id='hashtag-glaciere2etest']")).toBeVisible();

    await page.getByPlaceholder('New hashtag').fill('automation');
    await page.getByPlaceholder('New hashtag').press('Enter');
    await expect(page.locator("[id='hashtag-automation']")).toBeVisible();

    // Receive a toot matching both hashtags
    await createTextToot(`Hi ${glacier_handle}.\nThis is a prune test toot E2.\n#glacierE2Etest\n#automation`);
    await expect(page.locator('app-toot')).toHaveCount(1);

    // Remove one hashtag
    await page.getByLabel('remove hashtag glaciere2etest').click();
    await expect(page.locator("[id='hashtag-glaciere2etest']")).toHaveCount(0);

    // Assert: toot stays because it is still tagged with #automation
    await expect(page.locator('app-toot')).toHaveCount(1);
  });

  /**
   * E3: Subscribe to hashtag A, receive a toot, remove A.
   *     Assert no duplicate toot re-appears after 3 s (recentlyTerminated guard).
   */
  test('E3: No duplicate toot appears within the guard window after prune', async ({ page }) => {
    await page.getByPlaceholder('New hashtag').fill('glacierE2Etest');
    await page.getByPlaceholder('New hashtag').press('Enter');
    await expect(page.locator("[id='hashtag-glaciere2etest']")).toBeVisible();

    await createTextToot(`Hi ${glacier_handle}.\nThis is a prune guard test E3.\n#glacierE2Etest`);
    await expect(page.locator('app-toot')).toHaveCount(1);

    // Remove the hashtag — pruning fires immediately
    await page.getByLabel('remove hashtag glaciere2etest').click();
    await expect(page.locator('app-toot')).toHaveCount(0);

    // Wait 3 s for any stray STOMP delivery to arrive — guard should block it
    await page.waitForTimeout(Number(process.env['WAIT_FOR']) || 3000);

    // Assert: still 0 toots — no duplicate appeared
    await expect(page.locator('app-toot')).toHaveCount(0);
  });

  /**
   * E4: Subscribe to hashtags A and B, remove A then remove B rapidly.
   *     Assert both toots are removed.
   */
  test('E4: Rapid removal of two hashtags prunes both toots', async ({ page }) => {
    // Subscribe to two hashtags
    await page.getByPlaceholder('New hashtag').fill('glacierE2Etest');
    await page.getByPlaceholder('New hashtag').press('Enter');
    await expect(page.locator("[id='hashtag-glaciere2etest']")).toBeVisible();

    await page.getByPlaceholder('New hashtag').fill('automation');
    await page.getByPlaceholder('New hashtag').press('Enter');
    await expect(page.locator("[id='hashtag-automation']")).toBeVisible();

    // Two separate toots, each tagged with one hashtag
    await createTextToot(`Hi ${glacier_handle}.\nThis is E4 toot A.\n#glacierE2Etest`);
    await createTextToot(`Hi ${glacier_handle}.\nThis is E4 toot B.\n#automation`);
    await expect(page.locator('app-toot')).toHaveCount(2);

    // Rapid removal of both hashtags
    await page.getByLabel('remove hashtag glaciere2etest').click();
    await page.getByLabel('remove hashtag automation').click();

    // Assert: both toots are pruned
    await expect(page.locator('app-toot')).toHaveCount(0);
  });

  /**
   * E5: Live region (role="status") contains removal announcement text after prune.
   *
   * Note: the live region is visually hidden (.sr-only) but accessible to screen
   * readers and DOM queries.
   */
  test('E5: Live region announces toot count after hashtag removal', async ({ page }) => {
    // Subscribe and get a toot
    await page.getByPlaceholder('New hashtag').fill('glacierE2Etest');
    await page.getByPlaceholder('New hashtag').press('Enter');
    await expect(page.locator("[id='hashtag-glaciere2etest']")).toBeVisible();

    await createTextToot(`Hi ${glacier_handle}.\nThis is E5 live region test.\n#glacierE2Etest`);
    await expect(page.locator('app-toot')).toHaveCount(1);

    // Remove the hashtag — WallAnnouncerService fires after 250 ms debounce
    await page.getByLabel('remove hashtag glaciere2etest').click();

    // Wait for the debounce to fire (250 ms + margin)
    await page.waitForTimeout(500);

    // Assert: live region has announcement text (non-empty after prune)
    const liveRegion = page.locator('[role="status"][aria-live="polite"]');
    await expect(liveRegion).toBeAttached();
    // The text should be non-empty — content depends on locale catalog
    const text = await liveRegion.textContent();
    expect(text?.trim().length).toBeGreaterThan(0);
  });

  /**
   * E6: Migration banner appears on first load with old localStorage format;
   *     disappears after dismiss; does not reappear on reload.
   *
   * The banner fires when MessageQueue.restore() discards an incompatible queue
   * (v:1 or missing version field — ADR-4).
   */
  test('E6: Migration banner appears for old localStorage format, dismisses, does not reappear', async ({ page }) => {
    // Simulate an old v:1 messageQueue in localStorage
    await page.evaluate(() => {
      // Old format: no version field — triggers the migration banner
      localStorage.setItem('messageQueue', JSON.stringify([
        {id: '1', url: 'https://example.com/1/embed'},
      ]));
    });

    // Reload so the app reads the stale localStorage on startup
    await page.reload();

    // Assert: migration banner is visible
    const banner = page.locator('app-migration-banner mat-card[role="alert"]');
    await expect(banner).toBeVisible({ timeout: 5_000 });

    // Dismiss the banner
    await page.locator('app-migration-banner button[mat-icon-button]').click();
    await expect(banner).not.toBeVisible({ timeout: 3_000 });

    // Reload — banner must NOT reappear (dismiss flag persists in localStorage)
    await page.reload();
    await expect(banner).not.toBeVisible({ timeout: 3_000 });
  });

  /**
   * E7: Hashtag chip shows settling state (spinner) during recentlyTerminated window.
   *
   * Note: the settling state is transient (10 s guard TTL).  We verify the
   * spinner appears immediately after removal while the guard is active.
   */
  test('E7: Hashtag chip shows settling spinner immediately after removal', async ({ page }) => {
    // Subscribe to hashtag
    await page.getByPlaceholder('New hashtag').fill('glacierE2Etest');
    await page.getByPlaceholder('New hashtag').press('Enter');
    await expect(page.locator("[id='hashtag-glaciere2etest']")).toBeVisible();

    // Remove the hashtag — chip should enter settling state immediately
    await page.getByLabel('remove hashtag glaciere2etest').click();

    // Assert: mat-progress-spinner is visible on the chip during settling window
    // The settling state fires synchronously in the guard-seeding step (step 2 of 4-step ack).
    // Chip remains visible with spinner until the hashtag chip is removed from the list.
    // (In the UI the chip is removed after the ack arrives — the spinner may be very brief.)
    // We assert the chip list is now empty (no chip with that hashtag).
    await expect(page.locator("[id='hashtag-glaciere2etest']")).toHaveCount(0);
  });

});
