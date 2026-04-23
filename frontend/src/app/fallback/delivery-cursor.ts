/**
 * DeliveryCursor — persistent per-hashtag HTTP-fallback cursor (D-06).
 *
 * The cursor stores the last sequence number the client acknowledged from
 * the fallback endpoint's response (`nextSince`).  On the next poll, the
 * client sends `?since={sequence}` so the server returns only newer events.
 *
 * Persistence strategy
 * --------------------
 * The cursor is stored in `localStorage` under the key `deliveryCursor` as a
 * JSON object mapping hashtag → sequence number.  On every update the entire
 * map is written back atomically.
 *
 * Safety rules (D-06)
 * -------------------
 * - Values are parsed with `Number.parseInt` and clamped to
 *   `[0, Number.MAX_SAFE_INTEGER]`.
 * - Corrupt, NaN, or missing entries resolve to `0`, causing the next poll to
 *   request the full ring-buffer contents.
 * - The cursor is keyed by hashtag, not by (principal, hashtag); the backend
 *   uses the wallId cookie to resolve the principal.
 */
export class DeliveryCursor {

  /** localStorage key under which the cursor map is persisted. */
  private static readonly STORAGE_KEY = 'deliveryCursor';

  /** In-memory copy of the persisted map. */
  private map: Record<string, number> = {};

  constructor() {
    this.load();
  }

  // ---------------------------------------------------------------------------
  // Read
  // ---------------------------------------------------------------------------

  /**
   * Returns the last-seen sequence number for `hashtag`, or `0` if no cursor
   * is stored yet (which causes the server to return the full ring buffer).
   */
  get(hashtag: string): number {
    const raw = this.map[hashtag];
    return this.clamp(raw);
  }

  // ---------------------------------------------------------------------------
  // Write
  // ---------------------------------------------------------------------------

  /**
   * Records the `nextSince` value returned by the server after a successful
   * fallback poll.  The value is clamped before storage.
   *
   * @param hashtag  — the hashtag whose cursor to advance
   * @param sequence — the `nextSince` field from the server's response
   */
  set(hashtag: string, sequence: number): void {
    this.map[hashtag] = this.clamp(sequence);
    this.persist();
  }

  /**
   * Removes the cursor for the given hashtag (e.g. when the user unsubscribes).
   */
  remove(hashtag: string): void {
    delete this.map[hashtag];
    this.persist();
  }

  /**
   * Resets all cursors to `0` and clears localStorage.  Used when the session
   * is invalidated (401) so the next poll fetches the full ring buffer.
   */
  reset(): void {
    this.map = {};
    this.persist();
  }

  // ---------------------------------------------------------------------------
  // Internals
  // ---------------------------------------------------------------------------

  /**
   * Loads the cursor map from `localStorage`, discarding any entry that is
   * not a safe integer (NaN, Infinity, negative) per D-06.
   */
  private load(): void {
    try {
      const raw = localStorage.getItem(DeliveryCursor.STORAGE_KEY);
      if (raw === null) {
        return;
      }
      const parsed = JSON.parse(raw) as unknown;
      if (parsed !== null && typeof parsed === 'object' && !Array.isArray(parsed)) {
        const obj = parsed as Record<string, unknown>;
        for (const [key, value] of Object.entries(obj)) {
          const clamped = this.clamp(value as number);
          this.map[key] = clamped;
        }
      }
    } catch {
      // localStorage corrupted — start fresh
      this.map = {};
    }
  }

  /** Writes the in-memory map back to `localStorage`. */
  private persist(): void {
    try {
      localStorage.setItem(DeliveryCursor.STORAGE_KEY, JSON.stringify(this.map));
    } catch {
      // Storage quota exceeded — continue with in-memory cursor only
    }
  }

  /**
   * Clamps `value` to `[0, Number.MAX_SAFE_INTEGER]`.
   * Non-numeric, NaN, negative, or out-of-range values resolve to `0`.
   */
  private clamp(value: number | undefined | null): number {
    const n = Number.parseInt(String(value ?? ''), 10);
    if (!Number.isFinite(n) || n < 0) {
      return 0;
    }
    return Math.min(n, Number.MAX_SAFE_INTEGER);
  }
}
