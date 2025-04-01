import {TestBed} from '@angular/core/testing';

import {MessageQueue, SubscriptionService} from './subscription.service';
import {RxStompService} from './rx-stomp.service';
import {Observable, of, BehaviorSubject} from 'rxjs';
import {Message} from "@stomp/stompjs";
import {TerminationAckMessage} from "./message-types/termination-ack-message";
import {SafeMessage} from "./message-types/safe-message";

describe('SubscriptionService', () => {
  let service: SubscriptionService;
  let rxStompServiceSpy: jasmine.SpyObj<RxStompService>;

  beforeEach(() => {
    const spy = jasmine.createSpyObj('RxStompService', ['publish', 'watch']);
    spy.watch.and.returnValue(new Observable<Message>());

    TestBed.configureTestingModule({
      providers: [
        SubscriptionService,
        {provide: RxStompService, useValue: spy},
      ],
    });
    service = TestBed.inject(SubscriptionService);
    rxStompServiceSpy = TestBed.inject(RxStompService) as jasmine.SpyObj<RxStompService>;

    // Reset spies to ensure no state carried over between tests
    rxStompServiceSpy.publish.calls.reset();
  });

  it('should be created', () => {
    expect(service).toBeTruthy();
  });

  it('should restore previous messages from localStorage when getCreatedEvents is called', () => {
    spyOn(localStorage, 'getItem').and.callFake((key: string) => {
      if (key === 'hashtags') {
        return JSON.stringify(['hashtag1', 'hashtag2']);
      }
      if (key === 'messageQueue') {
        return JSON.stringify([
          {id: '1', url: 'https://example.com/message1'},
          {id: '2', url: 'https://example.com/message2'},
        ]);
      }
      return JSON.stringify([]);
    });

    // Mock für MessageQueue.restore()
    const mockMessages: SafeMessage[] = [
      {id: '1', url: 'https://example.com/message1'},
      {id: '2', url: 'https://example.com/message2'},
    ];

    // Methode aufrufen
    service.getCreatedEvents().subscribe((messages) => {
      expect(messages).toEqual(mockMessages); // Erwartet die wiederhergestellten Nachrichten
    });

    // Assertions
    expect(localStorage.getItem).toHaveBeenCalledWith('messageQueue');
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
      subscribe: (callback: (message: any) => void) => {
        return mockObservable
      },
    } as any);

    service.subscribeToStatusUpdatedMessages(destination);

    expect(service['rxStompService'].watch).toHaveBeenCalledWith(destination);
  });

  it('should process a received StatusUpdatedMessage by updating the queue', () => {
    const destination = '/topic/test-status-updated-destination';
    const testMessage = {
      body: JSON.stringify({id: '1234', url: 'updated-content', editedAt: '2025-01-17T12:00:00.000Z'}),
    };
    rxStompServiceSpy.watch.and.returnValue({
      subscribe: (callback: (message: any) => void) => {
        callback(testMessage);
        return {unsubscribe: jasmine.createSpy('unsubscribe')};
      },
    } as any);
    const updateSpy = spyOn(service['receivedMessages'], 'update');

    service.subscribeToStatusUpdatedMessages(destination);

    expect(updateSpy).toHaveBeenCalledWith({id: '1234', url: 'updated-content', editedAt: '2025-01-17T12:00:00.000Z'});
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
      subscribe: (callback: (message: any) => void) => {
        return mockObservable
      },
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

  it('should update subscriptions when a valid SubscriptionAckMessage is received', () => {
    const mockMessage = {
      body: JSON.stringify({
        principal: 'principalUser',
        hashtag: 'exampleHashtag',
        subscribed: true,
      }),
    };
    // send mockMessage over stomp after subscription
    rxStompServiceSpy.watch.and.returnValue(of({
      ...mockMessage, ack: () => {
      }, nack: () => {
      }, command: '', headers: {}, isBinaryBody: false, binaryBody: new Uint8Array(), destination: ''
    }));

    service = new SubscriptionService(rxStompServiceSpy);

    const hashtags = JSON.parse(localStorage.getItem('hashtags') || '[]');
    expect(hashtags).toContain('exampleHashtag');
  });

  it('should log an error for an invalid SubscriptionAckMessage', () => {
    const mockMessage = {
      body: JSON.stringify({
        principal: 'testUser',
        hashtag: 'testHashtag',
        subscribed: false,
      }),
    };
    rxStompServiceSpy.watch.and.returnValue(of({
      ...mockMessage, ack: () => {
      }, nack: () => {
      }, command: '', headers: {}, isBinaryBody: false, binaryBody: new Uint8Array()
    }));
    const consoleErrorSpy = spyOn(console, 'error');

    service = new SubscriptionService(rxStompServiceSpy);

    expect(consoleErrorSpy).toHaveBeenCalledWith('Could not subscribe to topic', 'testHashtag');
  });

  it('should call all subscription methods and update destinations correctly', () => {
    const mockMessage = {
      body: JSON.stringify({
        principal: 'principalUser',
        hashtag: 'exampleHashtag',
        subscribed: true,
      }),
    };
    rxStompServiceSpy.watch.and.returnValue(of({
      ...mockMessage, ack: () => {
      }, nack: () => {
      }, command: '', headers: {}, isBinaryBody: false, binaryBody: new Uint8Array(), destination: ''
    }));

    service = new SubscriptionService(rxStompServiceSpy);

    expect(service['destinations']).toContain('/topic/hashtags/principalUser/exampleHashtag/creation');
    expect(service['destinations']).toContain('/topic/hashtags/principalUser/exampleHashtag/modification');
    expect(service['destinations']).toContain('/topic/hashtags/principalUser/exampleHashtag/deletion');
    const hashtags = JSON.parse(localStorage.getItem('hashtags') || '[]');
    expect(hashtags).toContain('exampleHashtag');
  });

  it('should handle a successful termination acknowledgment', () => {
    localStorage.setItem('hashtags', JSON.stringify(['hashtag1', 'hashtag2']));
    service['hashtags'] = ['hashtag1', 'hashtag2'];
    const messageData: TerminationAckMessage = {
      hashtag: 'hashtag1',
      principal: 'test-user',
      terminated: true,
    };
    const terminateSpy = spyOn(service as any, 'terminateSubscriptionByDestination').and.callThrough();
    rxStompServiceSpy.watch.and.returnValue(new Observable<Message>((subscriber) => {
      subscriber.next({
        body: JSON.stringify(messageData),
      } as Message);
      subscriber.complete();
    }));

    service['terminationsSubscription'] = rxStompServiceSpy.watch('/user/topic/terminations')
      .subscribe((message) => {
        const data: TerminationAckMessage = JSON.parse(message.body);
        (service as any).handleTerminationAckMessage(data);
      });

    expect(service['hashtags']).not.toContain('hashtag1');
    expect(service['hashtags']).toContain('hashtag2');
    const storedHashtags = JSON.parse(localStorage.getItem('hashtags')!);
    expect(storedHashtags).not.toContain('hashtag1');
    expect(storedHashtags).toContain('hashtag2');
    expect(terminateSpy).toHaveBeenCalledWith('/topic/hashtags/test-user/hashtag1/creation');
    expect(terminateSpy).toHaveBeenCalledWith('/topic/hashtags/test-user/hashtag1/modification');
    expect(terminateSpy).toHaveBeenCalledWith('/topic/hashtags/test-user/hashtag1/deletion');
  });

  it('should log an error if termination acknowledgment fails', () => {
    localStorage.setItem('hashtags', JSON.stringify(['hashtag1', 'hashtag2']));
    service['hashtags'] = ['hashtag1', 'hashtag2'];
    const messageData: TerminationAckMessage = {
      hashtag: 'hashtag1',
      principal: 'test-user',
      terminated: false,
    };
    spyOn(console, 'error');
    rxStompServiceSpy.watch.and.returnValue(new Observable<Message>((subscriber) => {
      subscriber.next({
        body: JSON.stringify(messageData),
      } as Message);
      subscriber.complete();
    }));

    service['terminationsSubscription'] = rxStompServiceSpy.watch('/user/topic/terminations')
      .subscribe((message) => {
        const data: TerminationAckMessage = JSON.parse(message.body);
        (service as any).handleTerminationAckMessage(data);
      });

    expect(service['hashtags']).toEqual(['hashtag1', 'hashtag2']);
    const storedHashtags = JSON.parse(localStorage.getItem('hashtags')!);
    expect(storedHashtags).toEqual(['hashtag1', 'hashtag2']);
    expect(console.error)
      .toHaveBeenCalledWith('Could not terminate subscription for principal test-user and hashtag hashtag1');
  });

  describe('terminateSubscriptionByDestination', () => {
    beforeEach(() => {
      service['subscriptions'] = {
        existingDestination: jasmine.createSpyObj('Subscription', ['unsubscribe']),
      };
    });

    it('should unsubscribe and delete an existing subscription', () => {
      service.terminateSubscriptionByDestination('existingDestination');

      // Verify the subscription was deleted
      expect(service['subscriptions']['existingDestination']).toBeUndefined();
    });

    it('should log an error if the subscription does not exist', () => {
      const consoleErrorSpy = spyOn(console, 'error');

      service.terminateSubscriptionByDestination('nonExistingDestination');

      // Verify console.error was called with the correct message
      expect(consoleErrorSpy).toHaveBeenCalledWith('No subscription found with destination', 'nonExistingDestination');
    });
  });

});

