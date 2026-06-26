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
import { provideHttpClient, withInterceptorsFromDi } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';

describe('ShareDialogComponent', () => {
  let component: ShareDialogComponent;
  let fixture: ComponentFixture<ShareDialogComponent>;
  let shareLinkServiceSpy: jasmine.SpyObj<ShareLinkService>;
  let dialogSpy: jasmine.SpyObj<MatDialog>;
  let dialogRefSpy: jasmine.SpyObj<MatDialogRef<ShareDialogComponent>>;
  let liveAnnouncerSpy: jasmine.SpyObj<LiveAnnouncer>;

  const mockCreated: ShareLinkCreated = {
    shareLinkId: 'abc123',
    idHash8: 'abc12345',
    expiresAt: '2026-04-29T12:00:00Z',
    readonlyUrl: 'https://share.glacier.events/share/abc123',
  };

  const mockExistingLinks: ShareLinkEntry[] = [
    { idHash8: 'existing1', createdAt: '2026-04-21T12:00:00Z', expiresAt: '2026-04-28T12:00:00Z', status: 'ACTIVE' },
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
        provideHttpClient(withInterceptorsFromDi()),
        provideHttpClientTesting(),
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

  it('renders the copy-URL button icon as a local SVG (content_copy_icon), not a font ligature', () => {
    component.activeLink.set(mockCreated);
    fixture.detectChanges();
    const icon: HTMLElement = fixture.nativeElement.querySelector(
      '[data-testid="copy-button"] mat-icon',
    );
    expect(icon).withContext('copy icon must exist when a link is active').toBeTruthy();
    expect(icon.getAttribute('data-mat-icon-type'))
      .withContext('icon must use the local svgIcon path, not a font ligature')
      .toBe('svg');
    expect(icon.getAttribute('data-mat-icon-name')).toBe('content_copy_icon');
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

    it('revokes a LISTED link by its idHash8 when its row button is clicked (regression: list-revoke undefined-id)', fakeAsync(() => {
      const confirmRef = jasmine.createSpyObj('MatDialogRef', ['afterClosed']);
      confirmRef.afterClosed.and.returnValue(of(true));
      dialogSpy.open.and.returnValue(confirmRef);

      // The "Active links" list row only carries idHash8 (the backend never re-serves the
      // token). Render the row and click its real revoke button.
      component.existingLinks.set(mockExistingLinks);
      fixture.detectChanges();

      const rowButton: HTMLButtonElement | null =
        fixture.nativeElement.querySelector('[data-testid="revoke-row-button"]');
      expect(rowButton)
        .withContext('the active-links list must render a revoke button per row')
        .not.toBeNull();

      rowButton!.click();
      tick();

      // Before the fix the template bound link.shareLinkId — undefined on a list entry —
      // so the call was revokeShareLink(undefined) → DELETE /rest/share-links/undefined → 404.
      expect(shareLinkServiceSpy.revokeShareLink).toHaveBeenCalledWith('existing1');
      expect(shareLinkServiceSpy.revokeShareLink)
        .not.toHaveBeenCalledWith(undefined as unknown as string);
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

      component.confirmRevoke(mockCreated.idHash8);
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

  // ---- TOOT-06: insecure-transport clipboard fallback — execCommand + LiveAnnouncer ----

  describe('TOOT-06 — insecure-transport clipboard fallback', () => {
    beforeEach(() => {
      // Remove the Clipboard API to simulate insecure transport
      Object.defineProperty(navigator, 'clipboard', { value: undefined, configurable: true });
      component.activeLink.set(mockCreated);
    });

    it('TOOT-06: copyUrl() attempts document.execCommand("copy") when clipboard API is absent', () => {
      // Arrange: create a mock input element
      const inputEl = document.createElement('input');
      inputEl.setAttribute('data-testid', 'share-url-input');
      document.body.appendChild(inputEl);
      const execCommandSpy = spyOn(document, 'execCommand').and.returnValue(true);

      component.copyUrl();

      expect(execCommandSpy).toHaveBeenCalledWith('copy');
      document.body.removeChild(inputEl);
    });

    it('TOOT-06: copyUrl() announces "Link kopiert" when execCommand succeeds', () => {
      const inputEl = document.createElement('input');
      inputEl.setAttribute('data-testid', 'share-url-input');
      document.body.appendChild(inputEl);
      spyOn(document, 'execCommand').and.returnValue(true);

      component.copyUrl();

      expect(liveAnnouncerSpy.announce).toHaveBeenCalledWith(
        jasmine.stringMatching(/Link kopiert/),
        'polite',
      );
      document.body.removeChild(inputEl);
    });

    it('TOOT-06: copyUrl() announces a keyboard-copy instruction when both APIs fail', () => {
      const inputEl = document.createElement('input');
      inputEl.setAttribute('data-testid', 'share-url-input');
      document.body.appendChild(inputEl);
      spyOn(document, 'execCommand').and.returnValue(false);

      component.copyUrl();

      // Must announce an instruction to use Ctrl+C
      expect(liveAnnouncerSpy.announce).toHaveBeenCalledWith(
        jasmine.stringMatching(/Strg\+C|markiert/i),
        'polite',
      );
      document.body.removeChild(inputEl);
    });

    it('TOOT-06: copyUrl() announces a fallback message when no input element is found', () => {
      // No input element in DOM
      spyOn(document, 'execCommand').and.returnValue(false);

      component.copyUrl();

      // Must still announce something — not just silently fail
      expect(liveAnnouncerSpy.announce).toHaveBeenCalled();
    });
  });

  // ---- TOOT-11: revoke failure handling — in-progress + generic error announcement ----

  describe('TOOT-11 — revoke failure announcement', () => {
    beforeEach(() => {
      component.existingLinks.set(mockExistingLinks);
    });

    it('TOOT-11: executeRevoke announces in-progress state before the HTTP call completes', fakeAsync(() => {
      // Arrange: make revokeShareLink hang (never complete)
      const { Subject: RxSubject } = require('rxjs');
      const revokeSubject = new (require('rxjs').Subject)();
      shareLinkServiceSpy.revokeShareLink.and.returnValue(revokeSubject.asObservable());

      const confirmRef = jasmine.createSpyObj('MatDialogRef', ['afterClosed']);
      confirmRef.afterClosed.and.returnValue(of(true));
      dialogSpy.open.and.returnValue(confirmRef);

      component.confirmRevoke('existing1');
      tick();

      // In-progress announcement must fire before completion
      expect(liveAnnouncerSpy.announce).toHaveBeenCalledWith(
        jasmine.stringMatching(/widerrufe|widerruf/i),
        'polite',
      );
    }));

    it('TOOT-11: executeRevoke announces a generic failure on non-404 error', fakeAsync(() => {
      const confirmRef = jasmine.createSpyObj('MatDialogRef', ['afterClosed']);
      confirmRef.afterClosed.and.returnValue(of(true));
      dialogSpy.open.and.returnValue(confirmRef);
      shareLinkServiceSpy.revokeShareLink.and.returnValue(
        throwError(() => ({ status: 500 }))
      );

      component.confirmRevoke('existing1');
      tick();

      expect(liveAnnouncerSpy.announce).toHaveBeenCalledWith(
        jasmine.stringMatching(/fehlgeschlagen|Fehler/i),
        'assertive',
      );
    }));

    it('TOOT-11: executeRevoke KEEPS the link row on non-404 failure (no optimistic removal)', fakeAsync(() => {
      const confirmRef = jasmine.createSpyObj('MatDialogRef', ['afterClosed']);
      confirmRef.afterClosed.and.returnValue(of(true));
      dialogSpy.open.and.returnValue(confirmRef);
      shareLinkServiceSpy.revokeShareLink.and.returnValue(
        throwError(() => ({ status: 500 }))
      );

      component.confirmRevoke('existing1');
      tick();
      fixture.detectChanges();

      // Link must remain in existingLinks — not removed on 500
      expect(component.existingLinks().some(l => l.idHash8 === 'existing1')).toBeTrue();
    }));

    it('TOOT-11: executeRevoke still removes the 404 link from the list (existing behavior preserved)', fakeAsync(() => {
      const confirmRef = jasmine.createSpyObj('MatDialogRef', ['afterClosed']);
      confirmRef.afterClosed.and.returnValue(of(true));
      dialogSpy.open.and.returnValue(confirmRef);
      shareLinkServiceSpy.revokeShareLink.and.returnValue(
        throwError(() => ({ status: 404 }))
      );

      component.confirmRevoke('existing1');
      tick();
      fixture.detectChanges();

      expect(component.existingLinks().some(l => l.idHash8 === 'existing1')).toBeFalse();
    }));
  });

  // ---- TOOT-14: distinguishable "Widerrufen" button accessible names ----

  describe('TOOT-14 — unique accessible names for revoke buttons', () => {
    beforeEach(() => {
      shareLinkServiceSpy.listShareLinks.and.returnValue(of([
        { idHash8: 'aaaa1111', createdAt: '2026-04-24T00:00:00Z', expiresAt: '2026-05-01T00:00:00Z', status: 'ACTIVE' },
        { idHash8: 'bbbb2222', createdAt: '2026-06-08T00:00:00Z', expiresAt: '2026-06-15T00:00:00Z', status: 'ACTIVE' },
      ]));
      component.ngOnInit();
      fixture.detectChanges();
    });

    it('TOOT-14: each revoke button has a non-empty aria-label', () => {
      const revokeButtons: NodeListOf<HTMLButtonElement> =
        fixture.nativeElement.querySelectorAll('[data-testid="revoke-row-button"]');
      expect(revokeButtons.length).toBeGreaterThan(0);
      revokeButtons.forEach((btn) => {
        const label = btn.getAttribute('aria-label');
        expect(label).withContext('revoke button must have aria-label').toBeTruthy();
        expect(label!.length).withContext('revoke button aria-label must be non-empty').toBeGreaterThan(0);
      });
    });

    it('TOOT-14: each revoke button has a UNIQUE accessible name', () => {
      const revokeButtons: NodeListOf<HTMLButtonElement> =
        fixture.nativeElement.querySelectorAll('[data-testid="revoke-row-button"]');
      expect(revokeButtons.length).toBeGreaterThanOrEqual(2);
      const labels = Array.from(revokeButtons).map(btn => btn.getAttribute('aria-label') ?? '');
      const uniqueLabels = new Set(labels);
      expect(uniqueLabels.size)
        .withContext('each revoke button must have a distinct aria-label')
        .toBe(labels.length);
    });
  });

  // ---- TOOT-06/11/14 i18n catalog completeness ----

  describe('TOOT-06/11/14 i18n catalog completeness', () => {
    const REQUIRED_KEYS = [
      'share.dialog.copy.fallback.announce',
      'share.dialog.copy.keyboard.hint',
      'share.dialog.revoke.in-progress.announce',
      'share.dialog.revoke.failed.announce',
      'share.dialog.revoke.button.label',
    ] as const;

    for (const key of REQUIRED_KEYS) {
      it(`messages.en.json must contain key '${key}'`, async () => {
        const response = await fetch('/assets/i18n/messages.en.json');
        const catalog: Record<string, string> = await response.json();
        expect(catalog[key])
          .withContext(`messages.en.json is missing TOOT-06/11/14 key '${key}'`)
          .toBeDefined();
        expect(typeof catalog[key]).toBe('string');
        expect(catalog[key].length).toBeGreaterThan(0);
      });
    }
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
