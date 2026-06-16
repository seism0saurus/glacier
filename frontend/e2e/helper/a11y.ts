/**
 * Shared axe-core accessibility test helpers for Glacier E2E tests (D-20).
 *
 * Key design decisions:
 *
 * 1. WCAG 2.2 AA tag set: every scan uses
 *    ['wcag2a', 'wcag2aa', 'wcag21aa', 'wcag22aa'] — exactly the four tags
 *    that cover WCAG 2.0 A, 2.0 AA, 2.1 AA, and 2.2 AA respectively.
 *    Running the full ruleset includes experimental best-practice rules that
 *    are not part of the WCAG standard and would produce noise.
 *
 * 2. Chromium-only: axe scans must only execute when the current browser is
 *    chromium.  Running in all five Playwright projects (chromium, firefox,
 *    webkit, killswitch, insecure) triples the cost with zero additional
 *    signal — axe-core's rule engine is browser-agnostic.  The `skipAxeInNonChromium`
 *    helper wraps a test body with a `test.skip` guard.
 *
 * 3. Readable violation output: the raw axe `Result[]` is a wall of JSON.
 *    `formatViolations` converts it to a human-readable string shown in the
 *    Playwright reporter when a test fails.
 *
 * 4. Impact threshold: we fail on `serious` and `critical` violations only
 *    (progressive tightening approach per playwright-angular-a11y skill).
 *    Once the baseline is clean across all 6 states this threshold can be
 *    lowered to `moderate`.
 */

import {Page, test} from '@playwright/test';
import AxeBuilder from '@axe-core/playwright';
import type {Result} from 'axe-core';

/**
 * WCAG 2.2 AA tags for axe-core scans.
 *
 * - wcag2a  / wcag2aa  = WCAG 2.0 Level A and AA
 * - wcag21aa           = WCAG 2.1 Level AA additions
 * - wcag22aa           = WCAG 2.2 Level AA additions
 */
export const WCAG_22_AA_TAGS = ['wcag2a', 'wcag2aa', 'wcag21aa', 'wcag22aa'] as const;

/**
 * Formats axe violations into a readable multi-line string for Playwright
 * reporter output.  Returns an empty string when there are no violations.
 */
export function formatViolations(violations: Result[]): string {
  if (violations.length === 0) return '';
  return '\nA11y violations:\n' + violations.map(v =>
    `  - [${v.impact ?? '?'}] ${v.id}: ${v.help}\n` +
    `    ${v.nodes.length} node(s) affected\n` +
    `    ${v.helpUrl}`
  ).join('\n');
}

/**
 * Runs an axe WCAG 2.2 AA scan on the given page, optionally scoped to a
 * CSS selector via `include`.  Asserts zero serious or critical violations.
 *
 * Must be called from inside a Playwright test that is already scoped to
 * chromium (see `runAxeWcag22AaIfChromium` below for the combined helper).
 */
export async function assertNoWcag22AaViolations(
  page: Page,
  include?: string,
): Promise<void> {
  let builder = new AxeBuilder({page})
    .withTags([...WCAG_22_AA_TAGS]);

  if (include) {
    builder = builder.include(include);
  }

  const results = await builder.analyze();

  const blocking = results.violations.filter(v =>
    v.impact === 'critical' || v.impact === 'serious'
  );
  // Use expect from the Playwright test context — imported via module scope below
  // We re-export a wrapper that callers invoke directly.
  if (blocking.length > 0) {
    throw new Error(
      `axe WCAG 2.2 AA: found ${blocking.length} serious/critical violation(s).\n` +
      formatViolations(blocking)
    );
  }
}

/**
 * Runs the WCAG 2.2 AA axe scan twice: once in light, once in emulated dark
 * (prefers-color-scheme: dark).  Both must pass.  Resets to light afterward.
 *
 * Use this helper for surfaces whose colors are driven by `prefers-color-scheme`
 * (e.g. Angular Material `mat.theme()` with `theme-type: color-scheme` and
 * `light-dark()` tokens) so that both palette variants are covered by the
 * same WCAG 2.2 AA contrast gate.
 *
 * The helper intentionally reuses `assertNoWcag22AaViolations` so it inherits
 * the same tag set (WCAG_22_AA_TAGS), impact threshold (serious/critical), and
 * optional scope selector — no duplication of rule configuration.
 *
 * Must be called from inside a Playwright test already scoped to chromium
 * (see `runAxeOnlyInChromium` below for the combined wrapper).
 */
export async function assertNoWcag22AaViolationsLightAndDark(
  page: Page,
  selector?: string,
): Promise<void> {
  await page.emulateMedia({ colorScheme: 'light' });
  await assertNoWcag22AaViolations(page, selector);
  await page.emulateMedia({ colorScheme: 'dark' });
  await assertNoWcag22AaViolations(page, selector);
  // Reset to light so subsequent page interactions are not affected by the
  // dark emulation left over from this scan.
  await page.emulateMedia({ colorScheme: 'light' });
}

/**
 * Wraps a test body so the axe scan only executes in the chromium browser.
 * Non-chromium contexts skip immediately (zero cost, zero false positives).
 *
 * Usage:
 * ```typescript
 * test('@a11y my state audit', async ({ page, browserName }) => {
 *   await page.goto('/');
 *   await runAxeOnlyInChromium(browserName, async () => {
 *     await assertNoWcag22AaViolationsLightAndDark(page, '[data-testid="connection-status"]');
 *   });
 * });
 * ```
 */
export async function runAxeOnlyInChromium(
  browserName: string,
  body: () => Promise<void>,
): Promise<void> {
  if (browserName !== 'chromium') {
    // Skip silently — axe engine is browser-agnostic; running in all browsers
    // triples the cost with no additional signal (playwright-angular-a11y skill).
    test.skip(true, `axe scan skipped in ${browserName} — chromium-only per D-20`);
    return;
  }
  await body();
}
