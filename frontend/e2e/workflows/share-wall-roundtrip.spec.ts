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

      // Keep the share dialog open and revoke via the active-link card (revoke-button) for
      // spec simplicity. Both the active card and the reopened "Active links" list now revoke
      // by the non-secret idHash8 (DELETE /rest/share-links/{idHash8}); the token is never
      // re-served. List-row revocation (the row's revoke-row-button) is covered at the unit
      // level in share-dialog.component.spec.ts.

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

  // Complete coverage of the list-revoke path — the one the original "undefined-id" bug broke.
  // Instead of revoking the freshly-created link via the active-link card, the owner CLOSES and
  // REOPENS the dialog so the link appears in the "Active links" LIST (whose row only carries the
  // non-secret idHash8, never the token), and revokes from there. Also asserts the DELETE targets
  // /rest/share-links/{idHash8} — proving the token no longer leaks into request URLs / proxy logs.
  test(
    'owner revokes a shared wall from the reopened "Active links" list (by idHash8), viewer expires',
    async ({ browser }) => {
      const mastodon = new MastodonClient(
        process.env['MASTODON_USER_API_URL'] ?? 'https://proxy',
        process.env['MASTODON_USER_ACCESS_TOKEN'] ?? '',
      );

      const run = Date.now();
      const markerShared = `list-revoke shared toot ${run}`;

      // 1. Owner opens the wall, waits for the shell, subscribes to one hashtag.
      const ownerContext: BrowserContext = await browser.newContext();
      const ownerPage: Page = await ownerContext.newPage();
      await ownerPage.goto('/');
      await expect(ownerPage.getByTestId('connection-status')).toBeVisible();
      await ownerPage.locator('div').filter({ hasText: 'Followed hashtags' }).nth(3).click();
      await subscribe(ownerPage, TAG_A);

      // 2. Create a share link (shown once as the active-link card).
      await ownerPage.getByTestId('share-button').click();
      await expect(ownerPage.getByTestId('create-button')).toBeVisible();
      await ownerPage.getByTestId('create-button').click();
      const urlInput = ownerPage.getByTestId('share-url-input');
      await expect(urlInput).toBeVisible({ timeout: 10_000 });
      const shareUrl = await urlInput.inputValue();
      expect(shareUrl).toContain('/share/');
      const shareToken = shareUrl.split('/share/').pop() ?? '';
      expect(shareToken.length).toBeGreaterThan(20); // the real opaque token, not idHash8

      // 3. Close the dialog and REOPEN it: the link now renders in the "Active links" list,
      //    whose revoke-row-button is wired to the link's idHash8 (the token is never re-served).
      await ownerPage.getByTestId('close-button').click();
      await expect(ownerPage.getByTestId('share-url-input')).toBeHidden();
      await ownerPage.getByTestId('share-button').click();
      const revokeRow = ownerPage.getByTestId('revoke-row-button').first();
      await expect(revokeRow).toBeVisible({ timeout: 10_000 });

      // 4. Viewer opens the shared wall and reaches LIVE before any toot is posted.
      const viewerContext: BrowserContext = await browser.newContext();
      const viewerPage: Page = await viewerContext.newPage();
      await viewerPage.goto(shareUrl);
      await expect(viewerPage.getByTestId('share-banner')).toBeVisible({ timeout: 10_000 });
      await expect(viewerPage.getByTestId('transport-status'))
          .toHaveClass(/transport-status--live/, { timeout: 15_000 });

      // 5. A toot relays to the viewer — proves the link is genuinely live before revocation.
      await mastodon.postToot(toot(markerShared, TAG_A));
      await expect(viewerPage.getByText(markerShared)).toBeVisible({ timeout: 15_000 });

      // 6. Revoke from the LIST row. Capture the DELETE to assert it targets the idHash8 only.
      const deleteReqPromise = ownerPage.waitForRequest((req) =>
          req.method() === 'DELETE' && req.url().includes('/rest/share-links/'));
      await revokeRow.click();
      await expect(ownerPage.getByTestId('revoke-confirm')).toBeVisible();
      await ownerPage.getByTestId('revoke-confirm').click();

      const deleteReq = await deleteReqPromise;
      const lastSegment = new URL(deleteReq.url()).pathname.split('/').pop() ?? '';
      expect(lastSegment).toMatch(/^[0-9a-f]{8}$/);
      // The secret token must NOT appear anywhere in the revoke URL (no proxy-access-log leak).
      expect(deleteReq.url()).not.toContain(shareToken);

      // 7. The live revocation control frame redirects the viewer to /expired.
      await expect(viewerPage).toHaveURL(/\/expired/, { timeout: 10_000 });

      await ownerContext.close();
      await viewerContext.close();
    },
  );
});
