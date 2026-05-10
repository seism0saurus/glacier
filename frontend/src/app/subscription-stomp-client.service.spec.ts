import {TestBed} from '@angular/core/testing';
import {Observable, of, Subject} from 'rxjs';
import {Message} from '@stomp/stompjs';

import {SubscriptionStompClient} from './subscription-stomp-client.service';
import {SubscriptionPersistence} from './subscription-persistence.service';
import {SubscriptionStateService} from './subscription-state.service';
import {RxStompService} from './rx-stomp.service';
import {WallAnnouncerService} from './services/wall-announcer.service';
import {SubscriptionAckMessage} from './message-types/subscription-ack-message';
import {TerminationAckMessage} from './message-types/termination-ack-message';
import {WallMessage} from './model/wall-message';

// ---------------------------------------------------------------------------
// Shared helper: builds a minimal STOMP Message-like object from a plain body
// ---------------------------------------------------------------------------
function stompMsg(body: object): any {
  return {
    body: JSON.stringify(body),
    ack: () => {},
    nack: () => {},
    command: '',
    headers: {},
    isBinaryBody: false,
    binaryBody: new Uint8Array(),
    destination: '',
  };
}

// ---------------------------------------------------------------------------
// Common TestBed setup
// ---------------------------------------------------------------------------

function makeRxStompMock(): jasmine.SpyObj<RxStompService> {
  const spy = jasmine.createSpyObj<RxStompService>('RxStompService', ['publish', 'watch']);
  spy.watch.and.returnValue(new Observable<Message>());
  return spy;
}

function makeAnnouncerMock(): jasmine.SpyObj<WallAnnouncerService> {
  return jasmine.createSpyObj('WallAnnouncerService', [
    'announce',
    'setMessages',
  ]);
}

// ---------------------------------------------------------------------------
// SR-SPLIT-04 / T5 — Guard-gate: real SubscriptionStateService, no spy on
//                    isRecentlyTerminated, no bracket-access to internals.
// ---------------------------------------------------------------------------

/**
 * SR-SPLIT-04 / T5 — Guard gate tests use a REAL SubscriptionStateService.
 *
 * Security contract (SR-PRUNE-04/06):
 *   The guard gate that prevents late STOMP deliveries from re-adding pruned
 *   toots MUST use the real isRecentlyTerminated() implementation.  Mocking
 *   or spying on that method would defeat the contract: a spy that returns
 *   false unconditionally would silently allow re-injection of pruned content.
 *
 * Discipline check:
 *   - No spyOn(state, 'isRecentlyTerminated') anywhere in this spec.
 *   - No bracket-access mutation of guard state (state['recentlyTerminated']).
 *   - Guard window seeded exclusively via state.seedRecentlyTerminated() (public API).
 *
 * AC-18: this describe block must not contain 'state[\'recentlyTerminated\']'.
 */
