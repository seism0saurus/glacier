import { test, expect } from '@playwright/test';
import {createTextToot} from '../helper/mastodon-client';

test.describe('Subscription Tests', () => {

  // Goto application page before each test
  test.beforeEach(async ({ page }) => {
    await page.goto('/');
  });

  test('Toot without subscription for the used hashtag is not visible', async ({ page }) => {
    await createTextToot('Hi @glacier_e2e_test@mastodon.seism0saurus.de.\nThis is a private test toot.\n#glacierE2Etest');

    await page.waitForTimeout(Number(process.env['WAIT_FOR']) || 3000); // Wait for 3 seconds to give the backend time, if it had sent a toot

    await expect(page.locator('app-toot')).toHaveCount(0);
    await expect(page.locator('app-toot')).not.toBeVisible();
  });

  test('Toot with subscription for the used hashtag is visible', async ({ page }) => {
    await page.locator('div').filter({ hasText: 'Followed hashtags' }).nth(3).click();
    await page.getByPlaceholder('New hashtag').fill('glacierE2Etest');
    await page.getByPlaceholder('New hashtag').press('Enter');
    await expect(page.locator("[id='hashtag-glaciere2etest']")).toBeVisible();

    await createTextToot('Hi @glacier_e2e_test@mastodon.seism0saurus.de.\nThis is a test toot.\n#glacierE2Etest');

    await expect(page.locator('app-toot')).toHaveCount(1);
    await expect(page.locator('app-toot')).toBeVisible();
  });

  test('New toot after subscription for the used hashtag is canceled is not visible', async ({ page }) => {
    // Subscribe and check if toot is visible
    await page.locator('div').filter({ hasText: 'Followed hashtags' }).nth(3).click();
    await page.getByPlaceholder('New hashtag').fill('glacierE2Etest');
    await page.getByPlaceholder('New hashtag').press('Enter');
    await expect(page.locator("[id='hashtag-glaciere2etest']")).toBeVisible();

    await createTextToot('Hi @glacier_e2e_test@mastodon.seism0saurus.de.\nThis is a test toot.\n#glacierE2Etest');

    await expect(page.locator('app-toot')).toHaveCount(1);
    await expect(page.locator('app-toot')).toBeVisible();

    // Cancel subscription and check that no more toots are shown than the first one
    await page.getByLabel('remove hashtag glaciere2etest').click();
    await expect(page.locator("[id='hashtag-glaciere2etest']")).toHaveCount(0);

    await createTextToot('Hi @glacier_e2e_test@mastodon.seism0saurus.de.\nThis is a private test toot.\n#glacierE2Etest');

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

    await createTextToot('Hi @glacier_e2e_test@mastodon.seism0saurus.de.\nThis is a test toot.\n#glacierE2Etest\n#automation');

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

    await createTextToot('Hi @glacier_e2e_test@mastodon.seism0saurus.de.\nThis is a test toot.\n#glacierE2Etest\n#automation');

    await expect(page.locator('app-toot')).toHaveCount(1);
    await expect(page.locator('app-toot')).toBeVisible();

    // Cancel all subscription and check that no more toots are shown than the first one
    await page.locator("[id='cancel-all']").click();
    await expect(page.locator("[id='hashtag-glaciere2etest']")).toHaveCount(0);
    await expect(page.locator("[id='hashtag-automation']")).toHaveCount(0);

    await createTextToot('Hi @glacier_e2e_test@mastodon.seism0saurus.de.\nThis is a private test toot.\n#glacierE2Etest');

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

    await createTextToot('Hi @glacier_e2e_test@mastodon.seism0saurus.de.\nThis is a test toot.\n#automation');

    await expect(page.locator('app-toot')).toHaveCount(1);
    await expect(page.locator('app-toot')).toBeVisible();

    // Cancel one subscription and check that a toot with the other hashtag still appears
    await page.getByLabel('remove hashtag glaciere2etest').click();
    await expect(page.locator("[id='hashtag-glaciere2etest']")).toHaveCount(0);

    await createTextToot('Hi @glacier_e2e_test@mastodon.seism0saurus.de.\nThis is a test toot.\n#automation');

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

    await createTextToot('Hi @glacier_e2e_test@mastodon.seism0saurus.de.\nThis is a test toot.\n#glacierE2Etest');

    await expect(page.locator('app-toot')).toHaveCount(1);
    await expect(page.locator('app-toot')).toBeVisible();
  });

});
