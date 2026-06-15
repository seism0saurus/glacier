import {
  ComponentFixture,
  TestBed,
  fakeAsync,
  tick,
} from '@angular/core/testing';
import { provideRouter, ActivatedRoute, Router } from '@angular/router';
import { BehaviorSubject, Subject, of } from 'rxjs';
import { ReadonlyWallComponent } from './readonly-wall.component';
import { ReadonlyWallService } from '../../services/readonly-wall.service';
import { ReadonlyWallStompClient } from '../../services/readonly-wall-stomp-client.service';
import { ReadonlyTootView } from '../../model/readonly-toot-view';
import { ViewerTransportMode } from '../../services/viewer-transport-mode';
import { LiveAnnouncer } from '@angular/cdk/a11y';
import { provideAnimations } from '@angular/platform-browser/animations';
import { By } from '@angular/platform-browser';

describe('ReadonlyWallComponent', () => {
  let component: ReadonlyWallComponent;
  let fixture: ComponentFixture<ReadonlyWallComponent>;
  let wallServiceSpy: jasmine.SpyObj<ReadonlyWallService>;
  let liveAnnouncerSpy: jasmine.SpyObj<LiveAnnouncer>;
  let stompClientSpy: jasmine.SpyObj<ReadonlyWallStompClient>;

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

  const tootsSubject = new BehaviorSubject<ReadonlyTootView[]>([]);
  const catalogLoadedSubject = new BehaviorSubject<boolean>(false);
  let transportModeSubject: BehaviorSubject<ViewerTransportMode>;

  beforeEach(async () => {
    transportModeSubject = new BehaviorSubject<ViewerTransportMode>(ViewerTransportMode.PROBING);

    wallServiceSpy = jasmine.createSpyObj<ReadonlyWallService>(
      'ReadonlyWallService',
      ['initialize', 'destroy', 'startFallbackPolling', 'stopFallbackPolling', 'handleToot'],
      {
        toots$: tootsSubject,
        catalogLoaded$: catalogLoadedSubject,
        hashtags: ['glacier'],
        expiresAt: '2026-04-29T12:00:00Z',
        shareId: 'test-share-id',
      }
    );

    stompClientSpy = jasmine.createSpyObj<ReadonlyWallStompClient>(
      'ReadonlyWallStompClient',
      ['connect', 'disconnect', 'tootEvents$'],
      {
        transportMode$: transportModeSubject,
      }
    );
    // tootEvents$ returns an empty observable by default
    stompClientSpy.tootEvents$.and.returnValue(of());

    liveAnnouncerSpy = jasmine.createSpyObj<LiveAnnouncer>('LiveAnnouncer', ['announce']);

    await TestBed.configureTestingModule({
      imports: [ReadonlyWallComponent],
      providers: [
        provideAnimations(),
        provideRouter([]),
        { provide: ReadonlyWallService, useValue: wallServiceSpy },
        { provide: ReadonlyWallStompClient, useValue: stompClientSpy },
        { provide: LiveAnnouncer, useValue: liveAnnouncerSpy },
        {
          provide: ActivatedRoute,
          useValue: {
            snapshot: { paramMap: { get: () => 'test-share-id' } },
          },
        },
      ],
    })
    // ReadonlyWallComponent declares `providers: [ReadonlyWallService, ReadonlyWallStompClient]`
    // in its @Component decorator, which creates a component-level injector that shadows
    // the test module's spies.  overrideComponent() replaces that provider list with
    // an empty array so the module-level spies are used instead.
    .overrideComponent(ReadonlyWallComponent, {
      set: { providers: [] },
    })
    .compileComponents();

    fixture = TestBed.createComponent(ReadonlyWallComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  afterEach(() => {
    tootsSubject.next([]);
    catalogLoadedSubject.next(false);
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  // ---- role="feed" ----

  it('renders a section with role="feed"', () => {
    const feed = fixture.nativeElement.querySelector('[role="feed"]');
    expect(feed).not.toBeNull();
  });

  it('feed has aria-busy="true" while catalog loading', () => {
    catalogLoadedSubject.next(false);
    fixture.detectChanges();
    const feed = fixture.nativeElement.querySelector('[data-testid="share-feed"]');
    expect(feed.getAttribute('aria-busy')).toBe('true');
  });

  it('feed has aria-busy="false" when catalog loaded', () => {
    catalogLoadedSubject.next(true);
    fixture.detectChanges();
    const feed = fixture.nativeElement.querySelector('[data-testid="share-feed"]');
    expect(feed.getAttribute('aria-busy')).toBe('false');
  });

  it('feed has an aria-label', () => {
    catalogLoadedSubject.next(true);
    fixture.detectChanges();
    const feed = fixture.nativeElement.querySelector('[data-testid="share-feed"]');
    expect(feed.getAttribute('aria-label')).toBeTruthy();
  });

  // ---- Readonly banner ----

  it('renders the readonly banner', () => {
    const banner = fixture.nativeElement.querySelector('[data-testid="share-banner"]');
    expect(banner).not.toBeNull();
  });

  it('banner has role="region" and aria-labelledby', () => {
    const banner = fixture.nativeElement.querySelector('[data-testid="share-banner"]');
    expect(banner.getAttribute('role')).toBe('region');
    const labelledBy = banner.getAttribute('aria-labelledby');
    expect(fixture.nativeElement.querySelector(`#${labelledBy}`)).not.toBeNull();
  });

  // ---- Live region ----

  it('renders a polite live region', () => {
    const liveRegion = fixture.nativeElement.querySelector('[data-testid="live-region"]');
    expect(liveRegion).not.toBeNull();
    expect(liveRegion.getAttribute('aria-live')).toBe('polite');
  });

  // ---- Skip links ----

  it('renders skip links nav', () => {
    const nav = fixture.nativeElement.querySelector('nav');
    expect(nav).not.toBeNull();
  });

  // ---- Service initialization ----

  it('calls wallService.initialize() with the shareId from route', () => {
    expect(wallServiceSpy.initialize).toHaveBeenCalledWith('test-share-id');
  });

  // ---- Toot rendering ----

  it('renders toots when feed loaded', fakeAsync(() => {
    catalogLoadedSubject.next(true);
    tootsSubject.next([mockToot]);
    fixture.detectChanges();
    tick();
    fixture.detectChanges();

    const tootComponents = fixture.debugElement.queryAll(By.css('app-readonly-toot'));
    expect(tootComponents.length).toBe(1);
  }));

  // ---- Cleanup ----

  it('calls wallService.destroy() on component destroy', () => {
    component.ngOnDestroy();
    expect(wallServiceSpy.destroy).toHaveBeenCalled();
  });

  // ---- STOMP client wiring (SR-RELAY-17) ----

  it('ngOnInit_calls_stompClient_connect — stompClient.connect() called with shareId on init', () => {
    // fixture.detectChanges() in beforeEach already triggered ngOnInit
    expect(stompClientSpy.connect).toHaveBeenCalledWith('test-share-id');
  });

  it('ngOnDestroy_calls_stompClient_disconnect — stompClient.disconnect() called on destroy', () => {
    component.ngOnDestroy();
    expect(stompClientSpy.disconnect).toHaveBeenCalled();
  });

  // ---- Transport mode transitions ----

  it('transportMode_FALLBACK_starts_polling — FALLBACK mode triggers wallService.startFallbackPolling()', () => {
    transportModeSubject.next(ViewerTransportMode.FALLBACK);
    fixture.detectChanges();
    expect(wallServiceSpy.startFallbackPolling).toHaveBeenCalled();
  });

  it('transportMode_LIVE_after_FALLBACK_stops_polling — LIVE after FALLBACK triggers stopFallbackPolling()', () => {
    // Transition to FALLBACK first to start polling
    transportModeSubject.next(ViewerTransportMode.FALLBACK);
    fixture.detectChanges();
    expect(wallServiceSpy.startFallbackPolling).toHaveBeenCalled();

    // Then transition back to LIVE
    transportModeSubject.next(ViewerTransportMode.LIVE);
    fixture.detectChanges();
    expect(wallServiceSpy.stopFallbackPolling).toHaveBeenCalled();
  });

  it('transportMode_LIVE_does_not_start_polling', () => {
    transportModeSubject.next(ViewerTransportMode.LIVE);
    fixture.detectChanges();
    expect(wallServiceSpy.startFallbackPolling).not.toHaveBeenCalled();
  });

  it('transportMode_EXPIRED_announces_and_navigates', fakeAsync(() => {
    // Inject a router spy so navigate() does not throw on missing routes
    const routerSpy = jasmine.createSpyObj<Router>('Router', ['navigate']);
    // Override the component's router reference
    (component as unknown as { router: Router }).router = routerSpy;

    transportModeSubject.next(ViewerTransportMode.EXPIRED);
    fixture.detectChanges();

    // liveAnnouncer must be called immediately (WCAG 2.2.1 — announce before navigate)
    expect(liveAnnouncerSpy.announce).toHaveBeenCalledWith(
      jasmine.any(String),
      'assertive',
    );

    // After ~1000 ms router.navigate fires
    tick(1100);
    expect(routerSpy.navigate).toHaveBeenCalledWith(
      ['/share', 'test-share-id', 'expired'],
    );
  }));

  it('transportMode_EXPIRED_announces_exactly_once — single EXPIRED emission triggers exactly one announce and one navigate', fakeAsync(() => {
    // This test guards against double-announce / double-navigate.
    // Previously the service also called announce + navigate, causing two of each.
    // Now the service only emits on transportMode$; the component is the sole owner.
    const routerSpy = jasmine.createSpyObj<Router>('Router', ['navigate']);
    (component as unknown as { router: Router }).router = routerSpy;

    transportModeSubject.next(ViewerTransportMode.EXPIRED);
    fixture.detectChanges();

    // Announce must be called exactly once
    expect(liveAnnouncerSpy.announce).toHaveBeenCalledTimes(1);

    tick(1500);

    // Navigate must be called exactly once
    expect(routerSpy.navigate).toHaveBeenCalledTimes(1);
  }));

  it('tootEvents$_subscribes_to_each_hashtag_on_init', () => {
    // wallServiceSpy.hashtags is ['glacier']
    // The component should have subscribed tootEvents$('glacier')
    expect(stompClientSpy.tootEvents$).toHaveBeenCalledWith('glacier');
  });
});
