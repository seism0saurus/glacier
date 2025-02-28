import { test, expect } from '@playwright/test';

test.describe('Mainpage Tests', () => {

  // Goto application page and open the hashtag filter before each test
  test.beforeEach(async ({page}) => {
    await page.goto('/');
  });

  test('Header is visible', async ({page}) => {
    await expect(page.locator('app-header')).toBeVisible();
  });

  test('Hashtag filter is visible', async ({page}) => {
    await expect(page.locator('app-hashtag')).toBeVisible();
  });

  test('Wall area is visible', async ({page}) => {
    await expect(page.locator('app-wall')).toBeVisible();
  });

  test('Footer is visible', async ({page}) => {
    await expect(page.locator('app-footer')).toBeVisible();
  });
});