describe('SubscriptionService: terminateAllSubscriptions', () => {
  let service: SubscriptionService;
  let rxStompServiceSpy: jasmine.SpyObj<RxStompService>;

  beforeEach(() => {
    const spy = jasmine.createSpyObj('RxStompService', ['watch', 'publish']);
    spy.watch.and.callFake(() =>
      of({body: JSON.stringify({subscribed: true, hashtag: 'hashtag1', principal: 'user'})})
    );

    TestBed.configureTestingModule({
      providers: [
        SubscriptionService,
        {provide: RxStompService, useValue: spy},
      ],
    });

    service = TestBed.inject(SubscriptionService);
    rxStompServiceSpy = TestBed.inject(RxStompService) as jasmine.SpyObj<RxStompService>;

    // Set up mock subscriptions and hashtags
    service['hashtags'] = ['hashtag1', 'hashtag2'];
    service['subscriptions'] = {
      subscription1: jasmine.createSpyObj('Subscription', ['unsubscribe']),
      subscription2: jasmine.createSpyObj('Subscription', ['unsubscribe']),
    };
    service['subscriptionsSubscription'] = jasmine.createSpyObj('Subscription', ['unsubscribe']);
    service['terminationsSubscription'] = jasmine.createSpyObj('Subscription', ['unsubscribe']);
  });

  it('should unsubscribe all subscriptions in the subscriptions property', () => {
    service.terminateAllSubscriptions();

    expect(Object.keys(service['subscriptions']).length).toBe(0);
  });

  it('should unsubscribe subscriptionsSubscription and terminationsSubscription', () => {
    service.terminateAllSubscriptions();

    expect(service['subscriptionsSubscription'].unsubscribe).toHaveBeenCalled();
    expect(service['terminationsSubscription'].unsubscribe).toHaveBeenCalled();
  });

  it('should call unsubscribeHashtag for each hashtag in hashtags property', () => {
    spyOn(service, 'unsubscribeHashtag');

    service.terminateAllSubscriptions();

    expect(service.unsubscribeHashtag).toHaveBeenCalledWith('hashtag1');
    expect(service.unsubscribeHashtag).toHaveBeenCalledWith('hashtag2');
  });
});

