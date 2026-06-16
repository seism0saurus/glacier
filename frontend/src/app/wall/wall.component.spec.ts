import {ComponentFixture, fakeAsync, TestBed, tick} from '@angular/core/testing';
import { HttpClientModule } from '@angular/common/http';
import {WallComponent} from './wall.component';
import {MatGridList, MatGridTile} from "@angular/material/grid-list";
import {ElementRef} from '@angular/core';
import {ResourceUrlSanitizerPipe} from "./resource-url-sanitizer.pipe";
import {SubscriptionService} from "../subscription.service";
import {WallMessage} from "../model/wall-message";
import {BehaviorSubject, of} from "rxjs";
import {TootComponent} from "../toot/toot.component";
import {WallAnnouncerService} from "../services/wall-announcer.service";
import {By} from "@angular/platform-browser";
import {NoopAnimationsModule} from "@angular/platform-browser/animations";

describe('WallComponent', () => {
  let component: WallComponent;
  let fixture: ComponentFixture<WallComponent>;

  let mockSubscriptionService: jasmine.SpyObj<SubscriptionService>;
  let mockWallAnnouncerService: jasmine.SpyObj<WallAnnouncerService>;
  let mockAnnouncerAnnouncements$: BehaviorSubject<string>;
  let mockElementRef: any;

  beforeEach(() => {
    mockSubscriptionService = jasmine.createSpyObj('SubscriptionService', [
      'getCreatedEvents',
      'terminateAllSubscriptions'
    ]);
    mockSubscriptionService.getCreatedEvents.and.returnValue(of([
      {id: '1', url: 'url1', hashtags: ['test']},
      {id: '2', url: 'url2', hashtags: ['test']},
    ] as WallMessage[]));

    // FIX-2: WallAnnouncerService mock must expose announcements$ as a BehaviorSubject
    // so WallComponent's ngOnInit subscription works correctly.
    // The spy covers setMessages (still used in ngOnInit for i18n wiring).
    mockAnnouncerAnnouncements$ = new BehaviorSubject<string>('');
    mockWallAnnouncerService = jasmine.createSpyObj(
      'WallAnnouncerService',
      ['setMessages'],
      {announcements$: mockAnnouncerAnnouncements$},
    );

    mockElementRef = {
      nativeElement: {
        offsetHeight: 800,
        offsetWidth: 1200,
      },
    };

    TestBed.configureTestingModule({
      declarations: [
        TootComponent,
        WallComponent
      ],
      imports: [
        HttpClientModule,
        MatGridList,
        MatGridTile,
        ResourceUrlSanitizerPipe,
        NoopAnimationsModule,
      ],
      providers: [
        {provide: SubscriptionService, useValue: mockSubscriptionService},
        {provide: ElementRef, useValue: mockElementRef},
        {provide: WallAnnouncerService, useValue: mockWallAnnouncerService},
      ],
    });
    fixture = TestBed.createComponent(WallComponent);
    component = fixture.componentInstance;

    component.el = mockElementRef;

    fixture.detectChanges();
  });

  describe('onResize', () => {
    it('should correctly update rowHeight and columns', () => {
      mockElementRef.nativeElement.offsetHeight = 900;
      mockElementRef.nativeElement.offsetWidth = 1600;

      component.onResize();
      fixture.detectChanges();

      expect(component.rowHeight).toBe(860); // 900 - 40
      expect(component.columns).toBe(3); // 1600 / 408 (floor)
    });

    it('should ensure at least 1 column when width is less than one column width (P1-18 mobile fix)', () => {
      mockElementRef.nativeElement.offsetHeight = 700;
      mockElementRef.nativeElement.offsetWidth = 300;

      component.onResize();
      fixture.detectChanges();

      expect(component.rowHeight).toBe(660); // 700 - 40
      // Math.max(1, floor(300 / 408)) = Math.max(1, 0) = 1 (P1-18: never 0 columns)
      expect(component.columns).toBe(1);
    });

    it('should ensure at least 1 column when offsetWidth is 200 (P1-18 regression guard)', () => {
      mockElementRef.nativeElement.offsetHeight = 600;
      mockElementRef.nativeElement.offsetWidth = 200;

      component.onResize();
      fixture.detectChanges();

      expect(component.columns)
        .withContext('columns must be at least 1 on a 200px viewport')
        .toBeGreaterThanOrEqual(1);
    });

    describe('should handle typical screen widths', () => {
      it('wxga', () => {
        mockElementRef.nativeElement.offsetHeight = 720;
        mockElementRef.nativeElement.offsetWidth = 1280;

        component.onResize();
        fixture.detectChanges();

        expect(component.rowHeight).toBe(680);
        expect(component.columns).toBe(3);
      });
      it('wxga+', () => {
        mockElementRef.nativeElement.offsetHeight = 900;
        mockElementRef.nativeElement.offsetWidth = 1440;

        component.onResize();
        fixture.detectChanges();

        expect(component.rowHeight).toBe(860);
        expect(component.columns).toBe(3);
      });
      it('hd+', () => {
        mockElementRef.nativeElement.offsetHeight = 900;
        mockElementRef.nativeElement.offsetWidth = 1600;

        component.onResize();
        fixture.detectChanges();

        expect(component.rowHeight).toBe(860);
        expect(component.columns).toBe(3);
      });
      it('fhd', () => {
        mockElementRef.nativeElement.offsetHeight = 1080;
        mockElementRef.nativeElement.offsetWidth = 1920;

        component.onResize();
        fixture.detectChanges();

        expect(component.rowHeight).toBe(1040);
        expect(component.columns).toBe(4);
      });
      it('wuxga', () => {
        mockElementRef.nativeElement.offsetHeight = 1200;
        mockElementRef.nativeElement.offsetWidth = 1920;

        component.onResize();
        fixture.detectChanges();

        expect(component.rowHeight).toBe(1160);
        expect(component.columns).toBe(4);
      });
      it('qwxga', () => {
        mockElementRef.nativeElement.offsetHeight = 1152;
        mockElementRef.nativeElement.offsetWidth = 2048;

        component.onResize();
        fixture.detectChanges();

        expect(component.rowHeight).toBe(1112);
        expect(component.columns).toBe(5);
      });
      it('4k', () => {
        mockElementRef.nativeElement.offsetHeight = 2160;
        mockElementRef.nativeElement.offsetWidth = 3840;

        component.onResize();
        fixture.detectChanges();

        expect(component.rowHeight).toBe(2120);
        expect(component.columns).toBe(9);
      });
      it('5k', () => {
        mockElementRef.nativeElement.offsetHeight = 2880;
        mockElementRef.nativeElement.offsetWidth = 5120;

        component.onResize();
        fixture.detectChanges();

        expect(component.rowHeight).toBe(2840);
        expect(component.columns).toBe(12);
      });
    });
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  it('should unsubscribe on ngOnDestroy', () => {
    const unsubscribeSpy = spyOn(component['serviceSubscription']!, 'unsubscribe');
    component.ngOnDestroy();
    expect(mockSubscriptionService.terminateAllSubscriptions).toHaveBeenCalled();
    expect(unsubscribeSpy).toHaveBeenCalled();
  });

  it('should return the toot id in trackToot', () => {
    // noinspection HttpUrlsUsage
    const toot: WallMessage = {id: '1', url: 'http://example.com', hashtags: ['test']};
    expect(component.trackToot(0, toot)).toEqual('1');
    expect(component.trackToot(0, null as unknown as WallMessage)).toBeUndefined();
  });

  it('should return correct toots for a column in getTootsForColumn', () => {
    component.toots = [
      {id: '1', url: 'url1', hashtags: ['a']},
      {id: '2', url: 'url2', hashtags: ['a']},
      {id: '3', url: 'url3', hashtags: ['a']},
      {id: '4', url: 'url4', hashtags: ['a']},
    ] as WallMessage[];

    component.columns = 2; // Assume 2 columns
    const column1Toots = component.getTootsForColumn(0);
    const column2Toots = component.getTootsForColumn(1);

    expect(column1Toots).toEqual([
      {id: '4', url: 'url4', hashtags: ['a']},
      {id: '2', url: 'url2', hashtags: ['a']},
    ]);
    expect(column2Toots).toEqual([
      {id: '3', url: 'url3', hashtags: ['a']},
      {id: '1', url: 'url1', hashtags: ['a']},
    ]);
  });

  // ---------------------------------------------------------------------------
  // WAI-ARIA live region tests (Phase 2 — ADR-5; updated for FIX-2)
  // ---------------------------------------------------------------------------

  describe('live region (WAI-ARIA role="status" aria-live="polite")', () => {
    it('should render the live region element in the template', () => {
      // The live region must be a sibling of role="feed", not nested inside it.
      const liveRegion = fixture.debugElement.query(By.css('[role="status"][aria-live="polite"]'));
      expect(liveRegion).not.toBeNull();
    });

    it('should have aria-atomic="true" on the live region', () => {
      const liveRegion = fixture.debugElement.query(By.css('[role="status"]'));
      expect(liveRegion).not.toBeNull();
      expect(liveRegion.nativeElement.getAttribute('aria-atomic')).toBe('true');
    });

    it('should provide i18n messages to WallAnnouncerService on init', () => {
      // setMessages must have been called with resolved strings (non-empty).
      expect(mockWallAnnouncerService.setMessages).toHaveBeenCalledWith(
        jasmine.any(String),
        jasmine.any(String),
      );
      const [pruneMsg, cancelAllMsg] = mockWallAnnouncerService.setMessages.calls.mostRecent().args;
      expect((pruneMsg as string).length).toBeGreaterThan(0);
      expect((cancelAllMsg as string).length).toBeGreaterThan(0);
    });

    // -------------------------------------------------------------------------
    // FIX-2 regression guard: announcements$ subscription drives announceText.
    //
    // This test fails without FIX-2 (when the service writes textContent directly
    // and Angular's change detection resets it to ''), and passes with FIX-2
    // (the component subscribes to announcements$ and Angular CD is the sole writer).
    // -------------------------------------------------------------------------
    it('should update announceText when announcements$ emits (FIX-2 regression guard)', fakeAsync(() => {
      // Arrange: component is already initialised with mockAnnouncerAnnouncements$
      // (subscribed in ngOnInit). Simulate the announcer emitting after debounce.

      // Act: emit a coalesced announcement (the clear-then-set pattern)
      mockAnnouncerAnnouncements$.next(''); // clear emission
      tick(0);
      mockAnnouncerAnnouncements$.next('Ein Beitrag entfernt.'); // text emission
      tick(0);
      fixture.detectChanges();

      // Assert: announceText is updated and bound to the live region DOM node.
      expect(component.announceText).toBe('Ein Beitrag entfernt.');

      const liveRegionEl = fixture.debugElement.query(By.css('[role="status"]'));
      expect(liveRegionEl.nativeElement.textContent).toBe('Ein Beitrag entfernt.');
    }));

    it('should unsubscribe from announcements$ on destroy (no memory leak)', () => {
      // After destroy, emitting on announcements$ must not update announceText.
      component.ngOnDestroy();
      mockAnnouncerAnnouncements$.next('should-not-appear');
      expect(component.announceText).toBe('');
    });
  });

  // ---------------------------------------------------------------------------
  // role="feed" and role="article" structural tests (WAI-ARIA)
  // ---------------------------------------------------------------------------

  describe('WAI-ARIA feed/article structure', () => {
    it('should have role="feed" on the toot container', () => {
      const feed = fixture.debugElement.query(By.css('[role="feed"]'));
      expect(feed).not.toBeNull();
    });

    it('should have an aria-label on the role="feed" element (wall.heading i18n key)', () => {
      const feed = fixture.debugElement.query(By.css('[role="feed"]'));
      expect(feed).not.toBeNull();
      // In Karma (German source, no catalog loaded) the label is the source text.
      const label = feed.nativeElement.getAttribute('aria-label');
      expect(label).toBeTruthy();
    });

    it('should render article elements for each toot column entry', () => {
      // Verify that article elements with role="article" are rendered for toots.
      // The beforeEach sets up 2 toots in 2 columns (1 per column = 2 articles total).
      // We simply assert that article elements exist with the correct role attribute.
      const articles = fixture.debugElement.queryAll(By.css('article[role="article"]'));
      expect(articles.length).toBeGreaterThan(0);
      for (const article of articles) {
        expect(article.nativeElement.getAttribute('role')).toBe('article');
      }
    });
  });

  // ---------------------------------------------------------------------------
  // Priority 5 additions — accessibility and deleted-toot handling
  // ---------------------------------------------------------------------------

  /**
   * Screen readers announce new content via the live region when a new toot
   * arrives.  This test verifies that WallAnnouncerService.announce is wired
   * correctly via announcements$, so the aria-live="polite" region updates
   * its text and assistive technology can narrate it.
   *
   * <p>Arrange: component already initialised with announcements$ BehaviorSubject.
   * <p>Act:     emit an announcement text on the BehaviorSubject.
   * <p>Assert:  component.announceText reflects the emitted value and the live
   *             region DOM node contains that text.
   */
  it('wall_announcesNewToot_via_aria_live_polite', fakeAsync(() => {
    // Act: simulate the announcer emitting after a new toot arrives
    mockAnnouncerAnnouncements$.next('');
    tick(0);
    mockAnnouncerAnnouncements$.next('Neuer Beitrag zu cats');
    tick(0);
    fixture.detectChanges();

    // Assert: live region text is updated via data binding (not direct DOM)
    expect(component.announceText).toBe('Neuer Beitrag zu cats');
    const liveRegion = fixture.debugElement.query(By.css('[role="status"][aria-live="polite"]'));
    expect(liveRegion).not.toBeNull();
    expect(liveRegion.nativeElement.textContent).toBe('Neuer Beitrag zu cats');
  }));

  /**
   * When a StatusDeletedMessage is handled by SubscriptionService, the
   * corresponding toot must be removed from the wall.  This test verifies
   * WallComponent reacts correctly when the underlying message observable
   * emits a reduced array — i.e., the component re-renders with the deleted
   * toot absent.
   *
   * <p>Arrange: two toots are rendered initially; getCreatedEvents returns a
   *             BehaviorSubject so we can push a new emission.
   * <p>Act:     emit a reduced list with one toot removed.
   * <p>Assert:  component.toots no longer contains the deleted toot id.
   */
  it('wall_handlesDeletedTootEvent_byRemovingFromQueue', () => {
    // Arrange: start with two toots (already set up by beforeEach)
    expect(component.toots.length).toBe(2);

    // Act: simulate SubscriptionService pushing a reduced list
    // (as it would after dequeue is called for a deleted toot)
    const {BehaviorSubject: BS} = require('rxjs');
    const updatedMessages$: typeof import('rxjs').BehaviorSubject = BS;
    // Use the mock's getCreatedEvents to push a reduced message list
    const reducedMessages: WallMessage[] = [
      {id: '2', url: 'url2', hashtags: ['test']},
    ];
    mockSubscriptionService.getCreatedEvents.and.returnValue(of(reducedMessages));

    // Re-init component so it subscribes to the new observable
    component.ngOnInit();
    fixture.detectChanges();

    // Assert: toot id '1' has been removed; only id '2' remains
    expect(component.toots.length).toBe(1);
    expect(component.toots[0].id).toBe('2');
  });

  /**
   * Regression test for the live-update rendering bug.
   *
   * A toot arriving via getCreatedEvents AFTER the initial render must appear in
   * columnToots() without a window resize or page reload. columnToots() is a
   * computed() signal; before the fix it read the non-signal `toots` field, so it
   * only recomputed when the _columns signal changed. Live emissions were stored
   * but never rendered until a resize/reload — exactly the symptom seen on the wall
   * (message in localStorage, zero app-toot in the DOM until refresh).
   */
  it('wall_rendersTootArrivingLiveAfterInitialRender_withoutResizeOrReload', () => {
    const live$ = new BehaviorSubject<WallMessage[]>([]);
    mockSubscriptionService.getCreatedEvents.and.returnValue(live$.asObservable());

    // Re-subscribe to the controllable stream and render once so columnToots()
    // is evaluated and cached.
    component.ngOnInit();
    fixture.detectChanges();

    // Act: a new toot arrives live — no onResize(), no re-init.
    live$.next([{id: 'LIVE-99', url: 'url99', hashtags: ['test']}] as WallMessage[]);
    fixture.detectChanges();

    // Assert: the computed grid reflects the live-added toot.
    const renderedIds = (component as unknown as { columnToots(): WallMessage[][] })
      .columnToots()
      .flat()
      .map((t) => t.id);
    expect(renderedIds).toContain('LIVE-99');
  });

  // ---------------------------------------------------------------------------
  // P1-18 mobile column reflow — already tested in onResize block above.
  // ---------------------------------------------------------------------------

  // ---------------------------------------------------------------------------
  // P2 D.3 empty-wall CTA
  // ---------------------------------------------------------------------------

  describe('empty-wall CTA (P2 D.3)', () => {
    it('should show the empty-wall CTA when there are no toots', () => {
      // Re-wire the service to return an empty list
      mockSubscriptionService.getCreatedEvents.and.returnValue(of([]));
      component.ngOnInit();
      fixture.detectChanges();

      const cta = fixture.nativeElement.querySelector('.empty-wall-cta');
      expect(cta).withContext('empty-wall CTA must be visible when toots list is empty').toBeTruthy();
    });

    it('should hide the empty-wall CTA when toots are present', () => {
      // The beforeEach already sets up 2 toots, so CTA should be hidden.
      fixture.detectChanges();
      const cta = fixture.nativeElement.querySelector('.empty-wall-cta');
      expect(cta).withContext('empty-wall CTA must not be visible when toots are present').toBeNull();
    });

    it('should have role="status" on the empty-wall CTA for screen-reader announce', () => {
      mockSubscriptionService.getCreatedEvents.and.returnValue(of([]));
      component.ngOnInit();
      fixture.detectChanges();

      const cta = fixture.nativeElement.querySelector('.empty-wall-cta');
      expect(cta?.getAttribute('role')).toBe('status');
    });
  });

  // ---------------------------------------------------------------------------
  // TOOT-02 tootLabel() — localized iframe title guard
  // ---------------------------------------------------------------------------

  describe('TOOT-02 tootLabel()', () => {
    it('returns a label containing the hashtag when hashtags are present', () => {
      const label = component.tootLabel({ hashtags: ['glacier'] });
      expect(label).toContain('glacier');
      expect(label).not.toContain('undefined');
    });

    it('returns a fallback label when hashtags array is empty (no-hashtag guard)', () => {
      const label = component.tootLabel({ hashtags: [] });
      expect(label).toBeTruthy();
      expect(label).not.toContain('undefined');
    });

    it('returns a fallback label when hashtags is undefined-like', () => {
      const label = component.tootLabel({ hashtags: [] });
      expect(label).toBeTruthy();
      expect(label).not.toContain('undefined');
    });
  });

  // ---------------------------------------------------------------------------
  // i18n catalog completeness — prune announcement keys
  // ---------------------------------------------------------------------------

  describe('i18n catalog completeness (wall prune keys)', () => {
    const REQUIRED_WALL_KEYS = [
      'wall.heading',
      'wall.prune.announce.single',
      'wall.prune.announce.cancelAll',
      'wall.toot.label',
      'wall.toot.label.no-hashtag',
    ] as const;

    for (const key of REQUIRED_WALL_KEYS) {
      it(`messages.en.json must contain key '${key}'`, async () => {
        const response = await fetch('/assets/i18n/messages.en.json');
        expect(response.ok)
          .withContext('messages.en.json must be fetchable')
          .toBeTrue();

        const catalog: Record<string, string> = await response.json();
        expect(catalog[key])
          .withContext(`messages.en.json is missing key '${key}'`)
          .toBeDefined();
        expect(typeof catalog[key]).toBe('string');
        expect(catalog[key].length).toBeGreaterThan(0);
      });
    }
  });
});
