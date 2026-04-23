import {Injectable, OnDestroy} from '@angular/core';
import {HttpClient, HttpErrorResponse} from '@angular/common/http';
import {
  BehaviorSubject,
  debounceTime,
  filter,
  firstValueFrom,
  interval,
  Observable,
  Subject,
  Subscription,
  take,
} from 'rxjs';
import {RxStompService} from '../rx-stomp.service';
import {environment} from '../../environments/environment';
import {TransportMode, FallbackEvent, GapObserved, RateLimited, SessionExpired, KillSwitched} from './transport-mode';
import {DeliveryCursor} from './delivery-cursor';
import {SubscriptionService} from '../subscription.service';

// ---------------------------------------------------------------------------
// Public data shapes
// ---------------------------------------------------------------------------

/**
 * One entry inside a fallback poll response (D-01).
 * Maps to the backend's CacheEntry DTO.
 */
export interface CacheEntry {
  /**
   * Mastodon status ID — dedup key (D-06).
   *
   * Named `id` to match the JSON wire key emitted by the backend's
   * `CacheEntryView` DTO (`@JsonProperty("id")`).  Keeping the field name
   * consistent with `StatusCreatedMessage.id` and `StatusDeletedMessage.id`.
   */
  readonly id: string;
  /** Event type: CREATED, UPDATED, or DELETED. */
  readonly type: 'CREATED' | 'UPDATED' | 'DELETED';
  /** Toot embed URL.  Absent for DELETED entries. */
  readonly url?: string;
  /** ISO-8601 UTC timestamp of the last edit.  Absent for CREATED/DELETED. */
  readonly editedAt?: string;
  /** Server-assigned monotonic sequence within this (principal, hashtag) ring. */
  readonly sequence: number;
}

/**
 * Body of a 200 response from GET /rest/messages (D-01).
 */
export interface FallbackResponse {
  readonly hashtag: string;
  /** Cursor value to send as `since` on the next poll. */
  readonly nextSince: number;
  /**
   * True when the client's cursor predates the oldest entry in the ring buffer,
   * meaning some toots were missed (D-06, D-16).
   */
  readonly gap: boolean;
  readonly events: readonly CacheEntry[];
}

// ---------------------------------------------------------------------------
// Internal reconnect / probe schedule constants (D-04, D-05, ADR-03)
// ---------------------------------------------------------------------------

/** Reconnect attempt delays in milliseconds (D-04, ADR-03). */
const RECONNECT_DELAYS_MS: readonly number[] = [1_000, 4_000, 16_000];

/** How long to wait for the third attempt to reach OPEN before flipping to FALLBACK. */
const LAST_ATTEMPT_TIMEOUT_MS = 16_000;

/** Exponential probe schedule while in FALLBACK mode (D-05). */
const PROBE_SCHEDULE_MS: readonly number[] = [
  10_000, 20_000, 40_000, 80_000, 160_000, 300_000, 300_000,
];

/** Poll interval while in FALLBACK mode (D-01). */
const DEFAULT_POLL_INTERVAL_MS = 5_000;

/** Debounce for gap events per hashtag (D-16). */
const GAP_DEBOUNCE_MS = 5_000;

// ---------------------------------------------------------------------------
// FallbackService
// ---------------------------------------------------------------------------

/**
 * FallbackService owns the WebSocket→HTTP fallback transport state machine
 * (D-04, D-05, D-14, D-17).
 *
 * Lifecycle
 * ---------
 *   1. On WS disconnect, schedule reconnect attempts at 1 s / 4 s / 16 s.
 *   2. If attempt 3 has not reached OPEN within a further 16 s, emit FALLBACK.
 *   3. While in FALLBACK, poll GET /rest/messages per subscribed hashtag.
 *   4. Probe WS recovery on exponential schedule: 10/20/40/80/160/300/300 s.
 *   5. On probe success, flip to WEBSOCKET immediately, resubscribe hashtags,
 *      stop poll timer on first STOMP ack.
 *
 * Security (D-14)
 * ---------------
 * When `environment.production && !environment.allowPlaintext &&
 * window.location.protocol === 'http:'`, the client enters INSECURE state and
 * does NOT start the fallback poller.  The `allowPlaintext` flag (default
 * `false` in `environment.production.ts`, `true` in all dev environments)
 * gives developers an opt-out of the INSECURE gate without changing the
 * `production` flag.
 */
