/**
 * Kill-switch E2E tests (D-17, D-19).
 *
 * Runs against the `killswitch` Playwright project whose baseURL points to a
 * Glacier backend started with GLACIER_FALLBACK_ENABLED=false via
 * docker-compose.override.killswitch.yaml.
 *
 * Assertions:
 * - GET /rest/messages returns 404.
 * - Indicator shows KILLSWITCHED when WS is also down.
 * - Indicator is NOT visible (or shows WEBSOCKET) while WS is up.
 */

import {expect, test} from '@playwright/test';
import * as fs from 'fs';
import {assertNoWcag22AaViolations} from '../helper/a11y';

const DYNAMIC_YML = process.env['TRAEFIK_DYNAMIC_YML_PATH']
  || '/home/ulrich.viefhaus/git/seism0saurus/glacier/infrastructure/dynamic.yml';

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

test.describe('Kill-switch mode', () => {

  // -------------------------------------------------------------------------
  // GET /rest/messages returns 404 when kill-switch is active (D-11)
  // -------------------------------------------------------------------------
  test('GET /rest/messages returns 404 with kill switch active', async ({request}) => {
    const response = await request.get('/rest/messages?hashtag=test&since=0', {
      headers: {
        // wallId cookie would normally be set by the browser; for the raw API test
        // a missing cookie should also yield 404 (kill switch checked before auth)
      },
    });
    expect(response.status()).toBe(404);
  });

  // -------------------------------------------------------------------------
  // Indicator shows KILLSWITCHED when WS is also broken (D-17)
  // -------------------------------------------------------------------------
  test('indicator shows KILLSWITCHED state when WS is down and kill switch is on', async ({page}) => {
    const originalConfig = readDynamicYml();

    try {
      // Break WS so the client attempts fallback polling (and gets 404)
      writeDynamicYml(WS_BLOCK_CONFIG);
      await page.goto('/');
      await page.waitForTimeout(2_000);

      const indicator = page.locator('[data-testid="connection-status"]');
      await expect(indicator).toBeVisible({timeout: 10_000});

      // After the 3-attempt cycle + fallback poll attempt, 404 → KILLSWITCHED (D-17)
      await expect(indicator).toHaveClass(/connection-chip--killswitched/, {timeout: 60_000});

      // Popover should contain the operator explanation
      await indicator.click();
      const popoverDetail = page.locator('.connection-menu__detail');
      await expect(popoverDetail).toBeVisible({timeout: 5_000});
      await expect(popoverDetail).toContainText(/deaktiviert|disabled/i);

    } finally {
      writeDynamicYml(originalConfig);
    }
  });

  // -------------------------------------------------------------------------
  // Indicator is NOT killswitched while WS is up (D-17 — invisible while WS up)
  // -------------------------------------------------------------------------
  test('indicator shows WEBSOCKET (not KILLSWITCHED) while WS is up', async ({page}) => {
    await page.goto('/');

    const indicator = page.locator('[data-testid="connection-status"]');
    await expect(indicator).toBeVisible({timeout: 10_000});

    // With WS up, the indicator should be in WEBSOCKET state regardless of kill switch
    // (the kill switch only affects the HTTP fallback path)
    await expect(indicator).toHaveClass(/connection-chip--websocket/, {timeout: 15_000});
    await expect(indicator).not.toHaveClass(/connection-chip--killswitched/);
  });

  // -------------------------------------------------------------------------
  // axe-core WCAG 2.2 AA audit — KILLSWITCHED state (D-20)
  // The killswitch project runs only in chromium (see playwright.config.ts),
  // so no additional browser-skip guard is needed here.
  // -------------------------------------------------------------------------
  test('@a11y axe audit: KILLSWITCHED state — zero serious/critical WCAG 2.2 AA violations',
    async ({page}) => {
      const originalConfig = readDynamicYml();
      try {
        // Break WS so the client falls through to fallback polling, which
        // returns 404 (kill switch active) → KILLSWITCHED state.
        writeDynamicYml(WS_BLOCK_CONFIG);
        await page.goto('/');
        await page.waitForTimeout(2_000);

        const indicator = page.locator('[data-testid="connection-status"]');
        await expect(indicator).toBeVisible({timeout: 10_000});

        // Wait for KILLSWITCHED state (after 3-attempt cycle + one 404 poll)
        await expect(indicator).toHaveClass(/connection-chip--killswitched/, {timeout: 60_000});

        // assertNoWcag22AaViolations applies .withTags([...WCAG_22_AA_TAGS]) and
        // fails on serious/critical violations only (D-20).
        await assertNoWcag22AaViolations(page, '[data-testid="connection-status"]');
      } finally {
        writeDynamicYml(originalConfig);
      }
    });
});
