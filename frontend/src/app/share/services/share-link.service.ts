import { Injectable } from '@angular/core';
import { HttpClient, HttpErrorResponse, HttpHeaders } from '@angular/common/http';
import { MatSnackBar } from '@angular/material/snack-bar';
import { Observable, switchMap, throwError, catchError, map } from 'rxjs';
import {
  ShareLinkCreated,
  ShareLinkEntry,
} from '../model/readonly-toot-view';

/** Response shape of GET /rest/share-csrf */
interface CsrfTokenResponse {
  token: string;
}

/**
 * HTTP client service for the sharer-side share-link operations.
 *
 * Security note (CSRF):
 * The server uses a double-submit cookie pattern: it sets `__Host-shareCsrf`
 * as an HttpOnly cookie and expects the same value echoed in the
 * `X-Share-CSRF` request header.  This service reads the token via
 * `GET /rest/share-csrf` (which returns the cookie value safe for JS) and
 * includes it on all mutating requests.
 *
 * Rate limiting: 429 responses on `createShareLink` surface a snackbar
 * with the i18n key `rate.limited.share.create`.
 *
 * ADR-SHARE-01 / SR-SHARE concerns owned by `secure-tdd-implementer`.
 */
@Injectable({
  providedIn: 'root',
})
export class ShareLinkService {

  constructor(
    private http: HttpClient,
    private snackBar: MatSnackBar,
  ) {}

  /**
   * Reads the CSRF token value from the JS-readable cookie or session.
   *
   * In production the token comes from the `/rest/share-csrf` endpoint
   * (which reads the HttpOnly cookie server-side and returns it in JSON).
   * This method is a hook point to allow test spying.
   */
  getCsrfToken(): string | null {
    // In the browser the token is fetched from the endpoint; this method is
    // overridden in tests via jasmine.spyOn.  The actual value is always
    // obtained via fetchCsrfToken().
    return null;
  }

  /**
   * Fetches the CSRF token from the server endpoint.  The endpoint reads
   * the `__Host-shareCsrf` cookie (HttpOnly) and returns its value safely.
   */
  private fetchCsrfToken(): Observable<string> {
    return this.http
      .get<CsrfTokenResponse>('/rest/share-csrf')
      .pipe(map((res) => res.token));
  }

  /**
   * Creates a new share link for the authenticated sharer.
   *
   * Flow:
   * 1. Fetch CSRF token via GET /rest/share-csrf
   * 2. POST /rest/share-links with `X-Share-CSRF` header
   *
   * On 429: shows rate-limit snackbar; rethrows error.
   */
  createShareLink(): Observable<ShareLinkCreated> {
    return this.fetchCsrfToken().pipe(
      switchMap((token) => {
        const headers = new HttpHeaders({ 'X-Share-CSRF': token });
        return this.http
          .post<ShareLinkCreated>('/rest/share-links', {}, { headers })
          .pipe(
            catchError((err: HttpErrorResponse) => {
              if (err.status === 429) {
                this.snackBar.open(
                  $localize`:rate.limited.share.create@@rate.limited.share.create:Zu viele geteilte Links. Bitte später erneut versuchen.`,
                  $localize`:@@snackbar.dismiss:Schließen`,
                  { duration: 6000 },
                );
              }
              return throwError(() => err);
            }),
          );
      }),
    );
  }

  /**
   * Lists all active share links for the authenticated sharer.
   */
  listShareLinks(): Observable<ShareLinkEntry[]> {
    return this.http.get<ShareLinkEntry[]>('/rest/share-links');
  }

  /**
   * Revokes a share link by its ID.
   *
   * On success: completes.
   * On 404: the caller (ShareDialogComponent) shows "already expired" message.
   * Other errors: re-thrown to the caller.
   *
   * @param shareLinkId - The opaque share link ID token.
   */
  revokeShareLink(shareLinkId: string): Observable<void> {
    return this.fetchCsrfToken().pipe(
      switchMap((token) => {
        const headers = new HttpHeaders({ 'X-Share-CSRF': token });
        return this.http
          .delete<void>(`/rest/share-links/${shareLinkId}`, { headers });
      }),
    );
  }
}
