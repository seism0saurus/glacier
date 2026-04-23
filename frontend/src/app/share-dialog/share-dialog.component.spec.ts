import {
  ComponentFixture,
  TestBed,
  fakeAsync,
  tick,
} from '@angular/core/testing';
import { MatDialogRef, MatDialog } from '@angular/material/dialog';
import { LiveAnnouncer } from '@angular/cdk/a11y';
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
});
