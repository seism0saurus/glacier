import { test, expect } from '@playwright/test';
import {createMediaToot, createTextToot} from '../helper/mastodon-client';

test.describe('Toot Tests', () => {

  // Goto application page before each test and subscribe to glacierE2Etest
  test.beforeEach(async ({ page }) => {
    await page.goto('/');
    await page.locator('div').filter({ hasText: 'Followed hashtags' }).nth(3).click();
    await page.getByPlaceholder('New hashtag').fill('glacierE2Etest');
    await page.getByPlaceholder('New hashtag').press('Enter');
    await expect(page.locator("[id='hashtag-glaciere2etest']")).toBeVisible();
  });

  test('text toot with mention and hashtag is visible', async ({ page }) => {
    await createTextToot('Hi @glacier_e2e_test@mastodon.seism0saurus.de.\nThis is a test toot.\n#glacierE2Etest');

    await expect(page.locator('app-toot')).toHaveCount(1);
    await expect(page.locator('app-toot')).toBeVisible();
  });

  test('media toot with mention and hashtag is visible', async ({ page }) => {
    await createMediaToot('Hi @glacier_e2e_test@mastodon.seism0saurus.de.\nThis is a test toot with a cute mastodon image.\n#glacierE2Etest',
      'mastodon.jpeg',
      'A cute mastodon in front of a glacier. There are mountains in the background. The sky is blue and clouds fly between the mountains.'
    );

    await expect(page.locator('app-toot')).toHaveCount(1);
    await expect(page.locator('app-toot')).toBeVisible();
  });

  test('text toot with mention but without hashtag is not visible', async ({ page }) => {
    await createTextToot('Hi @glacier_e2e_test@mastodon.seism0saurus.de.\nThis is a test toot without hashtag.');

    await page.waitForTimeout(Number(Number(process.env['WAIT_FOR'])) || 3000); // Wait for 3 seconds to give the backend time, if it had sent a toot

    await expect(page.locator('app-toot')).toHaveCount(0);
    await expect(page.locator('app-toot')).not.toBeVisible();
  });

  test('text toot without mention but with a hashtag is not visible', async ({ page }) => {
    await createTextToot('Hi everyone\nThis is a test toot without mention.\n#glacierE2Etest');

    await page.waitForTimeout(Number(Number(process.env['WAIT_FOR'])) || 3000); // Wait for 3 seconds to give the backend time, if it had sent a toot

    await expect(page.locator('app-toot')).toHaveCount(0);
    await expect(page.locator('app-toot')).not.toBeVisible();
  });

  test('text toot without mention and without hashtag is not visible', async ({ page }) => {
    await createTextToot('Hi everyone\nThis is a plain toot without mention and hashtag.');

    await page.waitForTimeout(Number(process.env['WAIT_FOR']) || 3000); // Wait for 3 seconds to give the backend time, if it had sent a toot

    await expect(page.locator('app-toot')).toHaveCount(0);
    await expect(page.locator('app-toot')).not.toBeVisible();
  });

  test('private toot with mention and hashtag is not visible', async ({ page }) => {
    await createTextToot('Hi @glacier_e2e_test@mastodon.seism0saurus.de.\nThis is a private test toot.\n#glacierE2Etest', 'private');

    await page.waitForTimeout(Number(process.env['WAIT_FOR']) || 3000); // Wait for 3 seconds to give the backend time, if it had sent a toot

    await expect(page.locator('app-toot')).toHaveCount(0);
    await expect(page.locator('app-toot')).not.toBeVisible();
  });

  test('direct toot with mention and hashtag is not visible', async ({ page }) => {
    await createTextToot('Hi @glacier_e2e_test@mastodon.seism0saurus.de.\nThis is a private test toot.\n#glacierE2Etest', 'direct');

    await page.waitForTimeout(Number(process.env['WAIT_FOR']) || 3000); // Wait for 3 seconds to give the backend time, if it had sent a toot

    await expect(page.locator('app-toot')).toHaveCount(0);
    await expect(page.locator('app-toot')).not.toBeVisible();
  });

  test('unlisted toot with mention and hashtag is not visible', async ({ page }) => {
    await createTextToot('Hi @glacier_e2e_test@mastodon.seism0saurus.de.\nThis is a private test toot.\n#glacierE2Etest', 'unlisted');

    await page.waitForTimeout(Number(process.env['WAIT_FOR']) || 3000); // Wait for 3 seconds to give the backend time, if it had sent a toot

    await expect(page.locator('app-toot')).toHaveCount(0);
    await expect(page.locator('app-toot')).not.toBeVisible();
  });
});
