import { test, expect } from '@playwright/test';

test.describe('Header Tests', () => {

  // Goto application page and open the hashtag filter before each test
  test.beforeEach(async ({page}) => {
    await page.goto('/');
  });

  test('Is visible', async ({page}) => {
    await expect(page.locator('app-header')).toBeVisible();
    await expect(page.getByRole('banner')).toBeVisible();
  });

  test('Contains the logo', async ({page}) => {
    await expect(page.getByRole('img', {name: 'Logo of Glacier - the'})).toBeVisible();
  });

  test('Contains the title', async ({page}) => {
    await expect(page.getByRole('heading')).toBeVisible();
    await expect(page.getByRole('heading')).toContainText('Glacier - The Mastodon Social Wall');
  });
});
