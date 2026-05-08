/**
 * Unit tests for SubscriptionPersistence service.
 *
 * Scope: localStorage access for 'hashtags' and 'messageQueue' keys,
 * MessageQueue factory, and try/catch behaviour on malformed JSON
 * (SR-SPLIT-01a, AC-19).
 *
 * Tests in this file:
 *   T0 — loadHashtags() returns [] and calls console.warn when 'hashtags'
 *         localStorage value is malformed JSON (AC-19, CWE-117).
 *
 * Further T1/T2 structural invariants are enforced by ESLint rules, not Karma.
 */
describe('SubscriptionPersistence', () => {
  // T0 and full test suite added in commit 2.
  // Placeholder kept to prevent "describe with no children" error.
  it('placeholder — will be replaced by T0 in commit 2', () => {
    expect(true).toBeTrue();
  });
});
