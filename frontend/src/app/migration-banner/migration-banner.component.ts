import {Component, OnDestroy, OnInit} from '@angular/core';
import {Subscription} from 'rxjs';
import {SubscriptionService} from '../subscription.service';

/**
 * localStorage key used to persist the user's dismiss action.
 *
 * When this key is present (value 'true'), the banner is not shown on
 * subsequent page loads (ADR-4).
 */
const DISMISSED_KEY = 'glacier.migrationBannerDismissed';

/**
 * MigrationBannerComponent — dismissible one-shot notice for schema upgrade (ADR-4).
 *
 * Shown once after the MessageQueue is discarded during a v:1 → v:2 upgrade.
 * The banner is role="alert" so screen readers announce it immediately on
 * first render (WAI-ARIA assertive live region semantics for alerts).
 *
 * Dismiss behaviour:
 *   - The user clicks the close button.
 *   - The banner becomes invisible (`visible = false`).
 *   - A flag is written to localStorage so the banner is not shown on reload.
 *
 * The banner is controlled by `SubscriptionService.hasMigrated$`.  If that
 * observable emits `false`, or if the user has previously dismissed the banner,
 * the component renders nothing.
 *
 * Integration:
 *   - Declared in AppModule (non-standalone, consistent with WallComponent).
 *   - Placed in AppComponent template, between the header and wall regions.
 */
@Component({
  selector: 'app-migration-banner',
  templateUrl: './migration-banner.component.html',
  styleUrls: ['./migration-banner.component.css'],
  standalone: false,
})
export class MigrationBannerComponent implements OnInit, OnDestroy {

  /** Controls whether the banner is rendered. */
  visible: boolean = false;

  /**
   * i18n-resolved dismiss button aria-label.
   *
   * Resolved at runtime from the catalog via $localize so screen reader
   * users hear the locale-correct label (angular-i18n-localize skill).
   */
  dismissLabel: string = $localize`:wall.migration.banner.dismiss.aria@@wall.migration.banner.dismiss.aria:Hinweis schließen`;

  private migrationSub?: Subscription;

  constructor(private subscriptionService: SubscriptionService) {}

  ngOnInit(): void {
    // Do not show the banner if the user has already dismissed it this session
    // or in a previous session.
    if (localStorage.getItem(DISMISSED_KEY) === 'true') {
      return;
    }

    // Observe the migration flag from SubscriptionService.  The flag is set
    // when the MessageQueue.restore() call discards a v:1 (or missing-version)
    // queue and starts fresh (ADR-4).
    this.migrationSub = this.subscriptionService.hasMigrated$.subscribe(
      (migrated: boolean) => {
        if (migrated) {
          this.visible = true;
        }
      }
    );
  }

  ngOnDestroy(): void {
    this.migrationSub?.unsubscribe();
  }

  /**
   * Dismisses the banner.
   *
   * Hides the component and writes the persistent flag to localStorage so
   * that subsequent page loads do not show the banner again.
   */
  dismiss(): void {
    this.visible = false;
    try {
      localStorage.setItem(DISMISSED_KEY, 'true');
    } catch {
      // QuotaExceededError — swallow silently.  The banner will reappear on
      // next load (worst case), which is acceptable.
      console.warn('MigrationBannerComponent.dismiss: could not persist dismiss flag');
    }
  }
}
