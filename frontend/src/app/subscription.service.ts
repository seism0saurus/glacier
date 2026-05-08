import {Injectable} from '@angular/core';
import {Observable, Subscription} from 'rxjs';
import {RxStompService} from './rx-stomp.service';
import {Message} from '@stomp/stompjs';
import {SubscriptionAckMessage} from './message-types/subscription-ack-message';
import {TerminationAckMessage} from './message-types/termination-ack-message';
import {StatusCreatedMessage} from './message-types/status-created-message';
import {StatusUpdatedMessage} from './message-types/status-updated-message';
import {StatusDeletedMessage} from './message-types/status-deleted-message';
import {CacheEntry} from './fallback/fallback.service';
import {WallMessage} from './model/wall-message';
import {normalizeHashtag} from './util/hashtag';
import {WallAnnouncerService} from './services/wall-announcer.service';
import {SubscriptionPersistence} from './subscription-persistence.service';
import {SubscriptionStateService} from './subscription-state.service';

// Re-export MessageQueue so that existing tests that import it from this module
// continue to compile unchanged during the strangler-fig refactor (ADR-2).
// Lane C (commit 6) will migrate spec imports to subscription-persistence.service.ts.
export {MessageQueue} from './subscription-persistence.service';

/**
 * Facade service that preserves the original SubscriptionService public API
 * while delegating implementation to specialist services.
 *
 * Responsibilities of the facade (commit 5, AC-11):
 * - Expose the three observables from SubscriptionStateService.
 * - Own the STOMP subscription lifecycle (RxStomp watches, ack handlers,
 *   publish calls) — TODO: Lane B will extract this into SubscriptionStompClient.
 * - Restore persisted hashtags from SubscriptionPersistence on construction.
 * - Delegate all state mutations to SubscriptionStateService.
 *
 * Teardown order (AC-6, SR-SPLIT-06, T4):
 *   1. state.clearSettlingTimers() — cancel timer closures first.
 *   2. Tear down STOMP watches (unsubscribe hashtags + main subscriptions).
 */
@Injectable({
  providedIn: 'root'
})
export class SubscriptionService {

  // ── Public API — observables delegated to state ─────────────────────────

  get messageObservable$(): Observable<WallMessage[]> { return this.state.messageObservable$; }
  get hasMigrated$(): Observable<boolean>             { return this.state.hasMigrated$; }
  get settlingHashtags$(): Observable<Set<string>>    { return this.state.settlingHashtags$; }

  // ── STOMP subscription handles (TODO: Lane B → SubscriptionStompClient) ──

  private subscriptionsSubscription: Subscription;
  private terminationsSubscription: Subscription;
  private subscriptions: { [key: string]: Subscription } = {};
  private destinations: string[] = [];
  private hashtags: string[] = [];

  constructor(
    private rxStompService: RxStompService,
    private wallAnnouncerService: WallAnnouncerService,
    private persistence: SubscriptionPersistence,
    private state: SubscriptionStateService,
  ) {
    // TODO: Lane B — delegate to SubscriptionStompClient.attach()
    this.subscriptionsSubscription = this.rxStompService
      .watch('/user/topic/subscriptions')
      .subscribe((message: Message) => {
        const data: SubscriptionAckMessage = JSON.parse(message.body);
        this.handleSubscriptionAckMessage(data);
      });

    this.terminationsSubscription = this.rxStompService
      .watch('/user/topic/terminations')
      .subscribe((message: Message) => {
        const data: TerminationAckMessage = JSON.parse(message.body);
        this.handleTerminationAckMessage(data);
      });

    // Restore hashtags from previous session via SubscriptionPersistence.
    // SR-SPLIT-01a: loadHashtags() is the sole authorised reader of 'hashtags'
    // (OWASP A03:2021, CWE-20).  TODO: Lane B — delegate to stomp.subscribeHashtag
    const storedHashtags = this.persistence.loadHashtags();
    storedHashtags.forEach(tag => this.subscribeHashtag(tag));
  }

  // ── Public API — delegated to state ────────────────────────────────────

  getCreatedEvents(): Observable<WallMessage[]> {
    return this.state.restoreFromStorageAndEmit();
  }

  clearAllToots(): void { this.state.clearAllToots(); }

  ingestCacheEntries(hashtag: string, entries: readonly CacheEntry[]): void {
    this.state.ingestCacheEntries(hashtag, entries);
  }

  isRecentlyTerminated(hashtag: string): boolean {
    return this.state.isRecentlyTerminated(hashtag);
  }

  // ── Public API — STOMP publish (TODO: Lane B → stomp.subscribeHashtag) ──

  subscribeHashtag(hashtag: string): void {
    this.rxStompService.publish({destination: '/glacier/subscription', body: JSON.stringify({hashtag})});
  }

  unsubscribeHashtag(hashtag: string): void {
    this.rxStompService.publish({destination: '/glacier/termination', body: JSON.stringify({hashtag})});
  }

