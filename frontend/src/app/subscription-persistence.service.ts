import {Injectable} from '@angular/core';
import {WallMessage, WallMessageSchemaVersion, PruneResult} from './model/wall-message';
import {validateMessageQueue, validateHashtagsList} from './model/message-queue-validator';
import {StatusUpdatedMessage} from './message-types/status-updated-message';
import {normalizeHashtag} from './util/hashtag';
import {safeSetItem} from './util/safe-storage';

// Dependency-direction lock (SR-SPLIT-02, ESLint T2):
// SubscriptionPersistence must NOT import from:
//   ./subscription-state.service
//   ./subscription-stomp-client.service
//   ./subscription.service
// The dependency arrow is: Persistence ← State ← StompClient ← Facade.
// ESLint rule in eslint.config.js enforces this at lint time (AC-2, AC-17).

/**
 * Service responsible for persisting and restoring subscription-related data
 * to and from localStorage.
 *
 * SubscriptionPersistence is the sole authorised reader and writer of the
 * 'hashtags' and 'messageQueue' localStorage keys (SR-SPLIT-01, AC-1, AC-16).
 * All other services and components must go through this service rather than
 * calling localStorage directly.
 *
 * Security model:
 * - localStorage is attacker-controlled under XSS or a compromised browser
 *   extension (FIND-P3-SEC-4, OWASP A03:2021, CWE-20).
 * - Every read from localStorage goes through a validator before the data
 *   reaches the domain layer (validateHashtagsList, validateMessageQueue).
 * - Try/catch wraps JSON.parse; malformed values produce [] / empty queue
 *   without crashing the application (fail-safe, AC-19).
 * - Malformed values are never logged to avoid CWE-117 (log-injection risk
 *   if attacker-controlled data reaches the JSON encoder).
 *
 * Dependency direction (SR-SPLIT-02, ADR-1):
 * - Zero intra-project imports: Persistence is a leaf node in the dependency
 *   graph. No imports from SubscriptionStateService, SubscriptionStompClient,
 *   or SubscriptionService are allowed (ESLint T2).
 */
@Injectable({
  providedIn: 'root'
})
export class SubscriptionPersistence {

  /**
   * Reads and validates the persisted hashtag list from localStorage.
   *
   * Applies validateHashtagsList before returning any value so that
   * attacker-controlled localStorage data cannot reach the STOMP publish
   * path (SR-SPLIT-01a, OWASP A03:2021, CWE-20).
   *
   * On any parse failure, returns [] and emits a console.warn without
   * logging the malformed value itself (AC-19, CWE-117).
   *
   * @returns The validated list of persisted hashtags, or [] on any error.
   */
  loadHashtags(): string[] {
    const raw = localStorage.getItem('hashtags');
    if (raw === null) {
      return [];
    }
    try {
      const parsed = JSON.parse(raw);
      return validateHashtagsList(parsed) ?? [];
    } catch {
      // Do NOT log the malformed value — it is attacker-controlled and could
      // contain log-injection payloads (CWE-117, SR-SPLIT-01a, AC-19).
      console.warn('hashtags localStorage malformed, resetting');
      return [];
    }
  }

  /**
   * Persists the given hashtag list to localStorage.
   *
   * Uses safeSetItem to handle QuotaExceededError gracefully (ADR-7).
   *
   * @param hashtags - The validated list of hashtags to persist.
   */
  saveHashtags(hashtags: readonly string[]): void {
    safeSetItem('hashtags', JSON.stringify(hashtags));
  }

  /**
   * Returns true if a persisted messageQueue value exists in localStorage.
   *
   * Used by callers that need to distinguish "no stored data" from "stored data
   * that failed validation" before calling MessageQueue.restore() (ADR-4 migration
   * detection).
   *
   * This is the only authorised way to probe the 'messageQueue' key from outside
   * MessageQueue itself (SR-SPLIT-01, AC-1).
   *
   * @returns true if 'messageQueue' key is present in localStorage.
   */
  hasPersistedMessageQueue(): boolean {
    return localStorage.getItem('messageQueue') !== null;
  }

  /**
   * Factory for a new MessageQueue instance with the specified capacity.
   *
   * Called exactly once from SubscriptionStateService's constructor to
   * create the single shared queue instance (SR-SPLIT-03, AC-3).
   * Not injectable — MessageQueue is a pure data structure with no Angular
   * dependencies (ADR-2).
   *
   * @param capacity - Maximum number of WallMessages to hold. Defaults to 20.
   * @returns A new, empty MessageQueue.
   */
  createMessageQueue(capacity: number = 20): MessageQueue {
    return new MessageQueue(capacity);
  }
}

