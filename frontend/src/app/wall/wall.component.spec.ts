import {ComponentFixture, TestBed} from '@angular/core/testing';

import {WallComponent} from './wall.component';
import {MatGridList, MatGridTile} from "@angular/material/grid-list";
import {ElementRef} from '@angular/core';
import {ResourceUrlSanitizerPipe} from "./resource-url-sanitizer.pipe";
import {MessageQueue, SubscriptionService} from "../subscription.service";
import {SafeMessage} from "../message-types/safe-message";
import {of} from "rxjs";

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
    mockSubscriptionService.getCreatedEvents.and.returnValue(of({
      storage: [
        {id: '1', url: 'url1'},
        {id: '2', url: 'url2'},
      ],
      capacity: 2,
      restore: () => {},
      enqueue: () => {},
      dequeue: () => undefined,
      clear: () => {}, // Added missing method
      size: 2,         // Added missing property
      toArray: () => [] // Added missing method
    } as unknown as MessageQueue));

    mockElementRef = {
      nativeElement: {
        offsetHeight: 800,
        offsetWidth: 1200,
      },
    };

    TestBed.configureTestingModule({
      declarations: [WallComponent],
      imports: [
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
    fixture.detectChanges();
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
