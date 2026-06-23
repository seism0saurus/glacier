import {NgModule, DOCUMENT} from '@angular/core';
import {BrowserModule} from '@angular/platform-browser';
import {NgIf} from '@angular/common';
import {RouterModule, Routes} from '@angular/router';

import {AppComponent} from './app.component';
import {MainWallComponent} from './main-wall/main-wall.component';
import {SHARE_ROUTES} from './share/share.routes';
import {RxStompService} from "./rx-stomp.service";
import {rxStompServiceFactory} from "./rx-stomp.factory";
import {WallComponent} from './wall/wall.component';
import {HeaderComponent} from './header/header.component';
import {HashtagComponent} from './hashtag/hashtag.component';
import {FormsModule, ReactiveFormsModule} from "@angular/forms";
import {FooterComponent} from './footer/footer.component';
import {HttpClient, provideHttpClient, withInterceptorsFromDi} from "@angular/common/http";
import {TootComponent} from './toot/toot.component';
import {MatFormField} from "@angular/material/form-field";
import {MatChipGrid, MatChipInput, MatChipRemove, MatChipRow} from "@angular/material/chips";
import {MatIconModule} from "@angular/material/icon";
import {MatInputModule} from "@angular/material/input";
import {BrowserAnimationsModule, ANIMATION_MODULE_TYPE} from "@angular/platform-browser/animations";
import {ResourceUrlSanitizerPipe} from "./wall/resource-url-sanitizer.pipe";
import {CookieService} from "ngx-cookie-service";
import {MatGridList, MatGridTile} from "@angular/material/grid-list";
import {MatDialogModule} from "@angular/material/dialog";

import {MatSnackBarModule} from "@angular/material/snack-bar";
import {MatMenuModule} from "@angular/material/menu";
import {MatButtonModule} from "@angular/material/button";
import {ConnectionStatusComponent} from "./connection-status/connection-status.component";
import {MigrationBannerComponent} from "./migration-banner/migration-banner.component";
import {MatCardModule} from "@angular/material/card";
import {MatProgressSpinnerModule} from "@angular/material/progress-spinner";
import {MatTooltipModule} from "@angular/material/tooltip";
import {AnimationDriver, NoopAnimationDriver} from "@angular/animations/browser";

/**
 * Detects the user's OS-level reduced-motion preference via the Web Animations API.
 *
 * CSS `@media (prefers-reduced-motion: reduce)` rules only suppress CSS-declared
 * animations.  Angular 19's animation engine drives motion via WAAPI (JavaScript),
 * so CSS overrides have no effect on Angular triggers like `@pruneLeave`.
 *
 * By switching to NoopAnimationDriver at the provider level when this media query
 * matches, we ensure the 150 ms opacity fade is suppressed at the engine level —
 * not just visually — honouring the user's vestibular-disorder OS preference.
 *
 * This is evaluated once at module construction time (before any animation plays),
 * which is sufficient because the OS preference cannot change mid-session without
 * a page reload.
 */
const prefersReducedMotion: boolean =
  typeof window !== 'undefined' &&
  window.matchMedia('(prefers-reduced-motion: reduce)').matches;

/**
 * Application routes.
 *
 * - `''`        → the main social wall (header / hashtag panel / streaming wall / footer).
 * - `share/**`  → the dedicated readonly share view (SHARE_ROUTES: `:shareId` and
 *                 `:shareId/expired`). Mounted as children of `share` so the full paths
 *                 are `/share/:shareId` and `/share/:shareId/expired` — matching the
 *                 readonly URL built by ShareLinkController and the share-host routing.
 *
 * The backend forwards `/share/**` deep links to index.html (ShareViewSpaForwardController)
 * so these client routes resolve on a direct navigation / refresh.
 */
const routes: Routes = [
  {path: '', component: MainWallComponent},
  {path: 'share', children: SHARE_ROUTES},
];

@NgModule({
  declarations: [
    AppComponent,
    MainWallComponent,
    WallComponent,
    HeaderComponent,
    HashtagComponent,
    FooterComponent,
    TootComponent,
    ConnectionStatusComponent,
    MigrationBannerComponent,
  ],
  bootstrap: [AppComponent],
  imports: [
    BrowserModule,
    RouterModule.forRoot(routes),
    FormsModule,
    MatFormField,
    MatChipGrid,
    MatChipRow,
    MatIconModule,
    ReactiveFormsModule,
    BrowserAnimationsModule,
    MatInputModule,
    MatChipInput,
    MatChipGrid,
    MatChipRow,
    MatChipRemove,
    ResourceUrlSanitizerPipe,
    MatGridList,
    MatGridTile,
    MatDialogModule,
    MatSnackBarModule,
    MatMenuModule,
    MatButtonModule,
    NgIf,
    MatCardModule,
    MatProgressSpinnerModule,
    MatTooltipModule,
  ],
  providers: [
    CookieService,
    {
      provide: RxStompService,
      useFactory: rxStompServiceFactory,
      deps: [HttpClient, DOCUMENT],
    },
    provideHttpClient(withInterceptorsFromDi()),
    // FIX-1: Suppress Angular WAAPI animations when the user has enabled the
    // OS-level "reduce motion" accessibility preference.
    //
    // BrowserAnimationsModule (above) registers WebAnimationsDriver as the
    // default AnimationDriver.  Since Angular 19 uses the Web Animations API
    // (WAAPI) rather than CSS keyframes, the CSS rule
    //   @media (prefers-reduced-motion: reduce) { animation: none !important }
    // in wall.component.css does NOT suppress the 150 ms @pruneLeave fade.
    //
    // Overriding AnimationDriver with NoopAnimationDriver and setting
    // ANIMATION_MODULE_TYPE to 'NoopAnimations' disables motion at the engine
    // level, which is the correct fix for WAAPI-driven animations.
    //
    // Both overrides are required: ANIMATION_MODULE_TYPE alone only changes the
    // token value (read by AnimationBuilder), while AnimationDriver controls
    // what the animation renderer actually executes.
    ...(prefersReducedMotion
      ? [
          {provide: AnimationDriver, useClass: NoopAnimationDriver},
          {provide: ANIMATION_MODULE_TYPE, useValue: 'NoopAnimations' as const},
        ]
      : []),
  ],
})
export class AppModule {
}
