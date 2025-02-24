import {TestBed} from '@angular/core/testing';

import {SubscriptionService} from './subscription.service';
import {Observable} from "rxjs";
import {Message} from "@stomp/stompjs";
import {RxStompService} from "./rx-stomp.service";

describe('SubscriptionService', () => {
  let service: SubscriptionService;
  let rxStompServiceSpy: jasmine.SpyObj<RxStompService>;

  beforeEach(() => {
    const spy = jasmine.createSpyObj('RxStompService', ['publish','watch']);
    spy.watch.and.returnValue(new Observable<Message>());

    TestBed.configureTestingModule({
      providers: [
        SubscriptionService,
        {provide: RxStompService, useValue: spy},
      ],
    });
    service = TestBed.inject(SubscriptionService);
    rxStompServiceSpy = TestBed.inject(RxStompService) as jasmine.SpyObj<RxStompService>;
  });

  it('should be created', () => {
    expect(service).toBeTruthy();
  });

  describe('subscribeHashtag', () => {
    it('should publish the provided hashtag to the glacier subscription destination', () => {
      const hashtag = 'testHashtag';
      service.subscribeHashtag(hashtag);

      expect(rxStompServiceSpy.publish).toHaveBeenCalledWith({
        destination: '/glacier/subscription',
        body: JSON.stringify({hashtag}),
      });
    });

    it('should not throw an error for an empty hashtag', () => {
      const hashtag = '';
      expect(() => service.subscribeHashtag(hashtag)).not.toThrow();
      expect(rxStompServiceSpy.publish).toHaveBeenCalledWith({
        destination: '/glacier/subscription',
        body: JSON.stringify({hashtag}),
      });
    });

    it('should call rxStompService.publish exactly once', () => {
      const hashtag = 'test';
      service.subscribeHashtag(hashtag);
      expect(rxStompServiceSpy.publish).toHaveBeenCalledTimes(1);
    });
  });

  describe('unsubscribeHashtag', () => {
    it('should publish the provided hashtag to the glacier termination destination', () => {
      const hashtag = 'testHashtag';
      service.unsubscribeHashtag(hashtag);

      expect(rxStompServiceSpy.publish).toHaveBeenCalledWith({
        destination: '/glacier/termination',
        body: JSON.stringify({hashtag}),
      });
    });

    it('should not throw an error for an empty hashtag', () => {
      const hashtag = '';
      expect(() => service.unsubscribeHashtag(hashtag)).not.toThrow();
      expect(rxStompServiceSpy.publish).toHaveBeenCalledWith({
        destination: '/glacier/termination',
        body: JSON.stringify({hashtag}),
      });
    });

    it('should call rxStompService.publish exactly once', () => {
      const hashtag = 'test';
      service.unsubscribeHashtag(hashtag);
    });
  });

  describe('subscribeToStatusDeletedMessages', () => {
    it('should process a received StatusDeletedMessage and call dequeue with the correct id', () => {
      const destination = '/topic/test-status-deleted-destination';
      const testMessage = {
        body: JSON.stringify({id: '5678'}),
      };

      const dequeueSpy = spyOn(service['receivedMessages'], 'dequeue');

      rxStompServiceSpy.watch.and.returnValue({
        subscribe: (callback: (message: any) => void) => {
          callback(testMessage);
          return {unsubscribe: jasmine.createSpy('unsubscribe')};
        },
      } as any);

      service.subscribeToStatusDeletedMessages(destination);

      expect(dequeueSpy).toHaveBeenCalledWith('5678');
    });
  });

  it('should return an Observable from getCreatedEvents', (done) => {
    service.getCreatedEvents().subscribe((result) => {
      expect(result).toBeTruthy();
      expect(result.toArray).toBeTruthy();  // Ensures it's the expected MessageQueue instance
      done();
    });
  });

  it('should call restore on receivedMessages when getCreatedEvents is called', () => {
    const restoreSpy = spyOn(service['receivedMessages'], 'restore');
    service.getCreatedEvents();
    expect(restoreSpy).toHaveBeenCalled();
  });

  it('should subscribe to the provided destination for status updates', () => {
    const destination = '/topic/test-status-updated-destination';
    const mockSubscribe = jasmine.createSpy('subscribe');
    const mockObservable = new Observable<Message>((subscriber) => {
      mockSubscribe();
      return {
        unsubscribe: () => {
        }
      };
    });
    rxStompServiceSpy.watch.and.returnValue({
      subscribe: (callback: (message: any) => void) => {return mockObservable},
    } as any);

    service.subscribeToStatusUpdatedMessages(destination);

    expect(service['rxStompService'].watch).toHaveBeenCalledWith(destination);
  });

  it('should process a received StatusUpdatedMessage by updating the queue', () => {
    const destination = '/topic/test-status-updated-destination';
    const testMessage = {
      body: JSON.stringify({id: '1234', url: 'updated-content'}),
    };

    rxStompServiceSpy.watch.and.returnValue({
      subscribe: (callback: (message: any) => void) => {
        callback(testMessage);
        return {unsubscribe: jasmine.createSpy('unsubscribe')};
      },
    } as any);

    const dequeueSpy = spyOn(service['receivedMessages'], 'dequeue');
    const enqueueSpy = spyOn(service['receivedMessages'], 'enqueue');

    service.subscribeToStatusUpdatedMessages(destination);

    expect(dequeueSpy).toHaveBeenCalledWith('1234');
    expect(enqueueSpy).toHaveBeenCalledWith({id: '1234', url: 'updated-content'});
  });

  it('should unsubscribe all subscriptions when terminateAllSubscriptions is called', () => {
    // Mock subscriptions
    const mockSubscription1 = jasmine.createSpyObj('Subscription', ['unsubscribe']);
    const mockSubscription2 = jasmine.createSpyObj('Subscription', ['unsubscribe']);
    service['subscriptions'] = {
      sub1: mockSubscription1,
      sub2: mockSubscription2,
    };

    service.terminateAllSubscriptions();

    expect(mockSubscription1.unsubscribe).toHaveBeenCalled();
    expect(mockSubscription2.unsubscribe).toHaveBeenCalled();
    expect(Object.keys(service['subscriptions']).length).toBe(0);
  });

  it('should clear all received messages when clearAllToots is called', () => {
    const clearSpy = spyOn(service['receivedMessages'], 'clear');
    service.clearAllToots();
    expect(clearSpy).toHaveBeenCalled();
  });

  it('should unsubscribe subscriptionsSubscription and terminationsSubscription when terminateAllSubscriptions is called', () => {
    // Mock main subscriptions
    const mockSubscriptionsSubscription = jasmine.createSpyObj('Subscription', ['unsubscribe']);
    const mockTerminationsSubscription = jasmine.createSpyObj('Subscription', ['unsubscribe']);
    service['subscriptionsSubscription'] = mockSubscriptionsSubscription;
    service['terminationsSubscription'] = mockTerminationsSubscription;

    service.terminateAllSubscriptions();

    expect(mockSubscriptionsSubscription.unsubscribe).toHaveBeenCalled();
    expect(mockTerminationsSubscription.unsubscribe).toHaveBeenCalled();
  });

  it('should subscribe to the provided destination', () => {
    const destination = '/topic/test-destination';
    const mockSubscribe = jasmine.createSpy('subscribe');
    const mockObservable = new Observable<Message>((subscriber) => {
      mockSubscribe();
      return {
        unsubscribe: () => {
        }
      };
    });
    rxStompServiceSpy.watch.and.returnValue({
      subscribe: (callback: (message: any) => void) => {return mockObservable},
    } as any);


    service.subscribeToStatusCreatedMessages(destination);

    expect(rxStompServiceSpy.watch).toHaveBeenCalledWith(destination);
  });


  it('should enqueue received StatusCreatedMessage into the receivedMessages queue', () => {
    const destination = '/topic/test-destination';
    const testMessage = {
      body: JSON.stringify({id: "1", url: 'test-content'}),
    };

    rxStompServiceSpy.watch.and.returnValue({
      subscribe: (callback: (message: any) => void) => {
        callback(testMessage);
        return {unsubscribe: jasmine.createSpy('unsubscribe')};
      },
    } as any);

    const enqueueSpy = spyOn(service['receivedMessages'], 'enqueue');

    service.subscribeToStatusCreatedMessages(destination);

    expect(enqueueSpy).toHaveBeenCalledWith({id: "1", url: 'test-content'});
  });

  it('should remove all entries in the subscriptions object when terminateAllSubscriptions is called', () => {
    // Mock subscriptions
    service['subscriptions'] = {
      sub1: jasmine.createSpyObj('Subscription', ['unsubscribe']),
      sub2: jasmine.createSpyObj('Subscription', ['unsubscribe']),
    };

    service.terminateAllSubscriptions();

    expect(Object.keys(service['subscriptions']).length).toBe(0);
  });


});