describe('MessageQueue', () => {
  let messageQueue: MessageQueue;

  beforeEach(() => {
    messageQueue = new MessageQueue();
    // Mock the localStorage to isolate tests
    spyOn(localStorage, 'setItem').and.stub();
    spyOn(localStorage, 'getItem').and.returnValue(null);
  });

  describe('enqueue()', () => {
    it('should add a message to the storage array', () => {
      const message = {id: '1', content: 'Test Message', url: 'https://example.com'};

      messageQueue.enqueue(message);

      expect(messageQueue['storage'].length).toBe(1);
      expect(messageQueue['storage'][0]).toEqual(message);
      expect(localStorage.setItem).toHaveBeenCalledWith(
        'messageQueue',
        JSON.stringify([message])
      );
    });

    it('should append messages to the array in order', () => {
      const message1 = {id: '1', content: 'First Message', url: 'https://example.com/first'};
      const message2 = {id: '2', content: 'Second Message', url: 'https://example.com/second'};

      messageQueue.enqueue(message1);
      messageQueue.enqueue(message2);

      expect(messageQueue['storage'].length).toBe(2);
      expect(messageQueue['storage'][0]).toEqual(message1);
      expect(messageQueue['storage'][1]).toEqual(message2);
      expect(localStorage.setItem).toHaveBeenCalledTimes(2);
    });

    it('should remove oldest message, if queue limit is reached', () => {
      messageQueue = new MessageQueue(3);

      const message1 = {id: '1', content: 'First Message', url: 'https://example.com/first'};
      const message2 = {id: '2', content: 'Second Message', url: 'https://example.com/second'};
      const message3 = {id: '3', content: 'Third Message', url: 'https://example.com/third'};
      const message4 = {id: '4', content: 'Fourth Message', url: 'https://example.com/fourth'};

      messageQueue.enqueue(message1);
      messageQueue.enqueue(message2);
      messageQueue.enqueue(message3);
      messageQueue.enqueue(message4);

      expect(messageQueue['storage'].length).toBe(3);
      expect(messageQueue['storage'][0]).toEqual(message2);
      expect(messageQueue['storage'][1]).toEqual(message3);
      expect(messageQueue['storage'][2]).toEqual(message4);
      expect(localStorage.setItem).toHaveBeenCalledTimes(4);
    });
  });

  describe('dequeue()', () => {
    it('should remove and return the first message in the array', () => {
      const message1 = {id: '1', content: 'First Message', url: 'https://example.com/first'};
      const message2 = {id: '2', content: 'Second Message', url: 'https://example.com/second'};

      messageQueue.enqueue(message1);
      messageQueue.enqueue(message2);

      const dequeuedMessage = messageQueue.dequeue("1");

      expect(dequeuedMessage).toEqual(message1);
      expect(messageQueue['storage'].length).toBe(1);
      expect(messageQueue['storage'][0]).toEqual(message2);
    });

    it('should return undefined if the queue is empty', () => {
      const dequeuedMessage = messageQueue.dequeue("1");

      expect(dequeuedMessage).toBeUndefined();
      expect(messageQueue['storage'].length).toBe(0);
    });

    it('shouldn`t have empty spaces after removal', () => {
      const message1 = {id: '1', content: 'First Message', url: 'https://example.com/first'};
      const message2 = {id: '2', content: 'Second Message', url: 'https://example.com/second'};
      const message3 = {id: '3', content: 'Third Message', url: 'https://example.com/third'};

      messageQueue.enqueue(message1);
      messageQueue.enqueue(message2);
      messageQueue.enqueue(message3);

      const dequeuedMessage = messageQueue.dequeue("2");

      expect(dequeuedMessage).toEqual(message2);
      expect(messageQueue['storage'].length).toBe(2);
      expect(messageQueue['storage'][0]).toEqual(message1);
      expect(messageQueue['storage'][1]).toEqual(message3);
    });
  });

  describe('clear()', () => {
    it('should remove all messages from the storage array', () => {
      const message1 = {id: '1', content: 'First Message', url: ''};
      const message2 = {id: '2', content: 'Second Message', url: ''};

      messageQueue.enqueue(message1);
      messageQueue.enqueue(message2);

      messageQueue.clear();

      expect(messageQueue['storage'].length).toBe(0);
      expect(localStorage.setItem).toHaveBeenCalledWith(
        'messageQueue',
        JSON.stringify([])
      );
    });
  });

  describe('size()', () => {
    it('should be 0 without elements', () => {
      expect(messageQueue['storage'].length).toBe(0);
      expect(messageQueue.size()).toBe(0);
    });

    it('should be 1 with one element', () => {
      const message1 = {id: '1', content: 'First Message', url: ''};

      messageQueue.enqueue(message1);

      expect(messageQueue['storage'].length).toBe(1);
      expect(messageQueue.size()).toBe(1);
    });

    it('should be 2 with two elements', () => {
      const message1 = {id: '1', content: 'First Message', url: ''};
      const message2 = {id: '2', content: 'Second Message', url: ''};

      messageQueue.enqueue(message1);
      messageQueue.enqueue(message2);

      expect(messageQueue['storage'].length).toBe(2);
      expect(messageQueue.size()).toBe(2);
    });
  });

  describe('toArray()', () => {
    it('should return an empy array without elements', () => {
      expect(messageQueue.toArray()).toEqual([]);
    });

    it('should return an array with the same single element', () => {
      const message1 = {id: '1', content: 'First Message', url: ''};

      messageQueue.enqueue(message1);

      expect(messageQueue.toArray()).toEqual([message1]);
    });

    it('should return an array with the same elements', () => {
      const message1 = {id: '1', content: 'First Message', url: ''};
      const message2 = {id: '2', content: 'Second Message', url: ''};

      messageQueue.enqueue(message1);
      messageQueue.enqueue(message2);

      expect(messageQueue.toArray()).toEqual([message1, message2]);
    });
  });

});
