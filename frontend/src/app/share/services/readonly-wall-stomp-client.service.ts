import { Injectable, OnDestroy } from '@angular/core';
import { BehaviorSubject, Observable, Subject } from 'rxjs';
import { Client, Message } from '@stomp/stompjs';
import { ViewerTransportMode } from './viewer-transport-mode';
import { ReadonlyTootView } from '../model/readonly-toot-view';

/**
 * Manage the STOMP connection for the readonly share-link viewer.
 *
 * Security requirements:
 *   SR-RELAY-16: uses wss:// when location.protocol === 'https:'
 *   SR-RELAY-17: NOT providedIn:'root' — must be declared in ReadonlyWallComponent.providers
 *                so every viewer component owns its own isolated connection.
 *   SR-RELAY-18: uses native new WebSocket(...) — no SockJS — to comply with
 *                CSP: frame-ancestors 'none' on the share-view page.
 *
 * Transport state machine:
 *   PROBING → (STOMP CONNECTED)  → LIVE
 *   LIVE    → (WS closed)        → PROBING → (5 s grace elapsed) → FALLBACK
 *   PROBING → (WS reconnected <5 s) → LIVE  (grace window cancelled)
 *   Any state → (control frame revoked/expired) → EXPIRED
 *
 * Reconnect backoff (SEC-ACC-02 / Phase 1 UX requirement):
 *   Exponential backoff: 1 s → 2 s → 4 s → 8 s (capped), reset to 1 s on
 *   successful STOMP CONNECTED frame.  This limits the thundering-herd load on
 *   /share-view-ws after mass revocation (each rejected handshake emits an
 *   AUDIT log line and performs a DB lookup under SQLite).
 *
 * Navigation and accessibility announcements on EXPIRED are the sole
 * responsibility of ReadonlyWallComponent (the state consumer).
 * This service is a pure state machine — it does NOT announce or navigate.
 *
 * The service does NOT use RxStompService (the sharer-wall client) — it
 * instantiates a @stomp/stompjs `Client` directly to keep the two STOMP
 * connections fully isolated.
 */
@Injectable()
export class ReadonlyWallStompClient implements OnDestroy {

  /**
   * Current viewer transport state.
   * Starts at PROBING (before any connection attempt).
   */
  readonly transportMode$ = new BehaviorSubject<ViewerTransportMode>(ViewerTransportMode.PROBING);

  private stompClient: Client | null = null;

  /** The share ID used for topic paths and navigation. */
  private shareId = '';

  /** Handle for the PROBING → FALLBACK grace-window timer. */
  private fallbackTimer: ReturnType<typeof setTimeout> | null = null;

  /**
   * Exponential reconnect backoff delay (ms).
   *
   * Starts at INITIAL_RECONNECT_DELAY_MS (1 s).
   * Doubled on each STOMP disconnect, capped at MAX_RECONNECT_DELAY_MS (8 s).
   * Reset to INITIAL_RECONNECT_DELAY_MS on a successful STOMP CONNECTED frame.
   *
   * The current value is written into stompClient.reconnectDelay so the
   * @stomp/stompjs Client picks it up on the next reconnect attempt.
   */
  private currentReconnectDelay = ReadonlyWallStompClient.INITIAL_RECONNECT_DELAY_MS;

  /**
   * Per-hashtag toot event subjects.
   * ReadonlyWallComponent subscribes via tootEvents$(hashtag).
   */
  private tootSubjects = new Map<string, Subject<ReadonlyTootView>>();

  /** Per-hashtag STOMP subscription handles (for cleanup on disconnect). */
  private stompSubscriptions: Array<{ unsubscribe: () => void }> = [];

  /** Initial reconnect delay in ms (1 s). Reset to this on successful connect. */
  private static readonly INITIAL_RECONNECT_DELAY_MS = 1000;

  /** Maximum reconnect delay in ms (8 s cap, per Phase 1 UX requirement). */
  private static readonly MAX_RECONNECT_DELAY_MS = 8000;

  constructor() {}

  // ── Public API ─────────────────────────────────────────────────────────────

