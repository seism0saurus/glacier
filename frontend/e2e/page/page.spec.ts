import { test, expect } from '@playwright/test';

test.describe('Mainpage Tests', () => {

  // Goto application page and open the hashtag filter before each test
  test.beforeEach(async ({page}) => {
    await page.goto('/');
  });

  test('banner is visible', async ({page}) => {
    await expect(page.locator('app-header')).toBeVisible();
    await expect(page.getByRole('banner')).toBeVisible();
    await expect(page.getByRole('heading')).toContainText('Glacier - The Mastodon Social Wall');
    await expect(page.getByRole('img', {name: 'Logo of Glacier - the'})).toBeVisible();
  });

  test('hashtag filter is visible', async ({page}) => {
    await expect(page.locator('app-hashtag')).toBeVisible();
  });

  test('wall area is visible', async ({page}) => {
    await expect(page.locator('app-wall')).toBeVisible();
  });

  test('footer is visible', async ({page}) => {
    await expect(page.locator('app-footer')).toBeVisible();
  });
});