describe('SubscriptionStompClient — guard gate (SR-SPLIT-04 / T5)', () => {
  let stomp: SubscriptionStompClient;
  let state: SubscriptionStateService;
  let rxStompMock: jasmine.SpyObj<RxStompService>;
  let wallAnnouncerMock: jasmine.SpyObj<WallAnnouncerService>;

  beforeEach(() => {
    rxStompMock = makeRxStompMock();
    wallAnnouncerMock = makeAnnouncerMock();

    TestBed.configureTestingModule({
      providers: [
        SubscriptionPersistence,              // real, no deps
        SubscriptionStateService,             // real, depends only on Persistence
        SubscriptionStompClient,              // SUT
        {provide: RxStompService, useValue: rxStompMock},
        {provide: WallAnnouncerService, useValue: wallAnnouncerMock},
      ],
    });

    stomp = TestBed.inject(SubscriptionStompClient);
    state = TestBed.inject(SubscriptionStateService);
  });

  afterEach(() => {
    localStorage.clear();
    state.clearSettlingTimers();
  });

  it('T5 — isRecentlyTerminated on state is the real method, not a spy', () => {
    // Discipline check: confirm state.isRecentlyTerminated is not a spy.
    // A spy object has a `.and` property; the real method does not.
    // This assertion prevents future tests from accidentally adding a spy
    // and silently breaking the guard-gate contract (SR-PRUNE-04/06).
    expect((state.isRecentlyTerminated as any).and).toBeUndefined();
  });

  it('should drop incoming STOMP delivery when hashtag is in recentlyTerminated guard', () => {
    // Arrange: seed the guard window via the public API — never via bracket-access.
    // AC-18 / SR-SPLIT-04: do NOT write state['recentlyTerminated'].set(...)
    state.seedRecentlyTerminated('glacier');

    const enqueueSpy = spyOn(state, 'enqueueWallMessage');

    const testMsg = stompMsg({id: '1', author: '', url: 'https://example.com'});
    rxStompMock.watch.and.returnValue({
      subscribe: (callback: (m: any) => void) => {
        callback(testMsg);
        return {unsubscribe: jasmine.createSpy('unsubscribe')};
      },
    } as any);

    stomp['subscribeToCreated']('/topic/hashtags/u/glacier/creation', 'glacier');

    expect(enqueueSpy).not.toHaveBeenCalled();
  });

  it('should enqueue incoming STOMP delivery when hashtag is NOT in guard window', () => {
    // Arrange: guard map is empty — 'foss' has no guard entry
    const enqueueSpy = spyOn(state, 'enqueueWallMessage');

    const testMsg = stompMsg({id: '2', author: '', url: 'https://example.com/2'});
    rxStompMock.watch.and.returnValue({
      subscribe: (callback: (m: any) => void) => {
        callback(testMsg);
        return {unsubscribe: jasmine.createSpy('unsubscribe')};
      },
    } as any);

    stomp['subscribeToCreated']('/topic/hashtags/u/foss/creation', 'foss');

    expect(enqueueSpy).toHaveBeenCalledOnceWith(
      jasmine.objectContaining({id: '2', hashtags: ['foss']}),
    );
  });

  it('should drop STOMP delivery with empty hashtag (SR-PRUNE-08 guard)', () => {
    const enqueueSpy = spyOn(state, 'enqueueWallMessage');

    const testMsg = stompMsg({id: '3', author: '', url: 'https://example.com/3'});
    rxStompMock.watch.and.returnValue({
      subscribe: (callback: (m: any) => void) => {
        callback(testMsg);
        return {unsubscribe: jasmine.createSpy('unsubscribe')};
      },
    } as any);

    // Empty hashtag normalises to '' (falsy) — must be dropped before enqueue
    stomp['subscribeToCreated']('/topic/hashtags/u//creation', '');

    expect(enqueueSpy).not.toHaveBeenCalled();
  });
});

// ---------------------------------------------------------------------------
// SR-SPLIT-05 / T6 — handleTerminationAck 4-step sequence is synchronous
// ---------------------------------------------------------------------------

/**
 * SR-SPLIT-05 / T6 — The 4-step termination-ack sequence (SR-PRUNE-13, U-SEC-13):
 *
 *   Step 1: unsubscribe 3 topic watches (terminateByDest × 3)
 *   Step 2: state.seedRecentlyTerminated (guard window)
 *   Step 3: state.pruneByHashtag → PruneResult
 *   Step 4: wallAnnouncer.announce({type:'prune', result})
 *
 * All steps must execute within the same synchronous call — no await,
 * Promise.then(), setTimeout(), or queueMicrotask() between steps.
 *
 * The test captures call order in a string array and asserts the entire
 * array is populated BEFORE any flushMicrotasks() or tick().
 */
