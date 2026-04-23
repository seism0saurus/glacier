/**
 * Fallback transport E2E tests (D-19).
 *
 * Requires the full dockerized Mastodon stack
 * (`infrastructure/docker-compose.yaml`) plus the standard Glacier backend.
 *
 * WS failure is induced by writing a Traefik middleware that returns 503 on
 * the /websocket path into the mounted dynamic.yml file, then removing it to
 * recover.  The test waits ~2 s for Traefik to pick up the change (D-19).
 *
 * Test coverage:
 *   (a) Happy WS path — indicator shows WEBSOCKET.
 *   (b) WS break → FALLBACK indicator → toot appears via poll (no duplicate).
 *   (c) WS recovery → indicator returns to WEBSOCKET, new toot appears.
 *   (d) Cache gap scenario — fresh browser context with WS broken.
 *   (e) Visual regression screenshots of the header in WEBSOCKET / PROBING / FALLBACK.
 */

import {expect, test} from '@playwright/test';
import {createTextToot} from '../helper/mastodon-client';
import * as fs from 'fs';

const glacier_handle = process.env['GLACIER_HANDLE'] || '@glacier_e2e_test@proxy';
const HASHTAG = 'glaciere2efallback';

/** Path to the Traefik dynamic config mounted inside the infrastructure. */
const DYNAMIC_YML = process.env['TRAEFIK_DYNAMIC_YML_PATH']
  || '/home/ulrich.viefhaus/git/seism0saurus/glacier/infrastructure/dynamic.yml';

/** Middleware block that causes Traefik to return 503 on /websocket. */
const WS_BLOCK_CONFIG = `
http:
  middlewares:
    block-websocket:
      replacePath:
        path: /blocked
  routers:
    websocket-blocker:
      rule: "PathPrefix(\`/websocket\`)"
      middlewares:
        - block-websocket
      service: noop@internal
      priority: 100
`;

/**
 * Reads the current dynamic.yml content so it can be restored after test.
 */
function readDynamicYml(): string {
  try {
    return fs.readFileSync(DYNAMIC_YML, 'utf8');
  } catch {
    return '';
  }
}

function writeDynamicYml(content: string): void {
  fs.writeFileSync(DYNAMIC_YML, content, 'utf8');
}

