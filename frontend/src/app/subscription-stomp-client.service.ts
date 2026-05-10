import {Injectable} from '@angular/core';
import {Subscription} from 'rxjs';
import {Message} from '@stomp/stompjs';
import {RxStompService} from './rx-stomp.service';
import {SubscriptionAckMessage} from './message-types/subscription-ack-message';
import {TerminationAckMessage} from './message-types/termination-ack-message';
import {StatusCreatedMessage} from './message-types/status-created-message';
import {StatusUpdatedMessage} from './message-types/status-updated-message';
import {StatusDeletedMessage} from './message-types/status-deleted-message';
import {normalizeHashtag} from './util/hashtag';
import {WallAnnouncerService} from './services/wall-announcer.service';
import {SubscriptionPersistence} from './subscription-persistence.service';
import {SubscriptionStateService} from './subscription-state.service';

// Dependency-direction lock (SR-SPLIT-02, ESLint T2, ADR-1):
//   SubscriptionPersistence ← SubscriptionStateService ← SubscriptionStompClient ← SubscriptionService
// SubscriptionStompClient must NOT import from SubscriptionService.

/**
 * Service responsible for the STOMP lifecycle: ack listeners, topic watches,
 * and publish calls.
 *
 * Extracted from SubscriptionService as part of the Strangler-Fig refactor
 * (Lane B, commit 4).  The facade (SubscriptionService) delegates all STOMP
 * operations to this class; the facade itself owns only:
 *   - Exposing observables from SubscriptionStateService.
 *   - Calling stomp.attach() then restoring persisted hashtags (ADR-5).
 *   - The teardown ordering rule (AC-6, SR-SPLIT-06, T4).
 *
 * Security model:
 * - Topic paths are built exclusively from data.principal carried in the
 *   server ack — never from a locally-generated, cached, or caller-supplied
 *   identifier (SR-TEST-23, WallTopicAuthInterceptor trust boundary).
 * - The handleTerminationAck sequence is fully synchronous — no async
 *   primitives between any of the 4 steps (SR-SPLIT-05, U-SEC-13).
 * - The recentlyTerminated guard gate (SR-PRUNE-04/06) uses the real
 *   SubscriptionStateService.isRecentlyTerminated(); it must never be mocked
 *   in production paths (AC-18, SR-SPLIT-04).
 * - localStorage access is routed through SubscriptionPersistence only
 *   (SR-SPLIT-01, ESLint T1).
 *
 * Dependency direction (SR-SPLIT-02, ADR-1):
 *   - Imports from: RxStompService, SubscriptionStateService,
 *     SubscriptionPersistence, WallAnnouncerService.
 *   - Must NOT import from SubscriptionService (no back-edge).
 */
@Injectable({
  providedIn: 'root'
})
export class SubscriptionStompClient {

  // ── Ack-listener handles (registered by attach()) ────────────────────────

  private subscriptionsSubscription: Subscription | null = null;
  private terminationsSubscription: Subscription | null = null;

  // ── Per-topic subscription handles ────────────────────────────────────────

  private subscriptions: { [key: string]: Subscription } = {};

  // ── Internal hashtag list (mirrors persistence, updated on ack) ───────────

  private hashtags: string[] = [];

  constructor(
    private rxStomp: RxStompService,
    private state: SubscriptionStateService,
    private persistence: SubscriptionPersistence,
    private wallAnnouncer: WallAnnouncerService,
  ) {}

  /**
   * Registers the two user-topic ack listeners.
   *
   * Idempotent: a second call is a no-op if already attached (i.e., both
   * subscriptionsSubscription and terminationsSubscription are non-null).
   * This prevents double-registration when the facade is constructed more
   * than once in the same DI context (e.g. tests that call `new SubscriptionService()`
   * directly) — ADR-5.
   */
  attach(): void {
    if (this.subscriptionsSubscription !== null && this.terminationsSubscription !== null) {
      // Already attached — idempotency guard (ADR-5).
      return;
    }

    this.subscriptionsSubscription = this.rxStomp
      .watch('/user/topic/subscriptions')
      .subscribe((message: Message) => {
        const data: SubscriptionAckMessage = JSON.parse(message.body);
        this.handleSubscriptionAck(data);
      });

    this.terminationsSubscription = this.rxStomp
      .watch('/user/topic/terminations')
      .subscribe((message: Message) => {
        const data: TerminationAckMessage = JSON.parse(message.body);
        this.handleTerminationAck(data);
      });
  }

