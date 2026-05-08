import {Injectable} from '@angular/core';
import {Observable, BehaviorSubject, Subject} from 'rxjs';
import {WallMessage, PruneResult} from './model/wall-message';
import {StatusUpdatedMessage} from './message-types/status-updated-message';
import {CacheEntry} from './fallback/fallback.service';
import {normalizeHashtag} from './util/hashtag';
import {environment} from '../environments/environment';
import {SubscriptionPersistence, MessageQueue} from './subscription-persistence.service';

/**
 * Service responsible for managing the in-memory state of received wall messages.
 *
 * SubscriptionStateService owns:
 * - The single MessageQueue instance for this session (SR-SPLIT-03, AC-3, ADR-2).
 * - Three public observables: messageObservable$, hasMigrated$, settlingHashtags$.
 * - The recentlyTerminated guard map (ADR-3, SR-PRUNE-03/04/06).
 * - The ingest path from the HTTP fallback (ingestCacheEntries).
 * - The settling-timer set and clearSettlingTimers() teardown.
 *
 * Dependency direction (ADR-1, SR-SPLIT-02):
 * - Depends on SubscriptionPersistence (leaf) only.
 * - Does NOT import from SubscriptionStompClient or SubscriptionService.
 * - SubscriptionStateService can be unit-tested without any RxStomp mock.
 *
 * Security model:
 * - All localStorage access goes through SubscriptionPersistence (SR-SPLIT-01).
 * - ingestCacheEntries trusts the hashtag parameter to be caller-validated
 *   (trust boundary: FallbackService, SR-SPLIT-08/AC-15).
 * - Guard gate (recentlyTerminated) prevents late STOMP deliveries from
 *   re-adding pruned toots (SR-PRUNE-03, SR-PRUNE-06).
 */
@Injectable({
  providedIn: 'root'
})
export class SubscriptionStateService {

  /**
   * Single MessageQueue instance for this session.
   *
   * Created once in the constructor via persistence.createMessageQueue() and
   * never replaced.  Both STOMP and HTTP fallback ingest paths write to this
   * same instance so live and fallback toots are indistinguishable on the wall
   * (glacier-fallback-mode-discipline, ADR-6, SR-SPLIT-03, AC-3).
   */
  private readonly receivedMessages: MessageQueue;

  private messageSubject$: BehaviorSubject<WallMessage[]> = new BehaviorSubject<WallMessage[]>([]);

  /**
   * Observable stream of the current wall message array.
   *
   * Emits the full array on every state change (enqueue, dequeue, update,
   * prune, restore, or clear).  Consumers should not mutate the emitted array.
   */
  public readonly messageObservable$: Observable<WallMessage[]> = this.messageSubject$.asObservable();

  /**
   * Emits `true` once when MessageQueue.restore() discards an incompatible
   * queue (schema v:1 or missing) during the restoreFromStorageAndEmit() call
   * (ADR-4).
   *
   * MigrationBannerComponent subscribes to this to display its one-shot notice.
   * The Subject emits at most once per app session; subsequent subscribes that
   * arrive after the emit will not receive the value (use a BehaviorSubject
   * seeded `false` for that pattern, but a plain Subject is sufficient here
   * because the banner subscribes before restoreFromStorageAndEmit() is called).
   *
   * SR-SPLIT-07: this must remain a plain Subject<boolean>, NOT BehaviorSubject.
   * A BehaviorSubject seeded `false` would re-emit on subscribe, breaking the
   * one-shot semantic and causing the migration banner to re-appear on route
   * changes or re-renders (ADR-4, AC-10, T7).
   */
  private _migratedSubject = new Subject<boolean>();
  public readonly hasMigrated$: Observable<boolean> = this._migratedSubject.asObservable();

  /**
   * Guard map preventing late STOMP deliveries from re-adding pruned toots
   * (ADR-3, SR-PRUNE-03, SR-PRUNE-04, SR-PRUNE-06).
   *
   * Key:   normalised hashtag string (output of normalizeHashtag).
   * Value: absolute expiry timestamp in ms (Date.now() + guardTtlMs).
   *
   * An entry is active if Date.now() < value.  Entries are swept on each
   * seed operation to prevent unbounded growth (SR-PRUNE-03).
   */
  private recentlyTerminated: Map<string, number> = new Map();

  /**
   * BehaviorSubject that emits the current set of settling hashtags.
   *
   * A hashtag is "settling" while its recentlyTerminated guard is active
   * (the guard window has not expired yet).  The HashtagComponent subscribes
   * to settlingHashtags$ to drive the per-chip settling spinner (UX spec:
   * MatProgressSpinner diameter=16, aria-label = chip.settling.aria i18n key).
   *
   * The set uses normalised hashtag strings so it is consistent with the
   * recentlyTerminated map keys.
   */
  private _settlingHashtagsSubject = new BehaviorSubject<Set<string>>(new Set());
  public readonly settlingHashtags$: Observable<Set<string>> = this._settlingHashtagsSubject.asObservable();