describe('SubscriptionStompClient — 4-step ack sequence (SR-SPLIT-05 / T6 / U-SEC-13)', () => {
  let stomp: SubscriptionStompClient;
  let state: SubscriptionStateService;
  let rxStompMock: jasmine.SpyObj<RxStompService>;
  let wallAnnouncerMock: jasmine.SpyObj<WallAnnouncerService>;

  beforeEach(() => {
    rxStompMock = makeRxStompMock();
    wallAnnouncerMock = makeAnnouncerMock();

    TestBed.configureTestingModule({
      providers: [
        SubscriptionPersistence,
        SubscriptionStateService,
        SubscriptionStompClient,
        {provide: RxStompService, useValue: rxStompMock},
        {provide: WallAnnouncerService, useValue: wallAnnouncerMock},
      ],
    });

    stomp = TestBed.inject(SubscriptionStompClient);
    state = TestBed.inject(SubscriptionStateService);
  });

  afterEach(() => {
    localStorage.clear();
    state.clearSettlingTimers();
  });

  it('T6 — all 6 ack sub-steps execute synchronously in one tick, in correct order', () => {
    // Arrange: register 3 mock per-topic subscriptions so terminateByDest works
    stomp['subscriptions'] = {
      '/topic/hashtags/u/glacier/creation': jasmine.createSpyObj('Subscription', ['unsubscribe']),
      '/topic/hashtags/u/glacier/modification': jasmine.createSpyObj('Subscription', ['unsubscribe']),
      '/topic/hashtags/u/glacier/deletion': jasmine.createSpyObj('Subscription', ['unsubscribe']),
    };
    stomp['hashtags'] = ['glacier'];

    const callOrder: string[] = [];

    // Step 1: terminateByDest × 3 (track via subscriptions spy)
    spyOn(stomp as any, 'terminateByDest').and.callFake(() => {
      callOrder.push('terminate');
    });
    // Step 2: seedRecentlyTerminated
    spyOn(state, 'seedRecentlyTerminated').and.callFake(() => {
      callOrder.push('seed');
    });
    // Step 3: pruneByHashtag
    spyOn(state, 'pruneByHashtag').and.callFake(() => {
      callOrder.push('prune');
      return {removed: [], remaining: []};
    });
    // Step 4: wallAnnouncer.announce
    wallAnnouncerMock.announce.and.callFake(() => {
      callOrder.push('announce');
    });

    // Act: call handleTerminationAck synchronously (no await, no tick, no flush)
    const terminationAck: TerminationAckMessage = {
      principal: 'u',
      hashtag: 'glacier',
      terminated: true,
    };
    (stomp as any).handleTerminationAck(terminationAck);

    // Assert: all steps completed synchronously — callOrder is fully populated
    // BEFORE any flushMicrotasks() or tick() (SR-SPLIT-05, U-SEC-13).
    expect(callOrder.length).toBe(6, 'expected 6 sub-steps to complete synchronously');

    // terminate × 3 (creation/modification/deletion), then seed, prune, announce
    expect(callOrder.slice(0, 3)).toEqual(['terminate', 'terminate', 'terminate']);
    expect(callOrder[3]).toBe('seed');
    expect(callOrder[4]).toBe('prune');
    expect(callOrder[5]).toBe('announce');
  });
});

// ---------------------------------------------------------------------------
// SR-TEST-23 — Principal binding: topic path uses ack's principal exclusively
// ---------------------------------------------------------------------------

/**
 * SR-TEST-23 — Security contract:
 *
 * The STOMP topic path /topic/hashtags/{principal}/{hashtag}/{type} must use
 * `data.principal` from the server ack exclusively — never a locally-generated,
 * cached, or caller-supplied identifier.
 *
 * The server-side WallTopicAuthInterceptor validates the principal before the
 * ack is delivered, so the client-side contract is that it does not substitute
 * one principal for another.
 */
