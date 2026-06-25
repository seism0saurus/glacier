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
// VIEW-01: expired$ signal tests use this import
// (no additional imports required — Subject is already imported above)

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

    // FLAW-1 / ADR-RENDER-03: catalog with initialToots:[] must not throw and
    // must emit an empty array on toots$ so the live-toot path fills the wall.
    it('service_initialize_populatesHashtagsAndEmptyToots — catalog with initialToots:[] populates hashtags, emits empty toots$, no throw', fakeAsync(() => {
      const emptyCatalog: ShareCatalog = {
        ...mockCatalog,
        hashtags: ['cats'],
        initialToots: [],
      };

      let threwError = false;
      let capturedToots: ReadonlyTootView[] | undefined;
      let catalogLoaded = false;

      // Subscribe before initialize() fires so we catch the emission
      service.toots$.subscribe((t) => (capturedToots = t));
      service.catalogLoaded$.subscribe((loaded) => (catalogLoaded = loaded));

      // Must not throw during or after the HTTP response
      expect(() => {
        service.initialize(SHARE_ID);
      }).not.toThrow();

      const req = httpMock.expectOne(`/rest/share/${SHARE_ID}/catalog`);
      expect(() => {
        req.flush(emptyCatalog);
      }).not.toThrow();

      tick();

      // Catalog must be loaded
      expect(catalogLoaded).withContext('catalogLoaded$ must emit true').toBeTrue();
      // Hashtags must be set from the catalog
      expect(service.hashtags).withContext('hashtags must be populated from catalog').toEqual(['cats']);
      // toots$ must have emitted [] (not undefined, not thrown)
      expect(capturedToots).withContext('toots$ must have emitted').toBeDefined();
      expect(capturedToots!.length).withContext('toots$ must be empty (no initial toots)').toBe(0);
      expect(threwError).withContext('no error must be thrown').toBeFalse();
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

  // ---- VIEW-01: fallback polling expiry disambiguation ----
  // Security requirement: a 404 on /rest/share/{id}/messages during fallback
  // polling must be disambiguated before declaring the link expired.
  // - If catalog confirms the link is not-active (404) → emit expired$ and stop polling.
  // - If catalog confirms the link is still active (200) → killswitch is on; keep polling.
  // - Transient errors (500/network) → keep polling, no expiry.
  // OWASP A01: access-control enforcement; glacier-fallback-mode-discipline invariant.

  describe('VIEW-01 expiry disambiguation during fallback polling', () => {
    it('exposes an expired$ observable on the service', () => {
      // expired$ must exist as a readable Observable so the component can subscribe
      expect(service.expired$).toBeDefined();
      expect(typeof service.expired$.subscribe).toBe('function');
    });

    it('VIEW-01 poll_404_active_catalog — messages 404 when catalog says active does NOT emit expired$ (killswitch)', fakeAsync(() => {
      // Arrange: initialize with an active catalog
      service.initialize(SHARE_ID);
      const catalogReq = httpMock.expectOne(`/rest/share/${SHARE_ID}/catalog`);
      catalogReq.flush(mockCatalog); // state=active
      tick();

      service.startFallbackPolling();

      let expiredEmitted = false;
      service.expired$.subscribe(() => { expiredEmitted = true; });

      // First poll fires after 5 s
      tick(5000);

      // messages endpoint returns 404 (killswitch scenario)
      const msgReq = httpMock.expectOne(
        (req) => req.url.startsWith(`/rest/share/${SHARE_ID}/messages`)
      );
      msgReq.flush(null, { status: 404, statusText: 'Not Found' });
      tick();

      // Disambiguation: service re-fetches catalog — catalog says active (200)
      const catalogCheck = httpMock.expectOne(`/rest/share/${SHARE_ID}/catalog`);
      catalogCheck.flush(mockCatalog); // state=active → killswitch, NOT expiry
      tick();

      // Must NOT have emitted expiry — link is still active
      expect(expiredEmitted).toBeFalse();

      discardPeriodicTasks();
    }));

    it('VIEW-01 poll_404_inactive_catalog — messages 404 when catalog says not-active emits expired$ once and stops polling', fakeAsync(() => {
      // Arrange
      service.initialize(SHARE_ID);
      const catalogReq = httpMock.expectOne(`/rest/share/${SHARE_ID}/catalog`);
      catalogReq.flush(mockCatalog);
      tick();

      service.startFallbackPolling();

      let expiredCount = 0;
      service.expired$.subscribe(() => { expiredCount++; });

      // First poll fires
      tick(5000);

      const msgReq = httpMock.expectOne(
        (req) => req.url.startsWith(`/rest/share/${SHARE_ID}/messages`)
      );
      msgReq.flush(null, { status: 404, statusText: 'Not Found' });
      tick();

      // Catalog check — catalog also returns 404 (link revoked/expired)
      const catalogCheck = httpMock.expectOne(`/rest/share/${SHARE_ID}/catalog`);
      catalogCheck.flush(null, { status: 404, statusText: 'Not Found' });
      tick();

      expect(expiredCount).toBe(1);

      // Polling must have stopped — a second tick should produce NO new poll request
      tick(5000);
      httpMock.expectNone((req) => req.url.startsWith(`/rest/share/${SHARE_ID}/messages`));
    }));

    it('VIEW-01 poll_404_emits_exactly_once — expired$ emits only once even if polling fires multiple 404s', fakeAsync(() => {
      service.initialize(SHARE_ID);
      const catalogReq = httpMock.expectOne(`/rest/share/${SHARE_ID}/catalog`);
      catalogReq.flush(mockCatalog);
      tick();

      service.startFallbackPolling();

      let expiredCount = 0;
      service.expired$.subscribe(() => { expiredCount++; });

      // First poll — 404 → catalog confirms revoked
      tick(5000);
      httpMock.expectOne((req) => req.url.startsWith(`/rest/share/${SHARE_ID}/messages`))
        .flush(null, { status: 404, statusText: 'Not Found' });
      tick();
      httpMock.expectOne(`/rest/share/${SHARE_ID}/catalog`)
        .flush(null, { status: 404, statusText: 'Not Found' });
      tick();

      // Polling has stopped — second tick must produce nothing
      tick(5000);
      httpMock.expectNone((req) => req.url.startsWith(`/rest/share/${SHARE_ID}/messages`));
      expect(expiredCount).toBe(1);
    }));

    it('VIEW-01 poll_500_keeps_polling — transient 500 does NOT emit expired$ and keeps polling', fakeAsync(() => {
      service.initialize(SHARE_ID);
      const catalogReq = httpMock.expectOne(`/rest/share/${SHARE_ID}/catalog`);
      catalogReq.flush(mockCatalog);
      tick();

      service.startFallbackPolling();

      let expiredEmitted = false;
      service.expired$.subscribe(() => { expiredEmitted = true; });

      // First poll → transient server error
      tick(5000);
      httpMock.expectOne((req) => req.url.startsWith(`/rest/share/${SHARE_ID}/messages`))
        .flush(null, { status: 500, statusText: 'Internal Server Error' });
      tick();

      // No catalog disambiguation request for 5xx
      httpMock.expectNone(`/rest/share/${SHARE_ID}/catalog`);

      // Polling continues — second poll fires
      tick(5000);
      const secondPoll = httpMock.match(
        (req) => req.url.startsWith(`/rest/share/${SHARE_ID}/messages`)
      );
      expect(secondPoll.length).toBe(1);
      secondPoll[0].flush([]);
      tick();

      expect(expiredEmitted).toBeFalse();

      discardPeriodicTasks();
    }));

    it('VIEW-01 poll_network_error_keeps_polling — network error does NOT emit expired$ and keeps polling', fakeAsync(() => {
      service.initialize(SHARE_ID);
      const catalogReq = httpMock.expectOne(`/rest/share/${SHARE_ID}/catalog`);
      catalogReq.flush(mockCatalog);
      tick();

      service.startFallbackPolling();

      let expiredEmitted = false;
      service.expired$.subscribe(() => { expiredEmitted = true; });

      // First poll → network error
      tick(5000);
      httpMock.expectOne((req) => req.url.startsWith(`/rest/share/${SHARE_ID}/messages`))
        .error(new ProgressEvent('error'));
      tick();

      // Polling continues — second poll fires
      tick(5000);
      const secondPoll = httpMock.match(
        (req) => req.url.startsWith(`/rest/share/${SHARE_ID}/messages`)
      );
      expect(secondPoll.length).toBe(1);
      secondPoll[0].flush([]);
      tick();

      expect(expiredEmitted).toBeFalse();

      discardPeriodicTasks();
    }));

    it('VIEW-01 expired$_completes_on_destroy — expired$ completes when destroy() is called', fakeAsync(() => {
      service.initialize(SHARE_ID);
      const catalogReq = httpMock.expectOne(`/rest/share/${SHARE_ID}/catalog`);
      catalogReq.flush(mockCatalog);
      tick();

      let completed = false;
      service.expired$.subscribe({ complete: () => { completed = true; } });

      service.destroy();

      expect(completed).toBeTrue();
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