  /**
   * Tracks active setTimeout handles scheduled by seedRecentlyTerminated.
   *
   * Each call to seedRecentlyTerminated schedules a timer to remove the
   * settling hashtag from _settlingHashtagsSubject once the guard TTL
   * expires.  Without tracking, these handles cannot be cancelled during
   * teardown, leaving closures over _settlingHashtagsSubject alive and
   * delaying GC.
   *
   * Cleared by clearSettlingTimers() on teardown.
   */
  private _settlingTimerHandles: Set<ReturnType<typeof setTimeout>> = new Set();

  constructor(private persistence: SubscriptionPersistence) {
    // Create the single queue instance for this session (SR-SPLIT-03).
    // All STOMP and fallback ingest paths write to this same instance.
    this.receivedMessages = this.persistence.createMessageQueue();
  }

  /**
   * Restores previously persisted messages from localStorage and emits
   * the resulting queue as the first value on messageObservable$.
   *
   * Ordering (mirrors getCreatedEvents from original SubscriptionService):
   *   1. Capture sizeBeforeRestore.
   *   2. Capture hadStoredData (probed via SubscriptionPersistence, AC-1).
   *   3. Call this.receivedMessages.restore().
   *   4. Capture sizeAfterRestore.
   *   5. If hadStoredData && sizeBeforeRestore === 0 && sizeAfterRestore === 0:
   *      emit hasMigrated$ (migration-banner signal, ADR-4).
   *   6. Emit messageSubject$.next(toArray()).
   *   7. Return messageObservable$.
   *
   * @returns The messageObservable$ observable for callers that need to chain.
   */
  restoreFromStorageAndEmit(): Observable<WallMessage[]> {
    console.log('SubscriptionStateService:', 'restoreFromStorageAndEmit called');
    const sizeBeforeRestore = this.receivedMessages.size();
    const hadStoredData = this.persistence.hasPersistedMessageQueue();
    this.receivedMessages.restore();
    const sizeAfterRestore = this.receivedMessages.size();
    if (hadStoredData && sizeBeforeRestore === 0 && sizeAfterRestore === 0) {
      // The restore discarded the queue — signal the migration banner (ADR-4).
      this._migratedSubject.next(true);
    }
    this.messageSubject$.next(this.receivedMessages.toArray());
    return this.messageObservable$;
  }

  /**
   * Clears all wall messages from the queue and emits the resulting empty array.
   */
  clearAllToots(): void {
    this.receivedMessages.clear();
    this.messageSubject$.next(this.receivedMessages.toArray());
  }

  /**
   * Enqueues a new WallMessage and emits the updated array.
   *
   * @param msg - The WallMessage to add.
   */
  enqueueWallMessage(msg: WallMessage): void {
    this.receivedMessages.enqueue(msg);
    this.messageSubject$.next(this.receivedMessages.toArray());
  }

  /**
   * Updates an existing message in the queue and emits the updated array.
   *
   * @param msg - The update message containing id, url, and editedAt.
   */
  updateMessage(msg: StatusUpdatedMessage): void {
    this.receivedMessages.update(msg);
    this.messageSubject$.next(this.receivedMessages.toArray());
  }

  /**
   * Removes the message with the given id from the queue and emits the updated array.
   *
   * @param id - The id of the message to remove. NOOP if id is undefined.
   */
  dequeueById(id: string | undefined): void {
    this.receivedMessages.dequeue(id);
    this.messageSubject$.next(this.receivedMessages.toArray());
  }

  /**
   * Ingests a batch of cache entries delivered by the HTTP fallback endpoint
   * (D-06, D-07).  Applies CREATED / UPDATED / DELETED events in-order,
   * deduplicating by id so that an event already delivered over STOMP does
   * not appear twice on the wall.
   *
   * For CREATED entries: maps to WallMessage with hashtags populated from
   * the hashtag parameter (SR-PRUNE-08).  If a toot URL already exists in
   * the queue (deduplicate), merges the hashtag into the existing entry's
   * hashtags[] rather than adding a duplicate toot (ADR-6 ingest contract).
   *
   * Trust boundary: caller (FallbackService) is responsible for validating the
   * cache-entry shape.  This method enforces only WallMessage invariants on
   * hashtag (SR-PRUNE-08, AC-15).
   *
   * Called by FallbackService (via the SubscriptionService facade) after each
   * successful poll response (D-01).
   *
   * @param hashtag - The hashtag whose cache entries are being ingested.
   *   Used to populate hashtags[] on new WallMessage entries.
   * @param entries - Ordered list of cache entries from the server's ring buffer,
   *   oldest-first.
   */
  ingestCacheEntries(hashtag: string, entries: readonly CacheEntry[]): void {
    let changed = false;
    const normalised = normalizeHashtag(hashtag);

    for (const entry of entries) {
      switch (entry.type) {
        case 'CREATED': {
          if (!normalised) {
            // A cache entry without a known hashtag violates the WallMessage
            // invariant (hashtags.length >= 1).  Skip entirely rather than
            // producing an invalid entry that MessageQueueValidator would
            // reject on next restore (SR-PRUNE-08, ADR-6 ingest contract).
            break;
          }
          const wallMessage: WallMessage = {
            id: entry.id,
            url: entry.url ?? '',
            hashtags: [normalised],
          };
          this.receivedMessages.enqueueOrMergeHashtag(wallMessage, normalised);
          changed = true;
          break;
        }
        case 'UPDATED': {
          if (entry.url && entry.editedAt) {
            const msg: StatusUpdatedMessage = {
              id: entry.id,
              url: entry.url,
              editedAt: entry.editedAt,
            };
            this.receivedMessages.update(msg);
            changed = true;
          }
          break;
        }
        case 'DELETED': {
          this.receivedMessages.dequeue(entry.id);
          changed = true;
          break;
        }
      }
    }
    if (changed) {
      this.messageSubject$.next(this.receivedMessages.toArray());
    }
  }

