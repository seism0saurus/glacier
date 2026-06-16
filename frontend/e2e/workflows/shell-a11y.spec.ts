/**
 * XCUT-08: Axe-core WCAG 2.2 AA accessibility scans for surfaces that had
 * NO automated a11y coverage before this spec was added:
 *
 *   - Header (h1 + share button) and Footer (legal button + copyright)
 *   - GDPR / Legal Notice dialog (highest-risk gap — modal with long content)
 *   - Share-create dialog:  empty/create state, created state (URL + QR),
 *     and revoke-confirmation state
 *   - Share-expired page  (/share/:id/expired)
 *
 * All scans use `assertNoWcag22AaViolations` + `runAxeOnlyInChromium` from
 * `helper/a11y.ts` (D-20: axe engine is browser-agnostic; running in 5
 * projects adds cost with zero additional signal).
 *
 * Project assignment: `a11y` (testMatch glob `**\/*-a11y.spec.ts`, chromium).
 * Runtime requires the full dockerized Mastodon + Glacier stack
 * (see CLAUDE.md and infrastructure/README.md).  Tests collect cleanly
 * without the stack; they are executed in CI.
 *
 * Individual-toot media/CW/poll coverage:
 * The wall axe scans in `subscriptions-prune-a11y.spec.ts` (A4) already scan
 * the full page including all rendered toot components.  There is no
 * deterministic fixture toot with media/CW/poll in the seed snapshot that can
 * be targeted by a selector without posting via Mastodon.  Adding a focused
 * scan here would duplicate A4's coverage and require the same Mastodon post
 * setup.  Individual-toot media/CW/poll axe coverage therefore remains part of
 * the wall scan in A4 — documented here to avoid re-opening the question.
 */

import { test, expect } from '@playwright/test';
import { assertNoWcag22AaViolations, runAxeOnlyInChromium } from '../helper/a11y';

// ---------------------------------------------------------------------------
// Header and Footer (main wall)
// ---------------------------------------------------------------------------
test.describe('XCUT-08: Header and Footer — axe WCAG 2.2 AA', () => {

  test.beforeEach(async ({ page }) => {
    await page.goto('/');
    // Wait for the connection-status chip to render so the header is fully
    // hydrated before we scan.
    await expect(page.locator('[data-testid="connection-status"]'))
      .toBeVisible({ timeout: 10_000 });
  });

  /**
   * Scans the `<header>` element which contains: logo, h1, share button
   * (data-testid="share-button"), and the connection-status chip.
   */
  test('@a11y header: zero serious/critical WCAG 2.2 AA violations',
    async ({ page, browserName }) => {
      await runAxeOnlyInChromium(browserName, async () => {
        await assertNoWcag22AaViolations(page, 'header');
      });
    });

  /**
   * Scans the `<footer>` element which contains: how-to text, legal button,
   * and copyright link.
   */
  test('@a11y footer: zero serious/critical WCAG 2.2 AA violations',
    async ({ page, browserName }) => {
      await runAxeOnlyInChromium(browserName, async () => {
        await assertNoWcag22AaViolations(page, 'footer');
      });
    });

});

// ---------------------------------------------------------------------------
// GDPR / Legal Notice dialog
// ---------------------------------------------------------------------------
test.describe('XCUT-08: GDPR / Legal Notice dialog — axe WCAG 2.2 AA', () => {

  /**
   * Opens the legal dialog via the footer "Legal Notice & GDPR" button and
   * scans the Material dialog overlay (mat-dialog-container).
   *
   * The dialog is opened by clicking the `button.legal` element in the footer,
   * which calls `openLegal()` on FooterComponent.  The resulting MatDialog
   * wraps GdprComponent in a `<mat-dialog-container>`.
   *
   * This is the highest-risk gap identified in the audit (XCUT-08):
   * - Long scrollable dialog content
   * - Lists, links, and headings inside a modal
   * - Close button accessibility
   */
  test('@a11y legal dialog: zero serious/critical WCAG 2.2 AA violations',
    async ({ page, browserName }) => {
      await page.goto('/');
      await expect(page.locator('[data-testid="connection-status"]'))
        .toBeVisible({ timeout: 10_000 });

      // Open the GDPR dialog via footer button
      await page.locator('footer button.legal').click();

      // Wait for the Material dialog overlay to appear
      const dialog = page.locator('mat-dialog-container');
      await expect(dialog).toBeVisible({ timeout: 5_000 });

      // Verify the dialog title is visible before scanning
      await expect(page.locator('#legal-notice')).toBeVisible();

      await runAxeOnlyInChromium(browserName, async () => {
        // Scope the scan to the dialog container to avoid false positives
        // from the aria-hidden background content (MatDialog sets aria-hidden
        // on the rest of the DOM while the modal is open).
        await assertNoWcag22AaViolations(page, 'mat-dialog-container');
      });

      // Clean up: close the dialog so it does not bleed into subsequent tests
      await page.locator('#legal-notice-close-button').click();
      await expect(dialog).not.toBeVisible({ timeout: 3_000 });
    });

});

