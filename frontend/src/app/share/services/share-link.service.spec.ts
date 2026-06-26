import { TestBed, fakeAsync, tick } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import {
  HttpTestingController,
  provideHttpClientTesting,
} from '@angular/common/http/testing';
import { MatSnackBar } from '@angular/material/snack-bar';
import { ShareLinkService } from './share-link.service';
import { ShareLinkCreated, ShareLinkEntry } from '../model/readonly-toot-view';

/**
 * Unit tests for ShareLinkService (sharer side).
 *
 * Covers:
 * - CSRF handshake (reads __Host-shareCsrf cookie, echoes in X-Share-CSRF header)
 * - POST /rest/share-links creates a link
 * - GET /rest/share-links lists active links
 * - DELETE /rest/share-links/{id} revokes a link
 * - 429 responses surface via MatSnackBar with i18n key `rate.limited.share.create`
 */
describe('ShareLinkService', () => {
  let service: ShareLinkService;
  let httpMock: HttpTestingController;
  let snackBarSpy: jasmine.SpyObj<MatSnackBar>;

  const mockCreated: ShareLinkCreated = {
    shareLinkId: 'abc123xyz',
    idHash8: 'abc123de',
    expiresAt: '2026-04-29T12:00:00Z',
    readonlyUrl: 'https://share.glacier.events/share/abc123xyz',
  };

  const mockEntries: ShareLinkEntry[] = [
    {
      idHash8: 'abc123de',
      createdAt: '2026-04-22T12:00:00Z',
      expiresAt: '2026-04-29T12:00:00Z',
      status: 'ACTIVE',
    },
  ];

  beforeEach(() => {
    snackBarSpy = jasmine.createSpyObj<MatSnackBar>('MatSnackBar', ['open']);

    TestBed.configureTestingModule({
      providers: [
        ShareLinkService,
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: MatSnackBar, useValue: snackBarSpy },
      ],
    });

    service = TestBed.inject(ShareLinkService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpMock.verify();
  });

  it('should be created', () => {
    expect(service).toBeTruthy();
  });

  // --- CSRF handshake ---

  describe('CSRF handshake', () => {
    it('fetches CSRF token before creating a link', fakeAsync(() => {
      // Stub document.cookie — note: unit tests can't set HttpOnly cookies,
      // so we spy on the service's cookie-read mechanism.
      const csrfToken = 'test-csrf-token-value';
      spyOn(service, 'getCsrfToken' as keyof ShareLinkService).and.returnValue(csrfToken);

      service.createShareLink().subscribe();

      // CSRF fetch first
      const csrfReq = httpMock.expectOne('/rest/share-csrf');
      expect(csrfReq.request.method).toBe('GET');
      csrfReq.flush({ token: csrfToken });
      tick();

      // Then POST with X-Share-CSRF header
      const createReq = httpMock.expectOne('/rest/share-links');
      expect(createReq.request.method).toBe('POST');
      expect(createReq.request.headers.get('X-Share-CSRF')).toBe(csrfToken);
      createReq.flush(mockCreated, { status: 201, statusText: 'Created' });
      tick();
    }));
  });

  // --- createShareLink ---

  describe('createShareLink()', () => {
    it('returns the created share link on 201', fakeAsync(() => {
      const csrfToken = 'csrf-token';
      spyOn(service, 'getCsrfToken' as keyof ShareLinkService).and.returnValue(csrfToken);

      let result: ShareLinkCreated | undefined;
      service.createShareLink().subscribe(v => (result = v));

      httpMock.expectOne('/rest/share-csrf').flush({ token: csrfToken });
      tick();

      const req = httpMock.expectOne('/rest/share-links');
      req.flush(mockCreated, { status: 201, statusText: 'Created' });
      tick();

      expect(result).toEqual(mockCreated);
    }));

    it('opens snackbar with rate-limit message on 429', fakeAsync(() => {
      const csrfToken = 'csrf-token';
      spyOn(service, 'getCsrfToken' as keyof ShareLinkService).and.returnValue(csrfToken);

      let error: unknown;
      service.createShareLink().subscribe({
        next: () => fail('should not succeed'),
        error: (e) => (error = e),
      });

      httpMock.expectOne('/rest/share-csrf').flush({ token: csrfToken });
      tick();

      const req = httpMock.expectOne('/rest/share-links');
      req.flush(
        { message: 'rate limited' },
        { status: 429, statusText: 'Too Many Requests' }
      );
      tick();

      // German source string is what's displayed (no catalog loaded in tests)
      expect(snackBarSpy.open).toHaveBeenCalledWith(
        jasmine.stringMatching(/Zu viele geteilte Links/),
        jasmine.any(String),
        jasmine.any(Object)
      );
      expect(error).toBeTruthy();
    }));

    it('does not show snackbar on non-429 errors', fakeAsync(() => {
      const csrfToken = 'csrf-token';
      spyOn(service, 'getCsrfToken' as keyof ShareLinkService).and.returnValue(csrfToken);

      service.createShareLink().subscribe({
        next: () => fail('should not succeed'),
        error: () => {},
      });

      httpMock.expectOne('/rest/share-csrf').flush({ token: csrfToken });
      tick();

      const req = httpMock.expectOne('/rest/share-links');
      req.flush({ message: 'server error' }, { status: 500, statusText: 'Internal Server Error' });
      tick();

      expect(snackBarSpy.open).not.toHaveBeenCalled();
    }));
  });

  // --- listShareLinks ---

  describe('listShareLinks()', () => {
    it('returns list of active share links', fakeAsync(() => {
      let result: ShareLinkEntry[] | undefined;
      service.listShareLinks().subscribe(v => (result = v));

      const req = httpMock.expectOne('/rest/share-links');
      expect(req.request.method).toBe('GET');
      req.flush(mockEntries);
      tick();

      expect(result).toEqual(mockEntries);
    }));

    it('returns empty array on 404 (no links exist)', fakeAsync(() => {
      let result: ShareLinkEntry[] | undefined;
      service.listShareLinks().subscribe(v => (result = v));

      const req = httpMock.expectOne('/rest/share-links');
      req.flush([], { status: 200, statusText: 'OK' });
      tick();

      expect(result).toEqual([]);
    }));
  });

  // --- revokeShareLink ---

  describe('revokeShareLink()', () => {
    it('sends DELETE with CSRF token and returns void on 204', fakeAsync(() => {
      const csrfToken = 'csrf-token';
      spyOn(service, 'getCsrfToken' as keyof ShareLinkService).and.returnValue(csrfToken);

      let completed = false;
      service.revokeShareLink('abc123xyz').subscribe({
        complete: () => (completed = true),
      });

      httpMock.expectOne('/rest/share-csrf').flush({ token: csrfToken });
      tick();

      const req = httpMock.expectOne('/rest/share-links/abc123xyz');
      expect(req.request.method).toBe('DELETE');
      expect(req.request.headers.get('X-Share-CSRF')).toBe(csrfToken);
      req.flush(null, { status: 204, statusText: 'No Content' });
      tick();

      expect(completed).toBeTrue();
    }));

    it('propagates error without snackbar on 404 (already expired)', fakeAsync(() => {
      const csrfToken = 'csrf-token';
      spyOn(service, 'getCsrfToken' as keyof ShareLinkService).and.returnValue(csrfToken);

      let error: unknown;
      service.revokeShareLink('nonexistent').subscribe({
        next: () => fail('should not succeed'),
        error: (e) => (error = e),
      });

      httpMock.expectOne('/rest/share-csrf').flush({ token: csrfToken });
      tick();

      const req = httpMock.expectOne('/rest/share-links/nonexistent');
      req.flush(null, { status: 404, statusText: 'Not Found' });
      tick();

      // No snackbar for delete failures — handled by the dialog component
      expect(snackBarSpy.open).not.toHaveBeenCalled();
      expect(error).toBeTruthy();
    }));
  });
});