  /**
   * Opens a STOMP connection to /share-view-ws for the given share link.
   *
   * SR-RELAY-18: uses native new WebSocket(...) — NEVER SockJS.
   * SR-RELAY-16: uses wss:// when running over HTTPS.
   *
   * @param shareId - The shareLinkId query param sent to the backend.
   */
  connect(shareId: string): void {
    this.shareId = shareId;
    const wsUrl = this.buildWsUrl(shareId);

    // Reset backoff to the initial value whenever a new connection is opened.
    this.currentReconnectDelay = ReadonlyWallStompClient.INITIAL_RECONNECT_DELAY_MS;

    this.stompClient = new Client({
      // SR-RELAY-18: webSocketFactory returns a native WebSocket
      webSocketFactory: () => new WebSocket(wsUrl),
      heartbeatIncoming: 10000,
      heartbeatOutgoing: 10000,
      // Initial reconnect delay; updated dynamically by onStompConnected /
      // onStompDisconnected to implement exponential backoff.
      reconnectDelay: this.currentReconnectDelay,
      onConnect: () => this.onStompConnected(),
      onDisconnect: () => this.onStompDisconnected(),
      onStompError: () => this.onStompDisconnected(),
      onWebSocketClose: () => this.onStompDisconnected(),
    });

    this.stompClient.activate();
  }

  /**
   * Deactivates the STOMP connection and completes the state subject.
   * Called from ReadonlyWallComponent.ngOnDestroy().
   */
  disconnect(): void {
    this.clearFallbackTimer();
    this.stompSubscriptions.forEach((sub) => sub.unsubscribe());
    this.stompSubscriptions = [];

    if (this.stompClient) {
      this.stompClient.deactivate().catch(() => {
        // Ignore deactivation errors on cleanup
      });
      this.stompClient = null;
    }

    // Complete all toot subjects
    this.tootSubjects.forEach((subject) => subject.complete());
    this.tootSubjects.clear();

    if (!this.transportMode$.closed) {
      this.transportMode$.complete();
    }
  }

  /**
   * Returns an Observable that emits ReadonlyTootView events for a hashtag.
   *
   * The observable subscribes to the STOMP topic
   * /topic/share/{shareId}/{hashtag}/creation and
   * /topic/share/{shareId}/{hashtag}/modification once the STOMP connection
   * is LIVE.  Topic subscriptions are (re)registered in onStompConnected().
   *
   * @param hashtag - Hashtag without leading #.
   */
  tootEvents$(hashtag: string): Observable<ReadonlyTootView> {
    if (!this.tootSubjects.has(hashtag)) {
      this.tootSubjects.set(hashtag, new Subject<ReadonlyTootView>());
    }
    return this.tootSubjects.get(hashtag)!.asObservable();
  }

  /**
   * Handles a STOMP control frame on /topic/share/{shareId}/control.
   *
   * Revoked / expired → emits EXPIRED on transportMode$ immediately.
   * The component (ReadonlyWallComponent) is the sole owner of navigation
   * and accessibility announcements for the EXPIRED transition.
   * Ping → ignored.
   *
   * @param frame - Parsed control frame payload.
   */
  handleControlFrame(frame: { type: string }): void {
    if (frame.type === 'revoked' || frame.type === 'expired') {
      this.transportMode$.next(ViewerTransportMode.EXPIRED);
    }
    // 'ping' and unknown types are silently ignored
  }

  /**
   * Exposes the computed WebSocket URL for testing purposes.
   *
   * SR-RELAY-16: wss:// when location.protocol === 'https:'.
   * SR-RELAY-18: native WebSocket path (/share-view-ws?shareLinkId=...).
   *
   * @param shareId - The share link identifier.
   * @returns The fully qualified WebSocket URL.
   */
  buildWsUrl(shareId: string): string {
    const scheme = location.protocol === 'https:' ? 'wss' : 'ws';
    return scheme + '://' + location.host + '/share-view-ws?shareLinkId=' + shareId;
  }

  // ── STOMP lifecycle callbacks (package-internal, called by Client config) ─

  /**
   * Called by the STOMP Client on the CONNECTED frame.
   * Transitions to LIVE, resets the exponential backoff counter to 1 s, and
   * registers per-hashtag topic subscriptions.
   */
  private onStompConnected(): void {
    // Cancel any pending PROBING → FALLBACK grace timer
    this.clearFallbackTimer();
    this.transportMode$.next(ViewerTransportMode.LIVE);

    // Reset reconnect delay to the initial value after a successful connect.
    // This ensures the backoff sequence restarts from 1 s after any recovery.
    this.currentReconnectDelay = ReadonlyWallStompClient.INITIAL_RECONNECT_DELAY_MS;
    if (this.stompClient) {
      this.stompClient.reconnectDelay = this.currentReconnectDelay;
    }

    // Register topic subscriptions for each tracked hashtag
    this.registerTopicSubscriptions();
  }

