import {expect, test} from '@playwright/test';

test.describe('Legal Overlay Tests', () => {

  // Goto application page and open the hashtag filter before each test
  test.beforeEach(async ({page}) => {
    await page.goto('/');
    await expect(page.locator('app-footer')).toBeVisible();
    await expect(page.locator('.legal')).toBeVisible();
    await page.locator('.legal').click();
    await expect(page.locator('app-gdpr')).toBeVisible();
  });

  test('Has correct title', async ({page}) => {
    await expect(page.locator("[id='legal-notice']")).toContainText('Legal Notice and Privacy Policy');
  });

  test('Has close button', async ({page}) => {
    await expect(page.locator("[id='legal-notice-close-button']")).toBeVisible();
    await expect(page.locator("[id='legal-notice-close-button']")).toContainText('Close');
  });

  test('Can be closed with the close button', async ({page}) => {
    await expect(page.locator("[id='legal-notice-close-button']")).toBeVisible();
    await expect(page.locator("[id='legal-notice-close-button']")).toContainText('Close');
    await page.locator("[id='legal-notice-close-button']").click();
    await expect(page.locator('app-gdpr')).not.toBeVisible();
  });

  test('Can be closed by clicking outside the overlay', async ({page}) => {
    await page.mouse.click(10, 10);
    await expect(page.locator('app-gdpr')).not.toBeVisible();
  });

});
