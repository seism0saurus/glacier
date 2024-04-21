import { test, expect } from '@playwright/test';

test('hashtags - add two elements to filter and remove one of them', async ({ page }) => {
  await page.goto('http://localhost:8080/');
  await page.locator('div').filter({ hasText: 'Followed hashtags' }).nth(3).click();
  await page.getByPlaceholder('New hashtag').fill('bloomscrolling');
  await page.getByPlaceholder('New hashtag').press('Enter');
  await page.getByPlaceholder('New hashtag').fill('pizza');
  await page.getByPlaceholder('New hashtag').press('Enter');
  await page.getByLabel('remove hashtagbloomscrolling').click();
});
