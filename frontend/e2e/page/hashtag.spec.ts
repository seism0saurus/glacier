import { test, expect } from '@playwright/test';

test.describe('Hashtag Tests', () => {

  // Goto application page and open the hashtag filter before each test
  test.beforeEach(async ({ page }) => {
    await page.goto('/');
    await page.locator('div').filter({ hasText: 'Followed hashtags' }).nth(3).click();
  });

  test('add one element', async ({ page }) => {
    await page.getByPlaceholder('New hashtag').fill('bloomscrolling');
    await page.getByPlaceholder('New hashtag').press('Enter');
    await expect(page.locator("[id='hashtag-bloomscrolling']")).toBeVisible();
  });

  test('add one element and ignore uppercase letters', async ({ page }) => {
    await page.getByPlaceholder('New hashtag').fill('BloomScrolling');
    await page.getByPlaceholder('New hashtag').press('Enter');
    await expect(page.locator("[id='hashtag-bloomscrolling']")).toBeVisible();
  });

  test('add two elements', async ({ page }) => {
    await page.getByPlaceholder('New hashtag').fill('bloomscrolling');
    await page.getByPlaceholder('New hashtag').press('Enter');
    await expect(page.locator("[id='hashtag-bloomscrolling']")).toBeVisible();

    await page.getByPlaceholder('New hashtag').fill('pizza');
    await page.getByPlaceholder('New hashtag').press('Enter');
    await expect(page.locator("[id='hashtag-pizza']")).toBeVisible();
  });

  test('add two elements and remove one', async ({ page }) => {
    await page.getByPlaceholder('New hashtag').fill('bloomscrolling');
    await page.getByPlaceholder('New hashtag').press('Enter');
    await expect(page.locator("[id='hashtag-bloomscrolling']")).toBeVisible();

    await page.getByPlaceholder('New hashtag').fill('pizza');
    await page.getByPlaceholder('New hashtag').press('Enter');
    await expect(page.locator("[id='hashtag-pizza']")).toBeVisible();

    await page.getByLabel('remove hashtag bloomscrolling').click();
    await expect(page.locator("[id='hashtag-bloomscrolling']")).toHaveCount(0);
    await expect(page.locator("[id='hashtag-pizza']")).toBeVisible();
  });

  test('add two elements and remove one by one', async ({ page }) => {
    await page.getByPlaceholder('New hashtag').fill('bloomscrolling');
    await page.getByPlaceholder('New hashtag').press('Enter');
    await expect(page.locator("[id='hashtag-bloomscrolling']")).toBeVisible();

    await page.getByPlaceholder('New hashtag').fill('pizza');
    await page.getByPlaceholder('New hashtag').press('Enter');
    await expect(page.locator("[id='hashtag-pizza']")).toBeVisible();

    await page.getByLabel('remove hashtag bloomscrolling').click();
    await expect(page.locator("[id='hashtag-bloomscrolling']")).toHaveCount(0);
    await expect(page.locator("[id='hashtag-pizza']")).toBeVisible();

    await page.getByLabel('remove hashtag pizza').click();
    await expect(page.locator("[id='hashtag-bloomscrolling']")).toHaveCount(0);
    await expect(page.locator("[id='hashtag-pizza']")).toHaveCount(0);
  });

  test('add no elements and remove all', async ({ page }) => {
    await page.getByLabel('remove all hashtags').click();
    await expect(page.locator("[id^='hashtag-']")).toHaveCount(0);
  });

  test('add one elements and remove all', async ({ page }) => {
    await page.getByPlaceholder('New hashtag').fill('bloomscrolling');
    await page.getByPlaceholder('New hashtag').press('Enter');
    await expect(page.locator("[id='hashtag-bloomscrolling']")).toBeVisible();

    await page.getByLabel('remove all hashtags').click();
    await expect(page.locator("[id='hashtag-bloomscrolling']")).toHaveCount(0);
  });

  test('add two elements and remove all', async ({ page }) => {
    await page.getByPlaceholder('New hashtag').fill('bloomscrolling');
    await page.getByPlaceholder('New hashtag').press('Enter');
    await expect(page.locator("[id='hashtag-bloomscrolling']")).toBeVisible();

    await page.getByPlaceholder('New hashtag').fill('pizza');
    await page.getByPlaceholder('New hashtag').press('Enter');
    await expect(page.locator("[id='hashtag-pizza']")).toBeVisible();

    await page.getByLabel('remove all hashtags').click();
    await expect(page.locator("[id='hashtag-bloomscrolling']")).toHaveCount(0);
    await expect(page.locator("[id='hashtag-bloomscrolling']")).toHaveCount(0);
  });
});