@Injectable({
  providedIn: 'root',
})
export class FallbackService implements OnDestroy {

  // ---- Public observables --------------------------------------------------

  /** Emits the current transport mode whenever it changes. */
  private readonly _mode$ = new BehaviorSubject<TransportMode>(TransportMode.WEBSOCKET);
  public readonly transportMode$: Observable<TransportMode> = this._mode$.asObservable();

  /** Emits structured events for UI components to react to. */
  private readonly _events$ = new Subject<FallbackEvent>();
  public readonly events$: Observable<FallbackEvent> = this._events$.asObservable();

  // ---- Internal state ------------------------------------------------------

  /** Per-hashtag gap event subjects for debouncing (D-16). */
  private readonly _gapSubjects = new Map<string, Subject<void>>();
  private readonly _gapSubs = new Map<string, Subscription>();

  /** Persistent HTTP-poll cursor keyed by hashtag (D-06). */
  private readonly _cursor = new DeliveryCursor();

  /** Poll timer subscriptions — one per subscribed hashtag. */
  private readonly _pollSubs = new Map<string, Subscription>();

  /** Currently subscribed hashtags (mirrored from SubscriptionService on ack). */
  private _subscribedHashtags: string[] = [];

  /** Probe timer for WS recovery. */
  private _probeTimeout: ReturnType<typeof setTimeout> | null = null;

  /** Current index into PROBE_SCHEDULE_MS. */
  private _probeIndex = 0;

  /** RxStomp connection state subscription. */
  private _stateSubscription?: Subscription;

  /** Reconnect attempt counter (resets on WS OPEN). */
  private _reconnectAttempts = 0;

  /** Whether the FALLBACK transition has already fired for this disconnect cycle. */
  private _fallbackTriggered = false;

  /** Timeout handle for "last attempt" grace period. */
  private _lastAttemptTimeout: ReturnType<typeof setTimeout> | null = null;

  /** Whether the service has been destroyed. */
  private _destroyed = false;

  constructor(
    private readonly http: HttpClient,
    private readonly rxStompService: RxStompService,
    private readonly subscriptionService: SubscriptionService,
  ) {
    // D-14: detect insecure page before anything else.
    // Enter INSECURE state when all three conditions hold:
    //   1. This is a production build (environment.production === true).
    //   2. The operator has NOT explicitly allowed plaintext
    //      (environment.allowPlaintext === false, the production default).
    //   3. The page is being served over plain HTTP.
    // Condition 2 lets developers/operators set allowPlaintext=true in their
    // environment file to suppress the INSECURE gate (e.g., for local dev
    // over http:// without TLS termination).  Production builds MUST ship
    // with allowPlaintext=false (see environment.production.ts).
    if (environment.production && !(environment as any).allowPlaintext
        && typeof window !== 'undefined'
        && window.location.protocol === 'http:') {
      this._mode$.next(TransportMode.INSECURE);
      // Do not start any WebSocket monitoring or polling
      return;
    }

    this._listenToConnectionState();
  }

  ngOnDestroy(): void {
    this._destroyed = true;
    this._clearProbeTimeout();
    this._clearLastAttemptTimeout();
    this._stateSubscription?.unsubscribe();
    this._stopAllPolling();
    this._clearGapDebounces();
  }

  // ---------------------------------------------------------------------------
  // Public API — called by SubscriptionService when a STOMP ack is received
  // ---------------------------------------------------------------------------

