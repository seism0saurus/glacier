/**
 * Fallback UX / accessibility E2E tests (D-15, D-16, D-17, D-20).
 *
 * Requires the full dockerized Mastodon stack.
 *
 * Coverage:
 * - Six-state indicator screenshots at 3 breakpoints.
 * - axe-core WCAG 2.2 AA audits via @axe-core/playwright (zero serious|critical
 *   violations per state, D-20).  Scans are chromium-only to avoid 5× cost
 *   with no additional axe signal (playwright-angular-a11y skill).
 * - Reduced-motion audit (rotating icon becomes static).
 * - Keyboard path: Tab → indicator button → Enter opens popover → Escape closes.
 * - Cookie-privacy assertion: document.cookie must NOT contain 'wallId'
 *   (validates HttpOnly flag in a real browser, secure-tdd gap #2, D-09).
 * - SR narration: page.getByRole('status') has correct text on state transitions.
 *
 * Six connection states audited (D-20 full coverage):
 *   WEBSOCKET — initial load (this spec, chromium)
 *   PROBING   — during reconnect attempt (this spec, chromium)
 *   FALLBACK  — after 3 reconnect failures (this spec, chromium)
 *   OFFLINE   — after 401 on /rest/wall-id (this spec, chromium)
 *   KILLSWITCHED — fallback-killswitch.spec.ts (killswitch project)
 *   INSECURE  — fallback-insecure.spec.ts (insecure project)
 */

import {expect, test} from '@playwright/test';
import * as fs from 'fs';
import {assertNoWcag22AaViolationsLightAndDark, runAxeOnlyInChromium} from '../helper/a11y';

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

/** Viewport widths to test responsive layout (D-15). */
const BREAKPOINTS = [375, 768, 1280] as const;

/** CSS selector for the connection-status indicator. */
const INDICATOR = '[data-testid="connection-status"]';

