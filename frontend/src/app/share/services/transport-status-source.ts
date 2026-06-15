import { Observable } from 'rxjs';
import { ViewerTransportMode } from './viewer-transport-mode';

/**
 * Structural interface that any transport-layer service exposing its connection
 * state to the viewer wall can implement.
 *
 * ReadonlyWallStompClient implements this interface so that ReadonlyWallComponent
 * can subscribe to transport state changes without depending on the concrete class.
 *
 * ADR-SHARE-04: the readonly viewer uses a dedicated STOMP endpoint
 * (/share-view-ws) — this interface is the seam between that client and the
 * component that reacts to its state.
 */
export interface TransportStatusSource {
  /** Emits the current ViewerTransportMode on every state transition. */
  readonly transportMode$: Observable<ViewerTransportMode>;
}
