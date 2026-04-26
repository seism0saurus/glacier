import {Injectable, OnDestroy} from '@angular/core';
import {BehaviorSubject, Subject, Subscription} from 'rxjs';
import {debounceTime} from 'rxjs/operators';
import {PruneResult} from '../model/wall-message';

/**
 * Event payload fed into WallAnnouncerService.announce().
 *
 * The discriminated union lets the announcer pick the right ICU-plural
 * or fixed string for the live region depending on whether the call
 * originated from a single-hashtag termination or a cancel-all.
 */
export type AnnounceEvent =
  | { readonly type: 'prune'; readonly result: PruneResult }
  | { readonly type: 'cancelAll' };

/**
 * Coalescing live-region announcer for prune events (ADR-5).
 *
 * Problem context
 * ---------------
 * When the user removes a hashtag — or cancels all subscriptions —
 * SubscriptionService calls announce() once per acknowledgement.
 * A cancel-all with N hashtags would otherwise flood the ARIA live region
 * with N rapid-fire announcements, confusing screen reader users.
 *
 * Solution
 * --------
 * All announce() calls within a 250 ms window are accumulated.  At the
 * end of the window, a single coalesced text is emitted on announcements$.
 * WallComponent subscribes to announcements$ and assigns the value to its
 * announceText property, which is bound via {{ announceText }} in the
 * template.  Angular's change detection is the sole writer to the DOM.
 *
 * FIX-2 rationale
 * ---------------
 * The previous implementation called this.liveRegion.textContent directly,
 * creating a dual-write race: Angular's next change-detection cycle would
 * reset textContent back to announceText ('' in WallComponent), silencing
 * screen-reader announcements.  The new approach removes direct DOM mutation
 * and routes all updates through the component's data binding, so Angular
 * change detection and the live region are always in sync.
 *
 * Coalescing rules
 * ----------------
 *   - If the batch contains at least one cancelAll event, the fixed
 *     'wall.prune.announce.cancelAll' string is used (the wall is now empty).
 *   - Otherwise, the total count of removed toots across all prune events
 *     is summed and formatted via the ICU plural
 *     'wall.prune.announce.single'.
 *
 * Usage
 * -----
 * 1. WallComponent subscribes to announcements$ in ngOnInit and assigns the
 *    emitted value to this.announceText.
 * 2. SubscriptionService injects WallAnnouncerService and calls
 *    announce({ type: 'prune', result }) or announce({ type: 'cancelAll' })
 *    at step 4 of the 4-step ack handler.
 *
 * i18n
 * ----
 * This service does NOT depend on Angular's localize runtime.  The
 * strings are resolved by WallComponent from the runtime catalog and
 * passed to this service via setMessages().  This avoids circular
 * injection while keeping the announcer testable in isolation.
 */
@Injectable({
  providedIn: 'root',
})
export class WallAnnouncerService implements OnDestroy {

  /** Debounce window in ms (ADR-5). */
  private static readonly DEBOUNCE_MS = 250;

  /**
   * Observable stream of coalesced announcement strings.
   *
   * WallComponent subscribes here and assigns emitted values to
   * this.announceText, which is bound to the live region via {{ announceText }}.
   * An empty string is emitted first (clearing the region) before the
   * resolved text is emitted after the debounce window closes, forcing
   * screen readers to re-announce even for identical texts.
   *
   * Starts with '' so new subscribers see the current (empty) state.
   */
  readonly announcements$: BehaviorSubject<string> = new BehaviorSubject<string>('');

  /**
   * Resolved i18n strings provided by WallComponent after the catalog loads.
   *
   * The two keys correspond to the @@ids in the message catalogs:
   *   - pruneAnnounce: 'wall.prune.announce.single' ICU plural template.
   *     The caller substitutes {count} before passing to this service.
   *     Expected form (English): '{count, plural, =1 {One post removed.}
   *     other {# posts removed.}}'
   *     Since this service does not run ICU, WallComponent resolves the
   *     plural before calling announce().  The resolved string is what
   *     we receive here — we just write it verbatim.
   *   - cancelAllAnnounce: 'wall.prune.announce.cancelAll' fixed string.
   */
  private messages: { pruneAnnounce: string; cancelAllAnnounce: string } = {
    pruneAnnounce: '',
    cancelAllAnnounce: '',
  };