  /**
   * Publishes a subscription request for the given hashtag to the server.
   *
   * The server validates the hashtag and responds with a SubscriptionAckMessage
   * on /user/topic/subscriptions.  Topic watches are opened only after a
   * positive ack — never optimistically (SR-SPLIT-01a).
   *
   * @param hashtag - The raw hashtag string to subscribe to.
   */
  subscribeHashtag(hashtag: string): void {
    this.rxStomp.publish({destination: '/glacier/subscription', body: JSON.stringify({hashtag})});
  }

  /**
   * Publishes a termination request for the given hashtag to the server.
   *
   * Topic watches are torn down only after a positive TerminationAckMessage
   * is received — never optimistically.
   *
   * @param hashtag - The raw hashtag string to unsubscribe from.
   */
  unsubscribeHashtag(hashtag: string): void {
    this.rxStomp.publish({destination: '/glacier/termination', body: JSON.stringify({hashtag})});
  }

  /**
   * Terminates all active STOMP subscriptions.
   *
   * Steps (all in a single synchronous call):
   *   1. For each tracked hashtag: publish a termination message.
   *   2. Unsubscribe both user-topic ack watches.
   *   3. Unsubscribe all per-hashtag topic watches and clear the map.
   *
   * Must be called AFTER state.clearSettlingTimers() from the facade
   * (AC-6, SR-SPLIT-06, T4 ordering contract — the facade owns that ordering).
   */
  terminateAll(): void {
    this.hashtags.forEach(tag => this.unsubscribeHashtag(tag));

    if (this.subscriptionsSubscription !== null) {
      this.subscriptionsSubscription.unsubscribe();
    }
    if (this.terminationsSubscription !== null) {
      this.terminationsSubscription.unsubscribe();
    }

    Object.entries(this.subscriptions).forEach(([key, value]) => {
      value.unsubscribe();
      delete this.subscriptions[key];
    });
  }

  // ── Private: ack handlers ─────────────────────────────────────────────────

  /**
   * Handles a SubscriptionAckMessage from the server.
   *
   * On positive ack:
   *   1. Appends the hashtag to the internal list.
   *   2. Persists the updated list via SubscriptionPersistence (after ack,
   *      never optimistically — SR-SPLIT-01a, CWE-20).
   *   3. Opens 3 per-hashtag topic watches (creation, modification, deletion).
   *
   * Topic paths use data.principal exclusively (SR-TEST-23).
   *
   * @param data - The parsed SubscriptionAckMessage from the server.
   */
  private handleSubscriptionAck(data: SubscriptionAckMessage): void {
    if (data.subscribed) {
      this.hashtags.push(data.hashtag);
      this.persistence.saveHashtags(this.hashtags);

      const creation = this.destination(data.principal, data.hashtag, 'creation');
      this.subscriptions[creation] = this.subscribeToCreated(creation, data.hashtag);

      const modification = this.destination(data.principal, data.hashtag, 'modification');
      this.subscriptions[modification] = this.subscribeToUpdated(modification);

      const deletion = this.destination(data.principal, data.hashtag, 'deletion');
      this.subscriptions[deletion] = this.subscribeToDeleted(deletion);
    } else {
      console.error('Could not subscribe to topic', data.hashtag);
    }
  }

  /**
   * Handles a TerminationAckMessage from the server.
   *
   * 4-step synchronous sequence (SR-SPLIT-05, SR-PRUNE-13, U-SEC-13):
   *   Step 1: Unsubscribe 3 topic watches (creation, modification, deletion).
   *   Step 2: state.seedRecentlyTerminated — opens guard window.
   *   Step 3: state.pruneByHashtag → PruneResult (removes toots from queue).
   *   Step 4: wallAnnouncer.announce({type:'prune', result}) — screen-reader notification.
   *
   * Also: removes hashtag from internal list and persists.
   *
   * ALL 4 steps execute synchronously in one call — no await, Promise.then(),
   * setTimeout(), or queueMicrotask() between steps (U-SEC-13, SR-SPLIT-05).
   *
   * @param data - The parsed TerminationAckMessage from the server.
   */
  private handleTerminationAck(data: TerminationAckMessage): void {
    if (data.terminated) {
      this.hashtags = this.hashtags.filter(tag => tag !== data.hashtag);
      this.persistence.saveHashtags(this.hashtags);

      // Step 1: Unsubscribe RxStomp topic watches (SR-PRUNE-13)
      this.terminateByDest(this.destination(data.principal, data.hashtag, 'creation'));
      this.terminateByDest(this.destination(data.principal, data.hashtag, 'modification'));
      this.terminateByDest(this.destination(data.principal, data.hashtag, 'deletion'));

      // Step 2: Seed guard window before prune (U-SEC-06, ADR-3)
      this.state.seedRecentlyTerminated(data.hashtag);

      // Step 3: Prune toots and capture result (SR-PRUNE-13)
      const pruneResult = this.state.pruneByHashtag(data.hashtag);

      // Step 4: Announce to screen readers (ADR-5)
      this.wallAnnouncer.announce({type: 'prune', result: pruneResult});
    } else {
      console.error(
        'Could not terminate subscription for principal ' + data.principal +
        ' and hashtag ' + data.hashtag,
      );
    }
  }

