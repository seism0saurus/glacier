import { Routes } from '@angular/router';
import { ShareLinkGuard } from './guards/share-link.guard';

/**
 * Lazy-loaded routes for the share feature module.
 *
 * Route structure:
 *   /share/:shareId          → ReadonlyWallComponent (guarded by ShareLinkGuard)
 *   /share/:shareId/expired  → ShareExpiredComponent (no guard)
 *
 * The guard resolves the shareId via the catalog endpoint; redirects to
 * the /expired sub-route on 404 or non-active state.
 *
 * ADR-SHARE-01: share feature is a distinct lazy-loaded module so the
 * readonly view ships a minimum-surface bundle without iframe/embed code.
 */
export const SHARE_ROUTES: Routes = [
  {
    path: ':shareId',
    canActivate: [ShareLinkGuard],
    loadComponent: () =>
      import('./components/readonly-wall/readonly-wall.component').then(
        (m) => m.ReadonlyWallComponent
      ),
  },
  {
    path: ':shareId/expired',
    loadComponent: () =>
      import('./components/share-expired/share-expired.component').then(
        (m) => m.ShareExpiredComponent
      ),
  },
];
