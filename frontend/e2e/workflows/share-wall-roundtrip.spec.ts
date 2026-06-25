/**
 * E2E spec: full social-wall + share-view round trip (chromium project).
 *
 * User journey under test:
 *   1. Owner opens the social wall and waits for live streaming.
 *   2. Owner subscribes to MULTIPLE hashtags (tags).
 *   3. Owner posts toots that surface on the OWN wall (embedded iframes).
 *   4. Owner opens a share link ("shared wall") and a second browser context
 *      (the viewer) opens that read-only wall.
 *   5. A further toot is posted and surfaces on BOTH the owner wall and the
 *      shared wall (live relay).
 *   6. Owner closes (revokes) the shared wall; the viewer is redirected to the
 *      /expired route.
 *
 * Opt-in rule (StompCallback): a toot only reaches the wall if it mentions the
 * bot handle AND carries a subscribed hashtag (see toots.spec.ts, which proves
 * a hashtag-only toot is dropped). The share feed is downstream of the same
 * filter, so EVERY toot posted here mentions `${glacier_handle}`.
 *
 * No-mock rule (CLAUDE.md / playwright-e2e-patterns): all toots are posted
 * through the real dockerized Mastodon API via mastodon-client.ts — never
 * injected directly into the Spring app.
 */

import { test, expect, BrowserContext, Page } from '@playwright/test';
import { MastodonClient } from '../helper/mastodon-client';

const glacier_handle = process.env['GLACIER_HANDLE'] || '@glacier_e2e_test@proxy';

// Two distinct hashtags so the "add some tags" step is genuinely plural and the
// wall has to aggregate more than one subscription stream.
const TAG_A = 'glacierwallshareone';
const TAG_B = 'glacierwallsharetwo';

/** Build a toot body that satisfies the opt-in rule (mention + hashtag). */
function toot(marker: string, tag: string): string {
  return `Hi ${glacier_handle}.\n${marker}\n#${tag}`;
}

/** Open the followed-hashtags panel and subscribe to one hashtag. */
async function subscribe(page: Page, tag: string): Promise<void> {
  await page.getByPlaceholder('New hashtag').fill(tag);
  await page.getByPlaceholder('New hashtag').press('Enter');
  await expect(page.locator(`[id='hashtag-${tag.toLowerCase()}']`)).toBeVisible();
}

/**
 * Wait until the owner wall holds exactly `count` toots and the newest one is
 * visible. The owner wall renders each toot as a cross-origin Mastodon embed
 * iframe; we assert presence + count rather than reaching into the iframe body
 * (the embed shell renders before its content streams in, which makes deep text
 * extraction flaky — toots.spec.ts's primary "is visible" test asserts the same
 * way). Exact-content verification happens on the read-only share wall, which
 * renders toot text directly in the DOM (see the `getByText` assertions below).
 */
async function expectWallTootCount(page: Page, count: number): Promise<void> {
  await expect(page.locator('app-toot')).toHaveCount(count, { timeout: 15_000 });
  await expect(page.locator('app-toot').first()).toBeVisible();
}