  // ── Private: topic watch helpers ──────────────────────────────────────────

  /**
   * Opens a STOMP watch for status-created events on the given destination.
   *
   * Guard gate (SR-PRUNE-04/06): if the hashtag is in the recentlyTerminated
   * window, the delivery is dropped.  Uses the REAL state.isRecentlyTerminated()
   * — this method must NEVER be mocked or spied on in production paths
   * (SR-SPLIT-04, AC-18).
   *
   * Empty-hashtag guard (SR-PRUNE-08): if normalizeHashtag(hashtag) is falsy,
   * the delivery is dropped to avoid producing a WallMessage with hashtags.length < 1
   * (WallMessage invariant).
   *
   * @param dest    - STOMP destination to watch.
   * @param hashtag - Raw hashtag string; used for guard-gate and normalisation.
   */
  private subscribeToCreated(dest: string, hashtag: string = ''): Subscription {
    return this.rxStomp.watch(dest).subscribe((message: Message) => {
      // Guard gate: drop deliveries for recently-terminated hashtags (SR-PRUNE-04/06)
      if (hashtag && this.state.isRecentlyTerminated(hashtag)) {
        console.log('Dropping late delivery for recently-terminated hashtag:', hashtag);
        return;
      }
      const data: StatusCreatedMessage = JSON.parse(message.body);
      const normalised = normalizeHashtag(hashtag);
      // Empty-hashtag guard: never enqueue with hashtags.length < 1 (SR-PRUNE-08)
      if (!normalised) {
        console.log('Dropping STOMP delivery with empty hashtag');
        return;
      }
      this.state.enqueueWallMessage({id: data.id, url: data.url, hashtags: [normalised]});
    });
  }

  /**
   * Opens a STOMP watch for status-updated events on the given destination.
   *
   * @param dest - STOMP destination to watch.
   */
  private subscribeToUpdated(dest: string): Subscription {
    return this.rxStomp.watch(dest).subscribe((message: Message) => {
      const data: StatusUpdatedMessage = JSON.parse(message.body);
      this.state.updateMessage(data);
    });
  }

  /**
   * Opens a STOMP watch for status-deleted events on the given destination.
   *
   * @param dest - STOMP destination to watch.
   */
  private subscribeToDeleted(dest: string): Subscription {
    return this.rxStomp.watch(dest).subscribe((message: Message) => {
      const data: StatusDeletedMessage = JSON.parse(message.body);
      this.state.dequeueById(data.id);
    });
  }

  /**
   * Unsubscribes and removes the subscription at the given destination.
   *
   * @param dest - The STOMP destination to terminate.
   */
  private terminateByDest(dest: string): void {
    if (this.subscriptions[dest]) {
      this.subscriptions[dest].unsubscribe();
      delete this.subscriptions[dest];
    } else {
      console.error('No subscription found with destination', dest);
    }
  }

  /**
   * Builds the STOMP topic path for a hashtag event type.
   *
   * SR-TEST-23: uses `principal` from the server ack exclusively.
   * Never substitutes a locally-generated, cached, or caller-supplied identifier.
   *
   * @param principal - The wallId principal from the server ack.
   * @param hashtag   - The hashtag string.
   * @param type      - 'creation' | 'modification' | 'deletion'.
   */
  private destination(principal: string, hashtag: string, type: string): string {
    return '/topic/hashtags/' + principal + '/' + hashtag + '/' + type;
  }
}
