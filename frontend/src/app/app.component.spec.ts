import {TestBed} from '@angular/core/testing';
import {AppComponent} from './app.component';
import {WallComponent} from "./wall/wall.component";
import {HeaderComponent} from "./header/header.component";
import {HashtagComponent} from "./hashtag/hashtag.component";
import {FooterComponent} from "./footer/footer.component";
import {TootComponent} from "./toot/toot.component";
import { provideHttpClientTesting } from "@angular/common/http/testing";
import {BrowserModule} from "@angular/platform-browser";
import {FormsModule, ReactiveFormsModule} from "@angular/forms";
import {MatFormField} from "@angular/material/form-field";
import {MatChipGrid, MatChipInput, MatChipRemove, MatChipRow} from "@angular/material/chips";
import {MatIcon} from "@angular/material/icon";
import {BrowserAnimationsModule} from "@angular/platform-browser/animations";
import {MatInputModule} from "@angular/material/input";
import {ResourceUrlSanitizerPipe} from "./wall/resource-url-sanitizer.pipe";
import {MatGridList, MatGridTile} from "@angular/material/grid-list";
import { provideHttpClient, withInterceptorsFromDi } from '@angular/common/http';
import {MigrationBannerComponent} from "./migration-banner/migration-banner.component";
import {MatCardModule} from "@angular/material/card";
import {MatButtonModule} from "@angular/material/button";
import {NgIf} from "@angular/common";
import {MatProgressSpinnerModule} from "@angular/material/progress-spinner";
import {MatTooltipModule} from "@angular/material/tooltip";
import {MatSnackBarModule} from "@angular/material/snack-bar";
import {ConnectionStatusComponent} from "./connection-status/connection-status.component";
import {FallbackService} from "./fallback/fallback.service";
import {TransportMode} from "./fallback/transport-mode";
import {BehaviorSubject, Subject} from "rxjs";
import {MatMenuModule} from "@angular/material/menu";
import {MatIconModule} from "@angular/material/icon";

describe('AppComponent', () => {
  beforeEach(() => {
    const modeSubject = new BehaviorSubject<TransportMode>(TransportMode.WEBSOCKET);
    const eventsSubject = new Subject<any>();
    const fallbackServiceSpy = jasmine.createSpyObj<FallbackService>(
      'FallbackService', [],
      {transportMode$: modeSubject.asObservable(), events$: eventsSubject.asObservable()}
    );

    TestBed.configureTestingModule({
    declarations: [
        AppComponent,
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

  it('should create the app', () => {
    const fixture = TestBed.createComponent(AppComponent);
    const app = fixture.componentInstance;
    expect(app).toBeTruthy();
  });

  it('should contain a header component for title and logo', () => {
    const fixture = TestBed.createComponent(AppComponent);
    fixture.detectChanges();
    const compiled = fixture.nativeElement as HTMLElement;
    expect(compiled.querySelector('app-header')).toBeTruthy();
  });

  it('should contain a hashtag component for selecting hashtags', () => {
    const fixture = TestBed.createComponent(AppComponent);
    fixture.detectChanges();
    const compiled = fixture.nativeElement as HTMLElement;
    expect(compiled.querySelector('app-hashtag')).toBeTruthy();
  });

  it('should contain a wall component to show toots', () => {
    const fixture = TestBed.createComponent(AppComponent);
    fixture.detectChanges();
    const compiled = fixture.nativeElement as HTMLElement;
    expect(compiled.querySelector('app-wall')).toBeTruthy();
  });

  it('should contain a footer component for copyright and other hints', () => {
    const fixture = TestBed.createComponent(AppComponent);
    fixture.detectChanges();
    const compiled = fixture.nativeElement as HTMLElement;
    expect(compiled.querySelector('app-footer')).toBeTruthy();
  });

  // A11Y-F-04: Skip link
  it('should have a skip link as the first child pointing to #toots', () => {
    const fixture = TestBed.createComponent(AppComponent);
    fixture.detectChanges();
    const compiled = fixture.nativeElement as HTMLElement;
    const skipLink = compiled.querySelector('.skip-link') as HTMLAnchorElement | null;
    expect(skipLink).withContext('skip link element must exist').toBeTruthy();
    expect(skipLink?.getAttribute('href'))
      .withContext('skip link must point to the main toot feed')
      .toBe('#toots');
  });

  it('should render the skip link before the header (first interactive element)', () => {
    const fixture = TestBed.createComponent(AppComponent);
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
});
