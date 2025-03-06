import { test, expect } from '@playwright/test';

test.describe('Footer Tests', () => {

  // Goto application page and open the hashtag filter before each test
  test.beforeEach(async ({page}) => {
    await page.goto('/');
  });

  test('Footer and all its component are visible', async ({page}) => {
    await expect(page.locator('app-footer')).toBeVisible();
    await expect(page.locator('.howto')).toBeVisible();
    await expect(page.locator('.legal')).toBeVisible();
    await expect(page.locator('.copyright')).toBeVisible();
  });

  test('Howto contains correct explanation', async ({page}) => {
    await expect(page.locator('app-footer')).toBeVisible();
    await expect(page.locator('.howto')).toBeVisible();
    await expect(page.locator('.howto')).toContainText('You want your toots to be shown here? Mention @glacier_e2e_test@proxy in your toot and use one of the hashtags.');
  });

  test('Copyright contains correct text', async ({page}) => {
    await expect(page.locator('app-footer')).toBeVisible();
    await expect(page.locator('.copyright')).toBeVisible();
    await expect(page.locator('.copyright')).toContainText('Glacier is Open Source: https://github.com/seism0saurus/glacier');
  });

  test('Legal contains correct text', async ({page}) => {
    await expect(page.locator('app-footer')).toBeVisible();
    await expect(page.locator('.legal')).toBeVisible();
    await expect(page.locator('.legal>span')).toContainText('Legal Notice & GDPR');
  });
  test('Klick on legal opens overlay', async ({page}) => {
    await expect(page.locator('app-footer')).toBeVisible();
    await expect(page.locator('.legal')).toBeVisible();
    await page.locator('.legal').click();
    await expect(page.locator('app-gdpr')).toBeVisible();
  });

});
