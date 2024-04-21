import { test, expect } from '@playwright/test';

test('page - banner is visible', async ({ page }) => {
  await page.goto('http://localhost:8080/');
  await expect(page.locator('app-header')).toBeVisible();
  await expect(page.getByRole('banner')).toBeVisible();
  await expect(page.getByRole('heading')).toContainText('Glacier - The Mastodon Social Wall');
  await expect(page.getByRole('img', { name: 'Logo of Glacier - the' })).toBeVisible();
});

test('page - hashtag filter is visible', async ({ page }) => {
  await page.goto('http://localhost:8080/');
  await expect(page.locator('app-hashtag')).toBeVisible();
});

test('page - wall area is visible', async ({ page }) => {
  await page.goto('http://localhost:8080/');
  await expect(page.locator('app-wall')).toBeVisible();
});

test('page - footer is visible', async ({ page }) => {
  await page.goto('http://localhost:8080/');
  await expect(page.locator('app-footer')).toBeVisible();
  await expect(page.getByRole('contentinfo')).toBeVisible();
  await expect(page.getByRole('contentinfo')).toContainText('You don\'t want your toots to be shown here? Block glacier@botsin.space in your account.');
  await expect(page.getByRole('contentinfo')).toContainText('Glacier is Open Source: https://github.com/seism0saurus/glacier');
});
