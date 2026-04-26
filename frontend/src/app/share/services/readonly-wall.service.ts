import { Injectable, OnDestroy } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Router } from '@angular/router';
import {
  BehaviorSubject,
  Observable,
  Subscription,
  interval,
  EMPTY,
} from 'rxjs';
import { catchError, switchMap } from 'rxjs/operators';
import {
  ReadonlyTootView,
  ShareCatalog,
} from '../model/readonly-toot-view';
import { PruneResult } from '../../model/wall-message';

/** Control frame payload pushed on /topic/share/{shareId}/control. */
interface ControlFrame {
  type: 'revoked' | 'expired' | 'ping';
}

/**
 * Service for the viewer-side readonly wall.
 *
 * Critical invariants (all verified by tests):
 * 1. NEVER reads or writes localStorage — viewer state is ephemeral.
 * 2. NEVER calls /glacier/subscription or /glacier/termination.
 * 3. Handles STOMP or polling control frames: `{type:"revoked"}` or
 *    `{type:"expired"}` → router.navigate to /share/:id/expired.
 * 4. Fallback polling targets /rest/share/{shareId}/messages, NOT /rest/messages.
 *
 * ADR-SHARE-04 — the STOMP connection uses `/share-view-ws` (wired in
 * ReadonlyWallComponent); this service manages toot state only.
 */
@Injectable()
export class ReadonlyWallService implements OnDestroy {

  /** Emits the current flat list of toots (newest first). */
  readonly toots$: BehaviorSubject<ReadonlyTootView[]> = new BehaviorSubject<ReadonlyTootView[]>([]);

  /** Whether the catalog has finished loading. */
  readonly catalogLoaded$: BehaviorSubject<boolean> = new BehaviorSubject(false);

  /** The share ID currently displayed. */
  shareId = '';
  hashtags: string[] = [];
  expiresAt = '';

  private pollingSubscription: Subscription | null = null;

  constructor(
    private http: HttpClient,
    private router: Router,
  ) {}

  /**
   * Loads the catalog for a share link and populates initial toots.
   * Navigates to the expired route on 404 or non-active state.
   */
  initialize(shareId: string): void {
    this.shareId = shareId;
    this.http
      .get<ShareCatalog>(`/rest/share/${shareId}/catalog`)
      .pipe(
        catchError(() => {
          this.router.navigate(['/share', shareId, 'expired']);
          return EMPTY;
        }),
      )
      .subscribe((catalog) => {
        if (catalog.state !== 'active') {
          this.router.navigate(['/share', shareId, 'expired']);
          return;
        }
        this.hashtags = catalog.hashtags;
        this.expiresAt = catalog.expiresAt;
        this.toots$.next([...catalog.initialToots]);
        this.catalogLoaded$.next(true);
      });
  }

  /**
   * Adds a new toot to the front of the feed.
   * Called by the STOMP subscription in the component.
   */
  handleToot(toot: ReadonlyTootView): void {
    const current = this.toots$.getValue();
    // Deduplicate: ignore if already present
    if (!current.find((t) => t.id === toot.id)) {
      this.toots$.next([toot, ...current]);
    }
  }

  /**
   * Handles a control frame from STOMP or HTTP polling.
   * Routes to the expired view for revocation/expiry signals.
   */
  handleControlFrame(frame: ControlFrame): void {
    if (frame.type === 'revoked' || frame.type === 'expired') {
      this.router.navigate(['/share', this.shareId, 'expired']);
    }
  }

  /**
   * Starts HTTP fallback polling against the viewer-specific endpoint.
   *
   * Poll interval: 5 000 ms (matches glacier.fallback.pollIntervalMs=5000).
   * Each poll sends hashtags as query params and uses a `since` cursor.
   *
   * NOTE: Polling targets /rest/share/{shareId}/messages — NEVER /rest/messages
   * (the sharer-side endpoint).
   */
  startFallbackPolling(): void {
    if (this.pollingSubscription) {
      return; // Already polling
    }

    let since = 0;

    this.pollingSubscription = interval(5000)
      .pipe(
        switchMap(() => {
          const params = this.hashtags
            .map((h) => `hashtag=${encodeURIComponent(h)}`)
            .join('&');
          const url = `/rest/share/${this.shareId}/messages?${params}&since=${since}`;
          return this.http.get<ReadonlyTootView[]>(url).pipe(
            catchError(() => EMPTY),
          );
        }),
      )
      .subscribe((toots) => {
        toots.forEach((t) => this.handleToot(t));
        since = Date.now();
      });
  }

  /** Stops fallback polling. Called on component destroy. */
  stopFallbackPolling(): void {
    this.pollingSubscription?.unsubscribe();
    this.pollingSubscription = null;
  }

  /**
   * Guard-rail: prune operations are intentional no-ops in the viewer context
   * (SR-PRUNE-09).
   *
   * The viewer-side readonly wall is populated exclusively from the share
   * catalog (a controlled HTTP endpoint) and live STOMP deliveries scoped
   * to the share link's hashtags.  Prune semantics belong only to the
   * sharer-side SubscriptionService.
   *
   * Exposing these no-op methods via the service interface prevents any caller
   * (e.g. a component that treats both services as duck-typed) from
   * accidentally or maliciously triggering prune operations that would clear
   * the viewer's wall.
   *
   * Security invariants:
   *   1. toots$ is never modified — the current snapshot is returned as-is.
   *   2. localStorage is never touched (viewer state is ephemeral — invariant
   *      from the ReadonlyWallService contract, verified by the existing
   *      localStorage-isolation tests).
   *   3. The removed[] array is always empty — no toots are removed.
   *
   * OWASP A08 — Software and Data Integrity: the prune path in
   * SubscriptionService does mutate localStorage; blocking it here ensures
   * a viewer cannot be tricked into clearing a sharer's cache via crafted
   * WebSocket frames.
   *
   * @param _hashtag - Ignored.
   * @returns PruneResult with empty removed[] and the current toot snapshot.
   */
  pruneByHashtag(_hashtag: string): PruneResult {
    // SR-PRUNE-09: no-op guard-rail — viewer cannot prune toots
    return { removed: [], remaining: [] };
  }

  /**
   * Guard-rail: multi-hashtag prune is also a no-op in the viewer context
   * (SR-PRUNE-09).  See pruneByHashtag() for the full security rationale.
   *
   * @param _hashtags - Ignored.
   * @returns PruneResult with empty removed[] and the current toot snapshot.
   */
  pruneByHashtags(_hashtags: string[]): PruneResult {
    // SR-PRUNE-09: no-op guard-rail — viewer cannot prune toots
    return { removed: [], remaining: [] };
  }

  /** Full cleanup — called on component destroy. */
  destroy(): void {
    this.stopFallbackPolling();
    this.toots$.complete();
    this.catalogLoaded$.complete();
  }

  ngOnDestroy(): void {
    this.destroy();
  }
}