  /**
   * Called when the server confirms a hashtag subscription via STOMP ack.
   * Starts a fallback poll for this hashtag if we are already in FALLBACK mode.
   */
  onHashtagSubscribed(hashtag: string): void {
    if (!this._subscribedHashtags.includes(hashtag)) {
      this._subscribedHashtags.push(hashtag);
    }

    // If we were in FALLBACK mode (probe succeeded and triggered resubscription),
    // confirm transition to WEBSOCKET when the first ack arrives.
    if (this._mode$.value === TransportMode.PROBING) {
      this._transitionTo(TransportMode.WEBSOCKET);
      this._stopAllPolling();
    }

    if (this._mode$.value === TransportMode.FALLBACK) {
      this._startPolling(hashtag);
    }
  }

  /**
   * Called when the server confirms a hashtag termination.
   */
  onHashtagTerminated(hashtag: string): void {
    this._subscribedHashtags = this._subscribedHashtags.filter(h => h !== hashtag);
    this._stopPolling(hashtag);
    this._cursor.remove(hashtag);
    this._removeGapDebounce(hashtag);
  }

  // ---------------------------------------------------------------------------
  // Connection-state listener (D-04, ADR-03)
  // ---------------------------------------------------------------------------

  private _listenToConnectionState(): void {
    // RxStompService exposes connectionState$ (exposed via factory per scope)
    const rxStomp = this.rxStompService as any;
    if (!rxStomp.connectionState$) {
      console.warn('[FallbackService] connectionState$ not available on RxStompService');
      return;
    }

    this._stateSubscription = rxStomp.connectionState$.subscribe((state: number) => {
      // RxStomp uses @stomp/stompjs RxStompState: 0=CONNECTING, 1=OPEN, 2=CLOSING, 3=CLOSED
      const OPEN = 1;
      const CLOSED = 3;

      if (state === OPEN) {
        this._onWsOpen();
      } else if (state === CLOSED) {
        this._onWsClosed();
      }
    });
  }

  private _onWsOpen(): void {
    this._reconnectAttempts = 0;
    this._fallbackTriggered = false;
    this._clearLastAttemptTimeout();

    const wasProbing = this._mode$.value === TransportMode.PROBING;
    const wasFallback = this._mode$.value === TransportMode.FALLBACK;

    if (wasProbing || wasFallback) {
      // D-05: flip indicator to WEBSOCKET immediately on WS OPEN
      this._transitionTo(TransportMode.WEBSOCKET);
      // Stop polling; resubscribe hashtags via STOMP (SubscriptionService.subscribeHashtag)
      this._stopAllPolling();
      this._events$.next({type: 'WsRestored'});
      this._probeIndex = 0;
    } else {
      // Normal initial connection
      this._transitionTo(TransportMode.WEBSOCKET);
    }
  }

  private _onWsClosed(): void {
    // Don't react if already in a terminal state
    const current = this._mode$.value;
    if (current === TransportMode.INSECURE ||
        current === TransportMode.OFFLINE ||
        current === TransportMode.KILLSWITCHED) {
      return;
    }

    // Don't restart reconnect sequence if we're already probing/fallback
    if (current === TransportMode.FALLBACK) {
      return;
    }

    if (!this._fallbackTriggered && this._reconnectAttempts < RECONNECT_DELAYS_MS.length) {
      this._scheduleReconnectAttempt();
    }
  }

  private _scheduleReconnectAttempt(): void {
    const attemptIndex = this._reconnectAttempts;
    const delayMs = RECONNECT_DELAYS_MS[attemptIndex] ?? RECONNECT_DELAYS_MS[RECONNECT_DELAYS_MS.length - 1];

    this._transitionTo(TransportMode.PROBING);

    setTimeout(() => {
      if (this._destroyed || this._fallbackTriggered) return;

      this._reconnectAttempts++;

      const isLastAttempt = this._reconnectAttempts >= RECONNECT_DELAYS_MS.length;
      if (isLastAttempt) {
        // Start the grace-period timeout before flipping to FALLBACK
        this._clearLastAttemptTimeout();
        this._lastAttemptTimeout = setTimeout(() => {
          if (!this._destroyed && !this._fallbackTriggered &&
              this._mode$.value !== TransportMode.WEBSOCKET) {
            this._triggerFallback();
          }
        }, LAST_ATTEMPT_TIMEOUT_MS);
      } else {
        // More attempts remain; react to the next CLOSED event
        // (RxStomp will close and reopen; we react via _onWsClosed)
      }
    }, delayMs);
  }