  /**
   * Returns true if the given hashtag is currently in the recentlyTerminated
   * guard window (i.e. the guard is active and has not yet expired).
   *
   * Used by the STOMP creation handler to gate incoming deliveries that arrive
   * after the termination ack but before the guard TTL expires (ADR-3,
   * SR-PRUNE-06).
   *
   * @param hashtag - Raw hashtag string; will be normalised internally.
   */
  isRecentlyTerminated(hashtag: string): boolean {
    const normalised = normalizeHashtag(hashtag);
    const expiresAt = this.recentlyTerminated.get(normalised);
    if (expiresAt === undefined) {
      return false;
    }
    return Date.now() < expiresAt;
  }

  /**
   * Seeds the recentlyTerminated guard for a normalised hashtag.
   *
   * Before seeding, sweeps stale entries (expiresAt < Date.now()) to keep
   * the map bounded (SR-PRUNE-03).  Uses Date.now() — not performance.now()
   * — to reduce timing side-channel risk (SR-PRUNE-04).
   *
   * @param hashtag - Raw or normalised hashtag string; will be normalised
   *                  internally before storing.
   */
  seedRecentlyTerminated(hashtag: string): void {
    const guardTtlMs = (environment as any).prune?.guardTtlMs ?? 10_000;
    const now = Date.now();

    // Eviction sweep: remove entries whose TTL has expired (SR-PRUNE-03)
    for (const [key, expiresAt] of this.recentlyTerminated) {
      if (expiresAt < now) {
        this.recentlyTerminated.delete(key);
      }
    }

    const normalised = normalizeHashtag(hashtag);
    this.recentlyTerminated.set(normalised, now + guardTtlMs);

    // Emit the updated settling set so the chip spinner can react (UX spec).
    this._emitSettlingHashtags();

    // Schedule a clear of this hashtag's settling state once the TTL expires.
    // This allows the chip spinner to disappear automatically without polling.
    // The handle is tracked in _settlingTimerHandles so clearSettlingTimers()
    // can cancel it on teardown (FIND-P3-SEC-5/6).
    const handle = setTimeout(() => {
      this._settlingTimerHandles.delete(handle);
      const current = this._settlingHashtagsSubject.value;
      if (current.has(normalised)) {
        const updated = new Set(current);
        updated.delete(normalised);
        this._settlingHashtagsSubject.next(updated);
      }
    }, guardTtlMs + 50); // +50 ms safety margin over the guard TTL
    this._settlingTimerHandles.add(handle);
  }

  /**
   * Prunes the named hashtag from the queue and emits the updated array.
   *
   * @param hashtag - Raw or normalised hashtag string to remove.
   * @returns PruneResult with ids of removed messages and the remaining queue.
   */
  pruneByHashtag(hashtag: string): PruneResult {
    const result = this.receivedMessages.pruneByHashtag(hashtag);
    this.messageSubject$.next(this.receivedMessages.toArray());
    return result;
  }

  /**
   * Cancels all pending settling-spinner timers scheduled by seedRecentlyTerminated.
   *
   * Each seedRecentlyTerminated call schedules a setTimeout that removes a
   * hashtag from the settling set once the guard TTL expires.  On teardown
   * these handles must be cancelled to avoid closures over _settlingHashtagsSubject
   * delaying GC (FIND-P3-SEC-5/6).
   *
   * Must be called BEFORE stomp.terminateAll() (AC-6, SR-SPLIT-06, T4).
   */
  clearSettlingTimers(): void {
    for (const handle of this._settlingTimerHandles) {
      clearTimeout(handle);
    }
    this._settlingTimerHandles.clear();
  }

  /**
   * Rebuilds and emits the current set of actively settling hashtags.
   *
   * Called after each seedRecentlyTerminated to keep settlingHashtags$
   * in sync with the recentlyTerminated map.
   */
  private _emitSettlingHashtags(): void {
    const active = new Set<string>();
    const currentTime = Date.now();
    for (const [key, expiresAt] of this.recentlyTerminated) {
      if (currentTime < expiresAt) {
        active.add(key);
      }
    }
    // Only emit if the set has changed to avoid unnecessary renders
    const current = this._settlingHashtagsSubject.value;
    let changed = active.size !== current.size;
    if (!changed) {
      for (const k of active) {
        if (!current.has(k)) { changed = true; break; }
      }
    }
    if (changed) {
      this._settlingHashtagsSubject.next(active);
    }
  }
}
