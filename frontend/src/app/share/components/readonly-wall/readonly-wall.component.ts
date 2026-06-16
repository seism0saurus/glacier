import {
  Component,
  OnInit,
  OnDestroy,
  ChangeDetectionStrategy,
  ChangeDetectorRef,
  Inject,
  LOCALE_ID,
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { ActivatedRoute, Router } from '@angular/router';
import { LiveAnnouncer } from '@angular/cdk/a11y';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatIconModule } from '@angular/material/icon';
import { Subscription } from 'rxjs';
import { ReadonlyWallService } from '../../services/readonly-wall.service';
import { ReadonlyWallStompClient } from '../../services/readonly-wall-stomp-client.service';
import { ViewerTransportMode } from '../../services/viewer-transport-mode';
import { ReadonlyTootComponent } from '../readonly-toot/readonly-toot.component';
import { ReadonlyTootView } from '../../model/readonly-toot-view';

/**
 * Main viewer surface for the readonly share wall.
 *
 * Accessibility (UX plan §1.2):
 * - `role="feed"` on the toot list with `aria-busy` while loading.
 * - Skip links to feed and status banner.
 * - Persistent "Schreibgeschützte Ansicht" banner (not dismissible).
 * - Polite live region announces NEW toots (not the initial catalog hydration).
 * - Transport status indicator (VIEW-03): role="status" region shows LIVE/PROBING/FALLBACK.
 * - H1 heading for page structure.
 *
 * VIEW-02: Announcement suppresses initial catalog hydration; uses plural
 *   @@share.feed.new-toots.announce key when several arrive together.
 * VIEW-03: role="status" aria-live="polite" transport indicator bound to
 *   stompClient.transportMode$; does NOT announce EXPIRED (that path is
 *   handled assertively by triggerExpiry()).
 * VIEW-04: feedLabel computes the plural branch in TS to avoid raw ICU syntax
 *   reaching the aria-label.
 *
 * State management:
 * - ReadonlyWallService is provided per-component (not root) to allow teardown.
 * - ReadonlyWallStompClient is provided per-component (SR-RELAY-17): each viewer
 *   component owns its own connection; different share links must never share a
 *   connection instance.
 * - STOMP connects to /share-view-ws (native WebSocket, SR-RELAY-18).
 * - Falls back to HTTP polling via ReadonlyWallService.startFallbackPolling()
 *   when STOMP transitions to FALLBACK mode.
 */
