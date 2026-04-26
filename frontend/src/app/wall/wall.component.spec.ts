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

    it('should handle edge cases when width is less than one column width', () => {
      mockElementRef.nativeElement.offsetHeight = 700;
      mockElementRef.nativeElement.offsetWidth = 300;

      component.onResize();
      fixture.detectChanges();

      expect(component.rowHeight).toBe(660); // 700 - 40
      expect(component.columns).toBe(0); // 300 / 408 (floor)
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
  // i18n catalog completeness — prune announcement keys
  // ---------------------------------------------------------------------------

  describe('i18n catalog completeness (wall prune keys)', () => {
    const REQUIRED_WALL_KEYS = [
      'wall.heading',
      'wall.prune.announce.single',
      'wall.prune.announce.cancelAll',
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
