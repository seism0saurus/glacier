/**
 * E2E spec: cross-wall toot isolation (chromium project).
 *
 * Security invariant under test (BOLA / per-principal fan-out isolation):
 *   A toot routed to one wall MUST NOT surface on a different wall. Glacier has
 *   no login — identity is the `wallId` cookie, and every server→client fan-out
 *   is published to a per-principal topic (`/topic/hashtags/{wallId}/{tag}/...`,
 *   see StompCallback + WallTopicAuthInterceptor). This spec proves the guarantee
 *   end-to-end, black-box, through the real streaming stack
 *   (Bigbone → StompCallback → SimpMessagingTemplate → RxStomp) — the layer
 *   CLAUDE.md's testing policy requires an e2e for.
 *
 * Two independent walls:
 *   Each `browser.newContext()` has its own isolated cookie jar and its own
 *   WebSocket session, so the two walls are distinct principals with disjoint
 *   topic namespaces. Note: over the dockerized plain-HTTP origin the `Secure`
 *   `wallId` cookie is not persisted by the browser, so identity is the fresh
 *   UUID that PrincipalHandler mints per handshake — still distinct per context.
 *   The isolation boundary is therefore Playwright's context separation, which
 *   is why the two assertions below (A sees its toot, B does not, and vice-versa)
 *   are what actually prove the guarantee.
 *
 * Why different hashtags:
 *   The realistic, UI-drivable isolation scenario is two walls subscribed to
 *   DIFFERENT hashtags. A toot for wall A's hashtag must reach A and never B, and
 *   vice-versa. (The interceptor's explicit reject-branch — a client crafting a
 *   SUBSCRIBE to another wall's topic path — is a bean-layer concern covered by
 *   StompMassAssignmentIT / WallTopicAuthInterceptorTest; the Angular client
 *   never issues such a frame, so it is not reachable from an e2e.)
 *
 * Opt-in rule (StompCallback): a toot only reaches a wall if it mentions the bot
 * handle AND carries a subscribed hashtag — so every toot below mentions
 * `${glacier_handle}`.
 *
 * No-mock rule (CLAUDE.md / playwright-e2e-patterns): every toot is posted
 * through the real dockerized Mastodon API via mastodon-client.ts.
 */

import { test, expect, BrowserContext, Page } from '@playwright/test';
import { createTextToot } from '../helper/mastodon-client';

const glacier_handle = process.env['GLACIER_HANDLE'] || '@glacier_e2e_test@proxy';

// Distinct per-wall hashtags. Lower-cased chip ids are derived by the frontend.
const TAG_A = 'glacierwallisoalpha';
const TAG_B = 'glacierwallisobeta';

/** How long to wait before asserting a toot did NOT arrive (mirrors toots.spec.ts). */
const NEGATIVE_WAIT = Number(process.env['WAIT_FOR']) || 3000;

/** Build a toot body that satisfies the opt-in rule (mention + hashtag). */
function toot(marker: string, tag: string): string {
  return `Hi ${glacier_handle}.\n${marker}\n#${tag}`;
}

/** Open the followed-hashtags panel and subscribe to one hashtag. */
async function subscribe(page: Page, tag: string): Promise<void> {
  await page.locator('div').filter({ hasText: 'Followed hashtags' }).nth(3).click();
  await page.getByPlaceholder('New hashtag').fill(tag);
  await page.getByPlaceholder('New hashtag').press('Enter');
  await expect(page.locator(`[id='hashtag-${tag.toLowerCase()}']`)).toBeVisible();
}

test.describe('Cross-wall toot isolation', () => {

  test('a toot for one wall\'s hashtag never surfaces on a different wall', async ({ browser }) => {
    // Unique markers so assertions never collide with toots from earlier runs.
    const run = `${test.info().workerIndex}-${test.info().retry}`;
    const markerA = `wall-A isolation toot ${run}`;
    const markerB = `wall-B isolation toot ${run}`;

    // --- Two independent walls ------------------------------------------------
    const contextA: BrowserContext = await browser.newContext();
    const contextB: BrowserContext = await browser.newContext();
    const wallA: Page = await contextA.newPage();
    const wallB: Page = await contextB.newPage();

    await wallA.goto('/');
    await wallB.goto('/');
    await expect(wallA.getByTestId('connection-status')).toBeVisible();
    await expect(wallB.getByTestId('connection-status')).toBeVisible();

    // --- Each wall subscribes to its OWN hashtag ------------------------------
    await subscribe(wallA, TAG_A);
    await subscribe(wallB, TAG_B);

    // --- Post a toot for wall A's hashtag -------------------------------------
    await createTextToot(toot(markerA, TAG_A));

    // Wall A receives it...
    await expect(wallA.locator('app-toot')).toHaveCount(1);
    await expect(wallA.locator('app-toot')).toBeVisible();

    // ...wall B must NOT — it is subscribed to a different hashtag and lives in a
    // different principal's topic namespace. Give the backend time to (wrongly)
    // deliver before asserting absence.
    await wallB.waitForTimeout(NEGATIVE_WAIT);
    await expect(wallB.locator('app-toot')).toHaveCount(0);

    // --- Symmetric: post a toot for wall B's hashtag --------------------------
    await createTextToot(toot(markerB, TAG_B));

    // Wall B now receives its own toot...
    await expect(wallB.locator('app-toot')).toHaveCount(1);
    await expect(wallB.locator('app-toot')).toBeVisible();

    // ...and wall A must stay at exactly 1 (it never received wall B's toot).
    await wallA.waitForTimeout(NEGATIVE_WAIT);
    await expect(wallA.locator('app-toot')).toHaveCount(1);

    await contextA.close();
    await contextB.close();
  });

});
