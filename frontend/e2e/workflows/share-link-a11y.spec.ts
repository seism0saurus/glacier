/**
 * E2E a11y spec for the share-link readonly view (a11y project — chromium only).
 *
 * XCUT-09: Refactored the axe scan to use the shared `assertNoWcag22AaViolations`
 * helper from `helper/a11y.ts` wrapped in `runAxeOnlyInChromium`.  The previous
 * inline AxeBuilder usage ran in all five Playwright projects (3× wasted cost)
 * and duplicated the tag list and impact filter from the shared helper.
 *
 * This file matches the glob pattern `**\/*-a11y.spec.ts` so it is routed
 * exclusively to the
 * `a11y` project (chromium), consistent with D-20 and all other axe scans.
 *
 * Setup (`beforeAll`): the previous versions of this spec either navigated to a
 * hardcoded placeholder ID (which 404s — every test timed out) or built the share
 * link through raw HTTP/WebSocket calls that manually extracted and re-attached
 * `Set-Cookie` values, because the `a11y` stack used to serve the wall over plain
 * HTTP: the `Secure`-flagged `wallId` cookie is silently dropped by a real browser
 * on that origin, so neither UI-driven hashtag subscription nor UI-driven share
 * creation could authenticate.
 *
 * The `a11y` stack (infrastructure/docker-compose.override.a11y.yaml) now serves
 * the wall over TLS at https://glacier.proxy — the same realistic-HTTPS setup the
 * `share-https` project uses (Traefik routers + cert SANs in
 * infrastructure-content.tar.gz). On a secure origin the `Secure` cookie survives,
 * so this fixture is built the same way share-wall-roundtrip.spec.ts builds its
 * fixtures: open the wall, subscribe via the UI, post a real toot via the
 * dockerized Mastodon instance (helper/mastodon-client.ts — never inject directly
 * into the app, per CLAUDE.md), wait for it to render on the owner wall (so the
 * per-hashtag cache already holds it), then create the share link via the UI
 * (share-button → create-button). The link's catalog `initialToots` picks up the
 * already-cached toot immediately — this is what gives the "toot articles have
 * role=article" test below real content to assert on, since the readonly wall
 * renders no `<article>` at all when the feed is empty.
 */

import { test, expect } from '@playwright/test';
import { assertNoWcag22AaViolationsLightAndDark, runAxeOnlyInChromium } from '../helper/a11y';
import { MastodonClient } from '../helper/mastodon-client';

const SHARE_TEST_URL_OVERRIDE = process.env['SHARE_TEST_URL'];
const GLACIER_HANDLE = process.env['GLACIER_HANDLE'] || '@glacier_e2e_test@proxy';
const SETUP_HASHTAG = 'glaciera11ysetup';

let SHARE_TEST_URL = SHARE_TEST_URL_OVERRIDE || '/share/test-share-id-placeholder';