  /** Subject for raw announce events — debounced into _pending$. */
  private readonly _raw$ = new Subject<AnnounceEvent>();

  /** Accumulated events for the current debounce window. */
  private _pendingEvents: AnnounceEvent[] = [];

  /** Subscription to the debounced stream. */
  private _debounceSubscription: Subscription;

  constructor() {
    this._debounceSubscription = this._raw$
      .pipe(debounceTime(WallAnnouncerService.DEBOUNCE_MS))
      .subscribe(() => this._flush());
  }

  ngOnDestroy(): void {
    this._debounceSubscription.unsubscribe();
    this._raw$.complete();
    this.announcements$.complete();
  }

  /**
   * Provides the resolved i18n strings this service emits to subscribers.
   *
   * Must be called by WallComponent after the runtime catalog is loaded.
   * The strings should already have any ICU plurals resolved for the current
   * locale; this service emits them verbatim without further processing.
   *
   * @param pruneAnnounce     - Resolved ICU plural string for single-hashtag
   *                            prune events (count already substituted).
   *                            This is updated by WallComponent each time
   *                            announce() is called, so the count is always
   *                            current.  Pass '' to suppress the announcement.
   * @param cancelAllAnnounce - Fixed string for cancel-all events.
   */
  setMessages(pruneAnnounce: string, cancelAllAnnounce: string): void {
    this.messages = {pruneAnnounce, cancelAllAnnounce};
  }

  /**
   * Accepts a prune or cancel-all event and schedules a coalesced
   * announcement after 250 ms.
   *
   * Multiple calls within the debounce window are merged: if any event
   * in the window is a cancelAll, the cancelAll announcement takes
   * precedence.  Otherwise, removed counts are summed.
   *
   * Safe to call synchronously from the ack handler (step 4 of the
   * 4-step sequence — no await/setTimeout between steps 1–3 and this call).
   *
   * @param event - The prune or cancelAll event to announce.
   */
  announce(event: AnnounceEvent): void {
    this._pendingEvents.push(event);
    this._raw$.next(event);
  }

  /**
   * Flushes the accumulated pending events and emits the coalesced
   * announcement text on announcements$.
   *
   * Called by the debounced subscription after the 250 ms window closes.
   *
   * The clear-then-set pattern (emit '' first, then emit text) forces screen
   * readers to re-announce even when the resolved text is identical to the
   * previous announcement (e.g. two consecutive single-toot prunes).
   * The empty emission is synchronous; the text emission is deferred by one
   * macrotask (setTimeout 0) so the accessibility tree sees the clear before
   * the new content.
   */
  private _flush(): void {
    if (this._pendingEvents.length === 0) {
      return;
    }

    const hasCancelAll = this._pendingEvents.some(e => e.type === 'cancelAll');
    let text: string;

    if (hasCancelAll) {
      text = this.messages.cancelAllAnnounce;
    } else {
      const totalRemoved = this._pendingEvents
        .filter((e): e is { type: 'prune'; result: PruneResult } => e.type === 'prune')
        .reduce((sum, e) => sum + e.result.removed.length, 0);
      // pruneAnnounce is expected to be a fully-resolved plural string
      // with {count} already substituted by the caller (WallComponent).
      // However, for safety we substitute {count} here if it appears.
      text = this.messages.pruneAnnounce.replace('{count}', String(totalRemoved));
    }

    this._pendingEvents = [];

    if (!text) {
      return;
    }

    // Emit '' first so the accessibility tree sees the live region cleared.
    // This ensures the subsequent text emission triggers a fresh announcement
    // even when the text content has not changed.
    this.announcements$.next('');

    // Defer the actual text emission to the next macrotask so the clear has
    // been processed by the accessibility tree before the announcement arrives.
    setTimeout(() => {
      this.announcements$.next(text);
    }, 0);
  }
}
