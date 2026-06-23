import {Component} from '@angular/core';

/**
 * Root application shell.
 *
 * <p>Hosts the router outlet only. The main social wall lives at the default route
 * (`''` → {@link MainWallComponent}); the dedicated readonly share view is served at
 * `/share/:shareId` (see SHARE_ROUTES wired in AppModule). Keeping the shell empty
 * ensures the share view does not inherit the main-wall chrome (header / hashtag
 * panel / footer) or its fallback/streaming wiring.
 */
@Component({
  selector: 'app-root',
  template: '<router-outlet></router-outlet>',
  standalone: false,
})
export class AppComponent {
}