describe('SubscriptionStompClient — SR-TEST-23: topic path uses ack principal exclusively', () => {
  let stomp: SubscriptionStompClient;
  let state: SubscriptionStateService;
  let rxStompMock: jasmine.SpyObj<RxStompService>;
  let wallAnnouncerMock: jasmine.SpyObj<WallAnnouncerService>;

  beforeEach(() => {
    rxStompMock = makeRxStompMock();
    wallAnnouncerMock = makeAnnouncerMock();

    TestBed.configureTestingModule({
      providers: [
        SubscriptionPersistence,
        SubscriptionStateService,
        SubscriptionStompClient,
        {provide: RxStompService, useValue: rxStompMock},
        {provide: WallAnnouncerService, useValue: wallAnnouncerMock},
      ],
    });

    stomp = TestBed.inject(SubscriptionStompClient);
    state = TestBed.inject(SubscriptionStateService);
  });

  afterEach(() => {
    localStorage.clear();
    state.clearSettlingTimers();
  });

  it('positive case — watch() calls use the principal from the ack', () => {
    const ownPrincipal = 'wall-id-abc123';
    const hashtag = 'cats';

    rxStompMock.watch.and.returnValue(of(stompMsg({
      principal: ownPrincipal,
      hashtag,
      subscribed: true,
    })));

    // Construct with attach() so ack listeners are registered
    stomp.attach();

    const watchCalls: string[] = rxStompMock.watch.calls.allArgs().map(args => args[0]);
    const hashtagCalls = watchCalls.filter(dest => dest.includes('/topic/hashtags/'));

    expect(hashtagCalls.length).toBe(3);
    expect(hashtagCalls).toContain(`/topic/hashtags/${ownPrincipal}/${hashtag}/creation`);
    expect(hashtagCalls).toContain(`/topic/hashtags/${ownPrincipal}/${hashtag}/modification`);
    expect(hashtagCalls).toContain(`/topic/hashtags/${ownPrincipal}/${hashtag}/deletion`);
  });

  it('negative case — cats subscriptions never reference a different principal', () => {
    const ownPrincipal = 'wall-id-abc123';
    const otherPrincipal = 'wall-id-xyz789';

    const ackSubject = new Subject<any>();
    rxStompMock.watch.and.returnValue(ackSubject.asObservable());

    stomp.attach();

    ackSubject.next(stompMsg({principal: ownPrincipal, hashtag: 'cats', subscribed: true}));
    ackSubject.next(stompMsg({principal: otherPrincipal, hashtag: 'dogs', subscribed: true}));

    const watchCalls: string[] = rxStompMock.watch.calls.allArgs().map(args => args[0]);
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

    // 3 paths per hashtag × 2 hashtags = 6 hashtag topic calls
    expect(hashtagCalls.length).toBe(6);
  });

  it('negative-ack case — no topic subscription on subscribed: false', () => {
    rxStompMock.watch.and.returnValue(of(stompMsg({
      principal: 'wall-id-abc123',
      hashtag: 'cats',
      subscribed: false,
    })));

    stomp.attach();

    const watchCalls: string[] = rxStompMock.watch.calls.allArgs().map(args => args[0]);
    const hashtagCalls = watchCalls.filter(dest => dest.includes('/topic/hashtags/'));
    expect(hashtagCalls.length).toBe(0);
  });
});

// ---------------------------------------------------------------------------
// attach() idempotency
// ---------------------------------------------------------------------------

describe('SubscriptionStompClient — attach() idempotency', () => {
  let stomp: SubscriptionStompClient;
  let state: SubscriptionStateService;
  let rxStompMock: jasmine.SpyObj<RxStompService>;
  let wallAnnouncerMock: jasmine.SpyObj<WallAnnouncerService>;

  beforeEach(() => {
    rxStompMock = makeRxStompMock();
    wallAnnouncerMock = makeAnnouncerMock();

    TestBed.configureTestingModule({
      providers: [
        SubscriptionPersistence,
        SubscriptionStateService,
        SubscriptionStompClient,
        {provide: RxStompService, useValue: rxStompMock},
        {provide: WallAnnouncerService, useValue: wallAnnouncerMock},
      ],
    });

    stomp = TestBed.inject(SubscriptionStompClient);
    state = TestBed.inject(SubscriptionStateService);
  });

  afterEach(() => {
    localStorage.clear();
    state.clearSettlingTimers();
  });

  it('should register ack listeners exactly once even when attach() is called twice', () => {
    stomp.attach();
    stomp.attach(); // second call must be a no-op

    // /user/topic/subscriptions and /user/topic/terminations must be watched
    // only once each — idempotency guard prevents double-registration.
    const watchCalls: string[] = rxStompMock.watch.calls.allArgs().map(args => args[0]);
    const subscriptionWatches = watchCalls.filter(d => d === '/user/topic/subscriptions');
    const terminationWatches = watchCalls.filter(d => d === '/user/topic/terminations');

    expect(subscriptionWatches.length).toBe(1);
    expect(terminationWatches.length).toBe(1);
  });
});

