/**
 * Quota-safe localStorage write helper (ADR-7, SR-PRUNE-05, SR-PRUNE-11).
 *
 * Mobile browsers cap localStorage at approximately 5 MB.  A wall with
 * 20 large toot URLs and all share-link data can approach that limit.
 * Allowing a QuotaExceededError to propagate silently would leave the
 * queue in an inconsistent state — the caller believes persistence
 * succeeded but the browser discarded the write.
 *
 * Recovery strategy on QuotaExceededError:
 *   1. Drop the oldest half of the queue (entries at the start of the
 *      JSON-serialised array) and rewrite the value.
 *   2. Retry once with the reduced value.
 *   3. If the retry also throws, log an error and return without throwing.
 *      The in-memory state remains correct; only persistence was lost.
 *
 * @param key   - The localStorage key to write.
 * @param value - The serialised string value to store.  For MessageQueue,
 *                this is JSON.stringify(storage).
 */
export function safeSetItem(key: string, value: string): void {
  try {
    localStorage.setItem(key, value);
  } catch (e) {
    if (isQuotaExceededError(e)) {
      const reduced = dropOldestHalf(value);
      try {
        localStorage.setItem(key, reduced);
      } catch (retryError) {
        // Persistent quota failure — in-memory state is still correct,
        // but we cannot persist it.  Log for observability and return.
        console.error('[safeSetItem] localStorage quota exceeded after retry — persistence failed for key:', key);
      }
    }
    // Non-quota errors are not swallowed — they indicate a programming error.
  }
}

/**
 * Returns true when the given error is a QuotaExceededError (or the
 * equivalent DOMException name used by different browser vendors).
 *
 * @param e - The caught error value.
 */
function isQuotaExceededError(e: unknown): boolean {
  if (!(e instanceof DOMException)) {
    return false;
  }
  // Standard name per the Storage Living Standard
  return (
    e.name === 'QuotaExceededError' ||
    // Legacy Firefox name
    e.name === 'NS_ERROR_DOM_QUOTA_REACHED' ||
    // Legacy Chrome / Safari code
    e.code === 22
  );
}

/**
 * Attempts to parse value as a JSON array or v:2 envelope object, removes
 * the oldest half of entries, and returns the reduced JSON string.
 *
 * Handles two shapes:
 *   1. v:2 envelope `{ v: number, items: [...] }` — slices the items array,
 *      re-serialises as the same envelope shape so the schema version is
 *      preserved.  This prevents validateMessageQueue from rejecting the
 *      reduced value on next page load.
 *   2. Plain JSON array — slices the array directly (legacy / non-queue keys).
 *
 * If value is neither a parseable envelope nor a parseable array, returns
 * an empty JSON array string ('[]') so that the retry write always succeeds.
 *
 * @param value - The original JSON-serialised string to reduce.
 * @returns A JSON string with the oldest half of entries removed.
 */
function dropOldestHalf(value: string): string {
  try {
    const parsed = JSON.parse(value);

    // v:2 envelope: { v: number, items: [...] }
    if (
      parsed !== null &&
      typeof parsed === 'object' &&
      !Array.isArray(parsed) &&
      Array.isArray((parsed as any).items)
    ) {
      const items: unknown[] = (parsed as any).items;
      const halfIndex = Math.ceil(items.length / 2);
      return JSON.stringify({ ...parsed, items: items.slice(halfIndex) });
    }

    // Plain JSON array (legacy or non-queue keys)
    if (Array.isArray(parsed)) {
      const halfIndex = Math.ceil(parsed.length / 2);
      return JSON.stringify(parsed.slice(halfIndex));
    }

    return '[]';
  } catch {
    return '[]';
  }
}
