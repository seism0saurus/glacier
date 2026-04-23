/**
 * TransportMode enumerates the six possible connectivity states of the Glacier
 * WebSocket→HTTP fallback state machine (D-15).
 *
 * State transitions (managed exclusively by FallbackService):
 *
 *   WEBSOCKET   — STOMP/WebSocket connection is open and toots stream normally.
 *   PROBING     — A reconnect attempt is in progress (1 s / 4 s / 16 s schedule,
 *                 D-04 / ADR-03). Rotating icon in the indicator (static under
 *                 prefers-reduced-motion, D-15).
 *   FALLBACK    — All three WS reconnect attempts have failed; the client is
 *                 polling GET /rest/messages every 5 s per subscribed hashtag
 *                 (D-01, D-04).
 *   OFFLINE     — A 401 SESSION_EXPIRED response was received from the fallback
 *                 endpoint.  No silent retry; user must reload (D-17).
 *   KILLSWITCHED — The backend returned 404 on /rest/messages because the
 *                  operator set GLACIER_FALLBACK_ENABLED=false (D-11, D-17).
 *   INSECURE    — The page is served over plain HTTP in a production build.
 *                 The fallback poller is deliberately NOT started to avoid
 *                 exposing the wallId cookie over a cleartext channel (D-14).
 */
export enum TransportMode {
  WEBSOCKET    = 'WEBSOCKET',
  PROBING      = 'PROBING',
  FALLBACK     = 'FALLBACK',
  OFFLINE      = 'OFFLINE',
  KILLSWITCHED = 'KILLSWITCHED',
  INSECURE     = 'INSECURE',
}

// ---------------------------------------------------------------------------
// Discriminated-union event types emitted on FallbackService.events$
// ---------------------------------------------------------------------------

/** The WS probe succeeded; STOMP ack confirmed on the first subscribed topic. */
export interface WsRestored {
  readonly type: 'WsRestored';
}

/**
 * A 401 response was received on the fallback poll path.
 * The UI should surface a session-expired banner (D-17).
 */
export interface SessionExpired {
  readonly type: 'SessionExpired';
}

/**
 * A 429 response was received on the fallback poll path.
 * Optionally carries the server-supplied Retry-After value in seconds (D-17).
 */
export interface RateLimited {
  readonly type: 'RateLimited';
  /** Seconds until the next attempt is allowed, if the server supplied Retry-After. */
  readonly retryAfterSeconds?: number;
}

/**
 * A 404 response was received on the fallback poll path, indicating the kill
 * switch is active on the backend (D-11, D-17).
 */
export interface KillSwitched {
  readonly type: 'KillSwitched';
}

/**
 * The fallback response for a given hashtag contained gap:true, meaning the
 * server's ring buffer has overflowed since the client's last cursor and some
 * toots may be missing (D-16).
 */
export interface GapObserved {
  readonly type: 'GapObserved';
  readonly hashtag: string;
}

/** Union of all fallback event types. */
export type FallbackEvent =
  | WsRestored
  | SessionExpired
  | RateLimited
  | KillSwitched
  | GapObserved;