test.describe('Social wall + shared wall — full round trip', () => {

  // Full live round trip. Requires the realistic-HTTPS stack: the owner wall served
  // over TLS at https://glacier.proxy and the share view at https://share.glacier.proxy
  // (Traefik routing + ALPN http/1.1 + cert SANs in infrastructure/dynamic.yml / v3.ext /
  // proxy.crt — packed in infrastructure-content.tar.gz; glacier env MY_DOMAIN=glacier.proxy
  // + GLACIER_SHARE_HOST=share.glacier.proxy; playwright BASE_URL=https://glacier.proxy).
  // The toot-rendering MVP (catalog hashtags + per-link ReadonlyTootView relay on the
  // generic streaming path + frontend subscribe-after-catalog) makes the live render work.
  test(
    'owner builds a wall with tags, posts toots, shares the wall, posts on the shared wall, then closes it',
    async ({ browser }) => {
      const mastodon = new MastodonClient(
        process.env['MASTODON_USER_API_URL'] ?? 'https://proxy',
        process.env['MASTODON_USER_ACCESS_TOKEN'] ?? '',
      );

      // Unique per run so assertions never collide with toots from earlier runs.
      const run = Date.now();
      const markerA = `wall toot A ${run}`;
      const markerB = `wall toot B ${run}`;
      const markerShared = `shared wall toot ${run}`;

      // ---------------------------------------------------------------
      // 1. Owner opens the social wall and waits for live streaming.
      // ---------------------------------------------------------------
      const ownerContext: BrowserContext = await browser.newContext();
      const ownerPage: Page = await ownerContext.newPage();

      await ownerPage.goto('/');
      // Shell rendered = the connection-status chip is present. We deliberately do
      // NOT assert a specific transport state: when Glacier is reached over a plain
      // HTTP origin (as in the dockerized e2e, BASE_URL=http://glacier:8080) the app
      // runs in INSECURE mode — the WebSocket still streams live, only the HTTP
      // fallback is disabled. Live streaming is proven below by the posted toots
      // surfacing on the wall (the same approach as toots.spec.ts).
      await expect(ownerPage.getByTestId('connection-status')).toBeVisible();

      // ---------------------------------------------------------------
      // 2. Owner adds some tags (two hashtag subscriptions).
      // ---------------------------------------------------------------
      // Expand the "Followed hashtags" panel that holds the new-hashtag input.
      await ownerPage.locator('div').filter({ hasText: 'Followed hashtags' }).nth(3).click();
      await subscribe(ownerPage, TAG_A);
      await subscribe(ownerPage, TAG_B);

      // ---------------------------------------------------------------
      // 3. Owner posts toots that surface on the OWN wall.
      // ---------------------------------------------------------------
      await mastodon.postToot(toot(markerA, TAG_A));
      await expectWallTootCount(ownerPage, 1);

      await mastodon.postToot(toot(markerB, TAG_B));
      await expectWallTootCount(ownerPage, 2);

      // ---------------------------------------------------------------
      // 4. Owner opens a share link; a viewer opens the shared wall.
      // ---------------------------------------------------------------
      const shareButton = ownerPage.getByTestId('share-button');
      await expect(shareButton).toBeVisible();
      await shareButton.click();

      await expect(ownerPage.getByTestId('create-button')).toBeVisible();
      await ownerPage.getByTestId('create-button').click();

      const urlInput = ownerPage.getByTestId('share-url-input');
      await expect(urlInput).toBeVisible({ timeout: 10_000 });
      const shareUrl = await urlInput.inputValue();
      expect(shareUrl).toContain('/share/');

      // Keep the share dialog open: the freshly-created link is shown as the active-link
      // card (revoke-button), which carries the full shareLinkId needed to revoke. Reopening
      // the dialog would instead show the link in the "Active links" list, whose row only
      // exposes idHash8 (the full token is never re-served, by design) — so list revocation
      // is a separate, pre-existing concern outside this MVP. We revoke via the active card.

      const viewerContext: BrowserContext = await browser.newContext();
      const viewerPage: Page = await viewerContext.newPage();

      await viewerPage.goto(shareUrl);
      await expect(viewerPage.getByTestId('share-banner')).toBeVisible({ timeout: 10_000 });
      await expect(viewerPage.getByTestId('share-feed')).toBeVisible();

      // Wait for the viewer's share-view WebSocket to reach LIVE before posting — the
      // STOMP topic subscription is only active once connected, and STOMP does not replay,
      // so a toot posted during the connect window would be missed (MVP ships initialToots
      // empty — no catalog backfill). This is the skill's stable-state wait, the viewer-side
      // analogue of waiting on connection-status before actions that assume live streaming.
      await expect(viewerPage.getByTestId('transport-status'))
          .toHaveClass(/transport-status--live/, { timeout: 15_000 });

      // ---------------------------------------------------------------
      // 5. Post on the shared wall — surfaces on BOTH walls (live relay).
      // ---------------------------------------------------------------
      await mastodon.postToot(toot(markerShared, TAG_A));

      // Viewer (read-only wall) renders relayed toots as text, not iframes —
      // this is the exact-content proof that the specific toot crossed the relay.
      await expect(viewerPage.getByText(markerShared)).toBeVisible({ timeout: 15_000 });

      // Owner wall picks up the same toot as a third embed.
      await expectWallTootCount(ownerPage, 3);

      // ---------------------------------------------------------------
      // 6. Owner closes (revokes) the shared wall → viewer hits /expired.
      // ---------------------------------------------------------------
      // The dialog is still open from step 4, showing the active-link card.
      const revokeButton = ownerPage.getByTestId('revoke-button');
      await expect(revokeButton).toBeVisible();
      await revokeButton.click();
      await expect(ownerPage.getByTestId('revoke-confirm')).toBeVisible();
      await ownerPage.getByTestId('revoke-confirm').click();

      await expect(viewerPage).toHaveURL(/\/expired/, { timeout: 10_000 });

      await ownerContext.close();
      await viewerContext.close();
    },
  );
});