// ---------------------------------------------------------------------------
// subscribeHashtag / unsubscribeHashtag publish paths
// ---------------------------------------------------------------------------

describe('SubscriptionStompClient — publish paths', () => {
  let stomp: SubscriptionStompClient;
  let state: SubscriptionStateService;
  let rxStompMock: jasmine.SpyObj<RxStompService>;
  let wallAnnouncerMock: jasmine.SpyObj<WallAnnouncerService>;

  beforeEach(() => {
    rxStompMock = makeRxStompMock();
    wallAnnouncerMock = makeAnnouncerMock();

    TestBed.configureTestingModule({
      providers: [
        SubscriptionPersistence,
        SubscriptionStateService,
        SubscriptionStompClient,
        {provide: RxStompService, useValue: rxStompMock},
        {provide: WallAnnouncerService, useValue: wallAnnouncerMock},
      ],
    });

    stomp = TestBed.inject(SubscriptionStompClient);
    state = TestBed.inject(SubscriptionStateService);
    rxStompMock.publish.calls.reset();
  });

  afterEach(() => {
    localStorage.clear();
    state.clearSettlingTimers();
  });

  it('subscribeHashtag() publishes to /glacier/subscription', () => {
    stomp.subscribeHashtag('glacier');
    expect(rxStompMock.publish).toHaveBeenCalledOnceWith({
      destination: '/glacier/subscription',
      body: JSON.stringify({hashtag: 'glacier'}),
    });
  });

  it('unsubscribeHashtag() publishes to /glacier/termination', () => {
    stomp.unsubscribeHashtag('foss');
    expect(rxStompMock.publish).toHaveBeenCalledOnceWith({
      destination: '/glacier/termination',
      body: JSON.stringify({hashtag: 'foss'}),
    });
  });
});

// ---------------------------------------------------------------------------
// terminateAll() — unsubscribes all handles
// ---------------------------------------------------------------------------

