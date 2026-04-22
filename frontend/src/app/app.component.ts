import {Component, OnDestroy, OnInit} from '@angular/core';
import {MatSnackBar} from '@angular/material/snack-bar';
import {Subscription} from 'rxjs';
import {FallbackService} from './fallback/fallback.service';
import {FallbackEvent, GapObserved, RateLimited} from './fallback/transport-mode';

/**
 * Root application component.
 *
 * Wires the FallbackService event stream (D-16, D-17) to the
 * user-visible notification surfaces:
 *
 *   - SessionExpired  → sets `sessionExpired = true`, which shows the full-width
 *                       banner in the template (D-17).
 *   - GapObserved     → shows a MatSnackBar "Einige ältere Toots könnten fehlen."
 *                       (10 s, dismiss action), per-hashtag (D-16).
 *   - RateLimited     → shows a one-shot MatSnackBar (D-17).  If Retry-After > 60 s,
 *                       the detail text includes the countdown.
 *
 * The live-region suppression for simultaneous gap snackbar + indicator flip
 * (D-16) is handled inside ConnectionStatusComponent: the component debounces
 * its own liveRegionText update for 1.5 s when a GapObserved event fires on
 * FallbackService.events$ in the same tick.  This component therefore does not
 * need to coordinate with it directly — the shared events$ stream is the
 * single coordination point.
 */
@Component({
  selector: 'app-root',
  templateUrl: './app.component.html',
  styleUrls: ['./app.component.css'],
  standalone: false,
})
export class AppComponent implements OnInit, OnDestroy {

  /** Controls the full-width session-expired banner in the template (D-17). */
  sessionExpired = false;

  private _eventsSub?: Subscription;

  /** Per-hashtag flag to avoid stacking gap snackbars (D-16). */
  private readonly _gapAcked = new Set<string>();

  constructor(
    private readonly fallbackService: FallbackService,
    private readonly snackBar: MatSnackBar,
  ) {}

  ngOnInit(): void {
    this._eventsSub = this.fallbackService.events$.subscribe(event => {
      this._handleFallbackEvent(event);
    });
  }

  ngOnDestroy(): void {
    this._eventsSub?.unsubscribe();
  }

  /** Reload CTA action bound in the session-expired banner template (D-17). */
  onReload(): void {
    window.location.reload();
  }

  // ---------------------------------------------------------------------------
  // Private
  // ---------------------------------------------------------------------------

  private _handleFallbackEvent(event: FallbackEvent): void {
    switch (event.type) {
      case 'SessionExpired':
        this.sessionExpired = true;
        break;

      case 'GapObserved':
        this._showGapSnackbar(event as GapObserved);
        break;

      case 'RateLimited':
        this._showRateLimitedSnackbar(event as RateLimited);
        break;

      case 'KillSwitched':
      case 'WsRestored':
        // Handled by ConnectionStatusComponent via transportMode$ (D-15)
        break;
    }
  }

  /**
   * Shows the gap warning snackbar (D-16).
   * Per-hashtag debounce: only one snackbar per hashtag at a time.
   * After it is dismissed the hashtag is removed from the acked set so a
   * future gap can re-toast.
   */
  private _showGapSnackbar(event: GapObserved): void {
    if (this._gapAcked.has(event.hashtag)) {
      return;
    }
    this._gapAcked.add(event.hashtag);

    const ref = this.snackBar.open(
      $localize`:gap.snackbar.message@@gap.snackbar.message:Einige ältere Toots könnten fehlen.`,
      $localize`:gap.snackbar.dismiss@@gap.snackbar.dismiss:Schließen`,
      {
        duration: 10_000,
        panelClass: ['gap-snackbar'],
        // role="alert" is the correct ARIA for an assertive notification (D-16)
        announcementMessage: $localize`:gap.snackbar.message@@gap.snackbar.message:Einige ältere Toots könnten fehlen.`,
      },
    );

    ref.afterDismissed().subscribe(() => {
      this._gapAcked.delete(event.hashtag);
    });
  }

  /**
   * Shows the rate-limited snackbar (D-17).
   * One-shot: does not prevent subsequent 429 toasts.
   */
  private _showRateLimitedSnackbar(event: RateLimited): void {
    let message = $localize`:rate.limited.snackbar@@rate.limited.snackbar:Zu viele Anfragen. Wir versuchen es gleich erneut.`;
    if (event.retryAfterSeconds !== undefined && event.retryAfterSeconds > 60) {
      message = $localize`:rate.limited.retry.popover@@rate.limited.retry.popover:Nächster Versuch in ${event.retryAfterSeconds}s`;
    }

    this.snackBar.open(message, undefined, {
      duration: 6_000,
      panelClass: ['rate-limited-snackbar'],
    });
  }
}