  /**
   * Terminates all subscriptions and cancels timers.
   *
   * Teardown order (AC-6, SR-SPLIT-06, T4):
   *   1. state.clearSettlingTimers() — cancel timer closures before any STOMP teardown.
   *   2. Unsubscribe hashtags (tells backend).
   *   3. Unsubscribe main STOMP watches.
   *   4. Unsubscribe all per-hashtag topic watches.
   */
  terminateAllSubscriptions(): void {
    // Step 1: cancel timers before STOMP teardown (FIND-P3-SEC-5/6, AC-6, T4)
    this.state.clearSettlingTimers();

    this.hashtags.forEach(tag => this.unsubscribeHashtag(tag));
    this.subscriptionsSubscription.unsubscribe();
    this.terminationsSubscription.unsubscribe();
    Object.entries(this.subscriptions).forEach(([key, value]) => {
      value.unsubscribe();
      delete this.subscriptions[key];
    });
  }

  // ── STOMP ack handlers (TODO: Lane B → SubscriptionStompClient) ──────────

  terminateSubscriptionByDestination(dest: string): void {
    if (this.subscriptions[dest]) {
      this.subscriptions[dest].unsubscribe();
      delete this.subscriptions[dest];
    } else {
      console.error('No subscription found with destination', dest);
    }
  }

  subscribeToStatusCreatedMessages(dest: string, hashtag: string = ''): Subscription {
    return this.rxStompService.watch(dest).subscribe((message: Message) => {
      if (hashtag && this.state.isRecentlyTerminated(hashtag)) {
        console.log('Dropping late delivery for recently-terminated hashtag:', hashtag);
        return;
      }
      const data: StatusCreatedMessage = JSON.parse(message.body);
      const normalised = normalizeHashtag(hashtag);
      if (!normalised) {
        console.log('Dropping STOMP delivery with empty hashtag');
        return;
      }
      this.state.enqueueWallMessage({id: data.id, url: data.url, hashtags: [normalised]});
    });
  }

  subscribeToStatusUpdatedMessages(dest: string): Subscription {
    return this.rxStompService.watch(dest).subscribe((message: Message) => {
      const data: StatusUpdatedMessage = JSON.parse(message.body);
      this.state.updateMessage(data);
    });
  }

  subscribeToStatusDeletedMessages(dest: string): Subscription {
    return this.rxStompService.watch(dest).subscribe((message: Message) => {
      const data: StatusDeletedMessage = JSON.parse(message.body);
      this.state.dequeueById(data.id);
    });
  }

  private handleSubscriptionAckMessage(data: SubscriptionAckMessage): void {
    if (data.subscribed) {
      this.hashtags.push(data.hashtag);
      this.persistence.saveHashtags(this.hashtags);

      const creation = this.destination(data.principal, data.hashtag, 'creation');
      this.destinations.push(creation);
      this.subscriptions[creation] = this.subscribeToStatusCreatedMessages(creation, data.hashtag);

      const modification = this.destination(data.principal, data.hashtag, 'modification');
      this.destinations.push(modification);
      this.subscriptions[modification] = this.subscribeToStatusUpdatedMessages(modification);

      const deletion = this.destination(data.principal, data.hashtag, 'deletion');
      this.destinations.push(deletion);
      this.subscriptions[deletion] = this.subscribeToStatusDeletedMessages(deletion);
    } else {
      console.error('Could not subscribe to topic', data.hashtag);
    }
  }

  private handleTerminationAckMessage(data: TerminationAckMessage): void {
    if (data.terminated) {
      this.hashtags = this.hashtags.filter(tag => tag !== data.hashtag);
      this.persistence.saveHashtags(this.hashtags);

      // Step 1: unsubscribe RxStomp topic watches
      this.terminateSubscriptionByDestination(this.destination(data.principal, data.hashtag, 'creation'));
      this.terminateSubscriptionByDestination(this.destination(data.principal, data.hashtag, 'modification'));
      this.terminateSubscriptionByDestination(this.destination(data.principal, data.hashtag, 'deletion'));

      // Step 2: seed guard
      this.state.seedRecentlyTerminated(data.hashtag);

      // Step 3 + emit: prune queue and notify observers
      const pruneResult = this.state.pruneByHashtag(data.hashtag);

      // Step 4: announce to screen readers (ADR-5)
      this.wallAnnouncerService.announce({type: 'prune', result: pruneResult});
    } else {
      console.error('Could not terminate subscription for principal ' + data.principal + ' and hashtag ' + data.hashtag);
    }
  }

  private destination(principal: string, hashtag: string, type: string): string {
    return '/topic/hashtags/' + principal + '/' + hashtag + '/' + type;
  }
}

// MessageQueue class is defined in subscription-persistence.service.ts.
// Re-exported above (export {MessageQueue}) for backward compatibility.