describe('SubscriptionStompClient — terminateAll()', () => {
  let stomp: SubscriptionStompClient;
  let state: SubscriptionStateService;
  let rxStompMock: jasmine.SpyObj<RxStompService>;
  let wallAnnouncerMock: jasmine.SpyObj<WallAnnouncerService>;

  beforeEach(() => {
    rxStompMock = makeRxStompMock();
    wallAnnouncerMock = makeAnnouncerMock();

    TestBed.configureTestingModule({
      providers: [
        SubscriptionPersistence,
        SubscriptionStateService,
        SubscriptionStompClient,
        {provide: RxStompService, useValue: rxStompMock},
        {provide: WallAnnouncerService, useValue: wallAnnouncerMock},
      ],
    });

    stomp = TestBed.inject(SubscriptionStompClient);
    state = TestBed.inject(SubscriptionStateService);
  });

  afterEach(() => {
    localStorage.clear();
    state.clearSettlingTimers();
  });

  it('terminateAll() publishes termination for each tracked hashtag', () => {
    stomp['hashtags'] = ['glacier', 'foss'];
    rxStompMock.publish.calls.reset();

    stomp.terminateAll();

    expect(rxStompMock.publish).toHaveBeenCalledWith({
      destination: '/glacier/termination',
      body: JSON.stringify({hashtag: 'glacier'}),
    });
    expect(rxStompMock.publish).toHaveBeenCalledWith({
      destination: '/glacier/termination',
      body: JSON.stringify({hashtag: 'foss'}),
    });
  });

  it('terminateAll() unsubscribes all per-topic subscription handles', () => {
    const sub1 = jasmine.createSpyObj('Subscription', ['unsubscribe']);
    const sub2 = jasmine.createSpyObj('Subscription', ['unsubscribe']);
    stomp['subscriptions'] = {dest1: sub1, dest2: sub2};

    stomp.terminateAll();

    expect(sub1.unsubscribe).toHaveBeenCalled();
    expect(sub2.unsubscribe).toHaveBeenCalled();
    expect(Object.keys(stomp['subscriptions']).length).toBe(0);
  });

  it('terminateAll() unsubscribes the main ack-listener handles', () => {
    const subsSub = jasmine.createSpyObj('Subscription', ['unsubscribe']);
    const termSub = jasmine.createSpyObj('Subscription', ['unsubscribe']);
    stomp['subscriptionsSubscription'] = subsSub;
    stomp['terminationsSubscription'] = termSub;

    stomp.terminateAll();

    expect(subsSub.unsubscribe).toHaveBeenCalled();
    expect(termSub.unsubscribe).toHaveBeenCalled();
  });
});

// ---------------------------------------------------------------------------
// handleSubscriptionAck — full ack-flow coverage
// ---------------------------------------------------------------------------

describe('SubscriptionStompClient — handleSubscriptionAck', () => {
  let stomp: SubscriptionStompClient;
  let state: SubscriptionStateService;
  let persistence: SubscriptionPersistence;
  let rxStompMock: jasmine.SpyObj<RxStompService>;
  let wallAnnouncerMock: jasmine.SpyObj<WallAnnouncerService>;

  beforeEach(() => {
    rxStompMock = makeRxStompMock();
    wallAnnouncerMock = makeAnnouncerMock();

    TestBed.configureTestingModule({
      providers: [
        SubscriptionPersistence,
        SubscriptionStateService,
        SubscriptionStompClient,
        {provide: RxStompService, useValue: rxStompMock},
        {provide: WallAnnouncerService, useValue: wallAnnouncerMock},
      ],
    });

    stomp = TestBed.inject(SubscriptionStompClient);
    state = TestBed.inject(SubscriptionStateService);
    persistence = TestBed.inject(SubscriptionPersistence);
    rxStompMock.publish.calls.reset();
  });

  afterEach(() => {
    localStorage.clear();
    state.clearSettlingTimers();
  });

  it('positive ack — hashtag is appended to internal list and persisted', () => {
    rxStompMock.watch.and.returnValue(of(stompMsg({
      principal: 'wall-uuid-persist',
      hashtag: 'persistedHashtag',
      subscribed: true,
    })));

    stomp.attach();

    expect(stomp['hashtags']).toContain('persistedHashtag');
    const stored = JSON.parse(localStorage.getItem('hashtags') || '[]');
    expect(stored).toContain('persistedHashtag');
  });

  it('positive ack — three topic watches (creation, modification, deletion) are opened', () => {
    const principal = 'wall-id-abc123';
    const hashtag = 'cats';

    rxStompMock.watch.and.returnValue(of(stompMsg({
      principal,
      hashtag,
      subscribed: true,
    })));

    stomp.attach();

    const watchCalls: string[] = rxStompMock.watch.calls.allArgs().map(args => args[0]);
    const hashtagCalls = watchCalls.filter(d => d.includes('/topic/hashtags/'));
    expect(hashtagCalls).toContain(`/topic/hashtags/${principal}/${hashtag}/creation`);
    expect(hashtagCalls).toContain(`/topic/hashtags/${principal}/${hashtag}/modification`);
    expect(hashtagCalls).toContain(`/topic/hashtags/${principal}/${hashtag}/deletion`);
  });

  it('negative ack — hashtag is NOT persisted and no topic watch is opened', () => {
    rxStompMock.watch.and.returnValue(of(stompMsg({
      principal: 'wall-uuid-reject',
      hashtag: 'rejectedHashtag',
      subscribed: false,
    })));

    const consoleErrorSpy = spyOn(console, 'error');
    stomp.attach();

    const stored = JSON.parse(localStorage.getItem('hashtags') || '[]');
    expect(stored).not.toContain('rejectedHashtag');

    const watchCalls: string[] = rxStompMock.watch.calls.allArgs().map(args => args[0]);
    const hashtagCalls = watchCalls.filter(d => d.includes('/topic/hashtags/'));
    expect(hashtagCalls.length).toBe(0);
    expect(consoleErrorSpy).toHaveBeenCalledWith('Could not subscribe to topic', 'rejectedHashtag');
  });
});

