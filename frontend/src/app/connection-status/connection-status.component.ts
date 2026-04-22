import {Component, OnDestroy, OnInit} from '@angular/core';
import {Subscription} from 'rxjs';
import {TransportMode} from '../fallback/transport-mode';
import {FallbackService} from '../fallback/fallback.service';

/**
 * Per-state display metadata.
 */
interface StateDisplay {
  /** CSS modifier class suffix, e.g. "websocket" → .connection-chip--websocket */
  readonly modeKey: string;
  /** Material icon ligature name. */
  readonly iconName: string;
  /** Short chip label (German default, D-18). */
  readonly label: string;
  /** Longer explanation shown in the popover (German default, D-18). */
  readonly detail: string;
  /** Whether the "Neu laden" / Reload button is shown in the popover (D-17). */
  readonly showReloadCta: boolean;
}

/**
 * ConnectionStatusComponent — 32 px Material chip-pill in the header right (D-15).
 *
 * Six states drive distinct colours, icons, labels, and popover detail text
 * (WEBSOCKET, PROBING, FALLBACK, OFFLINE, KILLSWITCHED, INSECURE).
 *
 * Accessibility
 * - Native `<button type="button">` is keyboard-focusable.
 * - A visually-hidden `<span role="status" aria-live="polite">` narrates
 *   state transitions.  The initial WEBSOCKET state is suppressed so a
 *   connected user is not immediately announced on page load (D-15).
 * - `data-testid="connection-status"` attribute for E2E selectors (D-19).
 *
 * prefers-reduced-motion
 * - The PROBING spinning icon class `rotating-icon` is disabled via
 *   `@media (prefers-reduced-motion: reduce)` in the component CSS (D-15).
 */
@Component({
  selector: 'app-connection-status',
  templateUrl: './connection-status.component.html',
  styleUrls: ['./connection-status.component.css'],
  standalone: false,
})
export class ConnectionStatusComponent implements OnInit, OnDestroy {

  // ---- Template-bound properties -------------------------------------------

  /** CSS class modifier for the current state. */
  modeKey = 'websocket';

  /** Material icon name for the current state. */
  iconName = 'wifi';

  /** Short chip label. */
  chipLabel = $localize`:connection.websocket.label@@connection.websocket.label:Live`;

  /** Detail text shown in the popover. */
  chipDetail = $localize`:connection.websocket.detail@@connection.websocket.detail:Toots werden live via WebSocket gestreamt.`;

  /** Whether to show the Reload CTA in the popover. */
  showReloadCta = false;

  /** Whether the chip icon should spin (PROBING state only). */
  isProbing = false;

  /**
   * Text announced by the SR live region on state transitions.
   * Deliberately empty on initial WEBSOCKET render (D-15).
   */
  liveRegionText = '';

  // ---- Internal state ------------------------------------------------------

  private _modeSub?: Subscription;
  private _isInitialRender = true;

  // ---- State display map ---------------------------------------------------

  private readonly _stateDisplayMap: ReadonlyMap<TransportMode, StateDisplay> = new Map([
    [TransportMode.WEBSOCKET, {
      modeKey: 'websocket',
      iconName: 'wifi',
      label: $localize`:connection.websocket.label@@connection.websocket.label:Live`,
      detail: $localize`:connection.websocket.detail@@connection.websocket.detail:Toots werden live via WebSocket gestreamt.`,
      showReloadCta: false,
    }],
    [TransportMode.PROBING, {
      modeKey: 'probing',
      iconName: 'sync',
      label: $localize`:connection.probing.label@@connection.probing.label:Verbinde…`,
      detail: $localize`:connection.probing.detail@@connection.probing.detail:WebSocket-Verbindung wird wiederhergestellt.`,
      showReloadCta: false,
    }],
    [TransportMode.FALLBACK, {
      modeKey: 'fallback',
      iconName: 'cloud_download',
      label: $localize`:connection.fallback.label@@connection.fallback.label:Fallback-Modus`,
      detail: $localize`:connection.fallback.detail@@connection.fallback.detail:WebSocket ist nicht verfügbar. Toots werden alle 5 Sekunden abgerufen.`,
      showReloadCta: false,
    }],
    [TransportMode.OFFLINE, {
      modeKey: 'offline',
      iconName: 'cloud_off',
      label: $localize`:connection.offline.label@@connection.offline.label:Offline`,
      detail: $localize`:connection.offline.detail@@connection.offline.detail:Ihre Sitzung ist abgelaufen. Bitte laden Sie die Seite neu.`,
      showReloadCta: true,
    }],
    [TransportMode.KILLSWITCHED, {
      modeKey: 'killswitched',
      iconName: 'block',
      label: $localize`:connection.killswitched.label@@connection.killswitched.label:Eingeschränkt`,
      detail: $localize`:connection.killswitched.detail@@connection.killswitched.detail:Der Betreiber hat den Fallback-Modus deaktiviert.`,
      showReloadCta: true,
    }],
    [TransportMode.INSECURE, {
      modeKey: 'insecure',
      iconName: 'lock_open',
      label: $localize`:connection.insecure.label@@connection.insecure.label:Unsichere Verbindung`,
      detail: $localize`:connection.insecure.detail@@connection.insecure.detail:Diese Seite wird über eine unsichere Verbindung ausgeliefert. Der Fallback-Modus ist zu Ihrem Schutz deaktiviert.`,
      showReloadCta: false,
    }],
  ]);

  constructor(private readonly fallbackService: FallbackService) {}

  ngOnInit(): void {
    this._modeSub = this.fallbackService.transportMode$.subscribe(mode => {
      this._applyState(mode);
    });
  }

  ngOnDestroy(): void {
    this._modeSub?.unsubscribe();
  }

  // ---- User actions --------------------------------------------------------

  /** Invoked by the "Neu laden" CTA in the popover (D-17). */
  onReload(): void {
    window.location.reload();
  }

  // ---- Private helpers -----------------------------------------------------

  private _applyState(mode: TransportMode): void {
    const display = this._stateDisplayMap.get(mode);
    if (!display) {
      return;
    }

    this.modeKey      = display.modeKey;
    this.iconName     = display.iconName;
    this.chipLabel    = display.label;
    this.chipDetail   = display.detail;
    this.showReloadCta = display.showReloadCta;
    this.isProbing    = (mode === TransportMode.PROBING);

    // Suppress live-region narration on the initial WEBSOCKET render (D-15)
    if (this._isInitialRender && mode === TransportMode.WEBSOCKET) {
      this._isInitialRender = false;
      this.liveRegionText = '';
      return;
    }
    this._isInitialRender = false;

    // Announce the new state to screen readers
    this.liveRegionText = $localize`:connection.status.aria.live@@connection.status.aria.live:Verbindungsstatus: ${display.label}`;
  }
}
