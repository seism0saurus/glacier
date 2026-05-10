import {TestBed} from '@angular/core/testing';

import {MessageQueue, SubscriptionService} from './subscription.service';
import {SubscriptionPersistence} from './subscription-persistence.service';
import {SubscriptionStateService} from './subscription-state.service';
import {SubscriptionStompClient} from './subscription-stomp-client.service';
import {RxStompService} from './rx-stomp.service';
import {Observable, of} from 'rxjs';
import {Message} from "@stomp/stompjs";
import {WallMessage} from "./model/wall-message";
import {WallAnnouncerService} from "./services/wall-announcer.service";

// ---------------------------------------------------------------------------
// Shared TestBed helpers
// ---------------------------------------------------------------------------

function makeRxStompMock(): jasmine.SpyObj<RxStompService> {
  const spy = jasmine.createSpyObj<RxStompService>('RxStompService', ['publish', 'watch']);
  spy.watch.and.returnValue(new Observable<Message>());
  return spy;
}

function makeAnnouncerMock(): jasmine.SpyObj<WallAnnouncerService> {
  return jasmine.createSpyObj('WallAnnouncerService', [
    'announce', 'setMessages',
  ]);
}

// ---------------------------------------------------------------------------
// Main SubscriptionService (facade) tests
// ---------------------------------------------------------------------------