test.describe('Share link — accessibility', () => {

  test.beforeAll(async ({ browser }) => {
    if (SHARE_TEST_URL_OVERRIDE) {
      // An external caller already provided a real, active share link — nothing to set up.
      return;
    }

    const context = await browser.newContext();
    const page = await context.newPage();

    // 1. Open the owner wall and subscribe to a hashtag via the UI — identical to
    // share-wall-roundtrip.spec.ts. Requires the realistic HTTPS stack: on a
    // plain-HTTP origin the Secure wallId cookie set on first load would not
    // survive the WebSocket handshake.
    await page.goto('/');
    await expect(page.getByTestId('connection-status')).toBeVisible();
    await page.locator('div').filter({ hasText: 'Followed hashtags' }).nth(3).click();
    await page.getByPlaceholder('New hashtag').fill(SETUP_HASHTAG);
    await page.getByPlaceholder('New hashtag').press('Enter');
    await expect(page.locator(`[id='hashtag-${SETUP_HASHTAG.toLowerCase()}']`)).toBeVisible();

    // 2. Post one real toot via the dockerized Mastodon instance and wait for it to
    // render on the owner wall — proves it already sits in the per-hashtag cache.
    const mastodon = new MastodonClient(
      process.env['MASTODON_USER_API_URL'] ?? 'https://proxy',
      process.env['MASTODON_USER_ACCESS_TOKEN'] ?? '',
    );
    await mastodon.postToot(`Hi ${GLACIER_HANDLE}.\nShare a11y setup toot.\n#${SETUP_HASHTAG}`);
    await expect(page.locator('app-toot')).toHaveCount(1, { timeout: 15_000 });

    // 3. Create the share link via the UI (mirrors ShareLinkService.createShareLink()
    // in share-link.service.ts). Its catalog `initialToots` already contains the
    // toot cached in step 2.
    await page.getByTestId('share-button').click();
    await expect(page.getByTestId('create-button')).toBeVisible();
    await page.getByTestId('create-button').click();

    const urlInput = page.getByTestId('share-url-input');
    await expect(urlInput).toBeVisible({ timeout: 10_000 });
    SHARE_TEST_URL = await urlInput.inputValue();

    await context.close();
  });

  /**
   * XCUT-09: Replaced inline AxeBuilder + withTags() + manual filter with
   * assertNoWcag22AaViolations() wrapped in runAxeOnlyInChromium().
   * Single source of truth; no longer runs in firefox/webkit/killswitch/insecure.
   */
  test('@a11y readonly wall has zero critical/serious axe violations',
    async ({ page, browserName }) => {
      await page.goto(SHARE_TEST_URL);
      await expect(page.getByTestId('share-feed')).toHaveAttribute('aria-busy', 'false', {
        timeout: 15_000,
      });

      await runAxeOnlyInChromium(browserName, async () => {
        await assertNoWcag22AaViolationsLightAndDark(page);
      });
    });

  test('keyboard navigation: Tab moves through toots and links', async ({ page }) => {
    await page.goto(SHARE_TEST_URL);
    await expect(page.getByTestId('share-feed')).toHaveAttribute('aria-busy', 'false', {
      timeout: 15_000,
    });

    // First focusable element after skip links is the feed or first interactive element
    await page.keyboard.press('Tab');
    const focused = await page.evaluate(() => document.activeElement?.tagName);
    expect(['A', 'BUTTON', 'INPUT', 'DETAILS']).toContain(focused);
  });

  test('200% zoom: no horizontal scroll on 360px viewport', async ({ page }) => {
    // Emulate 360px viewport width at 200% zoom (effective CSS width = 180px)
    await page.setViewportSize({ width: 360, height: 640 });
    await page.evaluate(() => {
      (document.body.style as CSSStyleDeclaration & { zoom: string }).zoom = '200%';
    });
    await page.goto(SHARE_TEST_URL);

    const scrollWidth: number = await page.evaluate(() => document.body.scrollWidth);
    const clientWidth: number = await page.evaluate(() => document.body.clientWidth);
    // Allow a small tolerance (1px) for rounding
    expect(scrollWidth).toBeLessThanOrEqual(clientWidth + 1);
  });

  test('skip links are present and keyboard-reachable', async ({ page }) => {
    await page.goto(SHARE_TEST_URL);
    // Wait for the Angular app to hydrate before probing focus order — otherwise Tab
    // can land on not-yet-replaced static shell content instead of the rendered
    // skip-links nav (flaky without this, matching the other tests in this file).
    await expect(page.getByTestId('share-feed')).toHaveAttribute('aria-busy', 'false', {
      timeout: 15_000,
    });

    // First Tab should reach the skip links nav. Case-insensitive: the German source
    // text is "Zum Feed springen" but a Chromium instance with an English default
    // locale (as in this docker image) loads the "Skip to feed" runtime catalog
    // entry instead — both legitimately contain "feed", just with different casing.
    // The point of this assertion is the structural one (first Tab reaches the feed
    // skip link), not which locale happened to be negotiated.
    await page.keyboard.press('Tab');
    const focused = await page.evaluate(() => document.activeElement?.textContent?.trim());
    expect(focused?.toLowerCase()).toContain('feed');
  });

  test('toot articles have role=article with aria-labelledby', async ({ page }) => {
    await page.goto(SHARE_TEST_URL);
    await expect(page.getByTestId('share-feed')).toHaveAttribute('aria-busy', 'false', {
      timeout: 15_000,
    });

    const firstArticle = page.locator('article[role="article"]').first();
    await expect(firstArticle).toBeVisible();
    const labelledBy = await firstArticle.getAttribute('aria-labelledby');
    expect(labelledBy).toBeTruthy();
    // The referenced element must exist
    await expect(page.locator(`#${labelledBy}`)).toBeVisible();
  });
});