// ---------------------------------------------------------------------------
// handleTerminationAck — termination-ack happy path
// ---------------------------------------------------------------------------

describe('SubscriptionStompClient — handleTerminationAck', () => {
  let stomp: SubscriptionStompClient;
  let state: SubscriptionStateService;
  let rxStompMock: jasmine.SpyObj<RxStompService>;
  let wallAnnouncerMock: jasmine.SpyObj<WallAnnouncerService>;

  beforeEach(() => {
    rxStompMock = makeRxStompMock();
    wallAnnouncerMock = makeAnnouncerMock();

    TestBed.configureTestingModule({
      providers: [
        SubscriptionPersistence,
        SubscriptionStateService,
        SubscriptionStompClient,
        {provide: RxStompService, useValue: rxStompMock},
        {provide: WallAnnouncerService, useValue: wallAnnouncerMock},
      ],
    });

    stomp = TestBed.inject(SubscriptionStompClient);
    state = TestBed.inject(SubscriptionStateService);
  });

  afterEach(() => {
    localStorage.clear();
    state.clearSettlingTimers();
  });

  it('positive termination — hashtag is removed from internal list and persisted', () => {
    localStorage.setItem('hashtags', JSON.stringify(['hashtag1', 'hashtag2']));
    stomp['hashtags'] = ['hashtag1', 'hashtag2'];
    stomp['subscriptions'] = {
      '/topic/hashtags/test-user/hashtag1/creation': jasmine.createSpyObj('Subscription', ['unsubscribe']),
      '/topic/hashtags/test-user/hashtag1/modification': jasmine.createSpyObj('Subscription', ['unsubscribe']),
      '/topic/hashtags/test-user/hashtag1/deletion': jasmine.createSpyObj('Subscription', ['unsubscribe']),
    };

    const ack: TerminationAckMessage = {
      hashtag: 'hashtag1',
      principal: 'test-user',
      terminated: true,
    };
    (stomp as any).handleTerminationAck(ack);

    expect(stomp['hashtags']).not.toContain('hashtag1');
    expect(stomp['hashtags']).toContain('hashtag2');
    const stored = JSON.parse(localStorage.getItem('hashtags') || '[]');
    expect(stored).not.toContain('hashtag1');
    expect(stored).toContain('hashtag2');
  });

  it('positive termination — terminateByDest is called for all 3 topic paths', () => {
    stomp['hashtags'] = ['glacier'];
    stomp['subscriptions'] = {
      '/topic/hashtags/u/glacier/creation': jasmine.createSpyObj('Subscription', ['unsubscribe']),
      '/topic/hashtags/u/glacier/modification': jasmine.createSpyObj('Subscription', ['unsubscribe']),
      '/topic/hashtags/u/glacier/deletion': jasmine.createSpyObj('Subscription', ['unsubscribe']),
    };

    const terminateSpy = spyOn(stomp as any, 'terminateByDest').and.callThrough();
    const ack: TerminationAckMessage = {
      hashtag: 'glacier',
      principal: 'u',
      terminated: true,
    };
    (stomp as any).handleTerminationAck(ack);

    expect(terminateSpy).toHaveBeenCalledWith('/topic/hashtags/u/glacier/creation');
    expect(terminateSpy).toHaveBeenCalledWith('/topic/hashtags/u/glacier/modification');
    expect(terminateSpy).toHaveBeenCalledWith('/topic/hashtags/u/glacier/deletion');
  });

  it('negative termination — logs error and leaves hashtag list unchanged', () => {
    stomp['hashtags'] = ['hashtag1', 'hashtag2'];
    localStorage.setItem('hashtags', JSON.stringify(['hashtag1', 'hashtag2']));

    const consoleErrorSpy = spyOn(console, 'error');
    const ack: TerminationAckMessage = {
      hashtag: 'hashtag1',
      principal: 'test-user',
      terminated: false,
    };
    (stomp as any).handleTerminationAck(ack);

    expect(stomp['hashtags']).toEqual(['hashtag1', 'hashtag2']);
    expect(consoleErrorSpy).toHaveBeenCalledWith(
      'Could not terminate subscription for principal test-user and hashtag hashtag1',
    );
  });

  it('U-SEC-06 — guard seeded at step 2, before prune at step 3', () => {
    stomp['hashtags'] = ['glacier'];
    stomp['subscriptions'] = {
      '/topic/hashtags/u/glacier/creation': jasmine.createSpyObj('Subscription', ['unsubscribe']),
      '/topic/hashtags/u/glacier/modification': jasmine.createSpyObj('Subscription', ['unsubscribe']),
      '/topic/hashtags/u/glacier/deletion': jasmine.createSpyObj('Subscription', ['unsubscribe']),
    };

    const callOrder: string[] = [];
    spyOn(state, 'seedRecentlyTerminated').and.callFake(() => callOrder.push('seed'));
    spyOn(state, 'pruneByHashtag').and.callFake(() => {
      callOrder.push('prune');
      return {removed: [], remaining: []};
    });

    (stomp as any).handleTerminationAck({
      principal: 'u',
      hashtag: 'glacier',
      terminated: true,
    });

    expect(callOrder.indexOf('seed')).toBeLessThan(callOrder.indexOf('prune'));
  });
});

