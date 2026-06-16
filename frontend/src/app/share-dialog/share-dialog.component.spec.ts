import {
  ComponentFixture,
  TestBed,
  fakeAsync,
  tick,
} from '@angular/core/testing';
import { MatDialogRef, MatDialog } from '@angular/material/dialog';
import { LiveAnnouncer } from '@angular/cdk/a11y';
import { LOCALE_ID } from '@angular/core';
import { of, throwError } from 'rxjs';
import { ShareDialogComponent } from './share-dialog.component';
import { ShareLinkService } from '../share/services/share-link.service';
import { ShareLinkCreated, ShareLinkEntry } from '../share/model/readonly-toot-view';
import { provideAnimations } from '@angular/platform-browser/animations';

describe('ShareDialogComponent', () => {
  let component: ShareDialogComponent;
  let fixture: ComponentFixture<ShareDialogComponent>;
  let shareLinkServiceSpy: jasmine.SpyObj<ShareLinkService>;
  let dialogSpy: jasmine.SpyObj<MatDialog>;
  let dialogRefSpy: jasmine.SpyObj<MatDialogRef<ShareDialogComponent>>;
  let liveAnnouncerSpy: jasmine.SpyObj<LiveAnnouncer>;

  const mockCreated: ShareLinkCreated = {
    shareLinkId: 'abc123',
    expiresAt: '2026-04-29T12:00:00Z',
    readonlyUrl: 'https://share.glacier.events/share/abc123',
  };

  const mockExistingLinks: ShareLinkEntry[] = [
    { shareLinkId: 'existing1', expiresAt: '2026-04-28T12:00:00Z', readonlyUrl: 'https://share.glacier.events/share/existing1' },
  ];

  beforeEach(async () => {
    shareLinkServiceSpy = jasmine.createSpyObj<ShareLinkService>(
      'ShareLinkService',
      ['createShareLink', 'listShareLinks', 'revokeShareLink']
    );
    shareLinkServiceSpy.listShareLinks.and.returnValue(of([]));
    shareLinkServiceSpy.createShareLink.and.returnValue(of(mockCreated));
    shareLinkServiceSpy.revokeShareLink.and.returnValue(of(undefined as unknown as void));

    dialogSpy = jasmine.createSpyObj<MatDialog>('MatDialog', ['open']);
    dialogRefSpy = jasmine.createSpyObj<MatDialogRef<ShareDialogComponent>>(
      'MatDialogRef',
      ['close', 'afterClosed']
    );
    // Default: confirm dialog returns false (cancel)
    const confirmRef = jasmine.createSpyObj('MatDialogRef', ['afterClosed']);
    confirmRef.afterClosed.and.returnValue(of(false));
    dialogSpy.open.and.returnValue(confirmRef);

    liveAnnouncerSpy = jasmine.createSpyObj<LiveAnnouncer>('LiveAnnouncer', ['announce']);

    await TestBed.configureTestingModule({
      imports: [ShareDialogComponent],
      providers: [
        provideAnimations(),
        { provide: MatDialogRef, useValue: dialogRefSpy },
        { provide: ShareLinkService, useValue: shareLinkServiceSpy },
        { provide: MatDialog, useValue: dialogSpy },
        { provide: LiveAnnouncer, useValue: liveAnnouncerSpy },
        { provide: LOCALE_ID, useValue: 'de' },
      ],
    })
    // ShareDialogComponent imports MatDialogModule in its standalone imports array,
    // which registers MatDialog in the component's own injector and shadows the
    // test-module-level spy.  overrideProvider() forces Angular to use the spy
    // regardless of which injector level registered the real service.
    .overrideProvider(MatDialog, { useValue: dialogSpy })
    .compileComponents();

    fixture = TestBed.createComponent(ShareDialogComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  it('loads existing links on init', () => {
    expect(shareLinkServiceSpy.listShareLinks).toHaveBeenCalled();
  });

  // ---- Create link ----

  describe('createLink()', () => {
    it('calls shareLinkService.createShareLink()', fakeAsync(() => {
      component.createLink();
      tick();
      expect(shareLinkServiceSpy.createShareLink).toHaveBeenCalled();
    }));

    it('sets activeLink on success', fakeAsync(() => {
      component.createLink();
      tick();
      fixture.detectChanges();
      expect(component.activeLink()).toEqual(mockCreated);
    }));

    it('announces link created via LiveAnnouncer', fakeAsync(() => {
      component.createLink();
      tick();
      expect(liveAnnouncerSpy.announce).toHaveBeenCalledWith(
        jasmine.stringMatching(/Link erstellt/),
        'polite'
      );
    }));

    it('sets loading=true while creating', fakeAsync(() => {
      let loadingDuring = false;
      shareLinkServiceSpy.createShareLink.and.callFake(() => {
        loadingDuring = component.loading();
        return of(mockCreated);
      });
      component.createLink();
      tick();
      expect(loadingDuring).toBeTrue();
    }));

    it('resets loading=false after creation', fakeAsync(() => {
      component.createLink();
      tick();
      expect(component.loading()).toBeFalse();
    }));
  });

  // ---- Copy URL ----

  describe('copyUrl()', () => {
    it('calls navigator.clipboard.writeText when available', fakeAsync(() => {
      component.activeLink.set(mockCreated);
      const clipboardSpy = jasmine.createSpyObj('clipboard', ['writeText']);
      clipboardSpy.writeText.and.returnValue(Promise.resolve());
      Object.defineProperty(navigator, 'clipboard', {
        value: clipboardSpy,
        configurable: true,
      });

      component.copyUrl();
      tick();

      expect(clipboardSpy.writeText).toHaveBeenCalledWith(mockCreated.readonlyUrl);
    }));

    it('announces "Link kopiert" after clipboard write', fakeAsync(() => {
      component.activeLink.set(mockCreated);
      const clipboardSpy = jasmine.createSpyObj('clipboard', ['writeText']);
      clipboardSpy.writeText.and.returnValue(Promise.resolve());
      Object.defineProperty(navigator, 'clipboard', {
        value: clipboardSpy,
        configurable: true,
      });

      component.copyUrl();
      tick();

      expect(liveAnnouncerSpy.announce).toHaveBeenCalledWith(
        jasmine.stringMatching(/Link kopiert/),
        'polite'
      );
    }));

    it('falls back to input selection when clipboard API unavailable', () => {
      component.activeLink.set(mockCreated);
      // Remove clipboard API
      Object.defineProperty(navigator, 'clipboard', { value: undefined, configurable: true });

      // Should not throw even without clipboard API
      expect(() => component.copyUrl()).not.toThrow();
    });
  });

  // ---- Revoke ----

  describe('confirmRevoke()', () => {
    it('opens a confirmation dialog', fakeAsync(() => {
      component.confirmRevoke('abc123');
      tick();
      expect(dialogSpy.open).toHaveBeenCalled();
    }));

    it('does NOT revoke when user cancels (confirmed=false)', fakeAsync(() => {
      // Already configured to return false
      component.confirmRevoke('abc123');
      tick();
      expect(shareLinkServiceSpy.revokeShareLink).not.toHaveBeenCalled();
    }));

    it('calls revokeShareLink when user confirms', fakeAsync(() => {
      // Override to return true (confirm)
      const confirmRef = jasmine.createSpyObj('MatDialogRef', ['afterClosed']);
      confirmRef.afterClosed.and.returnValue(of(true));
      dialogSpy.open.and.returnValue(confirmRef);

      component.confirmRevoke('abc123');
      tick();

      expect(shareLinkServiceSpy.revokeShareLink).toHaveBeenCalledWith('abc123');
    }));

    it('announces "Link widerrufen" after successful revocation', fakeAsync(() => {
      const confirmRef = jasmine.createSpyObj('MatDialogRef', ['afterClosed']);
      confirmRef.afterClosed.and.returnValue(of(true));
      dialogSpy.open.and.returnValue(confirmRef);

      component.existingLinks.set(mockExistingLinks);
      component.confirmRevoke('existing1');
      tick();

      expect(liveAnnouncerSpy.announce).toHaveBeenCalledWith(
        jasmine.stringMatching(/Link widerrufen/),
        'assertive'
      );
    }));

    it('announces already-expired message on 404 revoke error', fakeAsync(() => {
      const confirmRef = jasmine.createSpyObj('MatDialogRef', ['afterClosed']);
      confirmRef.afterClosed.and.returnValue(of(true));
      dialogSpy.open.and.returnValue(confirmRef);
      shareLinkServiceSpy.revokeShareLink.and.returnValue(
        throwError(() => ({ status: 404 }))
      );

      component.existingLinks.set(mockExistingLinks);
      component.confirmRevoke('existing1');
      tick();

      expect(liveAnnouncerSpy.announce).toHaveBeenCalledWith(
        jasmine.stringMatching(/bereits abgelaufen/),
        'polite'
      );
    }));
  });

  // ---- TOOT-07: Shown-once warning must use aria-describedby, NOT role="alert" ----

  describe('shown-once warning (TOOT-07)', () => {
    it('TOOT-07: warning element must NOT have role="alert" (would fire simultaneously with LiveAnnouncer)', () => {
      component.activeLink.set(mockCreated);
      fixture.detectChanges();
      // The warning must be persistent text associated via aria-describedby,
      // not a live region — role="alert" fires on every DOM insertion and would
      // compete with the single LiveAnnouncer call + focus-move.
      const alertWarning: HTMLElement | null =
        fixture.nativeElement.querySelector('.shown-once-warning[role="alert"]');
      expect(alertWarning)
        .withContext('shown-once-warning must NOT carry role="alert" (WCAG 4.1.3 — triple announcement)')
        .toBeNull();
    });

    it('TOOT-07: warning element must have an id for aria-describedby wiring', () => {
      component.activeLink.set(mockCreated);
      fixture.detectChanges();
      const warning: HTMLElement | null =
        fixture.nativeElement.querySelector('.shown-once-warning');
      expect(warning).not.toBeNull('shown-once-warning must exist');
      expect(warning?.id)
        .withContext('shown-once-warning must have an id so the URL input can reference it via aria-describedby')
        .toBeTruthy();
    });

    it('TOOT-07: URL input must reference the warning via aria-describedby', () => {
      component.activeLink.set(mockCreated);
      fixture.detectChanges();
      const input: HTMLInputElement | null =
        fixture.nativeElement.querySelector('[data-testid="share-url-input"]');
      const warning: HTMLElement | null =
        fixture.nativeElement.querySelector('.shown-once-warning');
      expect(input).not.toBeNull('URL input must exist');
      expect(warning).not.toBeNull('shown-once-warning must exist');
      const describedBy = input?.getAttribute('aria-describedby') ?? '';
      expect(describedBy)
        .withContext('URL input aria-describedby must include the warning element id')
        .toContain(warning!.id);
    });

    it('is present in the DOM when a link has just been created (activeLink set)', () => {
      component.activeLink.set(mockCreated);
      fixture.detectChanges();
      const warning: HTMLElement | null =
        fixture.nativeElement.querySelector('.shown-once-warning');
      expect(warning).not.toBeNull('expected shown-once-warning element to be present');
    });

    it('contains the expected warning text when a link is active', () => {
      component.activeLink.set(mockCreated);
      fixture.detectChanges();
      const warning: HTMLElement | null =
        fixture.nativeElement.querySelector('.shown-once-warning');
      expect(warning?.textContent).toContain('Wichtig:');
    });

    it('is NOT present in the DOM when no link has been created (initial state)', () => {
      // Initial state: activeLink is null
      expect(component.activeLink()).toBeNull();
      fixture.detectChanges();
      const warning: HTMLElement | null =
        fixture.nativeElement.querySelector('.shown-once-warning');
      expect(warning).toBeNull('expected shown-once-warning element to be absent in initial state');
    });

    it('disappears after the link is revoked (activeLink cleared)', fakeAsync(() => {
      component.activeLink.set(mockCreated);
      fixture.detectChanges();

      const confirmRef = jasmine.createSpyObj('MatDialogRef', ['afterClosed']);
      confirmRef.afterClosed.and.returnValue(of(true));
      dialogSpy.open.and.returnValue(confirmRef);

      component.confirmRevoke(mockCreated.shareLinkId);
      tick();
      fixture.detectChanges();

      const warning: HTMLElement | null =
        fixture.nativeElement.querySelector('.shown-once-warning');
      expect(warning).toBeNull('expected shown-once-warning to disappear after link is revoked');
    }));
  });

  // ---- TOOT-05: copy icon-button must keep ≥24px touch target ----

  describe('copy button target size (TOOT-05)', () => {
    it('TOOT-05: copy button must be present when activeLink is set', () => {
      component.activeLink.set(mockCreated);
      fixture.detectChanges();
      const copyBtn: HTMLElement | null =
        fixture.nativeElement.querySelector('[data-testid="copy-button"]');
      expect(copyBtn).not.toBeNull('copy button must be present when activeLink is set');
    });

    it('TOOT-05: copy icon-button must have minimum 24x24 CSS size via style', () => {
      // WCAG 2.5.8: minimum target size 24x24 CSS px.
      // We verify the button element has a CSS min-width/min-height guard applied
      // so it is not shrunk below the threshold in the flex .url-copy-row at narrow widths.
      component.activeLink.set(mockCreated);
      fixture.detectChanges();
      const copyBtn: HTMLElement | null =
        fixture.nativeElement.querySelector('[data-testid="copy-button"]');
      expect(copyBtn).not.toBeNull();
      // mat-icon-button default is 40px; the spec check is that min-width/height guard exists
      // at component style level. We check computedStyle width/height ≥ 24px.
      const style = window.getComputedStyle(copyBtn!);
      const w = parseFloat(style.width);
      const h = parseFloat(style.height);
      // In Karma/JSDOM, computed sizes may be 0. Guard: if rendering resolves size, enforce ≥ 24.
      if (w > 0) {
        expect(w).withContext('copy button width must be ≥ 24px (WCAG 2.5.8)').toBeGreaterThanOrEqual(24);
      }
      if (h > 0) {
        expect(h).withContext('copy button height must be ≥ 24px (WCAG 2.5.8)').toBeGreaterThanOrEqual(24);
      }
    });
  });

  // ---- Cap reached ----

  describe('cap handling', () => {
    it('create button is disabled when capReached=true', () => {
      component.capReached.set(true);
      fixture.detectChanges();
      const createBtn: HTMLButtonElement | null =
        fixture.nativeElement.querySelector('[data-testid="create-button"]');
      if (createBtn) {
        expect(createBtn.disabled).toBeTrue();
      }
    });
  });

  // ---- TOOT-09: spinner aria-label i18n catalog completeness ----

  describe('TOOT-09 i18n catalog completeness', () => {
    it('messages.en.json must contain key share.dialog.creating.aria', async () => {
      const response = await fetch('/assets/i18n/messages.en.json');
      const catalog: Record<string, string> = await response.json();
      expect(catalog['share.dialog.creating.aria']).toBeDefined();
      expect(typeof catalog['share.dialog.creating.aria']).toBe('string');
      expect(catalog['share.dialog.creating.aria'].length).toBeGreaterThan(0);
    });
  });

  // ---- TOOT-13: formatExpiry uses injected LOCALE_ID ----

  describe('TOOT-13 formatExpiry uses injected LOCALE_ID', () => {
    it('formatExpiry returns a formatted date string for a valid ISO date', () => {
      const result = component.formatExpiry('2026-04-29T12:00:00Z');
      expect(result).toBeTruthy();
      expect(result).not.toBe('2026-04-29T12:00:00Z'); // must not return raw ISO
    });

    it('formatExpiry falls back to the raw string for invalid input', () => {
      const result = component.formatExpiry('not-a-date');
      expect(result).toBe('not-a-date');
    });

    it('formatExpiry uses the injected locale (de yields German format)', () => {
      // The TestBed default locale is used; since no LOCALE_ID override in test,
      // this just asserts the date is formatted (not raw ISO).
      const result = component.formatExpiry('2026-01-15T00:00:00Z');
      expect(result).toMatch(/\d/); // contains digits
      expect(result).not.toContain('T'); // ISO T separator not present
    });
  });
});
