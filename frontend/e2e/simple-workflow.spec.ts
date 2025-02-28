import { test, expect } from '@playwright/test';

test.describe('Simple Workflow Tests', () => {

  // Goto application page before each test
  test.beforeEach(async ({ page }) => {
    await page.goto('/');
  });

  test('text toot with mention and hashtag is visible', async ({ page }) => {
    const { createTextToot } = await import('./helper/mastodon-client');

    await page.locator('div').filter({ hasText: 'Followed hashtags' }).nth(3).click();
    await page.getByPlaceholder('New hashtag').fill('glacierE2Etest');
    await page.getByPlaceholder('New hashtag').press('Enter');
    await expect(page.locator("[id='hashtag-glaciere2etest']")).toBeVisible();

    await createTextToot('Hi @glacier_e2e_test@mastodon.seism0saurus.de.\nThis is a test toot.\n#glacierE2Etest');

    await expect(page.locator('app-toot')).toHaveCount(1, { timeout: 30000 } );
    await expect(page.locator('app-toot')).toBeVisible();
  });
});
