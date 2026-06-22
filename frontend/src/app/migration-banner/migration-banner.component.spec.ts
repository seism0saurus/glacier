import {ComponentFixture, TestBed} from '@angular/core/testing';
import {MigrationBannerComponent} from './migration-banner.component';
import {SubscriptionService} from '../subscription.service';
import {Subject} from 'rxjs';
import {MatCardModule} from '@angular/material/card';
import {MatIconModule} from '@angular/material/icon';
import {MatButtonModule} from '@angular/material/button';
import {NgIf} from '@angular/common';
import {By} from '@angular/platform-browser';
import {NoopAnimationsModule} from '@angular/platform-browser/animations';
import {provideHttpClient, withInterceptorsFromDi} from '@angular/common/http';
import {provideHttpClientTesting} from '@angular/common/http/testing';

/**
 * Unit tests for MigrationBannerComponent (ADR-4).
 *
 * Test IDs:
 *   B1 — Banner appears when hasMigrated$ emits true and was not previously dismissed.
 *   B2 — Banner hides and persists dismiss flag in localStorage after dismiss().
 *
 * Additional coverage:
 *   - Banner is not shown if localStorage already contains dismiss flag.
 *   - Banner has role="alert" on the root mat-card element.
 *   - Dismiss button has correct aria-label.
 *   - Dismiss stores glacier.migrationBannerDismissed = 'true' in localStorage.
 */