// ---------------------------------------------------------------------------
// Share-create dialog — empty/create state (no existing links)
// ---------------------------------------------------------------------------
test.describe('XCUT-08: Share-create dialog — axe WCAG 2.2 AA', () => {

  /**
   * Opens the share dialog via the header share button
   * (data-testid="share-button" / data-testid="qr-badge-button").
   *
   * The QR-badge button (on QrCodeComponent) is the primary entry point used
   * by all other share-link e2e specs.  The header also has a share button
   * (data-testid="share-button") that delegates to the same dialog.  We use
   * the qr-badge-button to stay consistent with the existing round-trip specs.
   *
   * Note: The share dialog requires a live STOMP connection to fetch existing
   * links (`GET /rest/share-links`).  We wait for the connection-status chip
   * to reach the `live` data-state before opening the dialog to avoid
   * scanning a partially-hydrated dialog.
   */
  test('@a11y share dialog — empty/create state: zero serious/critical WCAG 2.2 AA violations',
    async ({ page, browserName }) => {
      await page.goto('/');
      // Wait for live connection so the dialog can list existing links
      await expect(page.locator('[data-testid="connection-status"]'))
        .toHaveAttribute('data-state', 'live', { timeout: 10_000 });

      // Open share dialog
      await page.getByTestId('qr-badge-button').click();

      const dialog = page.locator('mat-dialog-container');
      await expect(dialog).toBeVisible({ timeout: 5_000 });

      // Verify the create button is present (empty / no-link state)
      await expect(page.getByTestId('create-button')).toBeVisible({ timeout: 5_000 });

      await runAxeOnlyInChromium(browserName, async () => {
        await assertNoWcag22AaViolations(page, 'mat-dialog-container');
      });

      // Clean up
      await page.getByTestId('close-button').click();
      await expect(dialog).not.toBeVisible({ timeout: 3_000 });
    });

  /**
   * Creates a share link and then scans the "created" state which shows:
   * - QR code canvas (app-qr-code)
   * - URL input (data-testid="share-url-input")
   * - Copy button (data-testid="copy-button")
   * - Shown-once warning paragraph
   * - Expiry text
   * - Revoke button (data-testid="revoke-button")
   *
   * We scan this state because it introduces several new interactive and
   * informational elements not present in the empty state.
   */
  test('@a11y share dialog — created state: zero serious/critical WCAG 2.2 AA violations',
    async ({ page, browserName }) => {
      await page.goto('/');
      await expect(page.locator('[data-testid="connection-status"]'))
        .toHaveAttribute('data-state', 'live', { timeout: 10_000 });

      // Open dialog and create a link
      await page.getByTestId('qr-badge-button').click();
      const dialog = page.locator('mat-dialog-container');
      await expect(dialog).toBeVisible({ timeout: 5_000 });

      await page.getByTestId('create-button').click();

      // Wait for the created state: URL input must be visible and populated
      const urlInput = page.getByTestId('share-url-input');
      await expect(urlInput).toBeVisible({ timeout: 10_000 });
      const shareUrl = await urlInput.inputValue();
      expect(shareUrl).toMatch(/\/share\/[A-Za-z0-9_-]{10,}/);

      await runAxeOnlyInChromium(browserName, async () => {
        await assertNoWcag22AaViolations(page, 'mat-dialog-container');
      });

      // Clean up: revoke the link to avoid leaving dangling test links,
      // then close the dialog.  We accept a failure here gracefully so that
      // a revoke error does not mask the axe result above.
      try {
        await page.getByTestId('revoke-button').click();
        // The revoke opens a confirmation dialog — cancel it to keep clean state
        const revokeCancel = page.getByTestId('revoke-cancel');
        await expect(revokeCancel).toBeVisible({ timeout: 3_000 });
        await revokeCancel.click();
      } catch {
        // Ignore cleanup errors
      }
      await page.getByTestId('close-button').click();
      await expect(dialog).not.toBeVisible({ timeout: 3_000 });
    });

  /**
   * Opens the revoke-confirmation dialog and scans it.
   *
   * The revoke-confirm dialog (ShareRevokeConfirmDialogComponent) is a
   * second-level Material dialog opened from inside the share dialog.
   * Focus should land on "Abbrechen" (revoke-cancel) per WCAG 3.3.4.
   *
   * We scan this state because it is a modal dialog with a destructive action
   * and must satisfy the same WCAG 2.2 AA requirements.
   */
  test('@a11y share dialog — revoke-confirm state: zero serious/critical WCAG 2.2 AA violations',
    async ({ page, browserName }) => {
      await page.goto('/');
      await expect(page.locator('[data-testid="connection-status"]'))
        .toHaveAttribute('data-state', 'live', { timeout: 10_000 });

      // Open dialog and create a link to reach the state that has a revoke button
      await page.getByTestId('qr-badge-button').click();
      const shareDialog = page.locator('mat-dialog-container').first();
      await expect(shareDialog).toBeVisible({ timeout: 5_000 });

      await page.getByTestId('create-button').click();

      const urlInput = page.getByTestId('share-url-input');
      await expect(urlInput).toBeVisible({ timeout: 10_000 });

      // Click revoke to open the confirmation dialog
      await page.getByTestId('revoke-button').click();

      const revokeDialog = page.locator('mat-dialog-container').last();
      await expect(revokeDialog).toBeVisible({ timeout: 5_000 });

      // Wait for focus to settle on the cancel button
      await expect(page.getByTestId('revoke-cancel')).toBeVisible({ timeout: 3_000 });

      await runAxeOnlyInChromium(browserName, async () => {
        // Scan only the topmost dialog — the backdrop makes the share dialog
        // aria-hidden, so we scan the last (topmost) mat-dialog-container.
        await assertNoWcag22AaViolations(page, 'mat-dialog-container:last-of-type');
      });

      // Clean up: cancel the revoke, then close the share dialog
      await page.getByTestId('revoke-cancel').click();
      await expect(revokeDialog).not.toBeVisible({ timeout: 3_000 });
      await page.getByTestId('close-button').click();
    });

});

