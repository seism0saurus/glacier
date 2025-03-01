import { test, expect } from '@playwright/test';
import {createTextToot} from '../helper/mastodon-client';

test.describe('Clear Tests', () => {

  // Goto application page before each test and subscribe to glacierE2Etest
  test.beforeEach(async ({ page }) => {
    await page.goto('/');
    await page.locator('div').filter({ hasText: 'Followed hashtags' }).nth(3).click();
    await page.getByPlaceholder('New hashtag').fill('glacierE2Etest');
    await page.getByPlaceholder('New hashtag').press('Enter');
    await expect(page.locator("[id='hashtag-glaciere2etest']")).toBeVisible();
  });

  test('Clear Toots works with one toot', async ({ page }) => {
    await createTextToot('Hi @glacier_e2e_test@mastodon.seism0saurus.de.\nThis is a test toot.\n#glacierE2Etest');

    await expect(page.locator('app-toot')).toHaveCount(1);
    await expect(page.locator('app-toot')).toBeVisible();

    await page.locator("[id='clear-toots']").click();

    await expect(page.locator('app-toot')).toHaveCount(0);
    await expect(page.locator('app-toot')).not.toBeVisible();
  });

  test('Clear Toots works with two toots', async ({ page }) => {
    await createTextToot('Hi @glacier_e2e_test@mastodon.seism0saurus.de.\nThis is a test toot.\n#glacierE2Etest');
    await createTextToot('Hi @glacier_e2e_test@mastodon.seism0saurus.de.\nThis is a test toot.\n#glacierE2Etest');

    await expect(page.locator('app-toot')).toHaveCount(2);

    await page.locator("[id='clear-toots']").click();

    await expect(page.locator('app-toot')).toHaveCount(0);
    await expect(page.locator('app-toot')).not.toBeVisible();
  });

  test('New toots after a clearance of old is shown', async ({ page }) => {
    await createTextToot('Hi @glacier_e2e_test@mastodon.seism0saurus.de.\nThis is a test toot.\n#glacierE2Etest');
    await createTextToot('Hi @glacier_e2e_test@mastodon.seism0saurus.de.\nThis is a test toot.\n#glacierE2Etest');

    await expect(page.locator('app-toot')).toHaveCount(2);

    await page.locator("[id='clear-toots']").click();

    await expect(page.locator('app-toot')).toHaveCount(0);
    await expect(page.locator('app-toot')).not.toBeVisible();

    await createTextToot('Hi @glacier_e2e_test@mastodon.seism0saurus.de.\nThis is a test toot.\n#glacierE2Etest');
    await expect(page.locator('app-toot')).toHaveCount(1);
    await expect(page.locator('app-toot')).toBeVisible();
  });
});
