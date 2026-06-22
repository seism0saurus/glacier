/**
 * ICON-LOCAL: Glacier renders every `<mat-icon>` from a locally-bundled SVG
 * asset, never from the Material Icons webfont and never via a runtime fetch
 * from Google.
 *
 * Background
 * ----------
 * Angular Material's `<mat-icon>` defaults to *font ligatures*
 * (`<mat-icon>share</mat-icon>` / `fontIcon="content_copy"`). Those only paint
 * once the Material Icons/Symbols webfont is loaded — historically via a
 * `<link href="fonts.googleapis.com/…">` tag, i.e. a runtime fetch from Google.
 * Glacier ships no such webfont, so the new header/connection-status/share
 * icons rendered blank until they were converted to local `svgIcon="…"` assets
 * (registered from `assets/icons/*.svg`).
 *
 * This spec is the only layer that can prove the SVG *bytes actually paint*
 * (the Karma unit tests only assert the svgIcon binding mode). It also guards
 * the hard requirement that no icon is fetched dynamically from Google.
 *
 * Project: chromium (default). The header + connection-status chip render on
 * the main wall regardless of Mastodon streaming state. Runtime requires the
 * dockerized Glacier backend serving the SPA (see CLAUDE.md / infrastructure).
 */

import { test, expect } from '@playwright/test';

test.describe('ICON-LOCAL: icons are local SVGs, never fetched from Google', () => {

  test('no icon font / icon asset is requested from a Google host', async ({ page }) => {
    const googleRequests: string[] = [];
    page.on('request', (req) => {
      const url = req.url();
      if (/fonts\.googleapis\.com|fonts\.gstatic\.com|material(icons|-symbols)/i.test(url)) {
        googleRequests.push(url);
      }
    });

    await page.goto('/');
    await expect(page.locator('[data-testid="connection-status"]'))
      .toBeVisible({ timeout: 10_000 });
    // Give any (unwanted) deferred font request a chance to fire.
    await page.waitForLoadState('networkidle');

    expect(googleRequests, `unexpected Google icon/font requests:\n${googleRequests.join('\n')}`)
      .toEqual([]);
  });

  test('connection-status chip paints an inline SVG icon (not a blank font ligature)', async ({ page }) => {
    await page.goto('/');
    const chip = page.locator('[data-testid="connection-status"]');
    await expect(chip).toBeVisible({ timeout: 10_000 });

    const icon = chip.locator('mat-icon');
    // svgIcon mode stamps data-mat-icon-type="svg" on the host element …
    await expect(icon).toHaveAttribute('data-mat-icon-type', 'svg');
    // … and inlines a real <svg> child once the local asset has loaded.
    await expect(icon.locator('svg')).toHaveCount(1);
    // A broken font ligature would leave its literal name as text content.
    await expect(icon).toHaveText('');
  });

  test('header share button paints an inline SVG icon', async ({ page }) => {
    await page.goto('/');
    const shareBtn = page.locator('[data-testid="share-button"]');
    await expect(shareBtn).toBeVisible({ timeout: 10_000 });

    const icon = shareBtn.locator('mat-icon');
    await expect(icon).toHaveAttribute('data-mat-icon-type', 'svg');
    await expect(icon.locator('svg')).toHaveCount(1);
    await expect(icon).toHaveText('');
  });

});