/**
 * A bounded FIFO queue of WallMessages with localStorage persistence.
 *
 * MessageQueue is a pure data structure: it has no Angular dependencies and
 * is not registered as an injectable service.  The single instance per session
 * is owned by SubscriptionStateService, created via
 * SubscriptionPersistence.createMessageQueue() (SR-SPLIT-03, AC-3, ADR-2).
 *
 * Persistence format (v:2 envelope):
 *   { v: WallMessageSchemaVersion, items: WallMessage[] }
 *
 * In schema v:2, each item carries a hashtags[] array.  The restore() method
 * delegates full schema validation to MessageQueueValidator; on any violation
 * the queue starts empty (clear-on-upgrade, ADR-4).
 *
 * All public methods maintain these invariants:
 * - Capacity is enforced: oldest entries are evicted when the queue is full.
 * - Deduplication by id: duplicate ids are silently ignored on enqueue.
 * - Hashtag membership: every WallMessage must have hashtags.length >= 1.
 *   Entries that violate this invariant are rejected or produced only after
 *   a validated normalisation step (SR-PRUNE-08).
 */
export class MessageQueue {
  private storage: WallMessage[] = [];

  constructor(private capacity: number = 20) {
  }

  /**
   * Restores the queue from localStorage.
   *
   * Validation is delegated to MessageQueueValidator (SR-PRUNE-01, SR-PRUNE-02).
   * The validator enforces the full v:2 schema including:
   *   - Correct schema version (v === WallMessageSchemaVersion)
   *   - Each item has id (string, ≤100), url (string, ≤512), hashtags (array, ≥1)
   *   - Prototype-pollution keys (__proto__, constructor, prototype) in item keys
   *     or hashtag values are rejected (SR-PRUNE-02)
   *   - Any single invalid item discards the entire queue (SR-PRUNE-01)
   *
   * If the stored value is absent, unparseable, or fails validation, the queue
   * starts empty (clear-on-upgrade, ADR-4).
   */
  restore(): void {
    let storedMessages: string | null = localStorage.getItem('messageQueue');
    if (storedMessages !== null) {
      try {
        const parsed = JSON.parse(storedMessages);
        // Full schema validation via MessageQueueValidator (SR-PRUNE-01, SR-PRUNE-02).
        // validateMessageQueue returns null on any violation — discard on null.
        const validated = validateMessageQueue(parsed);
        if (validated === null) {
          console.warn('MessageQueue.restore: validation failed — discarding queue (ADR-4, SR-PRUNE-01)');
          this.storage = [];
          safeSetItem('messageQueue', JSON.stringify({ v: WallMessageSchemaVersion, items: [] }));
          return;
        }
        this.storage = validated;
      } catch {
        console.warn('MessageQueue.restore: failed to parse stored queue — discarding');
        this.storage = [];
      }
    }
  }

  /**
   * Adds a WallMessage to the end of the queue.
   *
   * If the queue is at capacity, oldest entries are removed until space is
   * available.  Duplicate ids are silently ignored (dedup by id).
   *
   * Persists via safeSetItem to handle QuotaExceededError (ADR-7).
   *
   * @param item - The WallMessage to add.
   */
  enqueue(item: WallMessage): void {
    if (this.size() >= this.capacity) {
      console.log('Queue is full. Removing oldest entries');
      while (this.size() >= this.capacity) {
        this.storage.shift();
      }
      console.log('Queue size is now', this.size());
    }
    if (this.storage.filter(message => message.id === item.id).length) {
      console.log('Message with id', item.id, 'is already known. Ignore new one');
      return;
    }
    this.storage.push(item);
    this.persist();
  }

  /**
   * Enqueues a WallMessage, or if a message with the same id already exists,
   * merges the given hashtag into the existing entry's hashtags[] (no duplicate
   * hashtag entries).
   *
   * This is the ingest path for HTTP fallback cache entries where the same
   * toot may have been delivered under multiple hashtags (ADR-6 ingest contract).
   *
   * @param item        - The WallMessage to enqueue or merge into.
   * @param hashtagToMerge - Normalised hashtag to merge if the entry already exists.
   */
  enqueueOrMergeHashtag(item: WallMessage, hashtagToMerge: string): void {
    const existingIndex = this.storage.findIndex(m => m.id === item.id);
    if (existingIndex !== -1) {
      const existing = this.storage[existingIndex];
      if (hashtagToMerge && !existing.hashtags.includes(hashtagToMerge)) {
        this.storage[existingIndex] = {
          ...existing,
          hashtags: [...existing.hashtags, hashtagToMerge],
        };
        this.persist();
      }
      return;
    }
    this.enqueue(item);
  }

