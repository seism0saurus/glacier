/**
 * Unit tests for DeliveryCursor (D-06).
 *
 * Verifies localStorage persistence, NaN/corrupt handling, clamping behaviour,
 * and removal/reset operations.
 */

import {DeliveryCursor} from './delivery-cursor';

describe('DeliveryCursor', () => {

  beforeEach(() => {
    localStorage.removeItem('deliveryCursor');
  });

  afterEach(() => {
    localStorage.removeItem('deliveryCursor');
  });

  // -------------------------------------------------------------------------
  // Initial state
  // -------------------------------------------------------------------------
  it('should return 0 for an unknown hashtag', () => {
    const cursor = new DeliveryCursor();
    expect(cursor.get('unknown')).toBe(0);
  });

  // -------------------------------------------------------------------------
  // Set and get round-trip
  // -------------------------------------------------------------------------
  it('should persist a sequence value for a hashtag', () => {
    const cursor = new DeliveryCursor();
    cursor.set('test', 42);
    expect(cursor.get('test')).toBe(42);
  });

  it('should persist across instances (uses localStorage)', () => {
    const cursor1 = new DeliveryCursor();
    cursor1.set('abc', 99);

    const cursor2 = new DeliveryCursor();
    expect(cursor2.get('abc')).toBe(99);
  });

  // -------------------------------------------------------------------------
  // Clamping / safety (D-06)
  // -------------------------------------------------------------------------
  it('should clamp negative values to 0', () => {
    const cursor = new DeliveryCursor();
    cursor.set('neg', -1);
    expect(cursor.get('neg')).toBe(0);
  });

  it('should clamp values above Number.MAX_SAFE_INTEGER to Number.MAX_SAFE_INTEGER', () => {
    const cursor = new DeliveryCursor();
    cursor.set('huge', Number.MAX_SAFE_INTEGER + 1000);
    expect(cursor.get('huge')).toBe(Number.MAX_SAFE_INTEGER);
  });

  it('should return 0 for NaN (D-06 corrupt/NaN → 0)', () => {
    const cursor = new DeliveryCursor();
    cursor.set('nan', NaN);
    expect(cursor.get('nan')).toBe(0);
  });

  it('should return 0 when localStorage contains a non-numeric string for the key', () => {
    localStorage.setItem('deliveryCursor', JSON.stringify({broken: 'not-a-number'}));
    const cursor = new DeliveryCursor();
    expect(cursor.get('broken')).toBe(0);
  });

  it('should return 0 for Infinity', () => {
    const cursor = new DeliveryCursor();
    cursor.set('inf', Infinity);
    expect(cursor.get('inf')).toBe(0);
  });

  // -------------------------------------------------------------------------
  // Corrupt localStorage — start fresh (D-06)
  // -------------------------------------------------------------------------
  it('should start fresh when localStorage contains invalid JSON', () => {
    localStorage.setItem('deliveryCursor', 'not-valid-json{{{');
    expect(() => new DeliveryCursor()).not.toThrow();
    const cursor = new DeliveryCursor();
    expect(cursor.get('anything')).toBe(0);
  });

  it('should start fresh when localStorage contains a non-object (e.g. array)', () => {
    localStorage.setItem('deliveryCursor', JSON.stringify([1, 2, 3]));
    const cursor = new DeliveryCursor();
    expect(cursor.get('0')).toBe(0);
  });

  // -------------------------------------------------------------------------
  // Remove
  // -------------------------------------------------------------------------
  it('should remove a cursor for the given hashtag', () => {
    const cursor = new DeliveryCursor();
    cursor.set('toremove', 7);
    cursor.remove('toremove');
    expect(cursor.get('toremove')).toBe(0);
  });

  it('remove for unknown key is a no-op', () => {
    expect(() => {
      const cursor = new DeliveryCursor();
      cursor.remove('nonexistent');
    }).not.toThrow();
  });

  // -------------------------------------------------------------------------
  // Reset
  // -------------------------------------------------------------------------
  it('should reset all cursors to 0 and clear localStorage', () => {
    const cursor = new DeliveryCursor();
    cursor.set('a', 1);
    cursor.set('b', 2);
    cursor.reset();

    expect(cursor.get('a')).toBe(0);
    expect(cursor.get('b')).toBe(0);
    expect(localStorage.getItem('deliveryCursor')).toBe('{}');
  });

  // -------------------------------------------------------------------------
  // Multiple hashtags coexist
  // -------------------------------------------------------------------------
  it('should track independent cursors per hashtag', () => {
    const cursor = new DeliveryCursor();
    cursor.set('foo', 10);
    cursor.set('bar', 20);
    cursor.set('baz', 30);

    expect(cursor.get('foo')).toBe(10);
    expect(cursor.get('bar')).toBe(20);
    expect(cursor.get('baz')).toBe(30);
  });
});
