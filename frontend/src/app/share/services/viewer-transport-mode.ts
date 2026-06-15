/**
 * ViewerTransportMode enumerates the connectivity states of the share-viewer
 * STOMP client (ReadonlyWallStompClient).
 *
 * This is intentionally separate from the sharer wall's TransportMode
 * (frontend/src/app/fallback/transport-mode.ts) — the share-viewer has a
 * simpler state machine (no killswitch, no insecure-transport variant) and
 * uses a different WebSocket endpoint (/share-view-ws).
 *
 * State transitions (managed by ReadonlyWallStompClient):
 *
 *   PROBING  — Initial state; also entered when STOMP disconnects.
 *              Grace window: 5 s.  If reconnect succeeds → LIVE.
 *              If grace window expires without reconnect → FALLBACK.
 *   LIVE     — STOMP connection is established and the CONNECTED frame received.
 *   FALLBACK — STOMP reconnect failed or grace window elapsed.  ReadonlyWallService
 *              will be asked to start HTTP polling.
 *   EXPIRED  — A control frame of type "revoked" or "expired" was received, or
 *              the component navigated to /share/:id/expired.
 */
export enum ViewerTransportMode {
  PROBING  = 'PROBING',
  LIVE     = 'LIVE',
  FALLBACK = 'FALLBACK',
  EXPIRED  = 'EXPIRED',
}