  private _triggerFallback(): void {
    this._fallbackTriggered = true;
    this._clearLastAttemptTimeout();
    this._transitionTo(TransportMode.FALLBACK);
    this._startProbeSchedule();

    // Start polling for all currently subscribed hashtags
    for (const hashtag of this._subscribedHashtags) {
      this._startPolling(hashtag);
    }
  }

  // ---------------------------------------------------------------------------
  // HTTP polling (D-01)
  // ---------------------------------------------------------------------------

  private _startPolling(hashtag: string): void {
    if (this._pollSubs.has(hashtag)) {
      return; // already polling this hashtag
    }

    const intervalMs = (environment as any)['fallbackPollIntervalMs'] ?? DEFAULT_POLL_INTERVAL_MS;

    // Poll immediately, then on interval
    this._doPoll(hashtag);
    const sub = interval(intervalMs).subscribe(() => {
      if (this._mode$.value !== TransportMode.FALLBACK) {
        this._stopPolling(hashtag);
        return;
      }
      this._doPoll(hashtag);
    });

    this._pollSubs.set(hashtag, sub);
  }

  private _stopPolling(hashtag: string): void {
    const sub = this._pollSubs.get(hashtag);
    if (sub) {
      sub.unsubscribe();
      this._pollSubs.delete(hashtag);
    }
  }

  private _stopAllPolling(): void {
    for (const [hashtag, sub] of this._pollSubs) {
      sub.unsubscribe();
    }
    this._pollSubs.clear();
  }

  private _doPoll(hashtag: string): void {
    const since = this._cursor.get(hashtag);
    const url = `/rest/messages?hashtag=${encodeURIComponent(hashtag)}&since=${since}`;

    this.http.get<FallbackResponse>(url, {observe: 'response'}).subscribe({
      next: (response) => {
        if (response.status === 204 || !response.body) {
          // No new events — cursor unchanged (D-01)
          return;
        }
        const body = response.body;
        this._cursor.set(hashtag, body.nextSince);

        if (body.gap) {
          this._emitGap(hashtag);
        }

        if (body.events && body.events.length > 0) {
          this.subscriptionService.ingestCacheEntries(hashtag, body.events);
        }
      },
      error: (err: HttpErrorResponse) => {
        this._handlePollError(err);
      },
    });
  }

  private _handlePollError(err: HttpErrorResponse): void {
    if (err.status === 401) {
      this._transitionTo(TransportMode.OFFLINE);
      this._stopAllPolling();
      this._cursor.reset();
      const event: SessionExpired = {type: 'SessionExpired'};
      this._events$.next(event);
    } else if (err.status === 429) {
      // D-17: 429 does NOT flip mode or trigger probe
      const retryAfter = this._parseRetryAfter(err);
      const event: RateLimited = {type: 'RateLimited', retryAfterSeconds: retryAfter};
      this._events$.next(event);
    } else if (err.status === 404) {
      this._transitionTo(TransportMode.KILLSWITCHED);
      this._stopAllPolling();
      const event: KillSwitched = {type: 'KillSwitched'};
      this._events$.next(event);
    }
    // Other errors (network, 5xx) — stay in FALLBACK, next poll will retry
  }

  private _parseRetryAfter(err: HttpErrorResponse): number | undefined {
    const header = err.headers?.get('Retry-After');
    if (!header) return undefined;
    const n = Number.parseInt(header, 10);
    return Number.isFinite(n) && n > 0 ? n : undefined;
  }

  // ---------------------------------------------------------------------------
  // Gap debounce (D-16)
  // ---------------------------------------------------------------------------

