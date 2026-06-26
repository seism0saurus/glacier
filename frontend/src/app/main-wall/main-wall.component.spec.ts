import {TestBed} from '@angular/core/testing';
import {MainWallComponent} from './main-wall.component';
import {WallComponent} from "../wall/wall.component";
import {HeaderComponent} from "../header/header.component";
import {HashtagComponent} from "../hashtag/hashtag.component";
import {FooterComponent} from "../footer/footer.component";
import {TootComponent} from "../toot/toot.component";
import { provideHttpClientTesting } from "@angular/common/http/testing";
import {BrowserModule} from "@angular/platform-browser";
import {FormsModule, ReactiveFormsModule} from "@angular/forms";
import {MatFormField} from "@angular/material/form-field";
import {MatChipGrid, MatChipInput, MatChipRemove, MatChipRow} from "@angular/material/chips";
import {MatIcon} from "@angular/material/icon";
import {BrowserAnimationsModule} from "@angular/platform-browser/animations";
import {MatInputModule} from "@angular/material/input";
import {ResourceUrlSanitizerPipe} from "../wall/resource-url-sanitizer.pipe";
import {MatGridList, MatGridTile} from "@angular/material/grid-list";
import { provideHttpClient, withInterceptorsFromDi } from '@angular/common/http';
import {MigrationBannerComponent} from "../migration-banner/migration-banner.component";
import {MatCardModule} from "@angular/material/card";
import {MatButtonModule} from "@angular/material/button";
import {NgIf} from "@angular/common";
import {MatProgressSpinnerModule} from "@angular/material/progress-spinner";
import {MatTooltipModule} from "@angular/material/tooltip";
import {MatSnackBarModule} from "@angular/material/snack-bar";
import {ConnectionStatusComponent} from "../connection-status/connection-status.component";
import {FallbackService} from "../fallback/fallback.service";
import {TransportMode} from "../fallback/transport-mode";
import {BehaviorSubject, Subject} from "rxjs";
import {MatMenuModule} from "@angular/material/menu";
import {MatIconModule} from "@angular/material/icon";
import {MatSnackBar} from "@angular/material/snack-bar";