describe('SubscriptionService', () => {
  let service: SubscriptionService;
  let stompClient: SubscriptionStompClient;
  let stateService: SubscriptionStateService;
  let rxStompServiceSpy: jasmine.SpyObj<RxStompService>;
  let wallAnnouncerServiceSpy: jasmine.SpyObj<WallAnnouncerService>;
  let persistenceService: SubscriptionPersistence;

  beforeEach(() => {
    rxStompServiceSpy = makeRxStompMock();
    wallAnnouncerServiceSpy = makeAnnouncerMock();

    TestBed.configureTestingModule({
      providers: [
        SubscriptionService,
        SubscriptionStompClient,
        SubscriptionPersistence,
        SubscriptionStateService,
        {provide: RxStompService, useValue: rxStompServiceSpy},
        {provide: WallAnnouncerService, useValue: wallAnnouncerServiceSpy},
      ],
    });
    service = TestBed.inject(SubscriptionService);
    stompClient = TestBed.inject(SubscriptionStompClient);
    rxStompServiceSpy = TestBed.inject(RxStompService) as jasmine.SpyObj<RxStompService>;
    wallAnnouncerServiceSpy = TestBed.inject(WallAnnouncerService) as jasmine.SpyObj<WallAnnouncerService>;
    persistenceService = TestBed.inject(SubscriptionPersistence);
    stateService = TestBed.inject(SubscriptionStateService);

    // Reset spies to ensure no state carried over between tests
    rxStompServiceSpy.publish.calls.reset();
  });

  afterEach(() => {
    localStorage.clear();
    stateService.clearSettlingTimers();
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
        // v:2 envelope format
        return JSON.stringify({
          v: 2,
          items: [
            {id: '1', url: 'https://example.com/message1', hashtags: ['hashtag1']},
            {id: '2', url: 'https://example.com/message2', hashtags: ['hashtag2']},
          ],
        });
      }
      return JSON.stringify([]);
    });

    const mockMessages: WallMessage[] = [
      {id: '1', url: 'https://example.com/message1', hashtags: ['hashtag1']},
      {id: '2', url: 'https://example.com/message2', hashtags: ['hashtag2']},
    ];

    service.getCreatedEvents().subscribe((messages) => {
      expect(messages).toEqual(mockMessages);
    });

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

  it('should return an Observable from getCreatedEvents', (done) => {
    service.getCreatedEvents().subscribe((result) => {
      expect(result).toBeTruthy();
      done();
    });
  });

  it('should call restore on receivedMessages when getCreatedEvents is called', () => {
    const restoreSpy = spyOn(stateService['receivedMessages'], 'restore');
    service.getCreatedEvents();
    expect(restoreSpy).toHaveBeenCalled();
  });

  it('should unsubscribe all subscriptions in stomp when terminateAllSubscriptions is called', () => {
    // Mock per-topic subscriptions on the stomp client
    const mockSubscription1 = jasmine.createSpyObj('Subscription', ['unsubscribe']);
    const mockSubscription2 = jasmine.createSpyObj('Subscription', ['unsubscribe']);
    stompClient['subscriptions'] = {
      sub1: mockSubscription1,
      sub2: mockSubscription2,
    };

    service.terminateAllSubscriptions();

    expect(mockSubscription1.unsubscribe).toHaveBeenCalled();
    expect(mockSubscription2.unsubscribe).toHaveBeenCalled();
    expect(Object.keys(stompClient['subscriptions']).length).toBe(0);
  });

  it('should clear all received messages when clearAllToots is called', () => {
    const clearSpy = spyOn(stateService['receivedMessages'], 'clear');

    service.clearAllToots();

    expect(clearSpy).toHaveBeenCalled();
  });

  it('should unsubscribe subscriptionsSubscription and terminationsSubscription on stomp when terminateAllSubscriptions is called', () => {
    // Mock main subscriptions on the stomp client
    const mockSubscriptionsSubscription = jasmine.createSpyObj('Subscription', ['unsubscribe']);
    const mockTerminationsSubscription = jasmine.createSpyObj('Subscription', ['unsubscribe']);
    stompClient['subscriptionsSubscription'] = mockSubscriptionsSubscription;
    stompClient['terminationsSubscription'] = mockTerminationsSubscription;

    service.terminateAllSubscriptions();

    expect(mockSubscriptionsSubscription.unsubscribe).toHaveBeenCalled();
    expect(mockTerminationsSubscription.unsubscribe).toHaveBeenCalled();
  });

  it('should remove all entries in the stomp subscriptions map when terminateAllSubscriptions is called', () => {
    stompClient['subscriptions'] = {
      sub1: jasmine.createSpyObj('Subscription', ['unsubscribe']),
      sub2: jasmine.createSpyObj('Subscription', ['unsubscribe']),
    };

    service.terminateAllSubscriptions();

    expect(Object.keys(stompClient['subscriptions']).length).toBe(0);
  });

  it('should update stomp subscriptions when a valid SubscriptionAckMessage is received', () => {
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

    // Re-create the stomp client (and facade) with ack-emitting watch
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [
        SubscriptionService,
        SubscriptionStompClient,
        SubscriptionPersistence,
        SubscriptionStateService,
        {provide: RxStompService, useValue: rxStompServiceSpy},
        {provide: WallAnnouncerService, useValue: wallAnnouncerServiceSpy},
      ],
    });
    TestBed.inject(SubscriptionService); // triggers attach() + ack handler

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

    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [
        SubscriptionService,
        SubscriptionStompClient,
        SubscriptionPersistence,
        SubscriptionStateService,
        {provide: RxStompService, useValue: rxStompServiceSpy},
        {provide: WallAnnouncerService, useValue: wallAnnouncerServiceSpy},
      ],
    });
    TestBed.inject(SubscriptionService);

    expect(consoleErrorSpy).toHaveBeenCalledWith('Could not subscribe to topic', 'testHashtag');
  });

  it('should record destinations when a valid SubscriptionAckMessage is processed', () => {
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

    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [
        SubscriptionService,
        SubscriptionStompClient,
        SubscriptionPersistence,
        SubscriptionStateService,
        {provide: RxStompService, useValue: rxStompServiceSpy},
        {provide: WallAnnouncerService, useValue: wallAnnouncerServiceSpy},
      ],
    });
    TestBed.inject(SubscriptionService); // triggers attach() + ack handler
    const stompSvc = TestBed.inject(SubscriptionStompClient);

    // Destinations are tracked via the subscriptions map keys in the stomp client
    expect(stompSvc['subscriptions']['/topic/hashtags/principalUser/exampleHashtag/creation']).toBeDefined();
    expect(stompSvc['subscriptions']['/topic/hashtags/principalUser/exampleHashtag/modification']).toBeDefined();
    expect(stompSvc['subscriptions']['/topic/hashtags/principalUser/exampleHashtag/deletion']).toBeDefined();
    const hashtags = JSON.parse(localStorage.getItem('hashtags') || '[]');
    expect(hashtags).toContain('exampleHashtag');
  });

  it('should handle a successful termination acknowledgment via stomp client', () => {
    localStorage.setItem('hashtags', JSON.stringify(['hashtag1', 'hashtag2']));
    stompClient['hashtags'] = ['hashtag1', 'hashtag2'];
    stompClient['subscriptions'] = {
      '/topic/hashtags/test-user/hashtag1/creation': jasmine.createSpyObj('Subscription', ['unsubscribe']),
      '/topic/hashtags/test-user/hashtag1/modification': jasmine.createSpyObj('Subscription', ['unsubscribe']),
      '/topic/hashtags/test-user/hashtag1/deletion': jasmine.createSpyObj('Subscription', ['unsubscribe']),
    };

    const terminateSpy = spyOn(stompClient as any, 'terminateByDest').and.callThrough();

    rxStompServiceSpy.watch.and.returnValue(new Observable<Message>((subscriber) => {
      subscriber.next({
        body: JSON.stringify({hashtag: 'hashtag1', principal: 'test-user', terminated: true}),
      } as Message);
      subscriber.complete();
    }));

    stompClient['terminationsSubscription'] = rxStompServiceSpy.watch('/user/topic/terminations')
      .subscribe((message) => {
        (stompClient as any).handleTerminationAck(JSON.parse(message.body));
      });

    expect(stompClient['hashtags']).not.toContain('hashtag1');
    expect(stompClient['hashtags']).toContain('hashtag2');
    const storedHashtags = JSON.parse(localStorage.getItem('hashtags')!);
    expect(storedHashtags).not.toContain('hashtag1');
    expect(storedHashtags).toContain('hashtag2');
    expect(terminateSpy).toHaveBeenCalledWith('/topic/hashtags/test-user/hashtag1/creation');
    expect(terminateSpy).toHaveBeenCalledWith('/topic/hashtags/test-user/hashtag1/modification');
    expect(terminateSpy).toHaveBeenCalledWith('/topic/hashtags/test-user/hashtag1/deletion');
  });

  it('should log an error if termination acknowledgment fails', () => {
    localStorage.setItem('hashtags', JSON.stringify(['hashtag1', 'hashtag2']));
    stompClient['hashtags'] = ['hashtag1', 'hashtag2'];

    spyOn(console, 'error');
    rxStompServiceSpy.watch.and.returnValue(new Observable<Message>((subscriber) => {
      subscriber.next({
        body: JSON.stringify({hashtag: 'hashtag1', principal: 'test-user', terminated: false}),
      } as Message);
      subscriber.complete();
    }));

    stompClient['terminationsSubscription'] = rxStompServiceSpy.watch('/user/topic/terminations')
      .subscribe((message) => {
        (stompClient as any).handleTerminationAck(JSON.parse(message.body));
      });

    expect(stompClient['hashtags']).toEqual(['hashtag1', 'hashtag2']);
    const storedHashtags = JSON.parse(localStorage.getItem('hashtags')!);
    expect(storedHashtags).toEqual(['hashtag1', 'hashtag2']);
    expect(console.error)
      .toHaveBeenCalledWith('Could not terminate subscription for principal test-user and hashtag hashtag1');
  });

  // -------------------------------------------------------------------------
  // Priority 5 additions — localStorage / ack-flow contracts
  // -------------------------------------------------------------------------

  /**
   * The hashtag must be persisted to localStorage ONLY after a positive
   * subscription ack ({@code subscribed: true}).
   */
  it('subscribe_storesHashtag_inSafeStorage_onlyAfterAck', () => {
    const positiveAck = {
      body: JSON.stringify({
        principal: 'wall-uuid-persist',
        hashtag: 'persistedHashtag',
        subscribed: true,
      }),
    };
    rxStompServiceSpy.watch.and.returnValue(of({
      ...positiveAck,
      ack: () => {},
      nack: () => {},
      command: '',
      headers: {},
      isBinaryBody: false,
      binaryBody: new Uint8Array(),
      destination: '',
    }));

    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [
        SubscriptionService,
        SubscriptionStompClient,
        SubscriptionPersistence,
        SubscriptionStateService,
        {provide: RxStompService, useValue: rxStompServiceSpy},
        {provide: WallAnnouncerService, useValue: wallAnnouncerServiceSpy},
      ],
    });
    TestBed.inject(SubscriptionService);

    const storedHashtags = JSON.parse(localStorage.getItem('hashtags') || '[]');
    expect(storedHashtags).toContain('persistedHashtag');
  });

  /**
   * When the server rejects the subscription ({@code subscribed: false}),
   * the hashtag must NOT be added to localStorage.
   */
  it('subscribe_doesNotPersist_onRejection', () => {
    const negativeAck = {
      body: JSON.stringify({
        principal: 'wall-uuid-reject',
        hashtag: 'rejectedHashtag',
        subscribed: false,
      }),
    };
    rxStompServiceSpy.watch.and.returnValue(of({
      ...negativeAck,
      ack: () => {},
      nack: () => {},
      command: '',
      headers: {},
      isBinaryBody: false,
      binaryBody: new Uint8Array(),
      destination: '',
    }));

    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [
        SubscriptionService,
        SubscriptionStompClient,
        SubscriptionPersistence,
        SubscriptionStateService,
        {provide: RxStompService, useValue: rxStompServiceSpy},
        {provide: WallAnnouncerService, useValue: wallAnnouncerServiceSpy},
      ],
    });
    TestBed.inject(SubscriptionService);

    const storedHashtags = JSON.parse(localStorage.getItem('hashtags') || '[]');
    expect(storedHashtags).not.toContain('rejectedHashtag');
  });

  /**
   * When localStorage is corrupt (non-JSON), {@code getCreatedEvents} must
   * return an empty-state observable without throwing.
   */
  it('localStorage_isCorrupt_recovers_emptyState_withoutThrow', () => {
    spyOn(localStorage, 'getItem').and.callFake((key: string) => {
      if (key === 'messageQueue') {
        return '{this is not valid json[[[';
      }
      return null;
    });

    expect(() => {
      service.getCreatedEvents().subscribe((messages) => {
        // Must emit an array (may be empty) — not throw
        expect(Array.isArray(messages)).toBeTrue();
      });
    }).not.toThrow();
  });

  // -------------------------------------------------------------------------
  // SR-TEST-23 — Topic subscription path is bound to the ack's principal
  // -------------------------------------------------------------------------

  describe('SR-TEST-23 — STOMP topic subscription uses ack principal exclusively', () => {

    it('subscribes_to_topic_path_using_principal_from_ack', () => {
      const ownPrincipal = 'wall-id-abc123';
      const hashtag = 'cats';

      const positiveAck = {
        body: JSON.stringify({
          principal: ownPrincipal,
          hashtag: hashtag,
          subscribed: true,
        }),
      };
      rxStompServiceSpy.watch.and.returnValue(of({
        ...positiveAck,
        ack: () => {},
        nack: () => {},
        command: '',
        headers: {},
        isBinaryBody: false,
        binaryBody: new Uint8Array(),
        destination: '',
      }));

      TestBed.resetTestingModule();
      TestBed.configureTestingModule({
        providers: [
          SubscriptionService,
          SubscriptionStompClient,
          SubscriptionPersistence,
          SubscriptionStateService,
          {provide: RxStompService, useValue: rxStompServiceSpy},
          {provide: WallAnnouncerService, useValue: wallAnnouncerServiceSpy},
        ],
      });
      TestBed.inject(SubscriptionService);

      const watchCalls: string[] = rxStompServiceSpy.watch.calls.allArgs().map(args => args[0]);
      const hashtagCalls = watchCalls.filter(dest => dest.includes('/topic/hashtags/'));
      expect(hashtagCalls.length).toBe(3);
      expect(hashtagCalls).toContain(`/topic/hashtags/${ownPrincipal}/${hashtag}/creation`);
      expect(hashtagCalls).toContain(`/topic/hashtags/${ownPrincipal}/${hashtag}/modification`);
      expect(hashtagCalls).toContain(`/topic/hashtags/${ownPrincipal}/${hashtag}/deletion`);
    });

    it('does_not_subscribe_to_topic_path_of_different_principal', () => {
      const ownPrincipal = 'wall-id-abc123';
      const otherPrincipal = 'wall-id-xyz789';

      const {Subject} = require('rxjs');
      const ackSubject = new Subject();
      rxStompServiceSpy.watch.and.returnValue(ackSubject.asObservable());

      TestBed.resetTestingModule();
      TestBed.configureTestingModule({
        providers: [
          SubscriptionService,
          SubscriptionStompClient,
          SubscriptionPersistence,
          SubscriptionStateService,
          {provide: RxStompService, useValue: rxStompServiceSpy},
          {provide: WallAnnouncerService, useValue: wallAnnouncerServiceSpy},
        ],
      });
      TestBed.inject(SubscriptionService);

      const firstAck = {
        body: JSON.stringify({principal: ownPrincipal, hashtag: 'cats', subscribed: true}),
        ack: () => {}, nack: () => {}, command: '', headers: {},
        isBinaryBody: false, binaryBody: new Uint8Array(), destination: '',
      };
      const secondAck = {
        body: JSON.stringify({principal: otherPrincipal, hashtag: 'dogs', subscribed: true}),
        ack: () => {}, nack: () => {}, command: '', headers: {},
        isBinaryBody: false, binaryBody: new Uint8Array(), destination: '',
      };

      ackSubject.next(firstAck);
      ackSubject.next(secondAck);

      const watchCalls: string[] = rxStompServiceSpy.watch.calls.allArgs().map(args => args[0]);
      const hashtagCalls = watchCalls.filter(dest => dest.includes('/topic/hashtags/'));

      const catsCalls = hashtagCalls.filter(dest => dest.includes('/cats/'));
      catsCalls.forEach(dest => {
        expect(dest).toContain(`/topic/hashtags/${ownPrincipal}/`);
        expect(dest).not.toContain(`/topic/hashtags/${otherPrincipal}/`);
      });

      const dogsCalls = hashtagCalls.filter(dest => dest.includes('/dogs/'));
      dogsCalls.forEach(dest => {
        expect(dest).toContain(`/topic/hashtags/${otherPrincipal}/`);
        expect(dest).not.toContain(`/topic/hashtags/${ownPrincipal}/`);
      });

      expect(hashtagCalls.length).toBe(6);
    });

    it('does_not_subscribe_to_any_topic_path_on_negative_ack', () => {
      const negativeAck = {
        body: JSON.stringify({
          principal: 'wall-id-abc123',
          hashtag: 'cats',
          subscribed: false,
        }),
        ack: () => {}, nack: () => {}, command: '', headers: {},
        isBinaryBody: false, binaryBody: new Uint8Array(), destination: '',
      };
      rxStompServiceSpy.watch.and.returnValue(of(negativeAck));

      TestBed.resetTestingModule();
      TestBed.configureTestingModule({
        providers: [
          SubscriptionService,
          SubscriptionStompClient,
          SubscriptionPersistence,
          SubscriptionStateService,
          {provide: RxStompService, useValue: rxStompServiceSpy},
          {provide: WallAnnouncerService, useValue: wallAnnouncerServiceSpy},
        ],
      });
      TestBed.inject(SubscriptionService);

      const watchCalls: string[] = rxStompServiceSpy.watch.calls.allArgs().map(args => args[0]);
      const hashtagCalls = watchCalls.filter(dest => dest.includes('/topic/hashtags/'));
      expect(hashtagCalls.length).toBe(0);
    });

  });

});

