import {Injectable} from '@angular/core';
import {Observable} from 'rxjs';
import {CacheEntry} from './fallback/fallback.service';
import {WallMessage} from './model/wall-message';
import {SubscriptionPersistence} from './subscription-persistence.service';
import {SubscriptionStateService} from './subscription-state.service';
import {SubscriptionStompClient} from './subscription-stomp-client.service';

// Re-export MessageQueue so that existing tests that import it from this module
// continue to compile unchanged during the strangler-fig refactor (ADR-2).
// Lane C (commit 6) will migrate spec imports to subscription-persistence.service.ts.
export {MessageQueue} from './subscription-persistence.service';

/**
 * Facade service that preserves the original SubscriptionService public API
 * while delegating implementation to specialist services.
 *
 * Responsibilities of the facade (Lane B, commit 4):
 * - Expose the three observables from SubscriptionStateService.
 * - Call stomp.attach() before restoring persisted hashtags (ADR-5).
 * - Restore persisted hashtags from SubscriptionPersistence on construction.
 * - Delegate all state mutations to SubscriptionStateService.
 * - Delegate all STOMP operations to SubscriptionStompClient.
 *
 * Teardown order (AC-6, SR-SPLIT-06, T4):
 *   1. state.clearSettlingTimers() — cancel timer closures first.
 *   2. stomp.terminateAll()        — STOMP teardown.
 *
 * Dependency direction (SR-SPLIT-02, ADR-1):
 *   Facade is the top node; it may import Persistence, State, and StompClient
 *   but must not be imported BY any of them (no back-edge).
 */
@Injectable({
  providedIn: 'root'
})
export class SubscriptionService {

  // ── Public API — observables delegated to state ─────────────────────────

  get messageObservable$(): Observable<WallMessage[]> { return this.state.messageObservable$; }
  get hasMigrated$(): Observable<boolean>             { return this.state.hasMigrated$; }
  get settlingHashtags$(): Observable<Set<string>>    { return this.state.settlingHashtags$; }

  constructor(
    private state: SubscriptionStateService,
    private stomp: SubscriptionStompClient,
    private persistence: SubscriptionPersistence,
  ) {
    // attach() MUST come before loadHashtags() so that ack listeners are
    // registered before the first subscribeHashtag() publish is fired (ADR-5).
    // SR-SPLIT-01a: loadHashtags() is the sole authorised reader of 'hashtags'
    // (OWASP A03:2021, CWE-20).
    this.stomp.attach();
    this.persistence.loadHashtags().forEach(tag => this.stomp.subscribeHashtag(tag));
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

  // ── Public API — delegated to stomp ────────────────────────────────────

  subscribeHashtag(hashtag: string): void {
    this.stomp.subscribeHashtag(hashtag);
  }

  unsubscribeHashtag(hashtag: string): void {
    this.stomp.unsubscribeHashtag(hashtag);
  }

  /**
   * Terminates all subscriptions and cancels timers.
   *
   * Teardown order (AC-6, SR-SPLIT-06, T4):
   *   1. state.clearSettlingTimers() — cancel timer closures before any STOMP teardown.
   *   2. stomp.terminateAll()        — STOMP teardown (publishes terminations, clears watches).
   */
  terminateAllSubscriptions(): void {
    // Step 1: cancel timers before STOMP teardown (FIND-P3-SEC-5/6, AC-6, T4)
    this.state.clearSettlingTimers();
    // Step 2: STOMP teardown
    this.stomp.terminateAll();
  }
}

// MessageQueue class is defined in subscription-persistence.service.ts.
// Re-exported above (export {MessageQueue}) for backward compatibility.