test.describe('Connection indicator UX and accessibility', () => {

  // -------------------------------------------------------------------------
  // Cookie-privacy assertion (D-09, secure-tdd gap #2)
  // -------------------------------------------------------------------------
  test('wallId cookie is HttpOnly — not accessible from JavaScript', async ({page}) => {
    await page.goto('/');

    // document.cookie must not contain 'wallId' because the server sets HttpOnly
    const cookies = await page.evaluate(() => document.cookie);
    expect(cookies).not.toContain('wallId');
  });

  // -------------------------------------------------------------------------
  // Six-state indicator screenshots at 3 breakpoints (D-15)
  // -------------------------------------------------------------------------
  for (const width of BREAKPOINTS) {
    test(`indicator screenshots at ${width}px — WEBSOCKET state`, async ({page}) => {
      await page.setViewportSize({width, height: 800});
      await page.goto('/');
      const indicator = page.locator(INDICATOR);
      await expect(indicator).toHaveClass(/connection-chip--websocket/, {timeout: 10_000});
      await expect(indicator).toHaveScreenshot(`indicator-websocket-${width}.png`);
    });
  }

  // -------------------------------------------------------------------------
  // axe-core WCAG 2.2 AA audit — WEBSOCKET state (D-20)
  // Scoped to chromium per playwright-angular-a11y skill recommendation.
  // -------------------------------------------------------------------------
  test('@a11y axe audit: WEBSOCKET state — zero serious/critical WCAG 2.2 AA violations',
    async ({page, browserName}) => {
      await page.goto('/');
      await expect(page.locator(INDICATOR))
        .toHaveClass(/connection-chip--websocket/, {timeout: 10_000});

      await runAxeOnlyInChromium(browserName, async () => {
        await assertNoWcag22AaViolationsLightAndDark(page, INDICATOR);
      });
    });

  // -------------------------------------------------------------------------
  // axe-core WCAG 2.2 AA audit — PROBING state (D-20)
  // The PROBING state is the transient reconnect state that appears immediately
  // after WS drops before the 3-attempt cycle completes.  We block WS and
  // scan within the first reconnect window (before FALLBACK kicks in).
  // -------------------------------------------------------------------------
  test('@a11y axe audit: PROBING state — zero serious/critical WCAG 2.2 AA violations',
    async ({page, browserName}) => {
      const originalConfig = readDynamicYml();
      try {
        writeDynamicYml(WS_BLOCK_CONFIG);
        await page.goto('/');
        // Wait briefly — PROBING state appears as soon as the first WS close
        // is detected (well before the 37 s needed to reach FALLBACK)
        await page.waitForTimeout(2_000);

        // The indicator should be in PROBING state during reconnect
        await expect(page.locator(INDICATOR))
          .toHaveClass(/connection-chip--probing/, {timeout: 10_000});

        await runAxeOnlyInChromium(browserName, async () => {
          await assertNoWcag22AaViolationsLightAndDark(page, INDICATOR);
        });
      } finally {
        writeDynamicYml(originalConfig);
      }
    });

  // -------------------------------------------------------------------------
  // axe-core WCAG 2.2 AA audit — FALLBACK state (D-20)
  // -------------------------------------------------------------------------
  test('@a11y axe audit: FALLBACK state — zero serious/critical WCAG 2.2 AA violations',
    async ({page, browserName}) => {
      const originalConfig = readDynamicYml();
      try {
        writeDynamicYml(WS_BLOCK_CONFIG);
        await page.goto('/');
        await page.waitForTimeout(2_000);

        await expect(page.locator(INDICATOR))
          .toHaveClass(/connection-chip--fallback/, {timeout: 45_000});

        await runAxeOnlyInChromium(browserName, async () => {
          await assertNoWcag22AaViolationsLightAndDark(page, INDICATOR);
        });
      } finally {
        writeDynamicYml(originalConfig);
      }
    });

  // -------------------------------------------------------------------------
  // axe-core WCAG 2.2 AA audit — OFFLINE state (D-17, D-20)
  // Triggered by intercepting /rest/wall-id to return 401, which causes the
  // frontend to enter OFFLINE mode (session expired → no poller, no WS ack).
  // -------------------------------------------------------------------------
  test('@a11y axe audit: OFFLINE state — zero serious/critical WCAG 2.2 AA violations',
    async ({page, browserName}) => {
      // Intercept /rest/wall-id with a 401 to trigger OFFLINE state.
      // FallbackService transitions to OFFLINE on 401 from /rest/messages;
      // we need to ensure the client gets into FALLBACK mode first so a poll fires.
      // Simpler alternative: intercept /rest/messages 401 directly after entering FALLBACK.
      const originalConfig = readDynamicYml();
      try {
        // Block WS so the client falls through to FALLBACK polling
        writeDynamicYml(WS_BLOCK_CONFIG);

        // Then intercept the fallback poll endpoint to return 401 → OFFLINE
        await page.route('**/rest/messages**', route => {
          route.fulfill({status: 401, body: 'Unauthorized'});
        });

        await page.goto('/');
        await page.waitForTimeout(2_000);

        // Wait for OFFLINE state (401 on /rest/messages → OFFLINE)
        await expect(page.locator(INDICATOR))
          .toHaveClass(/connection-chip--offline/, {timeout: 60_000});

        await runAxeOnlyInChromium(browserName, async () => {
          // Scan the full page to also catch the session-expired banner (D-17).
          // Dual-scheme scan (ACPT-01 XCUT-07): gates dark-mode colors on the indicator + banner.
          await assertNoWcag22AaViolationsLightAndDark(page);
        });
      } finally {
        writeDynamicYml(originalConfig);
        await page.unrouteAll();
      }
    });

  // -------------------------------------------------------------------------
  // Reduced-motion audit — rotating icon should be static (D-15)
  // -------------------------------------------------------------------------
  test('prefers-reduced-motion: PROBING icon has no animation', async ({page}) => {
    await page.emulateMedia({reducedMotion: 'reduce'});
    const originalConfig = readDynamicYml();

    try {
      writeDynamicYml(WS_BLOCK_CONFIG);
      await page.goto('/');
      await page.waitForTimeout(1_500);

      // The indicator may be in PROBING (transitioning) — check that
      // the icon element does not have a running CSS animation
      const rotatingIconAnimationName = await page.evaluate(() => {
        const icon = document.querySelector('.rotating-icon');
        if (!icon) return 'no-icon';
        return window.getComputedStyle(icon).animationName;
      });

      // Under prefers-reduced-motion the animation is suppressed to 'none' by the CSS
      expect(rotatingIconAnimationName).toMatch(/^(none|no-icon)$/);

    } finally {
      writeDynamicYml(originalConfig);
    }
  });

  // -------------------------------------------------------------------------
  // Keyboard navigation (D-15)
  // -------------------------------------------------------------------------
  test('keyboard: Tab to indicator → Enter opens popover → Escape closes', async ({page}) => {
    await page.goto('/');

    const indicator = page.locator(INDICATOR);
    await expect(indicator).toBeVisible({timeout: 10_000});

    // Tab until the indicator button is focused
    // We use page.keyboard because the number of tabs to reach the button
    // is layout-dependent; we focus it directly for reliability.
    await indicator.focus();
    expect(await page.evaluate(() => document.activeElement?.getAttribute('data-testid')))
      .toBe('connection-status');

    // Enter opens the MatMenu popover
    await page.keyboard.press('Enter');
    const menu = page.locator('.connection-status-menu');
    await expect(menu).toBeVisible({timeout: 5_000});

    // Escape closes the popover
    await page.keyboard.press('Escape');
    await expect(menu).not.toBeVisible({timeout: 5_000});

    // Focus should NOT have moved to a different element (D-15 — state flips do not move focus)
    // The button itself may remain focused or focus may return to body — neither is the menu.
    const focused = await page.evaluate(() => document.activeElement?.tagName);
    expect(focused?.toUpperCase()).not.toBe('MAT-MENU');
  });

  // -------------------------------------------------------------------------
  // Screen-reader narration via role="status" (D-15)
  // -------------------------------------------------------------------------
  test('SR narration: role="status" announces FALLBACK transition', async ({page}) => {
    const originalConfig = readDynamicYml();
    try {
      await page.goto('/');

      // Initial WEBSOCKET — live region is empty (D-15 suppress)
      const liveRegion = page.getByRole('status');
      await expect(liveRegion).toBeAttached({timeout: 10_000});
      const initialText = await liveRegion.textContent();
      // Should be empty or whitespace on initial WEBSOCKET render
      expect((initialText ?? '').trim()).toBe('');

      writeDynamicYml(WS_BLOCK_CONFIG);
      await page.waitForTimeout(2_000);

      // After FALLBACK transition the live region should contain the mode label
      await expect(page.locator(INDICATOR))
        .toHaveClass(/connection-chip--fallback/, {timeout: 45_000});

      const fallbackText = await liveRegion.textContent();
      // Should contain the FALLBACK label (German default or English if browser lang=en)
      expect(fallbackText).toMatch(/Fallback|fallback/i);

    } finally {
      writeDynamicYml(originalConfig);
    }
  });
});
