/**
 * Schema version constant for the WallMessage format stored in localStorage.
 *
 * Version history:
 *   1 — original SafeMessage (id, url, editedAt?) with no hashtag membership
 *   2 — WallMessage adds required hashtags[] for prune-on-removal semantics
 *
 * On restore, any queue missing the version field is discarded entirely
 * (clear-on-upgrade — ADR-4).  The MessageQueueValidator (owned by
 * secure-tdd-implementer) enforces this at the boundary.
 */
export const WallMessageSchemaVersion = 2;

/**
 * Value object representing a single toot on the wall.
 *
 * Replaces SafeMessage in MessageQueue entries to carry explicit hashtag
 * membership per toot.  This is the minimum information needed for prune
 * semantics: when the user removes a hashtag, any WallMessage whose
 * hashtags[] becomes empty after removing that hashtag is dropped from
 * the wall (ADR-1, ADR-2).
 *
 * Invariants:
 *   - id   is non-empty (Mastodon status ID used as dedup key)
 *   - url  is non-empty
 *   - hashtags has at least one normalised (lowercase, no leading '#') entry
 */
export interface WallMessage {
  /** Mastodon status ID — dedup key and primary key in the queue. */
  readonly id: string;
  /** Toot embed URL. */
  readonly url: string;
  /** ISO-8601 UTC timestamp of the last edit; absent on creation. */
  readonly editedAt?: string;
  /**
   * Normalised hashtag labels that caused this toot to appear on the wall.
   * At least one entry is required.  All entries are lowercase, leading '#'
   * stripped, surrounding whitespace trimmed (normalizeHashtag contract).
   */
  readonly hashtags: string[];
}

/**
 * Result of a prune operation on the MessageQueue.
 *
 * Returned by pruneByHashtag / pruneByHashtags so callers can announce
 * the number of removed toots to screen reader users via WallAnnouncerService
 * and can update the BehaviorSubject in SubscriptionService without
 * re-querying the queue.
 */
export interface PruneResult {
  /**
   * IDs of WallMessages that were removed from the queue because their
   * hashtags[] became empty after the prune operation.
   */
  readonly removed: string[];
  /**
   * The WallMessages that remain in the queue after the prune operation.
   * This is a snapshot — callers must not mutate it.
   */
  readonly remaining: WallMessage[];
}
