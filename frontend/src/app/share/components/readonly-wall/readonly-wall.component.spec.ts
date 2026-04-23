import {
  ComponentFixture,
  TestBed,
  fakeAsync,
  tick,
} from '@angular/core/testing';
import { provideRouter, ActivatedRoute } from '@angular/router';
import { BehaviorSubject } from 'rxjs';
import { ReadonlyWallComponent } from './readonly-wall.component';
import { ReadonlyWallService } from '../../services/readonly-wall.service';
import { ReadonlyTootView } from '../../model/readonly-toot-view';
import { LiveAnnouncer } from '@angular/cdk/a11y';
import { provideAnimations } from '@angular/platform-browser/animations';
import { By } from '@angular/platform-browser';

describe('ReadonlyWallComponent', () => {
  let component: ReadonlyWallComponent;
  let fixture: ComponentFixture<ReadonlyWallComponent>;
  let wallServiceSpy: jasmine.SpyObj<ReadonlyWallService>;
  let liveAnnouncerSpy: jasmine.SpyObj<LiveAnnouncer>;

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

  beforeEach(async () => {
    wallServiceSpy = jasmine.createSpyObj<ReadonlyWallService>(
      'ReadonlyWallService',
      ['initialize', 'destroy', 'startFallbackPolling', 'stopFallbackPolling'],
      {
        toots$: tootsSubject,
        catalogLoaded$: catalogLoadedSubject,
        hashtags: ['glacier'],
        expiresAt: '2026-04-29T12:00:00Z',
        shareId: 'test-share-id',
      }
    );

    liveAnnouncerSpy = jasmine.createSpyObj<LiveAnnouncer>('LiveAnnouncer', ['announce']);

    await TestBed.configureTestingModule({
      imports: [ReadonlyWallComponent],
      providers: [
        provideAnimations(),
        provideRouter([]),
        { provide: ReadonlyWallService, useValue: wallServiceSpy },
        { provide: LiveAnnouncer, useValue: liveAnnouncerSpy },
        {
          provide: ActivatedRoute,
          useValue: {
            snapshot: { paramMap: { get: () => 'test-share-id' } },
          },
        },
      ],
    })
    // ReadonlyWallComponent declares `providers: [ReadonlyWallService]` in its
    // @Component decorator, which creates a component-level injector that shadows
    // the test module's spy.  overrideComponent() replaces that provider list with
    // an empty array so the module-level spy is used instead.
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
});
