import {
  ComponentFixture,
  TestBed,
  fakeAsync,
  tick,
} from '@angular/core/testing';
import { provideRouter, ActivatedRoute, Router } from '@angular/router';
import { LOCALE_ID } from '@angular/core';
import { BehaviorSubject, Subject, of, NEVER } from 'rxjs';
import { ReadonlyWallComponent } from './readonly-wall.component';
import { ReadonlyWallService } from '../../services/readonly-wall.service';
import { ReadonlyWallStompClient } from '../../services/readonly-wall-stomp-client.service';
import { ReadonlyTootView } from '../../model/readonly-toot-view';
import { ViewerTransportMode } from '../../services/viewer-transport-mode';
import { LiveAnnouncer } from '@angular/cdk/a11y';
import { provideAnimations } from '@angular/platform-browser/animations';
import { By } from '@angular/platform-browser';
import { provideHttpClient, withInterceptorsFromDi } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';

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
  // VIEW-01: expired$ subject shared across tests; reset in beforeEach
  let expiredSubject: Subject<void>;

  beforeEach(async () => {
    transportModeSubject = new BehaviorSubject<ViewerTransportMode>(ViewerTransportMode.PROBING);
    expiredSubject = new Subject<void>();

    wallServiceSpy = jasmine.createSpyObj<ReadonlyWallService>(
      'ReadonlyWallService',
      ['initialize', 'destroy', 'startFallbackPolling', 'stopFallbackPolling', 'handleToot'],
      {
        toots$: tootsSubject,
        catalogLoaded$: catalogLoadedSubject,
        hashtags: ['glacier'],
        expiresAt: '2026-04-29T12:00:00Z',
        shareId: 'test-share-id',
        // VIEW-01: expose expired$ so the component can subscribe to it
        expired$: expiredSubject.asObservable(),
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
        provideHttpClient(withInterceptorsFromDi()),
        provideHttpClientTesting(),
        provideRouter([]),
        { provide: ReadonlyWallService, useValue: wallServiceSpy },
        { provide: ReadonlyWallStompClient, useValue: stompClientSpy },
        { provide: LiveAnnouncer, useValue: liveAnnouncerSpy },
        { provide: LOCALE_ID, useValue: 'de' },
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

  // ---- VIEW-03: transport status icon is a local SVG, never a font ligature ----
  const TRANSPORT_ICON: ReadonlyArray<[ViewerTransportMode, string]> = [
    [ViewerTransportMode.LIVE, 'wifi_icon'],
    [ViewerTransportMode.PROBING, 'sync_icon'],
    [ViewerTransportMode.FALLBACK, 'sync_problem_icon'],
  ];

  for (const [mode, expectedIcon] of TRANSPORT_ICON) {
    it(`renders the transport-status icon as a local SVG (${expectedIcon}) in ${ViewerTransportMode[mode]} mode`, () => {
      transportModeSubject.next(mode);
      fixture.detectChanges();
      const icon: HTMLElement = fixture.nativeElement.querySelector(
        '[data-testid="transport-status"] mat-icon',
      );
      expect(icon).withContext('transport-status icon must exist').toBeTruthy();
      expect(icon.getAttribute('data-mat-icon-type'))
        .withContext('icon must use the local svgIcon path, not a font ligature')
        .toBe('svg');
      expect(icon.getAttribute('data-mat-icon-name')).toBe(expectedIcon);
    });
  }

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

  // ---- VIEW-01: expired$ from service triggers announce-then-navigate ----
  // Security requirement: revocation must reach the UI in ALL transport modes,
  // including fallback polling mode where STOMP is unavailable.
  // Component is the sole owner of announce-then-navigate (ADR-RELAY-05).

  describe('VIEW-01 expired$ from wallService triggers announce-then-navigate', () => {

    it('VIEW-01 expired$_announces_assertively — expired$ emit triggers assertive LiveAnnouncer', fakeAsync(() => {
      const routerSpy = jasmine.createSpyObj<Router>('Router', ['navigate']);
      (component as unknown as { router: Router }).router = routerSpy;

      // Emit expiry from the service (fallback polling discovered revocation)
      expiredSubject.next();
      fixture.detectChanges();

      expect(liveAnnouncerSpy.announce).toHaveBeenCalledWith(
        jasmine.any(String),
        'assertive',
      );

      tick(1100);
      expect(routerSpy.navigate).toHaveBeenCalledWith(
        ['/share', 'test-share-id', 'expired'],
      );
    }));

    it('VIEW-01 expired$_navigates_after_delay — navigate fires after ~1000ms delay (WCAG 2.2.1/3.2.5)', fakeAsync(() => {
      const routerSpy = jasmine.createSpyObj<Router>('Router', ['navigate']);
      (component as unknown as { router: Router }).router = routerSpy;

      expiredSubject.next();
      fixture.detectChanges();

      // Must NOT navigate immediately
      expect(routerSpy.navigate).not.toHaveBeenCalled();

      tick(1000);
      expect(routerSpy.navigate).toHaveBeenCalledWith(
        ['/share', 'test-share-id', 'expired'],
      );
    }));

    it('VIEW-01 expired$_fires_exactly_once — expired$ + transportMode$ EXPIRED together fire only once announce+navigate', fakeAsync(() => {
      // Guard: both expired$ and STOMP EXPIRED must not double-fire announce/navigate.
      // The expiryHandled guard inside triggerExpiry() must ensure exactly one announce
      // and one navigate regardless of how many signals arrive.
      const routerSpy = jasmine.createSpyObj<Router>('Router', ['navigate']);
      (component as unknown as { router: Router }).router = routerSpy;

      // Both signals fire "simultaneously"
      expiredSubject.next();
      transportModeSubject.next(ViewerTransportMode.EXPIRED);
      fixture.detectChanges();

      expect(liveAnnouncerSpy.announce).toHaveBeenCalledTimes(1);

      tick(1500);
      expect(routerSpy.navigate).toHaveBeenCalledTimes(1);
    }));

    it('VIEW-01 expired$_repeated_emission_fires_once — multiple expired$ emissions trigger only one announce+navigate', fakeAsync(() => {
      const routerSpy = jasmine.createSpyObj<Router>('Router', ['navigate']);
      (component as unknown as { router: Router }).router = routerSpy;

      expiredSubject.next();
      expiredSubject.next(); // duplicate emission
      fixture.detectChanges();

      expect(liveAnnouncerSpy.announce).toHaveBeenCalledTimes(1);

      tick(1500);
      expect(routerSpy.navigate).toHaveBeenCalledTimes(1);
    }));

    it('VIEW-01 transportMode$_EXPIRED_still_announces_and_navigates — STOMP EXPIRED path still works', fakeAsync(() => {
      // This ensures the existing STOMP-expiry path is not accidentally broken.
      const routerSpy = jasmine.createSpyObj<Router>('Router', ['navigate']);
      (component as unknown as { router: Router }).router = routerSpy;

      transportModeSubject.next(ViewerTransportMode.EXPIRED);
      fixture.detectChanges();

      expect(liveAnnouncerSpy.announce).toHaveBeenCalledWith(
        jasmine.any(String),
        'assertive',
      );

      tick(1100);
      expect(routerSpy.navigate).toHaveBeenCalledWith(
        ['/share', 'test-share-id', 'expired'],
      );
    }));
  });

  // ---- VIEW-02: Announce only genuinely-new toots, suppress initial hydration ----
  // WCAG 4.1.3: live announcements must not flood with false "new toot" notices
  // on page load. The initial BehaviorSubject emission is the catalog snapshot
  // and must NOT trigger an announcement.

  describe('VIEW-02 announce only genuinely-new toots', () => {

    it('VIEW-02 no_announcement_on_initial_load — initial toots$ emission does NOT announce a new toot', () => {
      // beforeEach already triggered ngOnInit. The component's initialLoadDone is
      // now true (first emission was the empty BehaviorSubject value).
      // Reset to "pre-load" state to simulate the catalog hydration scenario.
      (component as unknown as { initialLoadDone: boolean }).initialLoadDone = false;
      (component as unknown as { previousTootCount: number }).previousTootCount = 0;
      (component as unknown as { liveAnnouncement: string }).liveAnnouncement = '';

      const mockToots = [mockToot, { ...mockToot, id: 'toot-2' }, { ...mockToot, id: 'toot-3' }];

      // Simulate catalog hydration (first real emission — should be suppressed)
      tootsSubject.next(mockToots);
      fixture.detectChanges();

      // The live-region text must remain empty (no "Neuer Toot" announced on hydration)
      const liveRegion = fixture.nativeElement.querySelector('[data-testid="live-region"]');
      expect(liveRegion.textContent.trim()).toBe('');
    });

    it('VIEW-02 announces_single_new_toot — one new toot after catalog loads announces a message', fakeAsync(() => {
      // The component's initialLoadDone is true after ngOnInit.
      // previousTootCount was set to 0 (empty initial BehaviorSubject emission).
      // Emit 1 toot — newCount = 1-0 = 1 — should announce.
      (component as unknown as { previousTootCount: number }).previousTootCount = 0;

      const newToot = { ...mockToot, id: 'toot-new' };
      tootsSubject.next([newToot]);
      fixture.detectChanges();

      const liveRegion = fixture.nativeElement.querySelector('[data-testid="live-region"]');
      expect(liveRegion.textContent.trim()).not.toBe('');
    }));

    it('VIEW-02 announces_multiple_new_toots_as_count — 3 new toots produces a count announcement (uses plural key)', fakeAsync(() => {
      // initialLoadDone is already true from ngOnInit.
      // Set previousTootCount to 1 to simulate "1 toot already displayed".
      (component as unknown as { previousTootCount: number }).previousTootCount = 1;

      const toot2 = { ...mockToot, id: 'toot-2' };
      const toot3 = { ...mockToot, id: 'toot-3' };
      const toot4 = { ...mockToot, id: 'toot-4' };
      tootsSubject.next([toot4, toot3, toot2, mockToot]);
      fixture.detectChanges();

      const liveRegion = fixture.nativeElement.querySelector('[data-testid="live-region"]');
      // Should mention "3" (the count of new arrivals = 4-1)
      expect(liveRegion.textContent).toContain('3');
    }));

    it('VIEW-02 no_announcement_when_count_unchanged — toots$ emission with same length does not announce', () => {
      // initialLoadDone is true; previousTootCount = 1; emitting 1 toot = no change
      (component as unknown as { previousTootCount: number }).previousTootCount = 1;
      (component as unknown as { liveAnnouncement: string }).liveAnnouncement = '';

      // Emit with same count as previousTootCount (1-1=0 new)
      tootsSubject.next([mockToot]);
      fixture.detectChanges();

      const liveRegion = fixture.nativeElement.querySelector('[data-testid="live-region"]');
      expect(liveRegion.textContent.trim()).toBe('');
    });
  });

  // ---- VIEW-03: Transport status indicator ----
  // WCAG 1.3.3 / 1.4.1 / 4.1.3: transport state must be conveyed not by color
  // alone, must be visible and announced. Does NOT duplicate EXPIRED announcement.

  describe('VIEW-03 transport status indicator', () => {

    it('VIEW-03 renders_transport_status_region — a role="status" region is present', () => {
      const statusRegion = fixture.nativeElement.querySelector('[data-testid="transport-status"]');
      expect(statusRegion).not.toBeNull();
    });

    it('VIEW-03 status_region_is_aria_live_polite — aria-live="polite" on transport status', () => {
      const statusRegion = fixture.nativeElement.querySelector('[data-testid="transport-status"]');
      // role="status" implies aria-live="polite" but we verify explicit attribute or role
      expect(
        statusRegion.getAttribute('role') === 'status' ||
        statusRegion.getAttribute('aria-live') === 'polite'
      ).toBeTrue();
    });

    it('VIEW-03 LIVE_mode_shows_live_label — LIVE mode shows "Live" label text', () => {
      transportModeSubject.next(ViewerTransportMode.LIVE);
      fixture.detectChanges();
      const statusRegion = fixture.nativeElement.querySelector('[data-testid="transport-status"]');
      expect(statusRegion.textContent).toMatch(/Live/i);
    });

    it('VIEW-03 PROBING_mode_shows_probing_label — PROBING mode shows connecting label', () => {
      transportModeSubject.next(ViewerTransportMode.PROBING);
      fixture.detectChanges();
      const statusRegion = fixture.nativeElement.querySelector('[data-testid="transport-status"]');
      // The text must be non-empty (PROBING label)
      expect(statusRegion.textContent.trim()).not.toBe('');
    });

    it('VIEW-03 FALLBACK_mode_shows_fallback_label — FALLBACK mode shows fallback label text', () => {
      transportModeSubject.next(ViewerTransportMode.FALLBACK);
      fixture.detectChanges();
      const statusRegion = fixture.nativeElement.querySelector('[data-testid="transport-status"]');
      expect(statusRegion.textContent.trim()).not.toBe('');
    });

    it('VIEW-03 EXPIRED_mode_does_NOT_update_transport_status — EXPIRED mode defers to the assertive expiry announce', () => {
      const routerSpy = jasmine.createSpyObj<Router>('Router', ['navigate']);
      (component as unknown as { router: Router }).router = routerSpy;

      transportModeSubject.next(ViewerTransportMode.EXPIRED);
      fixture.detectChanges();

      // The transport status region must NOT contain an "expired" label
      // (expiry is handled by the assertive LiveAnnouncer call in triggerExpiry())
      const statusRegion = fixture.nativeElement.querySelector('[data-testid="transport-status"]');
      // Either it's hidden or it doesn't announce EXPIRED in the polite region
      // — it must not contain the expiry message text
      expect(statusRegion?.textContent ?? '').not.toMatch(/abgelaufen|expired/i);
    });

    it('VIEW-03 status_has_text_not_color_only — transport status renders visible text label', () => {
      transportModeSubject.next(ViewerTransportMode.LIVE);
      fixture.detectChanges();
      const statusRegion = fixture.nativeElement.querySelector('[data-testid="transport-status"]');
      // Must not be empty — no color-only indication
      expect(statusRegion.textContent.trim().length).toBeGreaterThan(0);
    });
  });

  // ---- VIEW-04: feedLabel must not contain raw ICU brace syntax ----
  // WCAG 4.1.2 (accessible name): aria-label computed from feedLabel must produce
  // clean human-readable text for count 0, 1, and 2+ (plural forms).

  describe('VIEW-04 feedLabel ICU expansion', () => {

    // Helper: override the read-only 'hashtags' property defined as a getter on the spy.
    // jasmine.createSpyObj defines it as an accessor on the prototype, so we must use
    // Object.defineProperty on the instance to shadow it in each test.
    function overrideHashtags(tags: string[]): void {
      Object.defineProperty(wallServiceSpy, 'hashtags', { get: () => tags, configurable: true });
    }

    it('VIEW-04 feedLabel_count_0_no_braces — feedLabel for 0 hashtags does not contain "{" or "}"', () => {
      overrideHashtags([]);
      const label = component.feedLabel;
      expect(label).not.toContain('{');
      expect(label).not.toContain('}');
    });

    it('VIEW-04 feedLabel_count_1_no_braces — feedLabel for 1 hashtag is the singular form with no ICU syntax', () => {
      overrideHashtags(['glacier']);
      const label = component.feedLabel;
      expect(label).not.toContain('{');
      expect(label).not.toContain('}');
    });

    it('VIEW-04 feedLabel_count_2_no_braces — feedLabel for 2 hashtags is the plural form with no ICU syntax', () => {
      overrideHashtags(['glacier', 'tech']);
      const label = component.feedLabel;
      expect(label).not.toContain('{');
      expect(label).not.toContain('}');
    });

    it('VIEW-04 feedLabel_count_1_contains_count_number — feedLabel for 1 hashtag contains "1"', () => {
      overrideHashtags(['glacier']);
      const label = component.feedLabel;
      expect(label).toContain('1');
    });

    it('VIEW-04 feedLabel_count_3_contains_count_number — feedLabel for 3 hashtags contains "3"', () => {
      overrideHashtags(['a', 'b', 'c']);
      const label = component.feedLabel;
      expect(label).toContain('3');
    });
  });

  // ---- VIEW-10: skip-link targets need tabindex="-1" for focus to land ----
  // WCAG 2.4.1: bypass blocks. Activating a skip link must move keyboard focus
  // to the target element, not just scroll. tabindex="-1" makes non-focusable
  // elements programmatically focusable via fragment navigation.

  describe('VIEW-10 — skip-link targets have tabindex="-1"', () => {
    it('VIEW-10: #share-feed section has tabindex="-1"', () => {
      const feedSection = fixture.nativeElement.querySelector('#share-feed');
      expect(feedSection).not.toBeNull('share-feed section must exist');
      expect(feedSection.getAttribute('tabindex'))
        .withContext('#share-feed must have tabindex="-1" so skip links can move focus there')
        .toBe('-1');
    });

    it('VIEW-10: #share-status section has tabindex="-1"', () => {
      const statusSection = fixture.nativeElement.querySelector('#share-status');
      expect(statusSection).not.toBeNull('share-status section must exist');
      expect(statusSection.getAttribute('tabindex'))
        .withContext('#share-status must have tabindex="-1" so skip links can move focus there')
        .toBe('-1');
    });
  });

  // ---- VIEW-12: empty-state block when catalog loaded with zero toots ----
  // Silently empty feed confuses users — a polite role="status" message reassures
  // them that live toots will appear when they arrive.

  describe('VIEW-12 — empty-state block on catalog loaded with zero toots', () => {
    it('VIEW-12: empty-state element is NOT shown while catalog is loading', () => {
      catalogLoadedSubject.next(false);
      tootsSubject.next([]);
      fixture.detectChanges();

      const emptyState = fixture.nativeElement.querySelector('[data-testid="share-feed-empty"]');
      expect(emptyState).toBeNull('empty-state must not appear while loading');
    });

    it('VIEW-12: empty-state element is shown when catalog loaded and toots list is empty', () => {
      catalogLoadedSubject.next(true);
      tootsSubject.next([]);
      fixture.detectChanges();

      const emptyState = fixture.nativeElement.querySelector('[data-testid="share-feed-empty"]');
      expect(emptyState)
        .withContext('empty-state must appear when feed is loaded but empty')
        .not.toBeNull();
    });

    it('VIEW-12: empty-state element has role="status" for polite announcement', () => {
      catalogLoadedSubject.next(true);
      tootsSubject.next([]);
      fixture.detectChanges();

      const emptyState = fixture.nativeElement.querySelector('[data-testid="share-feed-empty"]');
      expect(emptyState?.getAttribute('role'))
        .withContext('empty-state must have role="status" for polite live-region behaviour')
        .toBe('status');
    });

    it('VIEW-12: empty-state element is NOT shown when toots are present', () => {
      catalogLoadedSubject.next(true);
      tootsSubject.next([mockToot]);
      fixture.detectChanges();

      const emptyState = fixture.nativeElement.querySelector('[data-testid="share-feed-empty"]');
      expect(emptyState).toBeNull('empty-state must not appear when toots are present');
    });

    it('VIEW-12: empty-state element contains non-empty reassurance text', () => {
      catalogLoadedSubject.next(true);
      tootsSubject.next([]);
      fixture.detectChanges();

      const emptyState = fixture.nativeElement.querySelector('[data-testid="share-feed-empty"]');
      expect(emptyState?.textContent?.trim().length)
        .withContext('empty-state must contain reassurance text')
        .toBeGreaterThan(0);
    });
  });

  // ---- VIEW-12 i18n catalog completeness ----

  describe('VIEW-12 i18n catalog completeness', () => {
    it('messages.en.json must contain key share.feed.empty.message', async () => {
      const response = await fetch('/assets/i18n/messages.en.json');
      const catalog: Record<string, string> = await response.json();
      expect(catalog['share.feed.empty.message'])
        .withContext('messages.en.json is missing VIEW-12 key share.feed.empty.message')
        .toBeDefined();
      expect(typeof catalog['share.feed.empty.message']).toBe('string');
      expect(catalog['share.feed.empty.message'].length).toBeGreaterThan(0);
    });
  });

  // ---- VIEW-11: formattedExpiry uses injected LOCALE_ID ----

  describe('VIEW-11 formattedExpiry uses injected LOCALE_ID', () => {
    it('formattedExpiry returns a formatted date string when expiresAt is set', () => {
      // wallServiceSpy.expiresAt is set to '2026-04-29T12:00:00Z' in beforeEach
      const result = component.formattedExpiry;
      expect(result).toBeTruthy();
      expect(result).not.toBe('2026-04-29T12:00:00Z');
      expect(result).toMatch(/\d/);
      expect(result).not.toContain('T');
    });

    it('formattedExpiry returns empty string when expiresAt is null/empty', () => {
      // Override the expiresAt on the spy
      Object.defineProperty(wallServiceSpy, 'expiresAt', { get: () => null, configurable: true });
      const result = component.formattedExpiry;
      expect(result).toBe('');
    });
  });
});