@Component({
  selector: 'app-readonly-wall',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  // SR-RELAY-17: ReadonlyWallStompClient is per-component, NOT providedIn:'root'
  providers: [ReadonlyWallService, ReadonlyWallStompClient],
  imports: [
    CommonModule,
    MatProgressSpinnerModule,
    MatIconModule,
    ReadonlyTootComponent,
  ],
  template: `
    <!-- Skip links (UX plan §1.2) -->
    <nav class="skip-links" aria-label="Sprungmarken">
      <a href="#share-feed" i18n="@@share.skip.to-feed">Zum Feed springen</a>
      <a href="#share-status" i18n="@@share.skip.to-status">Zur Statusinfo springen</a>
    </nav>

    <main aria-labelledby="share-wall-heading">
      <h1 id="share-wall-heading" class="visually-hidden" i18n="@@share.wall.heading">Wand-Feed</h1>

      <!-- Readonly banner -->
      <!--
        VIEW-10: tabindex="-1" makes this section programmatically focusable so
        the "Zur Statusinfo springen" skip link actually moves keyboard focus here
        when activated (WCAG 2.4.1 — bypass blocks).
      -->
      <section
        id="share-status"
        role="region"
        aria-labelledby="share-banner"
        class="share-banner"
        data-testid="share-banner"
        tabindex="-1"
      >
        <h2
          id="share-banner"
          class="share-banner-title"
          i18n="@@share.readonly.banner"
        >Schreibgeschützte Ansicht</h2>

        @if (expiresAt) {
          <p class="share-expiry" aria-live="off">
            <span i18n="@@share.readonly.expires">Läuft ab am </span>
            <time [attr.datetime]="expiresAt">{{ formattedExpiry }}</time>
          </p>
        }
      </section>

      <!--
        VIEW-03: Transport status indicator.
        role="status" implies aria-live="polite" and aria-atomic="true".
        Bound to currentTransportMode; text+icon (not color alone) per WCAG 1.3.3/1.4.1.
        EXPIRED mode is deliberately excluded — that path uses an assertive
        LiveAnnouncer call in triggerExpiry() to avoid double-announcing.
      -->
      @if (showTransportStatus) {
        <div
          role="status"
          aria-live="polite"
          aria-atomic="true"
          class="transport-status"
          [class]="'transport-status transport-status--' + currentTransportMode.toLowerCase()"
          data-testid="transport-status"
        >
          <mat-icon aria-hidden="true" [fontIcon]="transportStatusIcon"></mat-icon>
          <span>{{ transportStatusLabel }}</span>
        </div>
      }

      <!-- Polite live region for new toot announcements (VIEW-02) -->
      <div
        aria-live="polite"
        aria-atomic="false"
        class="visually-hidden"
        data-testid="live-region"
      >{{ liveAnnouncement }}</div>

      <!-- Toot feed -->
      <!--
        VIEW-10: tabindex="-1" makes this section programmatically focusable so
        the "Zum Feed springen" skip link actually moves keyboard focus here
        when activated (WCAG 2.4.1 — bypass blocks).
      -->
      <section
        id="share-feed"
        role="feed"
        [attr.aria-busy]="!(catalogLoaded$ | async)"
        [attr.aria-label]="feedLabel"
        data-testid="share-feed"
        tabindex="-1"
      >
        @if (!(catalogLoaded$ | async)) {
          <mat-progress-spinner
            mode="indeterminate"
            diameter="48"
            aria-label="Lade Toots…"
          ></mat-progress-spinner>
        } @else {
          <!--
            VIEW-12: empty-state block shown when the catalog has loaded but no
            toots have arrived yet. role="status" (aria-live="polite") reassures
            users that new toots will appear live — they are NOT on a broken page.
            Shown only when (toots$ | async)?.length === 0.
          -->
          @if ((toots$ | async)?.length === 0) {
            <p
              role="status"
              class="feed-empty-state"
              data-testid="share-feed-empty"
              i18n="@@share.feed.empty.message"
            >Noch keine Toots — neue Einträge erscheinen hier live, sobald sie eintreffen.</p>
          }
          @for (toot of toots$ | async; track toot.id) {
            <app-readonly-toot [toot]="toot"></app-readonly-toot>
          }
        }
      </section>
    </main>
  `,
  styles: [`
    .skip-links {
      position: absolute;
      top: -100vh;
      left: 0;
      z-index: 1000;
    }
    .skip-links a:focus {
      position: fixed;
      top: 8px;
      left: 8px;
      background: var(--mat-sys-surface, white);
      padding: 8px 16px;
      border: 2px solid var(--mat-sys-primary, blue);
      border-radius: 4px;
    }
    .share-banner {
      background: var(--mat-sys-surface-container, #f5f5f5);
      padding: 12px 16px;
      border-bottom: 2px solid var(--mat-sys-primary, blue);
      display: flex;
      align-items: center;
      gap: 16px;
      flex-wrap: wrap;
    }
    .share-banner-title {
      font-size: 0.875rem;
      font-weight: 600;
      margin: 0;
    }
    .share-expiry {
      font-size: 0.75rem;
      color: var(--mat-sys-on-surface-variant, #666);
      margin: 0;
    }
    /* VIEW-03: transport status indicator — text+icon, no color-only meaning */
    .transport-status {
      display: flex;
      align-items: center;
      gap: 8px;
      padding: 6px 16px;
      font-size: 0.75rem;
      background: var(--mat-sys-surface-container-low, #fafafa);
      border-bottom: 1px solid var(--mat-sys-outline-variant, #ddd);
    }
    .transport-status--live { color: var(--mat-sys-primary, #1976d2); }
    .transport-status--probing { color: var(--mat-sys-secondary, #666); }
    .transport-status--fallback { color: var(--mat-sys-tertiary, #e65100); }
    /* VIEW-12: empty-state message when feed is loaded but has no toots yet */
    .feed-empty-state {
      padding: 32px 16px;
      text-align: center;
      color: var(--mat-sys-on-surface-variant, #666);
      font-size: 0.875rem;
      margin: 0;
    }
    .visually-hidden {
      position: absolute;
      width: 1px;
      height: 1px;
      padding: 0;
      overflow: hidden;
      clip: rect(0,0,0,0);
      white-space: nowrap;
      border: 0;
    }
    @media (prefers-reduced-motion: reduce) {
      .transport-status { transition: none; }
    }
  `],
})
export class ReadonlyWallComponent implements OnInit, OnDestroy {

  private subscription = new Subscription();

