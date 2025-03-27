import {ComponentFixture, TestBed} from '@angular/core/testing';
import { HttpClientModule } from '@angular/common/http';
import {WallComponent} from './wall.component';
import {MatGridList, MatGridTile} from "@angular/material/grid-list";
import {ElementRef} from '@angular/core';
import {ResourceUrlSanitizerPipe} from "./resource-url-sanitizer.pipe";
import {SubscriptionService} from "../subscription.service";
import {SafeMessage} from "../message-types/safe-message";
import {of} from "rxjs";
import {TootComponent} from "../toot/toot.component";

describe('WallComponent', () => {
  let component: WallComponent;
  let fixture: ComponentFixture<WallComponent>;

  let mockSubscriptionService: jasmine.SpyObj<SubscriptionService>;
  let mockElementRef: any;

  beforeEach(() => {
    mockSubscriptionService = jasmine.createSpyObj('SubscriptionService', [
      'getCreatedEvents',
      'terminateAllSubscriptions'
    ]);
    mockSubscriptionService.getCreatedEvents.and.returnValue(of([
      {id: '1', url: 'url1'},
      {id: '2', url: 'url2'},
    ]));

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
        HttpClientModule, // Add import below
        MatGridList,
        MatGridTile,
        ResourceUrlSanitizerPipe
      ],
      providers: [
        {provide: SubscriptionService, useValue: mockSubscriptionService},
        {provide: ElementRef, useValue: mockElementRef},
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
    const toot = {id: '1', url: 'http://example.com'} as SafeMessage;
    expect(component.trackToot(0, toot)).toEqual('1');
    expect(component.trackToot(0, null as unknown as SafeMessage)).toBeUndefined();
  });

  it('should return correct toots for a column in getTootsForColumn', () => {
    component.toots = [
      {id: '1', url: 'url1'},
      {id: '2', url: 'url2'},
      {id: '3', url: 'url3'},
      {id: '4', url: 'url4'},
    ] as SafeMessage[];

    component.columns = 2; // Assume 2 columns
    const column1Toots = component.getTootsForColumn(0);
    const column2Toots = component.getTootsForColumn(1);

    expect(column1Toots).toEqual([
      {id: '4', url: 'url4'},
      {id: '2', url: 'url2'},
    ]);
    expect(column2Toots).toEqual([
      {id: '3', url: 'url3'},
      {id: '1', url: 'url1'},
    ]);
  });
});
