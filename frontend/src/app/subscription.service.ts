import {Injectable} from '@angular/core';
import {Observable, Subscription, BehaviorSubject, Subject} from "rxjs";
import {RxStompService} from "./rx-stomp.service";
import {Message} from "@stomp/stompjs";
import {SubscriptionAckMessage} from "./message-types/subscription-ack-message";
import {TerminationAckMessage} from "./message-types/termination-ack-message";
import {StatusCreatedMessage} from "./message-types/status-created-message";
import {StatusUpdatedMessage} from "./message-types/status-updated-message";
import {StatusDeletedMessage} from "./message-types/status-deleted-message";
import {CacheEntry} from "./fallback/fallback.service";
import {WallMessage, WallMessageSchemaVersion, PruneResult} from "./model/wall-message";
import {validateMessageQueue, validateHashtagsList} from "./model/message-queue-validator";
import {normalizeHashtag} from "./util/hashtag";
import {safeSetItem} from "./util/safe-storage";
import {WallAnnouncerService} from "./services/wall-announcer.service";
import {environment} from "../environments/environment";

/**
 * Service for managing subscriptions to topics, handling received messages,
 * and subscribing/unsubscribing to specific hashtags. This service interacts
 * with an RxStompService to subscribe to message topics or publish subscription
 * and termination requests.
 *
 * Domain extensions for prune-on-removal (ADR-1 — ADR-7):
 *   - MessageQueue now stores WallMessage entries (with hashtags[]) rather
 *     than SafeMessage.
 *   - The 4-step ack handler sequences termination cleanly and guard-gates
 *     late in-flight STOMP deliveries via recentlyTerminated.
 *   - ingestCacheEntries populates hashtags[] from the hashtag parameter.
 */
@Injectable({
  providedIn: 'root'
})
export class SubscriptionService {

  private subscriptionsSubscription: Subscription;
  private terminationsSubscription: Subscription;
  private receivedMessages: MessageQueue = new MessageQueue();
  private messageSubject$: BehaviorSubject<WallMessage[]> = new BehaviorSubject<WallMessage[]>([]);
  public messageObservable$: Observable<WallMessage[]> = this.messageSubject$.asObservable(); // Nur als Observable nach außen exponieren.
  private subscriptions: { [key: string]: Subscription } = {};
  private destinations: string[] = [];
  private hashtags: string[] = [];

  /**
   * Emits `true` once when MessageQueue.restore() discards an incompatible
   * queue (schema v:1 or missing) during the getCreatedEvents() call (ADR-4).
   *
   * MigrationBannerComponent subscribes to this to display its one-shot notice.
   * The Subject emits at most once per app session; subsequent subscribes that
   * arrive after the emit will not receive the value (use a BehaviorSubject
   * seeded `false` for that pattern, but a plain Subject is sufficient here
   * because the banner subscribes before getCreatedEvents() is called).
   */
  private _migratedSubject = new Subject<boolean>();
  public hasMigrated$: Observable<boolean> = this._migratedSubject.asObservable();

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
  public settlingHashtags$: Observable<Set<string>> = this._settlingHashtagsSubject.asObservable();

  /**
   * Tracks active setTimeout handles scheduled by seedRecentlyTerminated.
   *
   * Each call to seedRecentlyTerminated schedules a timer to remove the
   * settling hashtag from _settlingHashtagsSubject once the guard TTL
   * expires.  Without tracking, these handles cannot be cancelled during
   * component teardown, leaving closures over _settlingHashtagsSubject
   * alive and delaying GC.
   *
   * Cleared by clearSettlingTimers() on teardown.
   */
  private _settlingTimerHandles: Set<ReturnType<typeof setTimeout>> = new Set();

  constructor(
    private rxStompService: RxStompService,
    private wallAnnouncerService: WallAnnouncerService,
  ) {
    this.subscriptionsSubscription = this.rxStompService
      .watch('/user/topic/subscriptions')
      .subscribe((message: Message) => {
        console.log('Subscription topic', message.body);
        const data: SubscriptionAckMessage = JSON.parse(message.body);
        this.handleSubscriptionAckMessage(data);
      });

    this.terminationsSubscription = this.rxStompService
      .watch('/user/topic/terminations')
      .subscribe((message: Message) => {
        console.log('Subscription topic', message.body);
        const data: TerminationAckMessage = JSON.parse(message.body);
        this.handleTerminationAckMessage(data);
      });

    // Restore hashtags from previous session.
    // FIND-P3-SEC-4 / OWASP A03: localStorage is attacker-controlled under XSS
    // or a compromised browser extension.  validateHashtagsList enforces the
    // hashtag charset allowlist, the length bounds, and the prototype-pollution
    // guard before any string is forwarded as a STOMP subscription request.
    const rawHashtags = JSON.parse(localStorage.getItem('hashtags') || '[]');
    const storedHashtags = validateHashtagsList(rawHashtags) ?? [];
    storedHashtags.forEach(tag => this.subscribeHashtag(tag));
  }