  dequeue(id: string | undefined): WallMessage | undefined {
    let messageToRemove: WallMessage | undefined;
    if (id == undefined) {
      return messageToRemove;
    }
    this.storage.forEach(scm => {
      if (scm.id === id) {
        messageToRemove = scm;
      }
    })
    if (messageToRemove) {
      const index = this.storage.indexOf(messageToRemove);
      if (index > -1) {
        this.storage.splice(index,1);
      }
    }
    // Compact the array by filtering out any undefined or empty values (if any remain)
    this.storage = this.storage.filter(item => item !== undefined);

    this.persist();
    return messageToRemove;
  }

  clear(): void {
    while (this.storage.length > 0){
      this.storage.pop();
    }
    this.persist();
  }

  size(): number {
    return this.storage.length;
  }

  toArray(): WallMessage[] {
    return this.storage;
  }

  /**
   * Removes the given hashtag from every WallMessage.hashtags[] in the queue,
   * then drops messages whose hashtags[] has become empty as a result.
   *
   * Pure operation on the in-memory snapshot; persists via safeSetItem on
   * completion.
   *
   * All hashtag comparisons use normalizeHashtag() (SR-PRUNE-07).
   *
   * @param hashtag - Raw or normalised hashtag string to remove.
   * @returns PruneResult with ids of removed messages and the remaining queue.
   */
  pruneByHashtag(hashtag: string): PruneResult {
    const normalised = normalizeHashtag(hashtag);
    const removed: string[] = [];

    // Remove the hashtag from every message's membership list
    this.storage = this.storage.map(msg => ({
      ...msg,
      hashtags: msg.hashtags.filter(h => normalizeHashtag(h) !== normalised),
    }));

    // Identify and collect messages that are now membership-empty
    const toRemove = this.storage.filter(msg => msg.hashtags.length === 0);
    toRemove.forEach(msg => removed.push(msg.id));

    // Keep only messages that still have at least one hashtag
    this.storage = this.storage.filter(msg => msg.hashtags.length > 0);

    this.persist();
    return { removed, remaining: [...this.storage] };
  }

  /**
   * Removes multiple hashtags from the queue by calling pruneByHashtag for
   * each.  Intended for the cancel-all scenario where all subscriptions
   * are terminated at once.
   *
   * The removed count in the returned PruneResult is the union of all messages
   * removed across the individual calls (a message is counted once even if it
   * was a member of multiple removed hashtags).
   *
   * @param hashtags - Array of raw or normalised hashtag strings.
   * @returns PruneResult reflecting the cumulative result of all prune operations.
   */
  pruneByHashtags(hashtags: string[]): PruneResult {
    const allRemoved = new Set<string>();
    let lastResult: PruneResult = { removed: [], remaining: [...this.storage] };

    for (const hashtag of hashtags) {
      lastResult = this.pruneByHashtag(hashtag);
      lastResult.removed.forEach(id => allRemoved.add(id));
    }

    return { removed: Array.from(allRemoved), remaining: lastResult.remaining };
  }

  /**
   * Updates an existing message in the queue only when the incoming `editedAt`
   * timestamp is at least as recent as the stored one (D-07).
   *
   * Both timestamps are normalised to UTC ISO-8601 strings before comparison
   * so that lexicographic ordering equals chronological ordering regardless
   * of the timezone offset in the original Mastodon or fallback payload.
   *
   * A NOOP when:
   * - No message with the given `id` exists in the queue.
   * - The incoming `editedAt` is older than the stored value.
   * - Either timestamp is unparseable (guards against malformed input).
   */
  update(item: StatusUpdatedMessage) {
    const index = this.storage.findIndex(scm => scm.id === item.id);
    if (index === -1) {
      return;
    }

    const existing = this.storage[index];

    // UTC-normalised comparison per D-07
    try {
      const incomingUtc = new Date(item.editedAt).toISOString();
      const currentUtc  = existing.editedAt
        ? new Date(existing.editedAt).toISOString()
        : ''; // empty string is lex-less-than any ISO date → allow update

      if (incomingUtc < currentUtc) {
        // Incoming edit is older than the stored edit — ignore
        console.log('MessageQueue.update: ignoring stale edit for', item.id);
        return;
      }
    } catch {
      // Unparseable date — skip to avoid corrupting the queue
      console.warn('MessageQueue.update: unparseable editedAt for', item.id);
      return;
    }

    this.storage = this.storage.map(smc =>
      smc.id === item.id ? {
        ...smc,
        url: item.url + '?cachebreaker=' + new Date().getTime(),
        id: item.id,
        editedAt: item.editedAt,
      } : smc
    );
    this.persist();
  }

  /**
   * Persists the current queue to localStorage using the v:2 envelope format.
   *
   * The envelope is { v: WallMessageSchemaVersion, items: WallMessage[] }.
   * Uses safeSetItem to handle QuotaExceededError (ADR-7).
   */
  private persist(): void {
    const envelope = { v: WallMessageSchemaVersion, items: this.storage };
    safeSetItem('messageQueue', JSON.stringify(envelope));
  }
}