describe('MainWallComponent', () => {
  let eventsSubject: Subject<any>;

  beforeEach(() => {
    const modeSubject = new BehaviorSubject<TransportMode>(TransportMode.WEBSOCKET);
    eventsSubject = new Subject<any>();
    const fallbackServiceSpy = jasmine.createSpyObj<FallbackService>(
      'FallbackService', [],
      {transportMode$: modeSubject.asObservable(), events$: eventsSubject.asObservable()}
    );

    TestBed.configureTestingModule({
    declarations: [
        MainWallComponent,
        WallComponent,
        HeaderComponent,
        HashtagComponent,
        FooterComponent,
        TootComponent,
        MigrationBannerComponent,
        ConnectionStatusComponent,
    ],
    imports: [BrowserModule,
        FormsModule,
        MatFormField,
        MatChipGrid,
        MatChipRow,
        MatIcon,
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
        MatCardModule,
        MatButtonModule,
        NgIf,
        MatProgressSpinnerModule,
        MatTooltipModule,
        MatSnackBarModule,
        MatMenuModule,
        MatIconModule,
    ],
    providers: [
        provideHttpClient(withInterceptorsFromDi()),
        provideHttpClientTesting(),
        {provide: FallbackService, useValue: fallbackServiceSpy},
    ]
  });
  });

  it('should create the main wall', () => {
    const fixture = TestBed.createComponent(MainWallComponent);
    const app = fixture.componentInstance;
    expect(app).toBeTruthy();
  });

  it('should contain a header component for title and logo', () => {
    const fixture = TestBed.createComponent(MainWallComponent);
    fixture.detectChanges();
    const compiled = fixture.nativeElement as HTMLElement;
    expect(compiled.querySelector('app-header')).toBeTruthy();
  });

  it('should contain a hashtag component for selecting hashtags', () => {
    const fixture = TestBed.createComponent(MainWallComponent);
    fixture.detectChanges();
    const compiled = fixture.nativeElement as HTMLElement;
    expect(compiled.querySelector('app-hashtag')).toBeTruthy();
  });

  it('should contain a wall component to show toots', () => {
    const fixture = TestBed.createComponent(MainWallComponent);
    fixture.detectChanges();
    const compiled = fixture.nativeElement as HTMLElement;
    expect(compiled.querySelector('app-wall')).toBeTruthy();
  });

  it('should contain a footer component for copyright and other hints', () => {
    const fixture = TestBed.createComponent(MainWallComponent);
    fixture.detectChanges();
    const compiled = fixture.nativeElement as HTMLElement;
    expect(compiled.querySelector('app-footer')).toBeTruthy();
  });

  // SHELL-01: <main> landmark must wrap the wall content
  it('SHELL-01: should contain a <main> landmark element', () => {
    const fixture = TestBed.createComponent(MainWallComponent);
    fixture.detectChanges();
    const compiled = fixture.nativeElement as HTMLElement;
    const main = compiled.querySelector('main');
    expect(main).withContext('<main> landmark must be present for keyboard/AT navigation').toBeTruthy();
  });

  it('SHELL-01: <main> landmark must have id="main-content"', () => {
    const fixture = TestBed.createComponent(MainWallComponent);
    fixture.detectChanges();
    const compiled = fixture.nativeElement as HTMLElement;
    const main = compiled.querySelector('main');
    expect(main?.id).withContext('<main> id must be "main-content" for skip link target').toBe('main-content');
  });

  it('SHELL-01: <main> landmark must have tabindex="-1" to receive programmatic focus', () => {
    const fixture = TestBed.createComponent(MainWallComponent);
    fixture.detectChanges();
    const compiled = fixture.nativeElement as HTMLElement;
    const main = compiled.querySelector('main');
    expect(main?.getAttribute('tabindex'))
      .withContext('<main> must have tabindex="-1" so skip link activation moves focus')
      .toBe('-1');
  });

  it('SHELL-01: <main> landmark must contain app-wall', () => {
    const fixture = TestBed.createComponent(MainWallComponent);
    fixture.detectChanges();
    const compiled = fixture.nativeElement as HTMLElement;
    const main = compiled.querySelector('main');
    const wall = main?.querySelector('app-wall');
    expect(wall).withContext('<main> must wrap app-wall').toBeTruthy();
  });

  // SHELL-02: Skip link must target #main-content (the new <main>)
  it('SHELL-02: should have a skip link as the first child pointing to #main-content', () => {
    const fixture = TestBed.createComponent(MainWallComponent);
    fixture.detectChanges();
    const compiled = fixture.nativeElement as HTMLElement;
    const skipLink = compiled.querySelector('.skip-link') as HTMLAnchorElement | null;
    expect(skipLink).withContext('skip link element must exist').toBeTruthy();
    expect(skipLink?.getAttribute('href'))
      .withContext('skip link must point to the <main> landmark (#main-content)')
      .toBe('#main-content');
  });

  it('should render the skip link before the header (first interactive element)', () => {
    const fixture = TestBed.createComponent(MainWallComponent);
    fixture.detectChanges();
    const compiled = fixture.nativeElement as HTMLElement;
    // The skip link must precede app-header in document order so keyboard
    // users encounter it first when navigating with Tab.
    const children = Array.from(compiled.children);
    const skipLinkIndex = children.findIndex(el => el.classList.contains('skip-link'));
    const headerIndex = children.findIndex(el => el.tagName.toLowerCase() === 'app-header');
    expect(skipLinkIndex).withContext('skip link must be found').toBeGreaterThanOrEqual(0);
    expect(skipLinkIndex).withContext('skip link must precede header').toBeLessThan(headerIndex);
  });

  // ---------------------------------------------------------------------------
  // FallbackService event wiring (D-16 / D-17) — previously 0% covered: the spec
  // only asserted DOM structure and never pushed an event through events$.
  // ---------------------------------------------------------------------------

  describe('fallback event handling', () => {
    it('SessionExpired event sets the session-expired banner flag (D-17)', () => {
      const fixture = TestBed.createComponent(MainWallComponent);
      fixture.detectChanges(); // ngOnInit subscribes
      expect(fixture.componentInstance.sessionExpired).toBeFalse();

      eventsSubject.next({type: 'SessionExpired'});

      expect(fixture.componentInstance.sessionExpired).toBeTrue();
    });

    it('GapObserved opens a gap snackbar, and debounces repeats per hashtag (D-16)', () => {
      const fixture = TestBed.createComponent(MainWallComponent);
      const snackBar = TestBed.inject(MatSnackBar);
      const dismissed = new Subject<any>();
      const openSpy = spyOn(snackBar, 'open').and.returnValue(
        {afterDismissed: () => dismissed.asObservable()} as any);
      fixture.detectChanges();

      eventsSubject.next({type: 'GapObserved', hashtag: 'java'});
      eventsSubject.next({type: 'GapObserved', hashtag: 'java'}); // same hashtag — suppressed

      expect(openSpy).toHaveBeenCalledTimes(1);

      // After the first snackbar is dismissed, a fresh gap for the same hashtag re-toasts.
      dismissed.next({});
      eventsSubject.next({type: 'GapObserved', hashtag: 'java'});
      expect(openSpy).toHaveBeenCalledTimes(2);
    });

    it('RateLimited with retryAfterSeconds > 60 shows a countdown message (D-17)', () => {
      const fixture = TestBed.createComponent(MainWallComponent);
      const snackBar = TestBed.inject(MatSnackBar);
      const openSpy = spyOn(snackBar, 'open').and.stub();
      fixture.detectChanges();

      eventsSubject.next({type: 'RateLimited', retryAfterSeconds: 120});

      expect(openSpy).toHaveBeenCalledTimes(1);
      expect(openSpy.calls.mostRecent().args[0]).toContain('120');
    });

    it('RateLimited without a long retry shows the generic message (no countdown)', () => {
      const fixture = TestBed.createComponent(MainWallComponent);
      const snackBar = TestBed.inject(MatSnackBar);
      const openSpy = spyOn(snackBar, 'open').and.stub();
      fixture.detectChanges();

      eventsSubject.next({type: 'RateLimited'}); // retryAfterSeconds undefined

      expect(openSpy).toHaveBeenCalledTimes(1);
      expect(openSpy.calls.mostRecent().args[0]).not.toContain('undefined');
    });

    it('KillSwitched / WsRestored events are no-ops here (handled by ConnectionStatus)', () => {
      const fixture = TestBed.createComponent(MainWallComponent);
      const snackBar = TestBed.inject(MatSnackBar);
      const openSpy = spyOn(snackBar, 'open').and.stub();
      fixture.detectChanges();

      eventsSubject.next({type: 'KillSwitched'});
      eventsSubject.next({type: 'WsRestored'});

      expect(openSpy).not.toHaveBeenCalled();
      expect(fixture.componentInstance.sessionExpired).toBeFalse();
    });
  });
});