test.describe('Fallback transport tests', () => {

  test.beforeEach(async ({page}) => {
    await page.goto('/');
    await page.locator('div').filter({hasText: 'Followed hashtags'}).nth(3).click();
    await page.getByPlaceholder('New hashtag').fill(HASHTAG);
    await page.getByPlaceholder('New hashtag').press('Enter');
    await expect(page.locator(`[id='hashtag-${HASHTAG}']`)).toBeVisible();
  });

  // -------------------------------------------------------------------------
  // (a) Happy WS path
  // -------------------------------------------------------------------------
  test('(a) happy WS path — indicator is WEBSOCKET on initial load', async ({page}) => {
    const indicator = page.locator('[data-testid="connection-status"]');
    await expect(indicator).toBeVisible();
    // CSS class for WEBSOCKET state
    await expect(indicator).toHaveClass(/connection-chip--websocket/);
  });

  // -------------------------------------------------------------------------
  // (b) WS break → FALLBACK → toot appears, no duplicate
  // -------------------------------------------------------------------------
  test('(b) WS break causes FALLBACK; toot arrives via poll without duplicate', async ({page}) => {
    // Capture original config for restoration
    const originalConfig = readDynamicYml();

    try {
      // Break the WS path via Traefik dynamic config
      writeDynamicYml(WS_BLOCK_CONFIG);
      // Give Traefik 2 s to apply the file-based config change
      await page.waitForTimeout(2_000);

      // Wait for indicator to transition to FALLBACK (up to ~40 s — the 3-attempt cycle)
      const indicator = page.locator('[data-testid="connection-status"]');
      await expect(indicator).toHaveClass(/connection-chip--fallback/, {timeout: 45_000});

      // Post a toot via Mastodon API (not injected directly — tests the full fan-out path)
      await createTextToot(`Hi ${glacier_handle}.\nFallback test toot.\n#${HASHTAG}`);

      // Toot must appear within the poll window (5 s poll + render buffer)
      await expect(page.locator('app-toot')).toHaveCount(1, {timeout: 20_000});
      await expect(page.locator('app-toot')).toBeVisible();

    } finally {
      // Always restore the dynamic config
      writeDynamicYml(originalConfig);
    }
  });

  // -------------------------------------------------------------------------
  // (c) WS recovery → indicator returns to WEBSOCKET, new toot appears
  // -------------------------------------------------------------------------
  test('(c) WS recovery transitions indicator back to WEBSOCKET', async ({page}) => {
    const originalConfig = readDynamicYml();

    try {
      writeDynamicYml(WS_BLOCK_CONFIG);
      await page.waitForTimeout(2_000);

      const indicator = page.locator('[data-testid="connection-status"]');
      await expect(indicator).toHaveClass(/connection-chip--fallback/, {timeout: 45_000});

      // Restore WS
      writeDynamicYml(originalConfig);
      await page.waitForTimeout(2_000);

      // The first WS probe (10 s) should succeed; indicator flips to WEBSOCKET
      await expect(indicator).toHaveClass(/connection-chip--websocket/, {timeout: 30_000});

      // Post a toot — should arrive via STOMP
      await createTextToot(`Hi ${glacier_handle}.\nRecovery toot.\n#${HASHTAG}`);
      await expect(page.locator('app-toot')).toHaveCount(1, {timeout: 20_000});

    } finally {
      writeDynamicYml(originalConfig);
    }
  });

  // -------------------------------------------------------------------------
  // (d) Cache gap — fresh browser context, WS broken, > 20 toots posted
  // -------------------------------------------------------------------------
  test('(d) cache gap: fresh context shows at most 20 toots and gap snackbar once', async ({browser}) => {
    const originalConfig = readDynamicYml();

    try {
      writeDynamicYml(WS_BLOCK_CONFIG);
      await browser.newPage(); // warm traefik
      await new Promise(r => setTimeout(r, 2_000));

      // Post 22 toots (exceeds ring-buffer capacity of 20)
      for (let i = 1; i <= 22; i++) {
        await createTextToot(`Hi ${glacier_handle}.\nGap toot ${i}.\n#${HASHTAG}`);
      }

      // Fresh context simulates a new wallId — server will return full ring (20 items) + gap:true
      const context = await browser.newContext();
      const page = await context.newPage();
      await page.goto('/');
      await page.locator('div').filter({hasText: 'Followed hashtags'}).nth(3).click();
      await page.getByPlaceholder('New hashtag').fill(HASHTAG);
      await page.getByPlaceholder('New hashtag').press('Enter');

      // Wait for FALLBACK state
      const indicator = page.locator('[data-testid="connection-status"]');
      await expect(indicator).toHaveClass(/connection-chip--fallback/, {timeout: 45_000});

      // Wall shows exactly 20 toots (ring-buffer cap, D-02)
      await expect(page.locator('app-toot')).toHaveCount(20, {timeout: 20_000});

      // Gap snackbar appears exactly once (D-16)
      const snackbar = page.locator('.gap-snackbar');
      await expect(snackbar).toBeVisible({timeout: 10_000});
      await expect(snackbar).toHaveCount(1);

      await context.close();

    } finally {
      writeDynamicYml(originalConfig);
    }
  });

  // -------------------------------------------------------------------------
  // (e) Visual regression screenshots
  // -------------------------------------------------------------------------
  test('(e) visual regression: header in WEBSOCKET, PROBING, FALLBACK states', async ({page}) => {
    const header = page.locator('header');
    const indicator = page.locator('[data-testid="connection-status"]');

    // WEBSOCKET state
    await expect(indicator).toHaveClass(/connection-chip--websocket/, {timeout: 10_000});
    await expect(header).toHaveScreenshot('header-websocket.png');

    const originalConfig = readDynamicYml();
    try {
      writeDynamicYml(WS_BLOCK_CONFIG);
      await page.waitForTimeout(1_000);

      // PROBING state (brief, captured opportunistically)
      // The indicator may be in PROBING transiently; we screenshot the header
      // within the first reconnect cycle
      await expect(header).toHaveScreenshot('header-probing-or-fallback.png');

      // Wait for full FALLBACK state
      await expect(indicator).toHaveClass(/connection-chip--fallback/, {timeout: 45_000});
      await expect(header).toHaveScreenshot('header-fallback.png');

    } finally {
      writeDynamicYml(originalConfig);
    }
  });
});