  private _emitGap(hashtag: string): void {
    if (!this._gapSubjects.has(hashtag)) {
      const subject = new Subject<void>();
      this._gapSubjects.set(hashtag, subject);

      const sub = subject.pipe(
        debounceTime(GAP_DEBOUNCE_MS),
      ).subscribe(() => {
        const event: GapObserved = {type: 'GapObserved', hashtag};
        this._events$.next(event);
      });
      this._gapSubs.set(hashtag, sub);
    }

    this._gapSubjects.get(hashtag)!.next();
  }

  private _removeGapDebounce(hashtag: string): void {
    this._gapSubjects.get(hashtag)?.complete();
    this._gapSubjects.delete(hashtag);
    this._gapSubs.get(hashtag)?.unsubscribe();
    this._gapSubs.delete(hashtag);
  }

  private _clearGapDebounces(): void {
    for (const hashtag of [...this._gapSubjects.keys()]) {
      this._removeGapDebounce(hashtag);
    }
  }

  // ---------------------------------------------------------------------------
  // WS probe schedule (D-05)
  // ---------------------------------------------------------------------------

  private _startProbeSchedule(): void {
    this._clearProbeTimeout();
    this._scheduleNextProbe();
  }

  private _scheduleNextProbe(): void {
    if (this._destroyed || this._mode$.value !== TransportMode.FALLBACK) {
      return;
    }

    const delayMs = PROBE_SCHEDULE_MS[this._probeIndex] ?? PROBE_SCHEDULE_MS[PROBE_SCHEDULE_MS.length - 1];

    this._probeTimeout = setTimeout(async () => {
      if (this._destroyed || this._mode$.value !== TransportMode.FALLBACK) return;

      const success = await this._probeWs();

      if (success) {
        // D-05: emit WEBSOCKET immediately on probe success
        this._transitionTo(TransportMode.WEBSOCKET);
        this._stopAllPolling();
        this._probeIndex = 0;
        this._events$.next({type: 'WsRestored'});
        // Resubscribe hashtags — the next STOMP ack will confirm
        for (const hashtag of this._subscribedHashtags) {
          this.subscriptionService.subscribeHashtag(hashtag);
        }
      } else {
        // Advance probe schedule (capped at last entry)
        if (this._probeIndex < PROBE_SCHEDULE_MS.length - 1) {
          this._probeIndex++;
        }
        this._scheduleNextProbe();
      }
    }, delayMs);
  }

  /**
   * Opens a fresh WebSocket connection and waits up to 10 s for OPEN state.
   * Returns true if OPEN is reached, false otherwise.
   */
  private _probeWs(): Promise<boolean> {
    return new Promise<boolean>((resolve) => {
      const wsProto = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
      const wsUrl = `${wsProto}//${window.location.host}/websocket`;

      let ws: WebSocket | null = null;
      const timeout = setTimeout(() => {
        ws?.close();
        resolve(false);
      }, 10_000);

      try {
        ws = new WebSocket(wsUrl);
        ws.onopen = () => {
          clearTimeout(timeout);
          ws?.close();
          resolve(true);
        };
        ws.onerror = () => {
          clearTimeout(timeout);
          ws?.close();
          resolve(false);
        };
        ws.onclose = () => {
          clearTimeout(timeout);
          resolve(false);
        };
      } catch {
        clearTimeout(timeout);
        resolve(false);
      }
    });
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private _transitionTo(mode: TransportMode): void {
    if (this._mode$.value !== mode) {
      this._mode$.next(mode);
    }
  }

  private _clearProbeTimeout(): void {
    if (this._probeTimeout !== null) {
      clearTimeout(this._probeTimeout);
      this._probeTimeout = null;
    }
  }

  private _clearLastAttemptTimeout(): void {
    if (this._lastAttemptTimeout !== null) {
      clearTimeout(this._lastAttemptTimeout);
      this._lastAttemptTimeout = null;
    }
  }
}