  /**
   * Retrieves an observable stream of created events from the message queue.
   * The method also restores previously received messages.
   *
   * On restore, if the stored queue does not carry the v:2 schema version
   * the MessageQueueValidator (secure-tdd-implementer lane) rejects it and
   * the queue starts empty.  A migration banner is shown by WallComponent
   * in that case (ADR-4).
   *
   * @return {Observable<WallMessage[]>} An observable emitting events from the message queue.
   */
  getCreatedEvents(): Observable<WallMessage[]> {
    console.log('SubscriptionService:', 'New Observable created');
    const sizeBeforeRestore = this.receivedMessages.size();
    const hadStoredData = localStorage.getItem('messageQueue') !== null;
    this.receivedMessages.restore();
    // Detect migration: there was stored data but restore discarded it
    // (queue size is still 0 after restore and we had a stored queue key).
    // This covers the v:1 → v:2 schema upgrade case (ADR-4).
    const sizeAfterRestore = this.receivedMessages.size();
    if (hadStoredData && sizeBeforeRestore === 0 && sizeAfterRestore === 0) {
      // The restore discarded the queue — signal the migration banner (ADR-4).
      this._migratedSubject.next(true);
    }
    this.messageSubject$.next(this.receivedMessages.toArray())
    return this.messageObservable$;
  }

  /**
   * Handles the subscription acknowledgment message and manages subscriptions
   * for creation, modification, and deletion events based on the provided data.
   *
   * @param {SubscriptionAckMessage} data - The subscription acknowledgment message,
   * including subscription status, principal, and hashtag information.
   * @return {void} This method does not return a value.
   */
  private handleSubscriptionAckMessage(data: SubscriptionAckMessage) {
    if (data.subscribed) {
      console.log('Adding subscriptions for creation, modification and deletion.');

      this.hashtags.push(data.hashtag);
      safeSetItem('hashtags', JSON.stringify(this.hashtags));

      const creationDestination = this.destination(data.principal, data.hashtag, 'creation');
      this.destinations.push(creationDestination);
      this.subscriptions[creationDestination] = this.subscribeToStatusCreatedMessages(creationDestination, data.hashtag);

      const modificationDestination = this.destination(data.principal, data.hashtag, 'modification');
      this.destinations.push(modificationDestination);
      this.subscriptions[modificationDestination] = this.subscribeToStatusUpdatedMessages(modificationDestination);

      const deletionDestination = this.destination(data.principal, data.hashtag, 'deletion');
      this.destinations.push(deletionDestination);
      this.subscriptions[deletionDestination] = this.subscribeToStatusDeletedMessages(deletionDestination);

    } else {
      console.error('Could not subscribe to topic', data.hashtag);
    }
  }

