import {TestBed} from '@angular/core/testing';
import {SubscriptionPersistence} from './subscription-persistence.service';

/**
 * Unit tests for SubscriptionPersistence service.
 *
 * Scope: localStorage access for 'hashtags' and 'messageQueue' keys,
 * MessageQueue factory, and try/catch behaviour on malformed JSON
 * (SR-SPLIT-01a, AC-19).
 *
 * T0 — loadHashtags() returns [] and calls console.warn (not logging the
 *        malformed value) when 'hashtags' localStorage value is malformed
 *        JSON (AC-19, CWE-117, SR-SPLIT-01a).
 *
 * T1/T2 structural invariants are enforced by ESLint rules, not Karma
 * (per the decision to use lint-time rather than runtime structural tests).
 */
describe('SubscriptionPersistence', () => {
  let service: SubscriptionPersistence;

  beforeEach(() => {
    TestBed.configureTestingModule({});
    service = TestBed.inject(SubscriptionPersistence);
    localStorage.clear();
  });

  afterEach(() => {
    localStorage.clear();
  });

  /**
   * T0 — Malformed JSON in 'hashtags' localStorage key.
   *
   * Arrange: set 'hashtags' to a string that is not valid JSON.
   * Act:     call loadHashtags().
   * Assert:  returns [] (fail-safe, SR-SPLIT-01a).
   *          calls console.warn with the prescribed message (AC-19).
   *          does NOT log the malformed value itself (CWE-117).
   */
  it('returns [] when hashtags localStorage is malformed JSON', () => {
    localStorage.setItem('hashtags', '{bad');
    const warnSpy = spyOn(console, 'warn');

    const result = service.loadHashtags();

    expect(result).toEqual([]);
    expect(warnSpy).toHaveBeenCalledWith('hashtags localStorage malformed, resetting');
    // Verify the malformed value itself is NOT passed to console.warn (CWE-117):
    // the warn call must have exactly one argument.
    expect(warnSpy.calls.mostRecent().args.length).toBe(1);
  });

  it('returns [] when hashtags key is absent from localStorage', () => {
    const result = service.loadHashtags();
    expect(result).toEqual([]);
  });

  it('returns validated hashtags when localStorage contains a valid list', () => {
    localStorage.setItem('hashtags', JSON.stringify(['angular', 'typescript']));

    const result = service.loadHashtags();

    expect(result).toEqual(['angular', 'typescript']);
  });

  it('returns [] when localStorage contains a non-array JSON value', () => {
    // validateHashtagsList rejects non-arrays; loadHashtags returns []
    localStorage.setItem('hashtags', JSON.stringify({not: 'an array'}));

    const result = service.loadHashtags();

    expect(result).toEqual([]);
  });

  it('saveHashtags persists the list to localStorage', () => {
    service.saveHashtags(['cats', 'dogs']);

    const stored = JSON.parse(localStorage.getItem('hashtags')!);
    expect(stored).toEqual(['cats', 'dogs']);
  });

  it('createMessageQueue returns a new MessageQueue instance', () => {
    const queue = service.createMessageQueue();
    expect(queue).toBeTruthy();
    expect(queue.size()).toBe(0);
  });

  it('createMessageQueue with custom capacity creates a bounded queue', () => {
    const queue = service.createMessageQueue(3);
    // Verify capacity by filling beyond the limit
    queue.enqueue({id: '1', url: 'u1', hashtags: ['a']});
    queue.enqueue({id: '2', url: 'u2', hashtags: ['a']});
    queue.enqueue({id: '3', url: 'u3', hashtags: ['a']});
    queue.enqueue({id: '4', url: 'u4', hashtags: ['a']});
    // Oldest entry (id:'1') should have been evicted
    expect(queue.size()).toBe(3);
    expect(queue.toArray().map(m => m.id)).not.toContain('1');
  });

  it('hasPersistedMessageQueue returns false when key is absent', () => {
    expect(service.hasPersistedMessageQueue()).toBeFalse();
  });

  it('hasPersistedMessageQueue returns true when key is present', () => {
    localStorage.setItem('messageQueue', JSON.stringify({v: 2, items: []}));
    expect(service.hasPersistedMessageQueue()).toBeTrue();
  });
});
