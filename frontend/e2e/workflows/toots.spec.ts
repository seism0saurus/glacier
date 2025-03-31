import { test, expect } from '@playwright/test';
import {createMediaToot, createTextToot, deleteToot, modifyTextToot} from '../helper/mastodon-client';

const glacier_handle = process.env['GLACIER_HANDLE'] || '@glacier_e2e_test@proxy';

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
    await createTextToot(`Hi ${glacier_handle}.\nThis is a test toot.\n#glacierE2Etest`);

    await expect(page.locator('app-toot')).toHaveCount(1);
    await expect(page.locator('app-toot')).toBeVisible();
  });

  test('media toot with mention and hashtag is visible', async ({ page }) => {
    await createMediaToot(`Hi ${glacier_handle}.\nThis is a test toot with a cute mastodon image.\n#glacierE2Etest`,
      'mastodon.jpeg',
      'A cute mastodon in front of a glacier. There are mountains in the background. The sky is blue and clouds fly between the mountains.'
    );

    await expect(page.locator('app-toot')).toHaveCount(1);
    await expect(page.locator('app-toot')).toBeVisible();
  });

  test('text toot with mention but without hashtag is not visible', async ({ page }) => {
    await createTextToot(`Hi ${glacier_handle}.\nThis is a test toot without hashtag.`);

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
    await createTextToot(`Hi ${glacier_handle}.\nThis is a private test toot.\n#glacierE2Etest`, 'private');

    await page.waitForTimeout(Number(process.env['WAIT_FOR']) || 3000); // Wait for 3 seconds to give the backend time, if it had sent a toot

    await expect(page.locator('app-toot')).toHaveCount(0);
    await expect(page.locator('app-toot')).not.toBeVisible();
  });

  test('direct toot with mention and hashtag is not visible', async ({ page }) => {
    await createTextToot(`Hi ${glacier_handle}.\nThis is a private test toot.\n#glacierE2Etest`, 'direct');

    await page.waitForTimeout(Number(process.env['WAIT_FOR']) || 3000); // Wait for 3 seconds to give the backend time, if it had sent a toot

    await expect(page.locator('app-toot')).toHaveCount(0);
    await expect(page.locator('app-toot')).not.toBeVisible();
  });

  test('unlisted toot with mention and hashtag is not visible', async ({ page }) => {
    await createTextToot(`Hi ${glacier_handle}.\nThis is a private test toot.\n#glacierE2Etest`, 'unlisted');

    await page.waitForTimeout(Number(process.env['WAIT_FOR']) || 3000); // Wait for 3 seconds to give the backend time, if it had sent a toot

    await expect(page.locator('app-toot')).toHaveCount(0);
    await expect(page.locator('app-toot')).not.toBeVisible();
  });

  test('toots are in reverse order', async ({ page }) => {
    await createTextToot(`Hi ${glacier_handle}.\nThis is the first toot.\n#glacierE2Etest`);
    await createTextToot(`Hi ${glacier_handle}.\nThis is the second toot.\n#glacierE2Etest`);
    await createTextToot(`Hi ${glacier_handle}.\nThis is the third toot.\n#glacierE2Etest`);

    await expect(page.locator('app-toot')).toHaveCount(3);

    // Verify if the order of `app-toot` elements is the reverse of the created toots
    let tootOrder = [
      `This is the third toot.`,
      `This is the second toot.`,
      `This is the first toot.`
    ];

    for (let i = 0; i < tootOrder.length; i++) {
      const toot = await page.locator('app-toot iframe').nth(i);
      await toot.waitFor({ state: 'attached' });
      const iframe = await toot.contentFrame();
      await expect(iframe?.locator('body')).toContainText(tootOrder[i]);
    }
  });

  test('edited toot is replaced in the same position as the original toot', async ({ page }) => {
    await createTextToot(`Hi ${glacier_handle}.\nThis is the first toot.\n#glacierE2Etest`);
    let idToot = await createTextToot(`Hi ${glacier_handle}.\nThis is the second toot.\n#glacierE2Etest`);
    await createTextToot(`Hi ${glacier_handle}.\nThis is the third toot.\n#glacierE2Etest`);

    await expect(page.locator('app-toot')).toHaveCount(3);

    // Verify if the order of `app-toot` elements is the reverse of the created toots
    let tootOrder = [
      `This is the third toot.`,
      `This is the second toot.`,
      `This is the first toot.`
    ];

    for (let i = 0; i < tootOrder.length; i++) {
      const toot = await page.locator('app-toot iframe').nth(i);
      await toot.waitFor({ state: 'attached' });
      const iframe = await toot.contentFrame();
      await expect(iframe?.locator('body')).toContainText(tootOrder[i]);
    }

    await modifyTextToot(idToot, `Hi ${glacier_handle}.\nThis is the edited toot.\n#glacierE2Etest`);

    const secondTootIframe = await page.locator('app-toot iframe').nth(1);
    await secondTootIframe.waitFor({ state: 'attached' });
    const secondIframe = await secondTootIframe.contentFrame();
    await secondIframe?.locator('body:has-text("This is the edited toot.")').waitFor({ state: 'visible' });

    await expect(page.locator('app-toot')).toHaveCount(3);

    // Verify if the order of `app-toot` elements is the reverse of the created toots
    tootOrder = [
      `This is the third toot.`,
      `This is the edited toot.`,
      `This is the first toot.`
    ];

    for (let i = 0; i < tootOrder.length; i++) {
      const toot = await page.locator('app-toot iframe').nth(i);
      await toot.waitFor({ state: 'attached' });
      const iframe = await toot.contentFrame();
      await expect(iframe?.locator('body')).toContainText(tootOrder[i]);
    }

  });

  test('deleted toot is removed', async ({ page }) => {
    let idToot = await createTextToot(`Hi ${glacier_handle}.\nThis is a toot.\n#glacierE2Etest`);
    await page.locator('app-toot iframe').first().waitFor({ state: 'attached' });
    await expect(page.locator('app-toot')).toHaveCount(1);

    await deleteToot(idToot);
    await page.waitForTimeout(1000);

    await expect(page.locator('app-toot')).toHaveCount(0);
  });

  test('deleted toot is removed and other toots close the gap', async ({ page }) => {
    await createTextToot(`Hi ${glacier_handle}.\nThis is the first toot.\n#glacierE2Etest`);
    let idToot = await createTextToot(`Hi ${glacier_handle}.\nThis is the second toot.\n#glacierE2Etest`);
    await createTextToot(`Hi ${glacier_handle}.\nThis is the third toot.\n#glacierE2Etest`);

    await expect(page.locator('app-toot')).toHaveCount(3);

    // Verify if the order of `app-toot` elements is the reverse of the created toots
    let tootOrder = [
      `This is the third toot.`,
      `This is the second toot.`,
      `This is the first toot.`
    ];

    for (let i = 0; i < tootOrder.length; i++) {
      const toot = await page.locator('app-toot iframe').nth(i);
      await toot.waitFor({ state: 'attached' });
      const iframe = await toot.contentFrame();

      await expect(iframe?.locator('body')).toContainText(tootOrder[i]);
    }


    await deleteToot(idToot);
    await page.waitForTimeout(3000);

    await expect(page.locator('app-toot')).toHaveCount(2);

    // Verify if the order of `app-toot` elements is the reverse of the created toots
    tootOrder = [
      `This is the third toot.`,
      `This is the first toot.`
    ];

    for (let i = 0; i < tootOrder.length; i++) {
      const toot = await page.locator('app-toot iframe').nth(i);
      await toot.waitFor({ state: 'attached' });
      const iframe = await toot.contentFrame();
      await expect(iframe?.locator('body')).toContainText(tootOrder[i]);
    }

  });
});
