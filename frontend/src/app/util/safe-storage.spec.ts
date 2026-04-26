import {safeSetItem} from './safe-storage';

/**
 * Unit tests for safeSetItem (ADR-7, SR-PRUNE-05, SR-PRUNE-11).
 *
 * U2  — safeSetItem writes to localStorage on success.
 * U3  — safeSetItem drops oldest half and retries on QuotaExceededError.
 * U-SEC-05 — no silent data loss on QuotaExceededError; drop oldest half,
 *             retry once, log on persistent failure.
 * U-SEC-11 — safeSetItem test covers QuotaExceededError path + retry-once.
 *
 * Each test documents Arrange / Act / Assert per TDD contract.
 */
describe('safeSetItem', () => {

  let setItemSpy: jasmine.Spy;

  beforeEach(() => {
    setItemSpy = spyOn(localStorage, 'setItem');
  });

  afterEach(() => {
    // Ensure no localStorage state bleeds between tests
    setItemSpy.calls.reset();
  });

  describe('U2 — normal write path', () => {
    it('should call localStorage.setItem with the provided key and value', () => {
      // Arrange
      const key = 'testKey';
      const value = JSON.stringify([{id: '1'}]);

      // Act
      safeSetItem(key, value);

      // Assert
      expect(localStorage.setItem).toHaveBeenCalledOnceWith(key, value);
    });

    it('should not throw on a successful write', () => {
      // Arrange + Act + Assert
      expect(() => safeSetItem('k', 'v')).not.toThrow();
    });
  });

  describe('U3 / U-SEC-05 / U-SEC-11 — QuotaExceededError handling', () => {

    /**
     * Builds a DOMException that browsers emit for localStorage quota errors.
     * Simulates the standard QuotaExceededError (code 22 / name='QuotaExceededError').
     */
    function makeQuotaError(): DOMException {
      const e = new DOMException('QuotaExceededError', 'QuotaExceededError');
      return e;
    }

    it('should retry with the oldest-half dropped when QuotaExceededError is thrown on first write', () => {
      // Arrange: 4-item array; first write throws, second write succeeds.
      const items = [
        {id: '1', url: 'u1', hashtags: ['a']},
        {id: '2', url: 'u2', hashtags: ['a']},
        {id: '3', url: 'u3', hashtags: ['a']},
        {id: '4', url: 'u4', hashtags: ['a']},
      ];
      const value = JSON.stringify(items);
      setItemSpy.and.callFake((_key: string, _val: string) => {
        if (setItemSpy.calls.count() === 1) {
          throw makeQuotaError();
        }
        // Second call succeeds (no-op)
      });

      // Act
      safeSetItem('messageQueue', value);

      // Assert: called twice — first attempt + retry with reduced array
      expect(localStorage.setItem).toHaveBeenCalledTimes(2);
      const retryValue = JSON.parse(setItemSpy.calls.argsFor(1)[1]);
      // oldest half (items 1+2) dropped; items 3+4 remain
      expect(retryValue.length).toBe(2);
      expect(retryValue[0].id).toBe('3');
      expect(retryValue[1].id).toBe('4');
    });

    it('should log an error and not throw when both writes throw QuotaExceededError', () => {
      // Arrange: both calls throw
      setItemSpy.and.throwError(makeQuotaError());
      const consoleSpy = spyOn(console, 'error');

      // Act + Assert: must not propagate
      expect(() => safeSetItem('messageQueue', JSON.stringify([{id: '1'}]))).not.toThrow();
      expect(consoleSpy).toHaveBeenCalled();
    });

    it('should drop the oldest half of an odd-length array correctly (ceil rounding)', () => {
      // Arrange: 3-item array — oldest half is ceil(3/2)=2; keep only item index 2
      const items = [
        {id: '1'},
        {id: '2'},
        {id: '3'},
      ];
      const value = JSON.stringify(items);
      setItemSpy.and.callFake((_key: string, _val: string) => {
        if (setItemSpy.calls.count() === 1) {
          throw makeQuotaError();
        }
      });

      safeSetItem('k', value);

      const retryValue = JSON.parse(setItemSpy.calls.argsFor(1)[1]);
      // ceil(3/2) = 2 dropped; only item '3' remains
      expect(retryValue.length).toBe(1);
      expect(retryValue[0].id).toBe('3');
    });

    it('should write empty array when value is not a JSON array', () => {
      // Arrange: non-array JSON value triggers fallback to '[]'
      setItemSpy.and.callFake((_key: string, _val: string) => {
        if (setItemSpy.calls.count() === 1) {
          throw makeQuotaError();
        }
      });

      safeSetItem('k', '"not-an-array"');

      const retryValue = JSON.parse(setItemSpy.calls.argsFor(1)[1]);
      expect(retryValue).toEqual([]);
    });

    it('should write empty array when value is invalid JSON', () => {
      // Arrange
      setItemSpy.and.callFake((_key: string, _val: string) => {
        if (setItemSpy.calls.count() === 1) {
          throw makeQuotaError();
        }
      });

      safeSetItem('k', '{invalid json}');

      const retryValue = JSON.parse(setItemSpy.calls.argsFor(1)[1]);
      expect(retryValue).toEqual([]);
    });

    it('should retry exactly once (not more) on QuotaExceededError', () => {
      // Arrange: always throw — ensures retry is bounded to 1 additional attempt
      setItemSpy.and.throwError(makeQuotaError());
      spyOn(console, 'error');

      safeSetItem('k', JSON.stringify([{id: '1'}]));

      // Exactly 2 calls: initial + 1 retry
      expect(localStorage.setItem).toHaveBeenCalledTimes(2);
    });

    it('should preserve the v:2 envelope when QuotaExceededError occurs', () => {
      // Arrange: a valid v:2 envelope with 20 items; first write throws, second succeeds.
      //
      // Without this fix, dropOldestHalf falls through the !Array.isArray check
      // and returns '[]'.  The retry then writes '[]' to localStorage.  On the
      // next page load, validateMessageQueue rejects '[]' (it's a top-level
      // array, not an envelope object) and silently wipes the queue.
      //
      // With this fix, dropOldestHalf detects the { v, items } shape, slices
      // items (dropping the oldest half), and re-serialises the envelope so the
      // schema version is preserved.
      const fullItems = Array.from({length: 20}, (_, i) => ({
        id: `item-${i}`,
        url: 'https://a.b/c',
        hashtags: ['t'],
      }));
      const envelope = {v: 2, items: fullItems};
      const value = JSON.stringify(envelope);

      setItemSpy.and.callFake((_key: string, _val: string) => {
        if (setItemSpy.calls.count() === 1) {
          throw makeQuotaError();
        }
        // Second call succeeds (no-op)
      });

      // Act
      safeSetItem('messageQueue', value);

      // Assert: retry was called with the reduced envelope (not '[]')
      expect(localStorage.setItem).toHaveBeenCalledTimes(2);
      const retryArg = setItemSpy.calls.argsFor(1)[1];
      const retryParsed = JSON.parse(retryArg);

      // Must still be a v:2 envelope, not a bare array
      expect(Array.isArray(retryParsed)).toBeFalse();
      expect(retryParsed.v).toBe(2);
      expect(Array.isArray(retryParsed.items)).toBeTrue();

      // Items array must be the newer half (~10 items), not 0
      expect(retryParsed.items.length).toBeGreaterThan(0);
      expect(retryParsed.items.length).toBeLessThan(fullItems.length);
    });
  });
});
