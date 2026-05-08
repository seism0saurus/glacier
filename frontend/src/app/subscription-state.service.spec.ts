/**
 * Unit tests for SubscriptionStateService.
 *
 * Scope: single MessageQueue instance ownership (SR-SPLIT-03), observable
 * contracts, recentlyTerminated guard, ingestCacheEntries path convergence
 * (glacier-fallback-mode-discipline), and hasMigrated$ plain-Subject semantics
 * (SR-SPLIT-07, AC-10).
 *
 * Tests in this file:
 *   T3 — Queue identity: enqueueWallMessage + ingestCacheEntries both write to
 *         the same MessageQueue instance → messageObservable$ emits both entries
 *         (SR-SPLIT-03, AC-3).
 *   T7 — hasMigrated$ is a plain Subject: a new subscriber arriving after the
 *         emit does NOT receive the cached 'true' value (AC-10, ADR-4).
 */
describe('SubscriptionStateService', () => {
  // T3 and T7 added in commit 3.
  // Placeholder kept to prevent "describe with no children" error.
  it('placeholder — will be replaced by T3/T7 in commit 3', () => {
    expect(true).toBeTrue();
  });
});