// ---------------------------------------------------------------------------
// terminateByDest — subscription map management
// ---------------------------------------------------------------------------

describe('SubscriptionStompClient — terminateByDest()', () => {
  let stomp: SubscriptionStompClient;
  let state: SubscriptionStateService;
  let rxStompMock: jasmine.SpyObj<RxStompService>;
  let wallAnnouncerMock: jasmine.SpyObj<WallAnnouncerService>;

  beforeEach(() => {
    rxStompMock = makeRxStompMock();
    wallAnnouncerMock = makeAnnouncerMock();

    TestBed.configureTestingModule({
      providers: [
        SubscriptionPersistence,
        SubscriptionStateService,
        SubscriptionStompClient,
        {provide: RxStompService, useValue: rxStompMock},
        {provide: WallAnnouncerService, useValue: wallAnnouncerMock},
      ],
    });

    stomp = TestBed.inject(SubscriptionStompClient);
    state = TestBed.inject(SubscriptionStateService);
    stomp['subscriptions'] = {
      existingDest: jasmine.createSpyObj('Subscription', ['unsubscribe']),
    };
  });

  afterEach(() => {
    localStorage.clear();
    state.clearSettlingTimers();
  });

  it('should unsubscribe and delete an existing subscription', () => {
    stomp['terminateByDest']('existingDest');
    expect(stomp['subscriptions']['existingDest']).toBeUndefined();
  });

  it('should log an error when the destination does not exist', () => {
    const consoleErrorSpy = spyOn(console, 'error');
    stomp['terminateByDest']('nonExistingDest');
    expect(consoleErrorSpy).toHaveBeenCalledWith(
      'No subscription found with destination',
      'nonExistingDest',
    );
  });
});