  /**
   * Called by the STOMP Client on disconnect / error / WebSocket close.
   * Transitions to PROBING, advances the exponential backoff counter, and
   * starts the 5 s grace window.
   *
   * Backoff sequence: 1000 → 2000 → 4000 → 8000 → 8000 (cap).
   * The updated delay is written into stompClient.reconnectDelay so the
   * @stomp/stompjs Client picks it up for the next reconnect attempt.
   */
  private onStompDisconnected(): void {
    // Don't transition if we've already expired (control-frame path)
    if (this.transportMode$.getValue() === ViewerTransportMode.EXPIRED) {
      return;
    }

    this.transportMode$.next(ViewerTransportMode.PROBING);

    // Advance the exponential backoff counter (double, capped at 8 s).
    this.currentReconnectDelay = Math.min(
      this.currentReconnectDelay * 2,
      ReadonlyWallStompClient.MAX_RECONNECT_DELAY_MS,
    );
    if (this.stompClient) {
      this.stompClient.reconnectDelay = this.currentReconnectDelay;
    }

    // Start grace window: if no reconnect within 5 s, flip to FALLBACK
    this.clearFallbackTimer();
    this.fallbackTimer = setTimeout(() => {
      // Only flip to FALLBACK if still in PROBING (reconnect cancels this timer)
      if (this.transportMode$.getValue() === ViewerTransportMode.PROBING) {
        this.transportMode$.next(ViewerTransportMode.FALLBACK);
      }
    }, 5000);
  }

  /**
   * Registers STOMP topic subscriptions for all known hashtags.
   *
   * Called after each reconnect so that previously tracked hashtags are
   * re-subscribed on a fresh STOMP session.
   */
  private registerTopicSubscriptions(): void {
    if (!this.stompClient) return;

    // Unsubscribe stale STOMP subscriptions from previous session
    this.stompSubscriptions.forEach((sub) => sub.unsubscribe());
    this.stompSubscriptions = [];

    // Subscribe to the control channel
    const controlDest = `/topic/share/${this.shareId}/control`;
    const controlSub = this.stompClient.subscribe(controlDest, (message: Message) => {
      try {
        const frame = JSON.parse(message.body) as { type: string };
        this.handleControlFrame(frame);
      } catch {
        // Ignore malformed control frames
      }
    });
    this.stompSubscriptions.push(controlSub);

    // Subscribe to each known hashtag
    this.tootSubjects.forEach((_subject, hashtag) => {
      this.subscribeToHashtag(hashtag);
    });
  }

  /**
   * Subscribes to the creation and modification STOMP topics for a hashtag.
   *
   * Topic paths: /topic/share/{shareId}/{hashtag}/creation
   *              /topic/share/{shareId}/{hashtag}/modification
   *
   * @param hashtag - Hashtag without leading #.
   */
  private subscribeToHashtag(hashtag: string): void {
    if (!this.stompClient) return;

    const subject = this.tootSubjects.get(hashtag);
    if (!subject) return;

    const creationDest = `/topic/share/${this.shareId}/${hashtag}/creation`;
    const modificationDest = `/topic/share/${this.shareId}/${hashtag}/modification`;

    const creationSub = this.stompClient.subscribe(creationDest, (message: Message) => {
      try {
        const toot = JSON.parse(message.body) as ReadonlyTootView;
        subject.next(toot);
      } catch {
        // Ignore malformed toot frames
      }
    });

    const modificationSub = this.stompClient.subscribe(modificationDest, (message: Message) => {
      try {
        const toot = JSON.parse(message.body) as ReadonlyTootView;
        subject.next(toot);
      } catch {
        // Ignore malformed toot frames
      }
    });

    this.stompSubscriptions.push(creationSub, modificationSub);
  }

  // ── Helpers ────────────────────────────────────────────────────────────────

  private clearFallbackTimer(): void {
    if (this.fallbackTimer !== null) {
      clearTimeout(this.fallbackTimer);
      this.fallbackTimer = null;
    }
  }

  ngOnDestroy(): void {
    this.disconnect();
  }
}