  /**
   * VIEW-01: Guard flag ensuring triggerExpiry() fires exactly once
   * regardless of how many signals arrive (STOMP EXPIRED, expired$ from
   * fallback polling, or both simultaneously).
   *
   * WCAG 2.2.1 / 3.2.5: announce before navigate; 1 000 ms delay preserves
   * reading time for screen-reader users.
   */
  private expiryHandled = false;

  /**
   * VIEW-02: Track whether the initial catalog hydration emission has been
   * observed. The BehaviorSubject replays its current value on subscription,
   * so the first emission is always the catalog snapshot — it must NOT trigger
   * a "new toot" announcement.
   */
  initialLoadDone = false;

  /**
   * VIEW-02: Count of toots from the previous emission, used to compute how
   * many genuinely-new toots arrived and select the correct plural form.
   */
  previousTootCount = 0;

  /**
   * VIEW-03: Current transport mode exposed for template binding.
   * Starts at PROBING (the initial state of ReadonlyWallStompClient).
   */
  currentTransportMode: ViewerTransportMode = ViewerTransportMode.PROBING;

  get toots$() { return this.wallService.toots$; }
  get catalogLoaded$() { return this.wallService.catalogLoaded$; }
  get expiresAt() { return this.wallService.expiresAt; }

  liveAnnouncement = '';

  /**
   * VIEW-03: Show the transport indicator for LIVE/PROBING/FALLBACK.
   * EXPIRED is intentionally excluded — that mode triggers an assertive
   * announce-then-navigate and must not double-announce via the polite region.
   */
  get showTransportStatus(): boolean {
    return this.currentTransportMode !== ViewerTransportMode.EXPIRED;
  }

  /**
   * VIEW-03: Icon name for the current transport mode (text+icon, not color alone).
   */
  get transportStatusIcon(): string {
    switch (this.currentTransportMode) {
      case ViewerTransportMode.LIVE:     return 'wifi';
      case ViewerTransportMode.PROBING:  return 'sync';
      case ViewerTransportMode.FALLBACK: return 'sync_problem';
      default:                           return 'sync';
    }
  }

  /**
   * VIEW-03: Human-readable label for the current transport mode.
   * Uses i18n keys @@share.connection.live.label / .probing.label / .fallback.label.
   */
  get transportStatusLabel(): string {
    switch (this.currentTransportMode) {
      case ViewerTransportMode.LIVE:
        return $localize`:@@share.connection.live.label:Live`;
      case ViewerTransportMode.PROBING:
        return $localize`:@@share.connection.probing.label:Verbinde…`;
      case ViewerTransportMode.FALLBACK:
        return $localize`:@@share.connection.fallback.label:Fallback-Modus`;
      default:
        return '';
    }
  }

  /**
   * VIEW-04: Compute the feed aria-label without raw ICU brace syntax.
   *
   * The $localize ICU template is NOT expanded at runtime by the runtime
   * loader — it would reach the aria-label as literal "{count, plural, …}".
   * Instead, we compute the count branch in TypeScript and use plain string
   * keys that the runtime catalog CAN substitute.
   */
  get feedLabel(): string {
    const count = this.wallService.hashtags.length;
    if (count === 1) {
      // Singular branch: "Toots von 1 Hashtag"
      return $localize`:@@share.feed.label.one:Toots von 1 Hashtag`;
    }
    // Plural branch: "Toots von N Hashtags" with simple named-param substitution
    return $localize`:@@share.feed.label.other:Toots von ${count} Hashtags`;
  }

  get formattedExpiry(): string {
    if (!this.expiresAt) return '';
    try {
      return new Intl.DateTimeFormat(this.locale, {
        day: '2-digit',
        month: '2-digit',
        year: 'numeric',
      }).format(new Date(this.expiresAt));
    } catch {
      return this.expiresAt;
    }
  }

  constructor(
    private route: ActivatedRoute,
    private router: Router,
    private wallService: ReadonlyWallService,
    private stompClient: ReadonlyWallStompClient,
    private liveAnnouncer: LiveAnnouncer,
    private cdr: ChangeDetectorRef,
    @Inject(LOCALE_ID) private locale: string,
  ) {}