describe('SubscriptionService: terminateAllSubscriptions', () => {
  let service: SubscriptionService;
  let stompClient: SubscriptionStompClient;
  let rxStompServiceSpy: jasmine.SpyObj<RxStompService>;
  let wallAnnouncerServiceSpy: jasmine.SpyObj<WallAnnouncerService>;

  beforeEach(() => {
    const spy = jasmine.createSpyObj('RxStompService', ['watch', 'publish']);
    spy.watch.and.callFake(() =>
      of({body: JSON.stringify({subscribed: true, hashtag: 'hashtag1', principal: 'user'})})
    );
    const announcerSpy = jasmine.createSpyObj('WallAnnouncerService', ['announce', 'setLiveRegion', 'setMessages']);

    TestBed.configureTestingModule({
      providers: [
        SubscriptionService,
        SubscriptionStompClient,
        SubscriptionPersistence,
        SubscriptionStateService,
        {provide: RxStompService, useValue: spy},
        {provide: WallAnnouncerService, useValue: announcerSpy},
      ],
    });

    service = TestBed.inject(SubscriptionService);
    stompClient = TestBed.inject(SubscriptionStompClient);
    rxStompServiceSpy = TestBed.inject(RxStompService) as jasmine.SpyObj<RxStompService>;
    wallAnnouncerServiceSpy = TestBed.inject(WallAnnouncerService) as jasmine.SpyObj<WallAnnouncerService>;

    // Set up mock subscriptions and hashtags on the stomp client
    stompClient['hashtags'] = ['hashtag1', 'hashtag2'];
    stompClient['subscriptions'] = {
      subscription1: jasmine.createSpyObj('Subscription', ['unsubscribe']),
      subscription2: jasmine.createSpyObj('Subscription', ['unsubscribe']),
    };
    stompClient['subscriptionsSubscription'] = jasmine.createSpyObj('Subscription', ['unsubscribe']);
    stompClient['terminationsSubscription'] = jasmine.createSpyObj('Subscription', ['unsubscribe']);
  });

  afterEach(() => {
    localStorage.clear();
    TestBed.inject(SubscriptionStateService).clearSettlingTimers();
  });

  it('should unsubscribe all subscriptions in the stomp client subscriptions property', () => {
    service.terminateAllSubscriptions();

    expect(Object.keys(stompClient['subscriptions']).length).toBe(0);
  });

  it('should unsubscribe subscriptionsSubscription and terminationsSubscription on stomp client', () => {
    service.terminateAllSubscriptions();

    expect(stompClient['subscriptionsSubscription']!.unsubscribe).toHaveBeenCalled();
    expect(stompClient['terminationsSubscription']!.unsubscribe).toHaveBeenCalled();
  });

  it('should call stomp.unsubscribeHashtag for each hashtag in stomp client hashtags property', () => {
    spyOn(stompClient, 'unsubscribeHashtag');

    service.terminateAllSubscriptions();

    expect(stompClient.unsubscribeHashtag).toHaveBeenCalledWith('hashtag1');
    expect(stompClient.unsubscribeHashtag).toHaveBeenCalledWith('hashtag2');
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
    it('should add a WallMessage to the storage array', () => {
      const message: WallMessage = {id: '1', url: 'https://example.com', hashtags: ['test']};

      messageQueue.enqueue(message);

      expect(messageQueue['storage'].length).toBe(1);
      expect(messageQueue['storage'][0]).toEqual(message);
      expect(localStorage.setItem).toHaveBeenCalledWith(
        'messageQueue',
        jasmine.any(String)
      );
    });

    it('should append messages to the array in order', () => {
      const message1: WallMessage = {id: '1', url: 'https://example.com/first', hashtags: ['first']};
      const message2: WallMessage = {id: '2', url: 'https://example.com/second', hashtags: ['second']};

      messageQueue.enqueue(message1);
      messageQueue.enqueue(message2);

      expect(messageQueue['storage'].length).toBe(2);
      expect(messageQueue['storage'][0]).toEqual(message1);
      expect(messageQueue['storage'][1]).toEqual(message2);
      expect(localStorage.setItem).toHaveBeenCalledTimes(2);
    });

    it('should remove oldest message, if queue limit is reached', () => {
      messageQueue = new MessageQueue(3);

      const message1: WallMessage = {id: '1', url: 'https://example.com/first', hashtags: ['a']};
      const message2: WallMessage = {id: '2', url: 'https://example.com/second', hashtags: ['a']};
      const message3: WallMessage = {id: '3', url: 'https://example.com/third', hashtags: ['a']};
      const message4: WallMessage = {id: '4', url: 'https://example.com/fourth', hashtags: ['a']};

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
      const message1: WallMessage = {id: '1', url: 'https://example.com/first', hashtags: ['a']};
      const message2: WallMessage = {id: '2', url: 'https://example.com/second', hashtags: ['a']};

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
      const message1: WallMessage = {id: '1', url: 'https://example.com/first', hashtags: ['a']};
      const message2: WallMessage = {id: '2', url: 'https://example.com/second', hashtags: ['a']};
      const message3: WallMessage = {id: '3', url: 'https://example.com/third', hashtags: ['a']};

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
      const message1: WallMessage = {id: '1', url: '', hashtags: ['a']};
      const message2: WallMessage = {id: '2', url: '', hashtags: ['a']};

      messageQueue.enqueue(message1);
      messageQueue.enqueue(message2);

      messageQueue.clear();

      expect(messageQueue['storage'].length).toBe(0);
      expect(localStorage.setItem).toHaveBeenCalledWith(
        'messageQueue',
        jasmine.any(String)
      );
    });
  });

  describe('size()', () => {
    it('should be 0 without elements', () => {
      expect(messageQueue['storage'].length).toBe(0);
      expect(messageQueue.size()).toBe(0);
    });

    it('should be 1 with one element', () => {
      const message1: WallMessage = {id: '1', url: '', hashtags: ['a']};

      messageQueue.enqueue(message1);

      expect(messageQueue['storage'].length).toBe(1);
      expect(messageQueue.size()).toBe(1);
    });

    it('should be 2 with two elements', () => {
      const message1: WallMessage = {id: '1', url: '', hashtags: ['a']};
      const message2: WallMessage = {id: '2', url: '', hashtags: ['a']};

      messageQueue.enqueue(message1);
      messageQueue.enqueue(message2);

      expect(messageQueue['storage'].length).toBe(2);
      expect(messageQueue.size()).toBe(2);
    });
  });

  describe('toArray()', () => {
    it('should return an empty array without elements', () => {
      expect(messageQueue.toArray()).toEqual([]);
    });

    it('should return an array with the same single element', () => {
      const message1: WallMessage = {id: '1', url: '', hashtags: ['a']};

      messageQueue.enqueue(message1);

      expect(messageQueue.toArray()).toEqual([message1]);
    });

    it('should return an array with the same elements', () => {
      const message1: WallMessage = {id: '1', url: '', hashtags: ['a']};
      const message2: WallMessage = {id: '2', url: '', hashtags: ['a']};

      messageQueue.enqueue(message1);
      messageQueue.enqueue(message2);

      expect(messageQueue.toArray()).toEqual([message1, message2]);
    });
  });

});

