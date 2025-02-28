import { test, expect } from '@playwright/test';

test('subscription - new hashtag is added to list of hashtags', async ({ page }) => {
  await page.goto('/');
  await page.locator('div').filter({ hasText: 'Followed hashtags' }).nth(3).click();
  await page.getByPlaceholder('New hashtag').fill('bloomscrolling');
  await page.getByPlaceholder('New hashtag').press('Enter');
  await page.getByPlaceholder('New hashtag').fill('pizza');
  await page.getByPlaceholder('New hashtag').press('Enter');
  await page.getByLabel('remove hashtagbloomscrolling').click();
});