  ngOnInit(): void {
    const shareId = this.route.snapshot.paramMap.get('shareId') ?? '';
    this.wallService.initialize(shareId);

    // Open the STOMP connection to the share-view WebSocket endpoint.
    // SR-RELAY-17: stompClient is per-component — each viewer owns its own connection.
    this.stompClient.connect(shareId);

    // Subscribe to STOMP toot events for each hashtag loaded from the catalog.
    // The catalog is loaded synchronously enough that hashtags are available
    // immediately after initialize(); for deferred hashtags, the STOMP client
    // re-registers topic subscriptions on each reconnect.
    this.wallService.hashtags.forEach((hashtag) => {
      this.subscription.add(
        this.stompClient.tootEvents$(hashtag).subscribe((toot: ReadonlyTootView) => {
          this.wallService.handleToot(toot);
        }),
      );
    });

    // React to transport mode changes.
    this.subscription.add(
      this.stompClient.transportMode$.subscribe((mode) => {
        if (mode === ViewerTransportMode.FALLBACK) {
          // STOMP grace window elapsed without reconnect — start HTTP polling
          this.wallService.startFallbackPolling();
        } else if (mode === ViewerTransportMode.LIVE) {
          // STOMP reconnected — stop HTTP polling if it was active
          this.wallService.stopFallbackPolling();
        } else if (mode === ViewerTransportMode.EXPIRED) {
          // STOMP control frame received (revoked/expired) — announce and navigate.
          // Delegates to triggerExpiry() which is guarded against double-firing.
          this.triggerExpiry(shareId);
        }

        // VIEW-03: Update the transport status indicator.
        // EXPIRED is kept in currentTransportMode for other logic but the
        // showTransportStatus getter gates out the polite region for EXPIRED.
        this.currentTransportMode = mode;
        this.cdr.markForCheck();
      }),
    );

    // VIEW-01: Subscribe to the fallback-polling expiry signal.
    // When the service confirms the link is revoked/expired via HTTP polling
    // disambiguation, the component takes over announce-then-navigate (ADR-RELAY-05).
    // triggerExpiry() is guarded so STOMP EXPIRED + expired$ together fire only once.
    this.subscription.add(
      this.wallService.expired$.subscribe(() => {
        this.triggerExpiry(shareId);
      }),
    );

    // VIEW-02: Announce new toots as they arrive.
    // Suppresses the initial BehaviorSubject emission (catalog hydration).
    // Batches multiple simultaneous arrivals into a count-based announcement using
    // the existing plural key @@share.feed.new-toots.announce.
    this.subscription.add(
      this.wallService.toots$.subscribe((toots) => {
        if (!this.initialLoadDone) {
          // First emission is always the catalog snapshot — record the count and skip.
          this.previousTootCount = toots.length;
          this.initialLoadDone = true;
          return;
        }

        const newCount = toots.length - this.previousTootCount;
        if (newCount > 0) {
          if (newCount === 1) {
            // Single new toot: use the singular key with author name
            const latest = toots[0];
            this.liveAnnouncement = $localize`:@@share.feed.new-toot.announce:Neuer Toot von ${latest.authorAcct}`;
          } else {
            // Multiple new toots: use plural count announcement
            // Runtime catalog substitutes {count} via named-param replacement.
            this.liveAnnouncement = newCount === 1
              ? $localize`:@@share.feed.new-toots.announce.one:${newCount} neuer Toot`
              : $localize`:@@share.feed.new-toots.announce.other:${newCount} neue Toots`;
          }
          this.cdr.markForCheck();
        }

        this.previousTootCount = toots.length;
      }),
    );
  }

  /**
   * VIEW-01: Announce-then-navigate for link expiry/revocation.
   *
   * Guarded by expiryHandled so it fires exactly once even when both the
   * STOMP EXPIRED path and the fallback-polling expired$ signal arrive.
   *
   * WCAG 2.2.1 / 3.2.5: announces assertively first, then navigates after
   * 1 000 ms to give screen readers time to read the announcement.
   *
   * ADR-RELAY-05: the COMPONENT is the sole owner of announce-then-navigate;
   * neither ReadonlyWallService nor ReadonlyWallStompClient navigate.
   *
   * @param shareId - The share link ID used to build the expired route.
   */
  private triggerExpiry(shareId: string): void {
    if (this.expiryHandled) {
      return;
    }
    this.expiryHandled = true;

    const expiryMsg = $localize`:@@share.connection.expired.announce:Dieser Link ist abgelaufen oder wurde widerrufen.`;
    this.liveAnnouncer.announce(expiryMsg, 'assertive');
    // WCAG 2.2.1 / 3.2.5: delay navigation so screen readers can read announcement
    setTimeout(() => {
      this.router.navigate(['/share', shareId, 'expired']);
    }, 1000);
  }

  ngOnDestroy(): void {
    this.stompClient.disconnect();
    this.subscription.unsubscribe();
    this.wallService.destroy();
  }
}
