import {
  Component,
  OnInit,
  OnDestroy,
  ChangeDetectionStrategy,
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { ActivatedRoute } from '@angular/router';
import { LiveAnnouncer } from '@angular/cdk/a11y';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { Subscription } from 'rxjs';
import { ReadonlyWallService } from '../../services/readonly-wall.service';
import { ReadonlyTootComponent } from '../readonly-toot/readonly-toot.component';
import { ReadonlyTootView } from '../../model/readonly-toot-view';

/**
 * Main viewer surface for the readonly share wall.
 *
 * Accessibility (UX plan §1.2):
 * - `role="feed"` on the toot list with `aria-busy` while loading.
 * - Skip links to feed and status banner.
 * - Persistent "Schreibgeschützte Ansicht" banner (not dismissible).
 * - Polite live region announces new toots without interrupting reading.
 * - H1 heading for page structure.
 *
 * State management:
 * - ReadonlyWallService is provided per-component (not root) to allow teardown.
 * - STOMP via /share-view-ws is wired by this component (placeholder pending
 *   backend WS endpoint; see CLARIFICATION REQUEST below).
 * - Falls back to HTTP polling when STOMP is unavailable.
 */
@Component({
  selector: 'app-readonly-wall',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  providers: [ReadonlyWallService],
  imports: [
    CommonModule,
    MatProgressSpinnerModule,
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
      <section
        id="share-status"
        role="region"
        aria-labelledby="share-banner"
        class="share-banner"
        data-testid="share-banner"
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

      <!-- Polite live region for new toot announcements (UX plan §1.2 Step 6) -->
      <div
        aria-live="polite"
        aria-atomic="false"
        class="visually-hidden"
        data-testid="live-region"
      >{{ liveAnnouncement }}</div>

      <!-- Toot feed -->
      <section
        id="share-feed"
        role="feed"
        [attr.aria-busy]="!(catalogLoaded$ | async)"
        [attr.aria-label]="feedLabel"
        data-testid="share-feed"
      >
        @if (!(catalogLoaded$ | async)) {
          <mat-progress-spinner
            mode="indeterminate"
            diameter="48"
            aria-label="Lade Toots…"
          ></mat-progress-spinner>
        } @else {
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
  `],
})
export class ReadonlyWallComponent implements OnInit, OnDestroy {

  private subscription = new Subscription();

  get toots$() { return this.wallService.toots$; }
  get catalogLoaded$() { return this.wallService.catalogLoaded$; }
  get expiresAt() { return this.wallService.expiresAt; }

  liveAnnouncement = '';

  get feedLabel(): string {
    const count = this.wallService.hashtags.length;
    return $localize`:@@share.feed.label:{count, plural, one {Toots von # Hashtag} other {Toots von # Hashtags}}`.replace('{count}', String(count)).replace('#', String(count));
  }

  get formattedExpiry(): string {
    if (!this.expiresAt) return '';
    try {
      return new Intl.DateTimeFormat(undefined, {
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
    private wallService: ReadonlyWallService,
    private liveAnnouncer: LiveAnnouncer,
  ) {}

  ngOnInit(): void {
    const shareId = this.route.snapshot.paramMap.get('shareId') ?? '';
    this.wallService.initialize(shareId);

    // Announce new toots as they arrive (batched — UX plan §1.2 Step 6)
    this.subscription.add(
      this.wallService.toots$.subscribe((toots) => {
        if (toots.length > 0) {
          const latest = toots[0];
          this.liveAnnouncement = $localize`:@@share.feed.new-toot.announce:Neuer Toot von ${latest.authorAcct}`;
        }
      }),
    );
  }

  ngOnDestroy(): void {
    this.subscription.unsubscribe();
    this.wallService.destroy();
  }
}
