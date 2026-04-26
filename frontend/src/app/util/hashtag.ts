/**
 * Canonical hashtag normalisation helper (ADR-6, SR-PRUNE-07, SR-PRUNE-10).
 *
 * All hashtag equality checks in the prune feature — pruneByHashtag,
 * recentlyTerminated guard, ingestCacheEntries, and ReadonlyWallService —
 * must route through this single function.  Using ad-hoc toLowerCase() at
 * call sites is explicitly forbidden (grep audit required before merge).
 *
 * Normalisation rules (in order):
 *   1. Trim surrounding whitespace.
 *   2. Strip a single leading '#' character if present.
 *   3. Convert to lowercase.
 *
 * The result is the canonical form used as the key in recentlyTerminated
 * and as the stored value inside WallMessage.hashtags[].
 *
 * @param s - Raw hashtag string as received from the UI or the server.
 *            May include a leading '#' and mixed case.
 * @returns Normalised hashtag string (lowercase, no '#', no surrounding whitespace).
 *
 * @example
 *   normalizeHashtag('#Glacier')  // → 'glacier'
 *   normalizeHashtag('Glacier')   // → 'glacier'
 *   normalizeHashtag('  #FOSS ')  // → 'foss'
 */
export function normalizeHashtag(s: string): string {
  const trimmed = s.trim();
  const withoutHash = trimmed.startsWith('#') ? trimmed.slice(1) : trimmed;
  return withoutHash.toLowerCase();
}