// ---------------------------------------------------------------------------
// Share-expired page  (/share/:shareId/expired)
// ---------------------------------------------------------------------------
test.describe('XCUT-08: Share-expired page — axe WCAG 2.2 AA', () => {

  /**
   * Navigates directly to a share/expired URL (the route is accessible without
   * a valid share-link guard — ShareExpiredComponent is loaded on any
   * `/share/:shareId/expired` path).
   *
   * The page is static (no data fetching) and shows:
   * - main[aria-labelledby="expired-heading"]
   * - h1 with tabindex="-1" that receives focus via AfterViewInit
   * - body paragraph
   * - "Zur Startseite" routerLink button
   *
   * We use a synthetic shareId so the test does not depend on any seed data.
   * The route `:shareId/expired` loads ShareExpiredComponent unconditionally.
   */
  test('@a11y share-expired page: zero serious/critical WCAG 2.2 AA violations',
    async ({ page, browserName }) => {
      // Navigate to the expired route with a synthetic share ID.
      // The route is path: ':shareId/expired' with no guard — it renders
      // ShareExpiredComponent directly regardless of the shareId value.
      await page.goto('/share/synthetic-expired-id-for-axe-test/expired');

      // Wait for the expired heading so the component is fully rendered
      const heading = page.locator('#expired-heading');
      await expect(heading).toBeVisible({ timeout: 10_000 });

      // Verify focus has moved to the heading (AfterViewInit behaviour VIEW-06)
      // We check via evaluate because Playwright's isFocused() requires a
      // known element handle, and the heading has tabindex="-1".
      const isFocused = await page.evaluate(
        () => document.activeElement?.id === 'expired-heading'
      );
      expect(isFocused).toBe(true);

      await runAxeOnlyInChromium(browserName, async () => {
        // Scan main landmark — contains all meaningful expired-page content.
        await assertNoWcag22AaViolations(page, 'main');
      });
    });

});