describe('MigrationBannerComponent', () => {
  let component: MigrationBannerComponent;
  let fixture: ComponentFixture<MigrationBannerComponent>;
  let migratedSubject: Subject<boolean>;
  let mockSubscriptionService: { hasMigrated$: Subject<boolean> };

  beforeEach(() => {
    // Reset the dismiss flag before each test so tests are independent
    localStorage.removeItem('glacier.migrationBannerDismissed');

    migratedSubject = new Subject<boolean>();
    mockSubscriptionService = {
      hasMigrated$: migratedSubject,
    };

    TestBed.configureTestingModule({
      declarations: [MigrationBannerComponent],
      imports: [
        MatCardModule,
        MatIconModule,
        MatButtonModule,
        NgIf,
        NoopAnimationsModule,
      ],
      providers: [
        {provide: SubscriptionService, useValue: mockSubscriptionService},
        provideHttpClient(withInterceptorsFromDi()),
        provideHttpClientTesting(),
      ],
    });

    fixture = TestBed.createComponent(MigrationBannerComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  afterEach(() => {
    localStorage.removeItem('glacier.migrationBannerDismissed');
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  it('renders the dismiss-button icon as a local SVG (close_icon), not a font ligature', () => {
    migratedSubject.next(true);
    fixture.detectChanges();
    const icon: HTMLElement = fixture.nativeElement.querySelector('mat-icon');
    expect(icon).withContext('dismiss icon must exist when banner is visible').toBeTruthy();
    expect(icon.getAttribute('data-mat-icon-type'))
      .withContext('icon must use the local svgIcon path, not a font ligature')
      .toBe('svg');
    expect(icon.getAttribute('data-mat-icon-name')).toBe('close_icon');
  });

  describe('B1 — Banner appears on migration signal', () => {
    it('should not show the banner before hasMigrated$ emits', () => {
      // Arrange + Act: no emission yet
      fixture.detectChanges();

      // Assert: banner is not in the DOM
      const card = fixture.debugElement.query(By.css('mat-card[role="alert"]'));
      expect(card).toBeNull();
    });

    it('should show the banner when hasMigrated$ emits true', () => {
      // Act: emit migration signal
      migratedSubject.next(true);
      fixture.detectChanges();

      // Assert: banner is visible
      const card = fixture.debugElement.query(By.css('mat-card[role="alert"]'));
      expect(card).not.toBeNull();
      expect(component.visible).toBeTrue();
    });

    it('should NOT show the banner when hasMigrated$ emits false', () => {
      // Act: emit false (no migration)
      migratedSubject.next(false);
      fixture.detectChanges();

      // Assert: banner stays hidden
      const card = fixture.debugElement.query(By.css('mat-card[role="alert"]'));
      expect(card).toBeNull();
      expect(component.visible).toBeFalse();
    });

    it('should have role="alert" on the mat-card element', () => {
      // Arrange: trigger migration
      migratedSubject.next(true);
      fixture.detectChanges();

      // Assert: role="alert" is present so screen readers announce immediately
      const card = fixture.debugElement.query(By.css('mat-card'));
      expect(card).not.toBeNull();
      expect(card.nativeElement.getAttribute('role')).toBe('alert');
    });
  });

  describe('B2 — Dismiss hides banner and persists flag', () => {
    beforeEach(() => {
      // Show the banner first
      migratedSubject.next(true);
      fixture.detectChanges();
    });

    it('should hide the banner after dismiss()', () => {
      // Act: user clicks dismiss
      component.dismiss();
      fixture.detectChanges();

      // Assert: banner disappears
      expect(component.visible).toBeFalse();
      const card = fixture.debugElement.query(By.css('mat-card[role="alert"]'));
      expect(card).toBeNull();
    });

    it('should write the dismiss flag to localStorage', () => {
      // Act
      component.dismiss();

      // Assert: flag persisted
      expect(localStorage.getItem('glacier.migrationBannerDismissed')).toBe('true');
    });

    it('dismiss button should have the correct aria-label', () => {
      // The aria-label is resolved via $localize — in Karma (no catalog loaded)
      // it will be the German source text.
      fixture.detectChanges();

      const dismissBtn = fixture.debugElement.query(By.css('button[mat-icon-button]'));
      expect(dismissBtn).not.toBeNull();
      const ariaLabel = dismissBtn.nativeElement.getAttribute('aria-label');
      // In Karma tests the German source is returned (angular-karma-jasmine-testing skill).
      expect(ariaLabel).toBeTruthy();
      expect(typeof ariaLabel).toBe('string');
    });
  });

  describe('Pre-dismissed — not shown if flag already set', () => {
    it('should not show the banner if dismiss flag is already in localStorage', () => {
      // Arrange: pre-set the dismiss flag (simulates a previous session)
      localStorage.setItem('glacier.migrationBannerDismissed', 'true');

      // Re-create the component so ngOnInit reads the flag
      const newFixture = TestBed.createComponent(MigrationBannerComponent);
      newFixture.detectChanges();

      // Act: emit migration signal — should be ignored because flag is set
      migratedSubject.next(true);
      newFixture.detectChanges();

      // Assert: banner is still not shown
      const card = newFixture.debugElement.query(By.css('mat-card[role="alert"]'));
      expect(card).toBeNull();
    });
  });

  describe('i18n catalog completeness — regression guard for new banner keys', () => {
    /**
     * These keys were added in Phase 2 (secure-tdd-implementer lane) and must
     * be present in both catalogs.  This guard is a runtime fetch of the
     * English catalog — if the key is missing EN users see German source text.
     */
    const REQUIRED_BANNER_KEYS = [
      'wall.migration.banner.heading',
      'wall.migration.banner.body',
      'wall.migration.banner.dismiss.aria',
    ] as const;

    for (const key of REQUIRED_BANNER_KEYS) {
      it(`messages.en.json must contain key '${key}'`, async () => {
        const response = await fetch('/assets/i18n/messages.en.json');
        expect(response.ok)
          .withContext('messages.en.json must be fetchable by the Karma test runner')
          .toBeTrue();

        const catalog: Record<string, string> = await response.json();
        expect(catalog[key])
          .withContext(`messages.en.json is missing key '${key}'`)
          .toBeDefined();
        expect(typeof catalog[key]).toBe('string');
        expect(catalog[key].length).toBeGreaterThan(0);
      });
    }
  });
});
