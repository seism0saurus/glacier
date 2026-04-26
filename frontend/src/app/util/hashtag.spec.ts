import {normalizeHashtag} from './hashtag';

/**
 * Unit tests for normalizeHashtag (ADR-6, SR-PRUNE-07, SR-PRUNE-10).
 *
 * U1 — normaliseHashtag strips leading '#', lowercases, trims.
 * U-SEC-10 — single canonical normaliseHashtag exported from hashtag.ts.
 *
 * Each test documents Arrange / Act / Assert per TDD contract.
 */
describe('normalizeHashtag', () => {

  describe('U1 — strips leading hash and lowercases', () => {
    it('should strip a leading # and lowercase the result', () => {
      // Arrange: mixed-case hashtag with leading hash
      // Act + Assert
      expect(normalizeHashtag('#Glacier')).toBe('glacier');
    });

    it('should lowercase a hashtag without a leading hash', () => {
      // Arrange: no leading hash, mixed case
      expect(normalizeHashtag('Glacier')).toBe('glacier');
    });

    it('should trim surrounding whitespace', () => {
      // Arrange: padded with spaces
      expect(normalizeHashtag('  #FOSS ')).toBe('foss');
    });

    it('should handle an all-lowercase hashtag with hash (no-op)', () => {
      expect(normalizeHashtag('#glacier')).toBe('glacier');
    });

    it('should handle an all-lowercase hashtag without hash (no-op)', () => {
      expect(normalizeHashtag('glacier')).toBe('glacier');
    });

    it('should handle an already-normalised hashtag idempotently', () => {
      // Arrange: output of a previous normalizeHashtag call
      expect(normalizeHashtag(normalizeHashtag('#Mastodon'))).toBe('mastodon');
    });

    it('should not strip a second # when the hashtag itself contains it', () => {
      // Arrange: only the first leading # is stripped
      expect(normalizeHashtag('##double')).toBe('#double');
    });

    it('should handle an empty string', () => {
      expect(normalizeHashtag('')).toBe('');
    });

    it('should handle a string that is only #', () => {
      expect(normalizeHashtag('#')).toBe('');
    });

    it('should handle an all-uppercase hashtag', () => {
      expect(normalizeHashtag('OPENDATA')).toBe('opendata');
    });

    it('should preserve digits and special characters after normalisation', () => {
      expect(normalizeHashtag('#COP26')).toBe('cop26');
    });
  });

  describe('U-SEC-10 — canonical export contract', () => {
    it('should be a function (exported from hashtag.ts)', () => {
      // Arrange + Act + Assert: confirm the export is a callable function
      expect(typeof normalizeHashtag).toBe('function');
    });
  });
});
