import { TestBed, fakeAsync, tick, discardPeriodicTasks } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import {
  HttpTestingController,
  provideHttpClientTesting,
} from '@angular/common/http/testing';
import { Router } from '@angular/router';
import { BehaviorSubject, Subject } from 'rxjs';
import { ReadonlyWallService } from './readonly-wall.service';
import { ReadonlyTootView, ShareCatalog } from '../model/readonly-toot-view';
import { PruneResult } from '../../model/wall-message';

/**
 * Unit tests for ReadonlyWallService (viewer side).
 *
 * Key invariants tested:
 * - NEVER stores anything in localStorage (viewer is ephemeral)
 * - NEVER calls /glacier/subscription or /glacier/termination
 * - Handles STOMP revocation control frame → routes to /share/:id/expired
 * - Falls through to HTTP polling when STOMP is unavailable
 * - Polling uses /rest/share/{shareId}/messages
 */
describe('ReadonlyWallService', () => {
  let service: ReadonlyWallService;
  let httpMock: HttpTestingController;
  let routerSpy: jasmine.SpyObj<Router>;

  const SHARE_ID = 'test-share-id-123';

  const mockToot: ReadonlyTootView = {
    id: 'toot-1',
    authorDisplayName: 'Alice',
    authorAcct: '@alice@example.com',
    authorProfileUrl: 'https://example.com/@alice',
    authorAvatarProxyUrl: 'https://example.com/avatar.png',
    createdAt: '2026-04-22T12:00:00Z',
    textContent: 'Hello world',
    spoilerText: '',
    sensitive: false,
    bidiStripped: false,
    links: [],
    mentions: [],
    hashtags: [],
    customEmojis: [],
    media: [],
    poll: null,
    language: 'en',
  };

  const mockCatalog: ShareCatalog = {
    shareId: SHARE_ID,
    state: 'active',
    hashtags: ['glacier', 'a11y'],
    initialToots: [mockToot],
    expiresAt: '2026-04-29T12:00:00Z',
  };

  beforeEach(() => {
    routerSpy = jasmine.createSpyObj<Router>('Router', ['navigate']);
    // Suppress localStorage access to detect any violations
    spyOn(localStorage, 'setItem').and.callThrough();
    spyOn(localStorage, 'getItem').and.callThrough();

    TestBed.configureTestingModule({
      providers: [
        ReadonlyWallService,
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: Router, useValue: routerSpy },
      ],
    });

    service = TestBed.inject(ReadonlyWallService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    service.destroy();
    // Flush any pending requests before verifying.
    // destroy() → stopFallbackPolling() cancels the RxJS subscription which
    // causes in-flight HTTP requests to be cancelled.  We must NOT flush cancelled
    // requests (Angular throws "Cannot flush a cancelled request") — only flush
    // requests that are still outstanding (open).
    const pending = httpMock.match(() => true);
    pending
      .filter((req) => !req.cancelled)
      .forEach((req) => req.flush([]));
    httpMock.verify({ ignoreCancelled: true });
    // Restore localStorage spies
    (localStorage.setItem as jasmine.Spy).calls.reset();
    (localStorage.getItem as jasmine.Spy).calls.reset();
  });

  it('should be created', () => {
    expect(service).toBeTruthy();
  });

  // ---- Critical invariant: no localStorage ----

  describe('localStorage isolation', () => {
    it('NEVER writes to localStorage', fakeAsync(() => {
      service.initialize(SHARE_ID);

      const req = httpMock.expectOne(`/rest/share/${SHARE_ID}/catalog`);
      req.flush(mockCatalog);
      tick();

      // Simulate some toots arriving
      service['handleToot'](mockToot);

      expect(localStorage.setItem).not.toHaveBeenCalled();
    }));

    it('NEVER reads from localStorage', fakeAsync(() => {
      service.initialize(SHARE_ID);

      const req = httpMock.expectOne(`/rest/share/${SHARE_ID}/catalog`);
      req.flush(mockCatalog);
      tick();

      expect(localStorage.getItem).not.toHaveBeenCalled();
    }));
  });

  // ---- Critical invariant: no subscription calls ----

  describe('subscription endpoint isolation', () => {
    it('NEVER calls /glacier/subscription', fakeAsync(() => {
      service.initialize(SHARE_ID);

      const req = httpMock.expectOne(`/rest/share/${SHARE_ID}/catalog`);
      req.flush(mockCatalog);
      tick();

      // verify() would catch any unexpected requests, but also assert explicitly
      const allRequests = httpMock.match(() => true);
      allRequests.forEach((r) => r.flush({}));

      const glacierRequests = allRequests.filter((r) =>
        r.request.url.includes('/glacier/subscription')
      );
      expect(glacierRequests.length).toBe(0);
    }));

    it('NEVER calls /glacier/termination', fakeAsync(() => {
      service.initialize(SHARE_ID);

      const req = httpMock.expectOne(`/rest/share/${SHARE_ID}/catalog`);
      req.flush(mockCatalog);
      tick();

      const allRequests = httpMock.match(() => true);
      allRequests.forEach((r) => r.flush({}));

      const terminationRequests = allRequests.filter((r) =>
        r.request.url.includes('/glacier/termination')
      );
      expect(terminationRequests.length).toBe(0);
    }));
  });

  // ---- Catalog load ----

  describe('initialize()', () => {
    it('loads catalog and populates initial toots', fakeAsync(() => {
      service.initialize(SHARE_ID);

      const req = httpMock.expectOne(`/rest/share/${SHARE_ID}/catalog`);
      expect(req.request.method).toBe('GET');
      req.flush(mockCatalog);
      tick();

      let toots: ReadonlyTootView[] = [];
      service.toots$.subscribe((t) => (toots = t));

      expect(toots.length).toBe(1);
      expect(toots[0].id).toBe('toot-1');
    }));

    it('navigates to /share/:id/expired when catalog returns state=expired', fakeAsync(() => {
      service.initialize(SHARE_ID);

      const req = httpMock.expectOne(`/rest/share/${SHARE_ID}/catalog`);
      req.flush({ ...mockCatalog, state: 'expired' });
      tick();

      expect(routerSpy.navigate).toHaveBeenCalledWith(
        ['/share', SHARE_ID, 'expired']
      );
    }));

    it('navigates to /share/:id/expired when catalog returns 404', fakeAsync(() => {
      service.initialize(SHARE_ID);

      const req = httpMock.expectOne(`/rest/share/${SHARE_ID}/catalog`);
      req.flush(null, { status: 404, statusText: 'Not Found' });
      tick();

      expect(routerSpy.navigate).toHaveBeenCalledWith(
        ['/share', SHARE_ID, 'expired']
      );
    }));

    it('exposes hashtags from catalog', fakeAsync(() => {
      service.initialize(SHARE_ID);

      const req = httpMock.expectOne(`/rest/share/${SHARE_ID}/catalog`);
      req.flush(mockCatalog);
      tick();

      expect(service.hashtags).toEqual(['glacier', 'a11y']);
    }));

    it('exposes expiresAt from catalog', fakeAsync(() => {
      service.initialize(SHARE_ID);

      const req = httpMock.expectOne(`/rest/share/${SHARE_ID}/catalog`);
      req.flush(mockCatalog);
      tick();

      expect(service.expiresAt).toBe('2026-04-29T12:00:00Z');
    }));
  });

  // ---- Revocation frame handling ----

  describe('revocation handling', () => {
    it('routes to /share/:id/expired when control frame type=revoked arrives', fakeAsync(() => {
      service.initialize(SHARE_ID);
      const req = httpMock.expectOne(`/rest/share/${SHARE_ID}/catalog`);
      req.flush(mockCatalog);
      tick();

      // Simulate the service receiving a revocation frame (as if from STOMP/polling)
      service['handleControlFrame']({ type: 'revoked' });

      expect(routerSpy.navigate).toHaveBeenCalledWith(
        ['/share', SHARE_ID, 'expired']
      );
    }));

    it('routes to expired on control frame type=expired', fakeAsync(() => {
      service.initialize(SHARE_ID);
      const req = httpMock.expectOne(`/rest/share/${SHARE_ID}/catalog`);
      req.flush(mockCatalog);
      tick();

      service['handleControlFrame']({ type: 'expired' });

      expect(routerSpy.navigate).toHaveBeenCalledWith(
        ['/share', SHARE_ID, 'expired']
      );
    }));
  });

  // ---- Fallback polling ----

  describe('fallback polling', () => {
    it('polls /rest/share/{id}/messages when startPolling() is called', fakeAsync(() => {
      service.initialize(SHARE_ID);
      const catalogReq = httpMock.expectOne(`/rest/share/${SHARE_ID}/catalog`);
      catalogReq.flush(mockCatalog);
      tick();

      service.startFallbackPolling();

      // Advance 5 seconds to trigger first poll
      tick(5000);

      const pollReq = httpMock.expectOne(
        (req) =>
          req.url.startsWith(`/rest/share/${SHARE_ID}/messages`) &&
          req.method === 'GET'
      );
      expect(pollReq.request.url).toContain(`/rest/share/${SHARE_ID}/messages`);
      pollReq.flush([mockToot]);
      tick();

      discardPeriodicTasks();
    }));

    it('does NOT poll /rest/messages (the main wall endpoint)', fakeAsync(() => {
      service.initialize(SHARE_ID);
      const catalogReq = httpMock.expectOne(`/rest/share/${SHARE_ID}/catalog`);
      catalogReq.flush(mockCatalog);
      tick();

      service.startFallbackPolling();
      tick(5000);

      const allReqs = httpMock.match(() => true);
      const mainWallPollReqs = allReqs.filter((r) =>
        r.request.url === '/rest/messages'
      );
      expect(mainWallPollReqs.length).toBe(0);

      allReqs.forEach((r) => r.flush([]));
      tick();

      discardPeriodicTasks();
    }));

    it('appends new toots from poll to the feed', fakeAsync(() => {
      service.initialize(SHARE_ID);
      const catalogReq = httpMock.expectOne(`/rest/share/${SHARE_ID}/catalog`);
      catalogReq.flush({ ...mockCatalog, initialToots: [] });
      tick();

      service.startFallbackPolling();
      tick(5000);

      const pollReq = httpMock.expectOne(
        (req) => req.url.startsWith(`/rest/share/${SHARE_ID}/messages`)
      );
      pollReq.flush([mockToot]);
      tick();

      let toots: ReadonlyTootView[] = [];
      service.toots$.subscribe((t) => (toots = t));
      expect(toots.length).toBe(1);

      discardPeriodicTasks();
    }));
  });

  // ---- SR-PRUNE-09: Guard-rail — prune operations are no-ops ----

  describe('SR-PRUNE-09: prune guard-rail', () => {
    /**
     * ReadonlyWallService is a viewer-only service for share links.
     * It must never allow prune operations that could be triggered by
     * WebSocket injection or other untrusted sources.
     *
     * pruneByHashtag and pruneByHashtags are explicit no-ops that return
     * an empty PruneResult without modifying toot state.  This prevents
     * an attacker from crafting STOMP termination frames that could clear
     * the viewer's wall.
     */

    it('pruneByHashtag is a no-op and returns empty removed array', fakeAsync(() => {
      service.initialize(SHARE_ID);
      const req = httpMock.expectOne(`/rest/share/${SHARE_ID}/catalog`);
      req.flush(mockCatalog);
      tick();

      // The service starts with one toot in the catalog
      let currentToots: ReadonlyTootView[] = [];
      service.toots$.subscribe((t) => (currentToots = t));
      expect(currentToots.length).toBe(1);

      // Call pruneByHashtag — must be a no-op
      const result: PruneResult = service.pruneByHashtag('glacier');
      expect(result.removed).toEqual([]);
      // The remaining list contains the current toots (not modified)
      expect(currentToots.length).toBe(1);
    }));

    it('pruneByHashtags is a no-op and returns empty removed array', fakeAsync(() => {
      service.initialize(SHARE_ID);
      const req = httpMock.expectOne(`/rest/share/${SHARE_ID}/catalog`);
      req.flush(mockCatalog);
      tick();

      let currentToots: ReadonlyTootView[] = [];
      service.toots$.subscribe((t) => (currentToots = t));
      expect(currentToots.length).toBe(1);

      const result: PruneResult = service.pruneByHashtags(['glacier', 'a11y']);
      expect(result.removed).toEqual([]);
      expect(currentToots.length).toBe(1);
    }));

    it('pruneByHashtag does not mutate toots$ observable', fakeAsync(() => {
      service.initialize(SHARE_ID);
      const req = httpMock.expectOne(`/rest/share/${SHARE_ID}/catalog`);
      req.flush(mockCatalog);
      tick();

      const snapshotBefore: ReadonlyTootView[] = service.toots$.getValue();
      service.pruneByHashtag('glacier');
      const snapshotAfter: ReadonlyTootView[] = service.toots$.getValue();

      // Same reference or same content — toots were not removed
      expect(snapshotAfter.length).toBe(snapshotBefore.length);
    }));

    it('pruneByHashtags does not mutate toots$ observable', fakeAsync(() => {
      service.initialize(SHARE_ID);
      const req = httpMock.expectOne(`/rest/share/${SHARE_ID}/catalog`);
      req.flush(mockCatalog);
      tick();

      const snapshotBefore: ReadonlyTootView[] = service.toots$.getValue();
      service.pruneByHashtags(['glacier', 'a11y', 'foss']);
      const snapshotAfter: ReadonlyTootView[] = service.toots$.getValue();

      expect(snapshotAfter.length).toBe(snapshotBefore.length);
    }));

    it('hashtags[] on the service is set only from the catalog HTTP response, not from handleToot', fakeAsync(() => {
      service.initialize(SHARE_ID);
      const req = httpMock.expectOne(`/rest/share/${SHARE_ID}/catalog`);
      req.flush(mockCatalog);
      tick();

      // Record the hashtags from catalog
      const hashtagsAfterCatalog = [...service.hashtags];
      expect(hashtagsAfterCatalog).toEqual(['glacier', 'a11y']);

      // Calling handleToot with a toot that has a different hashtag reference
      // must NOT modify service.hashtags
      service.handleToot({
        ...mockToot,
        id: 'new-toot-99',
        hashtags: [{ tag: 'injected', searchUrl: 'https://evil.example.com/injected' }],
      });

      // service.hashtags must remain unchanged
      expect(service.hashtags).toEqual(hashtagsAfterCatalog);
    }));

    it('does not write to localStorage even when pruneByHashtag is called', fakeAsync(() => {
      service.initialize(SHARE_ID);
      const req = httpMock.expectOne(`/rest/share/${SHARE_ID}/catalog`);
      req.flush(mockCatalog);
      tick();

      // Reset the spy counter (catalog load itself does not write localStorage)
      (localStorage.setItem as jasmine.Spy).calls.reset();

      service.pruneByHashtag('glacier');

      expect(localStorage.setItem).not.toHaveBeenCalled();
    }));
  });
});