  /**
   * Handles termination acknowledgment messages using the 4-step ack sequence
   * (ADR-3, SR-PRUNE-06, SR-PRUNE-13).
   *
   * The 4 steps execute synchronously in the same tick — no await or
   * setTimeout between them:
   *
   *   Step 1: Unsubscribe the three RxStomp topic subscriptions for this
   *           hashtag (creation / modification / deletion).
   *   Step 2: Seed recentlyTerminated for the normalised hashtag with an
   *           expiry of Date.now() + guardTtlMs.  The sweep runs here too,
   *           evicting stale entries (SR-PRUNE-03).
   *   Step 3: Call pruneByHashtag on the MessageQueue and persist the result.
   *   Step 4: Call wallAnnouncerService.announce() with the prune result so
   *           screen reader users hear a coalesced announcement (ADR-5).
   *
   * Also removes the hashtag from the local hashtags[] list and persists it.
   *
   * @param {TerminationAckMessage} data - The termination acknowledgment message.
   * @return {void}
   */
  private handleTerminationAckMessage(data: TerminationAckMessage) {
    if (data.terminated) {

      this.hashtags = this.hashtags.filter(tag => tag !== data.hashtag);
      safeSetItem('hashtags', JSON.stringify(this.hashtags));

      // Step 1: unsubscribe RxStomp topic subscriptions for this hashtag
      const creationDestination = this.destination(data.principal, data.hashtag, 'creation');
      this.terminateSubscriptionByDestination(creationDestination);

      const modificationDestination = this.destination(data.principal, data.hashtag, 'modification');
      this.terminateSubscriptionByDestination(modificationDestination);

      const deletionDestination = this.destination(data.principal, data.hashtag, 'deletion');
      this.terminateSubscriptionByDestination(deletionDestination);

      // Step 2: seed recentlyTerminated (sweep stale entries first — SR-PRUNE-03)
      this.seedRecentlyTerminated(data.hashtag);

      // Step 3: prune the MessageQueue
      const pruneResult = this.receivedMessages.pruneByHashtag(data.hashtag);
      this.messageSubject$.next(this.receivedMessages.toArray());

      // Step 4: announce to screen readers via the coalescing announcer (ADR-5)
      this.wallAnnouncerService.announce({ type: 'prune', result: pruneResult });

    } else {
      console.error('Could not terminate subscription for principal ' + data.principal + " and hashtag " + data.hashtag);
    }
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
  private seedRecentlyTerminated(hashtag: string): void {
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

  /**
   * Returns true if the given hashtag is currently in the recentlyTerminated
   * guard window (i.e. the guard is active and has not yet expired).
   *
   * Used by subscribeToStatusCreatedMessages to gate incoming STOMP deliveries
   * that arrive after the termination ack but before the guard TTL expires.
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
   * Terminates a subscription associated with the specified destination.
   * If a subscription exists for the given destination, it unsubscribes and removes the subscription.
   * If no subscription is found, an error is logged.
   *
   * @param {string} dest - The destination identifier for the subscription to terminate.
   * @return {void}
   */
  terminateSubscriptionByDestination(dest: string) {
    console.log('Terminating subscriptions for', dest);
    if (this.subscriptions[dest]) {
      this.subscriptions[dest].unsubscribe();
      delete this.subscriptions[dest];
    } else {
      console.error('No subscription found with destination', dest);
    }
  }

  /**
   * Cancels all pending settling-spinner timers scheduled by seedRecentlyTerminated.
   *
   * Each seedRecentlyTerminated call schedules a setTimeout that removes a
   * hashtag from the settling set once the guard TTL expires.  On teardown
   * (terminateAllSubscriptions) these handles must be cancelled to avoid
   * closures over _settlingHashtagsSubject delaying GC (FIND-P3-SEC-5/6).
   *
   * Safe to call at any time — operates only on the tracked set.
   */
  private clearSettlingTimers(): void {
    for (const handle of this._settlingTimerHandles) {
      clearTimeout(handle);
    }
    this._settlingTimerHandles.clear();
  }

  /**
   * Terminates all active subscriptions and unsubscribes from hashtag-based subscriptions and main subscriptions.
   *
   * Also cancels any pending settling-spinner timers so that closures over
   * _settlingHashtagsSubject do not outlive the service (FIND-P3-SEC-5/6).
   *
   * Removes all stored subscriptions and informs the backend that the subscriptions have been terminated.
   *
   * @return {void} Does not return a value.
   */
  terminateAllSubscriptions() {

    // Cancel all pending settling-spinner timers before any subscriptions are
    // torn down, so no timer fires mid-teardown referencing the subject.
    this.clearSettlingTimers();

    // Tell the backend, that you terminate all subscriptions
    this.hashtags.forEach(tag => {
      this.unsubscribeHashtag(tag);
    });

    // unsubscribe your main subscription
    this.subscriptionsSubscription.unsubscribe();
    this.terminationsSubscription.unsubscribe();

    // Unsubscribe each hashtag subscription
    Object.entries(this.subscriptions).forEach(
      ([key, value]) => {
        value.unsubscribe();
        delete this.subscriptions[key];
      }
    );
  }

  /**
   * Clears all the items from the receivedMessages collection.
   * This method removes all stored toots, resetting the state of the collection.
   *
   * @return {void} Does not return a value.
   */
  clearAllToots() {
    this.receivedMessages.clear();
    this.messageSubject$.next(this.receivedMessages.toArray())
  }

  /**
   * Subscribes to messages of the type 'StatusCreated' on the given destination.
   * Processes each received message by parsing its content and queuing it for further handling.
   *
   * Applies the recentlyTerminated guard: if the hashtag for this destination
   * is currently in the guard window, the incoming toot is silently dropped.
   * This prevents late in-flight STOMP deliveries from re-adding pruned toots
   * (ADR-3, SR-PRUNE-06).
   *
   * @param {string} dest - The destination to subscribe to for 'StatusCreated' messages.
   * @param {string} hashtag - The hashtag associated with this subscription destination,
   *                           used for guard-gate checks.
   * @return {Subscription} Returns a subscription object that can be used to manage the lifecycle of the subscription.
   */
  subscribeToStatusCreatedMessages(dest: string, hashtag: string = '') {
    return this.rxStompService
      .watch(dest)
      .subscribe((message: Message) => {
        // Guard gate: drop deliveries for recently-terminated hashtags (ADR-3)
        if (hashtag && this.isRecentlyTerminated(hashtag)) {
          console.log('Dropping late delivery for recently-terminated hashtag:', hashtag);
          return;
        }
        console.log('StatusCreatedMessage received:', message.body);
        const data: StatusCreatedMessage = JSON.parse(message.body);
        const normalised = normalizeHashtag(hashtag);
        if (!normalised) {
          // A toot without a known hashtag has no business being on the wall.
          // An empty hashtags[] would violate the WallMessage invariant
          // (hashtags.length >= 1) and cause MessageQueueValidator to reject
          // the entire queue on next restore (SR-PRUNE-08).
          console.log('Dropping STOMP delivery with empty hashtag');
          return;
        }
        const wallMessage: WallMessage = {
          id: data.id,
          url: data.url,
          hashtags: [normalised],
        };
        this.receivedMessages.enqueue(wallMessage);
        this.messageSubject$.next(this.receivedMessages.toArray())
      });
  }

  /**
   * Subscribes to status updated messages from the specified destination.
   *
   * @param {string} dest The destination to subscribe to for status updated messages.
   * @return {Subscription} A subscription object that can be used to manage the subscription.
   */
  subscribeToStatusUpdatedMessages(dest: string) {
    return this.rxStompService
      .watch(dest)
      .subscribe((message: Message) => {
        console.log('StatusUpdatedMessage received:', message.body);
        const data: StatusUpdatedMessage = JSON.parse(message.body);
        this.receivedMessages.update(data);
        this.messageSubject$.next(this.receivedMessages.toArray())
      });
  }

  /**
   * Subscribes to a destination for listening to status deleted messages.
   * Processes the message, logs it, and dequeues it from received messages based on its ID.
   *
   * @param {string} dest The destination to subscribe to for receiving status deleted messages.
   * @return {Subscription} A subscription object that can be used to manage the lifecycle of the subscription.
   */
  subscribeToStatusDeletedMessages(dest: string) {
    return this.rxStompService
      .watch(dest)
      .subscribe((message: Message) => {
        console.log('StatusDeletedMessage received:', message.body);
        const data: StatusDeletedMessage = JSON.parse(message.body);
        this.receivedMessages.dequeue(data.id);
        this.messageSubject$.next(this.receivedMessages.toArray())
      });
  }

  /**
   * Ingests a batch of cache entries delivered by the HTTP fallback endpoint
   * (D-06, D-07).  Applies CREATED / UPDATED / DELETED events in-order,
   * deduplicating by id so that an event already delivered over STOMP
   * does not appear twice on the wall.
   *
   * For CREATED entries: maps to WallMessage with hashtags populated from
   * the hashtag parameter (SR-PRUNE-08).  If a toot URL already exists in
   * the queue (deduplicate), merges the hashtag into the existing entry's
   * hashtags[] rather than adding a duplicate toot (ADR-6 ingest contract).
   *
   * Called by FallbackService after each successful poll response (D-01).
   *
   * @param {string} hashtag - The hashtag whose cache entries are being ingested.
   *   Used to populate hashtags[] on new WallMessage entries.
   * @param {readonly CacheEntry[]} entries - Ordered list of cache entries from
   *   the server's ring buffer, oldest-first.
   * @return {void}
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
   * Subscribes to updates for the specified hashtag by publishing a subscription request.
   *
   * @param {string} hashtag - The hashtag to subscribe to for updates.
   * @return {void}
   */
  subscribeHashtag(hashtag: string) {
    const message = {hashtag: hashtag};
    this.rxStompService.publish({destination: '/glacier/subscription', body: JSON.stringify(message)});
  }

  /**
   * Unsubscribes from updates for the specified hashtag. Sends a termination request to the server.
   *
   * @param {string} hashtag - The hashtag to unsubscribe from.
   * @return {void} This method does not return a value.
   */
  unsubscribeHashtag(hashtag: string) {
    const message = {hashtag: hashtag};
    this.rxStompService.publish({destination: '/glacier/termination', body: JSON.stringify(message)});
  }

  /**
   * Constructs a destination string based on the provided principal, hashtag, and type.
   *
   * @param {string} principal - The principal or main identifier to be included in the destination path.
   * @param {string} hashtag - The hashtag to be included in the destination path.
   * @param {string} type - The type of the destination or category to be appended.
   * @return {string} The constructed destination path string.
   */
  private destination(principal: string, hashtag: string, type: string): string {
    return '/topic/hashtags/' + principal + '/' + hashtag + '/' + type;
  }
}

/**
 * Class representing a message queue with a limited capacity.
 * The queue provides persistence through localStorage and supports deduplication of messages.
 *
 * In schema v:2, the queue stores WallMessage entries (with hashtags[]) rather
 * than the original SafeMessage.  The restore() method delegates validation to
 * MessageQueueValidator (secure-tdd-implementer lane); if validation fails the
 * queue starts empty.
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
