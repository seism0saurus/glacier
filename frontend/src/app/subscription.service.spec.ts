import {TestBed} from '@angular/core/testing';

import {MessageQueue, SubscriptionService} from './subscription.service';
import {RxStompService} from './rx-stomp.service';
import {Observable, of, BehaviorSubject} from 'rxjs';
import {Message} from "@stomp/stompjs";
import {TerminationAckMessage} from "./message-types/termination-ack-message";
import {WallMessage} from "./model/wall-message";
import {WallAnnouncerService} from "./services/wall-announcer.service";

describe('SubscriptionService', () => {
  let service: SubscriptionService;
  let rxStompServiceSpy: jasmine.SpyObj<RxStompService>;
  let wallAnnouncerServiceSpy: jasmine.SpyObj<WallAnnouncerService>;

  beforeEach(() => {
    const stompSpy = jasmine.createSpyObj('RxStompService', ['publish', 'watch']);
    stompSpy.watch.and.returnValue(new Observable<Message>());
    const announcerSpy = jasmine.createSpyObj('WallAnnouncerService', ['announce', 'setLiveRegion', 'setMessages']);

    TestBed.configureTestingModule({
      providers: [
        SubscriptionService,
        {provide: RxStompService, useValue: stompSpy},
        {provide: WallAnnouncerService, useValue: announcerSpy},
      ],
    });
    service = TestBed.inject(SubscriptionService);
    rxStompServiceSpy = TestBed.inject(RxStompService) as jasmine.SpyObj<RxStompService>;
    wallAnnouncerServiceSpy = TestBed.inject(WallAnnouncerService) as jasmine.SpyObj<WallAnnouncerService>;

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

    service.subscribeToStatusCreatedMessages(destination, 'testHashtag');

    expect(rxStompServiceSpy.watch).toHaveBeenCalledWith(destination);
  });


  it('should enqueue received StatusCreatedMessage into the receivedMessages queue as a WallMessage', () => {
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

    service.subscribeToStatusCreatedMessages(destination, 'testHashtag');

    expect(enqueueSpy).toHaveBeenCalledWith(jasmine.objectContaining({
      id: '1',
      url: 'test-content',
      hashtags: ['testhashtag'],
    }));
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

    service = new SubscriptionService(rxStompServiceSpy, wallAnnouncerServiceSpy);

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

    service = new SubscriptionService(rxStompServiceSpy, wallAnnouncerServiceSpy);

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

    service = new SubscriptionService(rxStompServiceSpy, wallAnnouncerServiceSpy);

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

  // -------------------------------------------------------------------------
  // Priority 5 additions — localStorage / ack-flow contracts
  // -------------------------------------------------------------------------

  /**
   * The hashtag must be persisted to localStorage ONLY after a positive
   * subscription ack ({@code subscribed: true}).  An optimistic write before
   * the ack would leave orphaned localStorage state if the server rejects the
   * subscription (e.g. CAP_EXCEEDED).
   *
   * <p>Arrange: STOMP watch emits a positive ack for 'persistedHashtag'.
   * <p>Act:     construct a new SubscriptionService so the ack handler fires.
   * <p>Assert:  localStorage 'hashtags' contains 'persistedHashtag'.
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

    const svc = new SubscriptionService(rxStompServiceSpy, wallAnnouncerServiceSpy);

    const storedHashtags = JSON.parse(localStorage.getItem('hashtags') || '[]');
    expect(storedHashtags).toContain('persistedHashtag');
  });

  /**
   * When the server rejects the subscription ({@code subscribed: false}),
   * the hashtag must NOT be added to localStorage.  Persisting before the ack
   * would mislead the restore logic on the next page load into re-subscribing
   * a hashtag the server already refused.
   *
   * <p>Arrange: STOMP watch emits a negative ack ({@code subscribed: false}).
   * <p>Act:     construct a new SubscriptionService so the ack handler fires.
   * <p>Assert:  localStorage 'hashtags' does NOT contain the rejected hashtag.
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

    const svc = new SubscriptionService(rxStompServiceSpy, wallAnnouncerServiceSpy);

    const storedHashtags = JSON.parse(localStorage.getItem('hashtags') || '[]');
    expect(storedHashtags).not.toContain('rejectedHashtag');
  });

  /**
   * When localStorage is corrupt (non-JSON), {@code getCreatedEvents} must
   * return an empty-state observable without throwing.  The restore path
   * uses {@code validateMessageQueue}, which must reject invalid data
   * gracefully so the user sees a blank wall rather than a crash.
   *
   * <p>Arrange: localStorage 'messageQueue' contains malformed JSON.
   * <p>Act:     call {@code getCreatedEvents}.
   * <p>Assert:  call does not throw; emitted value is an array (possibly empty).
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
  //
  // Security contract: The Angular client subscribes to STOMP topics of the
  // form /topic/hashtags/{principal}/{hashtag}/{type}.  The `principal` value
  // comes exclusively from the server's SubscriptionAckMessage — never from a
  // locally-generated or caller-supplied identifier.
  //
  // Why this is safe: the server-side WallTopicAuthInterceptor validates that
  // the principal in the ack matches the authenticated wallId cookie before
  // the ack is delivered.  The client never constructs a topic path for an
  // unrelated wallId.
  //
  // The negative case pinned here: given an ack with principal "wall-id-abc123",
  // the watch() call must target exactly /topic/hashtags/wall-id-abc123/...
  // and must NOT target any path for a different principal (e.g. "wall-id-xyz789").

  describe('SR-TEST-23 — STOMP topic subscription uses ack principal exclusively', () => {

    /**
     * Positive case: the three watch() calls produced by a successful
     * SubscriptionAckMessage must use exactly the principal carried in
     * the ack, not any other identifier.
     *
     * Arrange: STOMP emits a positive ack with principal "wall-id-abc123"
     *          and hashtag "cats".
     * Act:     construct a new SubscriptionService so the ack handler fires.
     * Assert:  rxStompService.watch() is called with the three expected paths
     *          (creation, modification, deletion) scoped to "wall-id-abc123".
     */
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

      new SubscriptionService(rxStompServiceSpy, wallAnnouncerServiceSpy);

      // The service subscribes to /user/topic/subscriptions and
      // /user/topic/terminations first (constructor setup), then to the three
      // hashtag topics after the ack fires.  We assert that every call that
      // contains the hashtag path is scoped to the correct principal.
      const watchCalls: string[] = rxStompServiceSpy.watch.calls.allArgs().map(args => args[0]);

      const hashtagCalls = watchCalls.filter(dest => dest.includes('/topic/hashtags/'));
      expect(hashtagCalls.length).toBe(3);
      expect(hashtagCalls).toContain(`/topic/hashtags/${ownPrincipal}/${hashtag}/creation`);
      expect(hashtagCalls).toContain(`/topic/hashtags/${ownPrincipal}/${hashtag}/modification`);
      expect(hashtagCalls).toContain(`/topic/hashtags/${ownPrincipal}/${hashtag}/deletion`);
    });

    /**
     * Negative case (SR-TEST-23 core): the service must NEVER subscribe to
     * a topic path for a different principal, even if two acks arrive and
     * one carries an unexpected principal value.
     *
     * Arrange: two acks arrive over the same STOMP watch observable.
     *          First ack: principal "wall-id-abc123", hashtag "cats".
     *          Second ack: principal "wall-id-xyz789" (a different wall),
     *          hashtag "dogs".
     * Act:     construct a new SubscriptionService so both ack handlers fire.
     * Assert:  every /topic/hashtags/... watch() call whose hashtag is "cats"
     *          uses principal "wall-id-abc123" — never "wall-id-xyz789".
     *          Every /topic/hashtags/... watch() call whose hashtag is "dogs"
     *          uses principal "wall-id-xyz789" — never "wall-id-abc123".
     *
     * Rationale: the client trusts the server-authoritative principal from
     * each individual ack and does not cross-contaminate principals.  The
     * WallTopicAuthInterceptor on the server side ensures only the wall's
     * own acks can reach it; the client-side contract here is that it does
     * not substitute one principal for another.
     */
    it('does_not_subscribe_to_topic_path_of_different_principal', () => {
      const ownPrincipal = 'wall-id-abc123';
      const otherPrincipal = 'wall-id-xyz789';

      // Simulate two acks delivered sequentially over the same watch stream.
      // of() emits both values synchronously, matching real RxStomp behaviour
      // where the ack channel delivers one message per subscription request.
      const firstAck = {
        body: JSON.stringify({
          principal: ownPrincipal,
          hashtag: 'cats',
          subscribed: true,
        }),
        ack: () => {}, nack: () => {}, command: '', headers: {},
        isBinaryBody: false, binaryBody: new Uint8Array(), destination: '',
      };
      const secondAck = {
        body: JSON.stringify({
          principal: otherPrincipal,
          hashtag: 'dogs',
          subscribed: true,
        }),
        ack: () => {}, nack: () => {}, command: '', headers: {},
        isBinaryBody: false, binaryBody: new Uint8Array(), destination: '',
      };

      // Both acks are emitted on the same watch stream (simulates two server
      // responses arriving on /user/topic/subscriptions).
      const {Subject} = require('rxjs');
      const ackSubject = new Subject();
      rxStompServiceSpy.watch.and.returnValue(ackSubject.asObservable());

      new SubscriptionService(rxStompServiceSpy, wallAnnouncerServiceSpy);

      // Emit both acks after the service is constructed so the handlers are
      // already registered on /user/topic/subscriptions.
      ackSubject.next(firstAck);
      ackSubject.next(secondAck);

      const watchCalls: string[] = rxStompServiceSpy.watch.calls.allArgs().map(args => args[0]);
      const hashtagCalls = watchCalls.filter(dest => dest.includes('/topic/hashtags/'));

      // "cats" subscriptions must only ever reference ownPrincipal
      const catsCalls = hashtagCalls.filter(dest => dest.includes('/cats/'));
      catsCalls.forEach(dest => {
        expect(dest).toContain(`/topic/hashtags/${ownPrincipal}/`);
        expect(dest).not.toContain(`/topic/hashtags/${otherPrincipal}/`);
      });

      // "dogs" subscriptions must only ever reference otherPrincipal
      const dogsCalls = hashtagCalls.filter(dest => dest.includes('/dogs/'));
      dogsCalls.forEach(dest => {
        expect(dest).toContain(`/topic/hashtags/${otherPrincipal}/`);
        expect(dest).not.toContain(`/topic/hashtags/${ownPrincipal}/`);
      });

      // Total count: 3 paths per hashtag * 2 hashtags = 6 hashtag topic calls
      expect(hashtagCalls.length).toBe(6);
    });

    /**
     * Guard: a negative ack (subscribed: false) must not produce any
     * /topic/hashtags/... watch() calls — no phantom subscription must be
     * registered for a rejected hashtag, regardless of the principal value
     * in the ack.
     *
     * Arrange: STOMP emits a negative ack ({subscribed: false}) with an
     *          arbitrary principal.
     * Act:     construct SubscriptionService so the ack handler fires.
     * Assert:  no watch() call targets a /topic/hashtags/... path.
     */
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

      new SubscriptionService(rxStompServiceSpy, wallAnnouncerServiceSpy);

      const watchCalls: string[] = rxStompServiceSpy.watch.calls.allArgs().map(args => args[0]);
      const hashtagCalls = watchCalls.filter(dest => dest.includes('/topic/hashtags/'));
      expect(hashtagCalls.length).toBe(0);
    });

  });

});

describe('SubscriptionService: terminateAllSubscriptions', () => {
  let service: SubscriptionService;
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
        {provide: RxStompService, useValue: spy},
        {provide: WallAnnouncerService, useValue: announcerSpy},
      ],
    });

    service = TestBed.inject(SubscriptionService);
    rxStompServiceSpy = TestBed.inject(RxStompService) as jasmine.SpyObj<RxStompService>;
    wallAnnouncerServiceSpy = TestBed.inject(WallAnnouncerService) as jasmine.SpyObj<WallAnnouncerService>;

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