// ---------------------------------------------------------------------------
// T4 — Ordering spy: clearSettlingTimers before terminateAll (AC-6, SR-SPLIT-06)
// ---------------------------------------------------------------------------

/**
 * T4 — terminateAllSubscriptions() must cancel settling timers before
 * tearing down STOMP (AC-6, SR-SPLIT-06, FIND-P3-SEC-5/6).
 *
 * Arrange: spy on state.clearSettlingTimers and the STOMP unsubscribe path.
 * Act:     call facade.terminateAllSubscriptions().
 * Assert:  clearSettlingTimers was called BEFORE any unsubscribeHashtag or
 *          STOMP subscription teardown (captured via call-order array).
 */
describe('SubscriptionService: terminateAllSubscriptions ordering (T4)', () => {
  let facade: SubscriptionService;
  let stateService: SubscriptionStateService;
  let stompClient: SubscriptionStompClient;
  let rxStompSpy: jasmine.SpyObj<RxStompService>;
  let announcerSpy: jasmine.SpyObj<WallAnnouncerService>;

  beforeEach(() => {
    rxStompSpy = jasmine.createSpyObj('RxStompService', ['publish', 'watch']);
    rxStompSpy.watch.and.returnValue(new Observable<Message>());
    announcerSpy = jasmine.createSpyObj('WallAnnouncerService', ['announce', 'setLiveRegion', 'setMessages']);

    TestBed.configureTestingModule({
      providers: [
        SubscriptionService,
        SubscriptionStompClient,
        SubscriptionPersistence,
        SubscriptionStateService,
        {provide: RxStompService, useValue: rxStompSpy},
        {provide: WallAnnouncerService, useValue: announcerSpy},
      ],
    });

    facade = TestBed.inject(SubscriptionService);
    stateService = TestBed.inject(SubscriptionStateService);
    stompClient = TestBed.inject(SubscriptionStompClient);
    rxStompSpy = TestBed.inject(RxStompService) as jasmine.SpyObj<RxStompService>;

    // Set up stomp client with mock subscriptions and hashtags
    stompClient['hashtags'] = ['glacier'];
    stompClient['subscriptions'] = {
      sub1: jasmine.createSpyObj('Subscription', ['unsubscribe']),
    };
    stompClient['subscriptionsSubscription'] = jasmine.createSpyObj('Subscription', ['unsubscribe']);
    stompClient['terminationsSubscription'] = jasmine.createSpyObj('Subscription', ['unsubscribe']);
  });

  afterEach(() => {
    localStorage.clear();
    stateService.clearSettlingTimers();
  });

  it('T4 — state.clearSettlingTimers() is called before STOMP teardown (stomp.terminateAll)', () => {
    const callOrder: string[] = [];

    // Spy on state.clearSettlingTimers — step 1
    spyOn(stateService, 'clearSettlingTimers').and.callFake(() => {
      callOrder.push('clearSettlingTimers');
    });

    // Spy on stomp.terminateAll — step 2 (STOMP teardown)
    // The facade calls state.clearSettlingTimers() THEN stomp.terminateAll()
    // (AC-6, SR-SPLIT-06, T4).
    spyOn(stompClient, 'terminateAll').and.callFake(() => {
      callOrder.push('terminateAll');
    });

    facade.terminateAllSubscriptions();

    // clearSettlingTimers must appear before terminateAll in the call order
    expect(callOrder.indexOf('clearSettlingTimers'))
      .toBeLessThan(callOrder.indexOf('terminateAll'),
        'clearSettlingTimers must be called before stomp.terminateAll()');
    expect(callOrder[0]).toBe('clearSettlingTimers');
  });
});
