import { Injectable } from '@angular/core';
import {
  ActivatedRouteSnapshot,
  CanActivate,
  Router,
  RouterStateSnapshot,
  UrlTree,
} from '@angular/router';
import { HttpClient } from '@angular/common/http';
import { Observable, of } from 'rxjs';
import { catchError, map } from 'rxjs/operators';
import { ShareCatalog } from '../model/readonly-toot-view';

/**
 * Route guard for the readonly wall.
 *
 * Resolves the shareId from the route params and calls
 * `GET /rest/share/{shareId}/catalog` to check liveness.
 *
 * - On 404 or `state !== 'active'`: redirects to `/share/:shareId/expired`.
 * - On success: allows navigation.
 *
 * Note: This is a lightweight canActivate check only — the actual catalog
 * data is loaded by ReadonlyWallService on component init to avoid holding
 * the response between guard and component.
 */
@Injectable({
  providedIn: 'root',
})
export class ShareLinkGuard implements CanActivate {

  constructor(
    private http: HttpClient,
    private router: Router,
  ) {}

  canActivate(
    route: ActivatedRouteSnapshot,
    _state: RouterStateSnapshot,
  ): Observable<boolean | UrlTree> {
    const shareId = route.paramMap.get('shareId');

    if (!shareId) {
      return of(this.router.createUrlTree(['/']));
    }

    return this.http
      .get<ShareCatalog>(`/rest/share/${shareId}/catalog`)
      .pipe(
        map((catalog) => {
          if (catalog.state === 'active') {
            return true;
          }
          return this.router.createUrlTree(['/share', shareId, 'expired']);
        }),
        catchError(() => {
          return of(this.router.createUrlTree(['/share', shareId, 'expired']));
        }),
      );
  }
}
