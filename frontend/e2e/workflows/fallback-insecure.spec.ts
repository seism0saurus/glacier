/**
 * Insecure-connection E2E tests (D-14, D-17, D-19, C-02).
 *
 * Runs against the `insecure` Playwright project.
 * baseURL = http://host.docker.internal:8081 (plain HTTP, no TLS).
 * The backend is exposed via docker-compose.override.insecure.yaml which binds
 * 127.0.0.1:8081:8080; the playwright container reaches it via the
 * extra_hosts gateway bridge (C-02).
 *
 * Toot posting still uses https://proxy (mastodon-client.ts is unchanged).
 *
 * Assertions:
 * - Indicator shows INSECURE state (D-17).
 * - No fallback poller starts (D-14 — INSECURE state suppresses poller).
 * - Popover copy contains the expected explanation text.
 * - axe-core audit: zero serious/critical violations in INSECURE state.
 */

import {expect, test} from '@playwright/test';
import {assertNoWcag22AaViolations} from '../helper/a11y';

test.describe('Insecure connection mode', () => {

  test.beforeEach(async ({page}) => {
    // Navigate to the plain-HTTP backend.  The baseURL is already set to
    // http://host.docker.internal:8081 by the `insecure` Playwright project.
    await page.goto('/');
  });

  // -------------------------------------------------------------------------
  // Indicator shows INSECURE state (D-14, D-17)
  // -------------------------------------------------------------------------
  test('indicator shows INSECURE state on plain-HTTP page', async ({page}) => {
    const indicator = page.locator('[data-testid="connection-status"]');
    await expect(indicator).toBeVisible({timeout: 15_000});
    await expect(indicator).toHaveClass(/connection-chip--insecure/, {timeout: 15_000});
  });

  // -------------------------------------------------------------------------
  // Fallback poller does NOT start (D-14)
  // -------------------------------------------------------------------------
  test('no fallback poll requests are made in INSECURE state', async ({page}) => {
    // Monitor network requests for /rest/messages
    let fallbackPollCalled = false;
    page.on('request', req => {
      if (req.url().includes('/rest/messages')) {
        fallbackPollCalled = true;
      }
    });

    // Wait well past the first poll interval (5 s) to give time for a stray request
    await page.waitForTimeout(8_000);

    expect(fallbackPollCalled).toBe(false);
  });

  // -------------------------------------------------------------------------
  // Popover copy contains the protection explanation (D-17)
  // -------------------------------------------------------------------------
  test('INSECURE popover explains why fallback is disabled', async ({page}) => {
    const indicator = page.locator('[data-testid="connection-status"]');
    await expect(indicator).toHaveClass(/connection-chip--insecure/, {timeout: 15_000});

    // Open the popover
    await indicator.click();

    const detail = page.locator('.connection-menu__detail');
    await expect(detail).toBeVisible({timeout: 5_000});

    // German default text from D-17 (or English translation if browser lang=en)
    await expect(detail).toContainText(/unsichere Verbindung|insecure connection/i);
    await expect(detail).toContainText(/deaktiviert|disabled/i);
  });

  // -------------------------------------------------------------------------
  // No reload CTA in the INSECURE popover (D-17 — no reload for insecure)
  // -------------------------------------------------------------------------
  test('INSECURE popover does NOT show a reload button', async ({page}) => {
    const indicator = page.locator('[data-testid="connection-status"]');
    await expect(indicator).toHaveClass(/connection-chip--insecure/, {timeout: 15_000});

    await indicator.click();
    const cta = page.locator('.connection-menu__cta');
    await expect(cta).not.toBeVisible({timeout: 3_000});
  });

  // -------------------------------------------------------------------------
  // axe-core WCAG 2.2 AA audit: INSECURE state (D-20)
  // The insecure project runs only in chromium (see playwright.config.ts),
  // so no additional browser-skip guard is needed here.
  // -------------------------------------------------------------------------
  test('@a11y axe audit: INSECURE state — zero serious/critical WCAG 2.2 AA violations',
    async ({page}) => {
      const indicator = page.locator('[data-testid="connection-status"]');
      await expect(indicator).toHaveClass(/connection-chip--insecure/, {timeout: 15_000});

      // assertNoWcag22AaViolations applies .withTags([...WCAG_22_AA_TAGS]) and
      // fails on serious/critical violations only (D-20, playwright-angular-a11y skill).
      await assertNoWcag22AaViolations(page, '[data-testid="connection-status"]');
    });
});
