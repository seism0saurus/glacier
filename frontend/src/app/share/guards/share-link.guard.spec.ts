import { TestBed } from '@angular/core/testing';
import {
  HttpClientTestingModule,
  HttpTestingController,
} from '@angular/common/http/testing';
import {
  ActivatedRouteSnapshot,
  Router,
  RouterStateSnapshot,
  UrlTree,
} from '@angular/router';
import { RouterTestingModule } from '@angular/router/testing';

import { ShareLinkGuard } from './share-link.guard';
import { ShareCatalog } from '../model/readonly-toot-view';

/**
 * Unit tests for {@link ShareLinkGuard}.
 *
 * <p>The guard is a thin adapter: it calls
 * {@code GET /rest/share/{shareId}/catalog} and converts the HTTP result into
 * either {@code true} (allow) or a {@link UrlTree} redirect (deny).  These
 * tests verify every branch of that decision tree without requiring a real
 * backend.
 *
 * <p>Test strategy: {@link HttpClientTestingModule} intercepts the outgoing
 * HTTP requests so assertions run against real HTTP serialisation without a
 * live server.  {@link RouterTestingModule} supplies the {@link Router} needed
 * to construct {@link UrlTree} instances.
 */
describe('ShareLinkGuard', () => {
  let guard: ShareLinkGuard;
  let httpController: HttpTestingController;
  let router: Router;

  /**
   * Builds a minimal {@link ActivatedRouteSnapshot} that returns the given
   * {@code shareId} from {@code paramMap.get('shareId')}.
   */
  function routeSnapshotWith(shareId: string | null): ActivatedRouteSnapshot {
    const snapshot = jasmine.createSpyObj<ActivatedRouteSnapshot>(
      'ActivatedRouteSnapshot',
      [],
      { paramMap: jasmine.createSpyObj('ParamMap', ['get']) },
    );
    (snapshot.paramMap.get as jasmine.Spy).and.returnValue(shareId);
    return snapshot;
  }

  /** Sentinel — the RouterStateSnapshot argument is ignored by the guard. */
  const dummyState = {} as RouterStateSnapshot;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [HttpClientTestingModule, RouterTestingModule],
      providers: [ShareLinkGuard],
    });

    guard = TestBed.inject(ShareLinkGuard);
    httpController = TestBed.inject(HttpTestingController);
    router = TestBed.inject(Router);
  });

  afterEach(() => {
    // Verify that no unmatched requests are pending after each test.
    httpController.verify();
  });

  // -----------------------------------------------------------------------
  // Happy path — active catalog
  // -----------------------------------------------------------------------

  /**
   * When the catalog endpoint returns state === 'active', the guard must
   * resolve to {@code true} so Angular proceeds with the route activation.
   *
   * <p>Arrange: server returns a catalog with {@code state: 'active'}.
   * <p>Act:     call {@code canActivate}.
   * <p>Assert:  observable emits {@code true}.
   */
  it('canActivate_returnsTrue_onActiveCatalog', (done) => {
    const shareId = 'share-abc-123';
    const route = routeSnapshotWith(shareId);

    const activeCatalog: ShareCatalog = {
      shareId,
      state: 'active',
      hashtags: ['cats'],
      initialToots: [],
      expiresAt: '2099-01-01T00:00:00Z',
    };

    guard.canActivate(route, dummyState).subscribe((result) => {
      expect(result).toBeTrue();
      done();
    });

    httpController
      .expectOne(`/rest/share/${shareId}/catalog`)
      .flush(activeCatalog);
  });

  // -----------------------------------------------------------------------
  // Non-active catalog — redirect to /share/:shareId/expired
  // -----------------------------------------------------------------------

  /**
   * When the catalog endpoint returns {@code state !== 'active'} (e.g.
   * 'expired'), the guard must redirect to {@code /share/:shareId/expired}
   * rather than blocking the user with an error.
   *
   * <p>Arrange: server returns a catalog with {@code state: 'expired'}.
   * <p>Act:     call {@code canActivate}.
   * <p>Assert:  observable emits a {@link UrlTree} pointing to the expired page.
   */
  it('canActivate_redirectsToExpired_onStateNotActive', (done) => {
    const shareId = 'share-expired-456';
    const route = routeSnapshotWith(shareId);

    const expiredCatalog: ShareCatalog = {
      shareId,
      state: 'expired',
      hashtags: [],
      initialToots: [],
      expiresAt: '2020-01-01T00:00:00Z',
    };

    guard.canActivate(route, dummyState).subscribe((result) => {
      expect(result instanceof UrlTree).toBeTrue();
      const urlTree = result as UrlTree;
      expect(urlTree.toString()).toContain(`/share/${shareId}/expired`);
      done();
    });

    httpController
      .expectOne(`/rest/share/${shareId}/catalog`)
      .flush(expiredCatalog);
  });

  // -----------------------------------------------------------------------
  // HTTP 404 — share link not found
  // -----------------------------------------------------------------------

  /**
   * When the catalog endpoint responds with 404 (share link not found or
   * already consumed), the guard's {@code catchError} handler must redirect
   * to {@code /share/:shareId/expired} — the same destination as a non-active
   * catalog — so callers see a consistent UX regardless of the HTTP status.
   *
   * <p>Arrange: server returns HTTP 404.
   * <p>Act:     call {@code canActivate}.
   * <p>Assert:  observable emits a redirect {@link UrlTree} to the expired page.
   */
  it('canActivate_redirectsToExpired_on404', (done) => {
    const shareId = 'share-missing-789';
    const route = routeSnapshotWith(shareId);

    guard.canActivate(route, dummyState).subscribe((result) => {
      expect(result instanceof UrlTree).toBeTrue();
      const urlTree = result as UrlTree;
      expect(urlTree.toString()).toContain(`/share/${shareId}/expired`);
      done();
    });

    httpController
      .expectOne(`/rest/share/${shareId}/catalog`)
      .flush('Not Found', { status: 404, statusText: 'Not Found' });
  });

  // -----------------------------------------------------------------------
  // Network / transport error
  // -----------------------------------------------------------------------

  /**
   * When a network error occurs (e.g. the server is unreachable), the guard
   * must not throw — it must redirect the user to the expired page gracefully,
   * identical to the 404 case.
   *
   * <p>Arrange: HTTP request fails with a network error.
   * <p>Act:     call {@code canActivate}.
   * <p>Assert:  observable emits a redirect {@link UrlTree} to the expired page;
   *             no uncaught exception is raised.
   */
  it('canActivate_redirectsToExpired_onNetworkError', (done) => {
    const shareId = 'share-offline-000';
    const route = routeSnapshotWith(shareId);

    guard.canActivate(route, dummyState).subscribe({
      next: (result) => {
        expect(result instanceof UrlTree).toBeTrue();
        const urlTree = result as UrlTree;
        expect(urlTree.toString()).toContain(`/share/${shareId}/expired`);
        done();
      },
      error: () => {
        fail('Guard must not propagate network errors to the caller');
        done();
      },
    });

    httpController
      .expectOne(`/rest/share/${shareId}/catalog`)
      .error(new ProgressEvent('network'));
  });

  // -----------------------------------------------------------------------
  // Missing shareId — redirect to root
  // -----------------------------------------------------------------------

  /**
   * When the route does not carry a {@code shareId} parameter (e.g. a
   * misconfigured deep link), the guard must not make any HTTP requests and
   * must redirect the user to {@code /} (the application root).
   *
   * <p>Arrange: route snapshot returns {@code null} for 'shareId'.
   * <p>Act:     call {@code canActivate}.
   * <p>Assert:  observable emits a {@link UrlTree} pointing to '/' with no
   *             outgoing HTTP requests.
   */
  it('canActivate_redirectsToRoot_whenShareIdMissing', (done) => {
    const route = routeSnapshotWith(null);

    guard.canActivate(route, dummyState).subscribe((result) => {
      expect(result instanceof UrlTree).toBeTrue();
      const urlTree = result as UrlTree;
      expect(urlTree.toString()).toBe('/');
      done();
    });

    // No HTTP call must be made when the shareId is absent.
    // HttpTestingController.expectNone accepts a predicate function in Angular 15+.
    httpController.expectNone((req) => req.url.startsWith('/rest/share/'));
  });
});
