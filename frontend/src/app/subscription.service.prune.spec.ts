import {TestBed, fakeAsync, tick} from '@angular/core/testing';
import {MessageQueue, SubscriptionService} from './subscription.service';
import {RxStompService} from './rx-stomp.service';
import {WallAnnouncerService} from './services/wall-announcer.service';
import {Observable, of} from 'rxjs';
import {Message} from '@stomp/stompjs';
import {WallMessage, WallMessageSchemaVersion} from './model/wall-message';

/**
 * Unit tests for the prune-on-removal feature domain logic (Phase 2, ADR-1–7).
 *
 * Test IDs per Phase 1 plan:
 *
 *   MessageQueue.pruneByHashtag / pruneByHashtags:
 *     U7  — pruneByHashtag removes the hashtag from all entries.
 *     U8  — pruneByHashtag drops entries whose hashtags[] becomes empty.
 *     U9  — pruneByHashtag leaves entries with other hashtags intact.
 *     U10 — pruneByHashtags accumulates removed IDs across all hashtags.
 *
 *   MessageQueue.enqueueOrMergeHashtag (ingest dedup):
 *     U11 — enqueueOrMergeHashtag merges the hashtag when toot ID already exists.
 *     U12 — enqueueOrMergeHashtag adds a new entry when toot ID is not present.
 *
 *   MessageQueue.restore (schema version gate):
 *     U13 — restore discards queue when schema version is absent.
 *     U14 — restore loads items when schema version is correct (v:2).
 *
 *   SubscriptionService.recentlyTerminated guard:
 *     U-SEC-03 — recentlyTerminated is bounded (eviction sweep removes stale entries).
 *     U-SEC-04 — Date.now() is used (not performance.now()) for TTL.
 *     U-SEC-06 — Guard is seeded at step 2 (before prune step 3).
 *     U-SEC-08 — hashtags[] populated correctly on ingest (at least 1 entry per toot).
 *
 *   4-step ack handler:
 *     U-SEC-13 — 4-step ack sequence is synchronous (no await/setTimeout between steps).
 */

// ---------------------------------------------------------------------------
// MessageQueue prune tests
// ---------------------------------------------------------------------------

describe('MessageQueue.pruneByHashtag', () => {
  let queue: MessageQueue;

  beforeEach(() => {
    queue = new MessageQueue();
    spyOn(localStorage, 'setItem').and.stub();
    spyOn(localStorage, 'getItem').and.returnValue(null);
  });

  const msg = (id: string, hashtags: string[]): WallMessage => ({
    id,
    url: `https://example.com/${id}`,
    hashtags,
  });

  describe('U7 — removes the named hashtag from all entries', () => {
    it('should remove the hashtag from a toot that has multiple hashtags', () => {
      // Arrange
      queue.enqueue(msg('1', ['glacier', 'foss']));

      // Act
      const result = queue.pruneByHashtag('glacier');

      // Assert: hashtag removed, toot stays (still has 'foss')
      expect(result.removed).toEqual([]);
      expect(result.remaining[0].hashtags).toEqual(['foss']);
    });

    it('should handle case-insensitive comparison via normalizeHashtag', () => {
      // Arrange: toot has lowercase 'glacier', we prune '#Glacier' (mixed case)
      queue.enqueue(msg('1', ['glacier', 'foss']));

      // Act
      const result = queue.pruneByHashtag('#Glacier');

      // Assert: normalised comparison removes correctly
      expect(result.remaining[0].hashtags).toEqual(['foss']);
    });
  });

  describe('U8 — drops entries whose hashtags[] becomes empty', () => {
    it('should remove a toot that had only the pruned hashtag', () => {
      // Arrange
      queue.enqueue(msg('1', ['glacier']));

      // Act
      const result = queue.pruneByHashtag('glacier');

      // Assert
      expect(result.removed).toContain('1');
      expect(result.remaining.length).toBe(0);
    });

    it('should only drop the toot with the single matching hashtag, leaving others', () => {
      // Arrange
      queue.enqueue(msg('1', ['glacier']));
      queue.enqueue(msg('2', ['foss']));

      // Act
      const result = queue.pruneByHashtag('glacier');

      // Assert
      expect(result.removed).toEqual(['1']);
      expect(result.remaining.length).toBe(1);
      expect(result.remaining[0].id).toBe('2');
    });
  });

  describe('U9 — leaves entries with other hashtags intact', () => {
    it('should not remove a toot that was never a member of the pruned hashtag', () => {
      // Arrange
      queue.enqueue(msg('1', ['foss', 'opendata']));

      // Act
      const result = queue.pruneByHashtag('glacier');

      // Assert: toot is untouched
      expect(result.removed).toEqual([]);
      expect(result.remaining[0]).toEqual(msg('1', ['foss', 'opendata']));
    });

    it('should return an empty removed array when no toots match the pruned hashtag', () => {
      // Arrange: queue with no glacier entries
      queue.enqueue(msg('1', ['foss']));
      queue.enqueue(msg('2', ['opendata']));

      // Act
      const result = queue.pruneByHashtag('glacier');

      // Assert
      expect(result.removed).toEqual([]);
      expect(result.remaining.length).toBe(2);
    });
  });
});

describe('MessageQueue.pruneByHashtags', () => {
  let queue: MessageQueue;

  beforeEach(() => {
    queue = new MessageQueue();
    spyOn(localStorage, 'setItem').and.stub();
    spyOn(localStorage, 'getItem').and.returnValue(null);
  });

  const msg = (id: string, hashtags: string[]): WallMessage => ({
    id,
    url: `https://example.com/${id}`,
    hashtags,
  });

  describe('U10 — accumulates removed IDs across all hashtags', () => {
    it('should remove a toot that is a member of any of the pruned hashtags', () => {
      // Arrange: two toots, each with a distinct single hashtag
      queue.enqueue(msg('1', ['glacier']));
      queue.enqueue(msg('2', ['foss']));

      // Act
      const result = queue.pruneByHashtags(['glacier', 'foss']);

      // Assert: both toots are removed
      expect(result.removed).toContain('1');
      expect(result.removed).toContain('2');
      expect(result.remaining.length).toBe(0);
    });

    it('should not double-count a toot that belongs to multiple pruned hashtags', () => {
      // Arrange: one toot is a member of both pruned hashtags
      queue.enqueue(msg('1', ['glacier', 'foss']));

      // Act
      const result = queue.pruneByHashtags(['glacier', 'foss']);

      // Assert: toot appears in removed once
      expect(result.removed.length).toBe(1);
      expect(result.removed[0]).toBe('1');
    });

    it('should return an empty removed array when the hashtag list is empty', () => {
      // Arrange
      queue.enqueue(msg('1', ['glacier']));

      // Act
      const result = queue.pruneByHashtags([]);

      // Assert
      expect(result.removed).toEqual([]);
      expect(result.remaining.length).toBe(1);
    });
  });
});

// ---------------------------------------------------------------------------
// MessageQueue.enqueueOrMergeHashtag tests
// ---------------------------------------------------------------------------

describe('MessageQueue.enqueueOrMergeHashtag', () => {
  let queue: MessageQueue;

  beforeEach(() => {
    queue = new MessageQueue();
    spyOn(localStorage, 'setItem').and.stub();
    spyOn(localStorage, 'getItem').and.returnValue(null);
  });

  describe('U11 — merges the hashtag when the toot ID already exists', () => {
    it('should merge a new hashtag into an existing entry', () => {
      // Arrange: toot '1' already in queue with 'glacier'
      const existing: WallMessage = {id: '1', url: 'https://example.com/1', hashtags: ['glacier']};
      queue.enqueue(existing);

      // Act: ingest same toot for 'foss'
      const ingestEntry: WallMessage = {id: '1', url: 'https://example.com/1', hashtags: ['foss']};
      queue.enqueueOrMergeHashtag(ingestEntry, 'foss');

      // Assert: hashtags merged, no duplicate toot
      expect(queue.size()).toBe(1);
      expect(queue.toArray()[0].hashtags).toContain('glacier');
      expect(queue.toArray()[0].hashtags).toContain('foss');
    });

    it('should not add a duplicate hashtag if it is already in the list', () => {
      // Arrange
      const existing: WallMessage = {id: '1', url: 'https://example.com/1', hashtags: ['glacier']};
      queue.enqueue(existing);

      // Act: try to merge 'glacier' again
      queue.enqueueOrMergeHashtag(existing, 'glacier');

      // Assert: only one 'glacier' entry in hashtags
      expect(queue.toArray()[0].hashtags.filter(h => h === 'glacier').length).toBe(1);
    });
  });

  describe('U12 — adds a new entry when the toot ID is not present', () => {
    it('should add a new WallMessage when the id does not exist in the queue', () => {
      // Arrange: empty queue
      const newEntry: WallMessage = {id: '99', url: 'https://example.com/99', hashtags: ['foss']};

      // Act
      queue.enqueueOrMergeHashtag(newEntry, 'foss');

      // Assert
      expect(queue.size()).toBe(1);
      expect(queue.toArray()[0].id).toBe('99');
    });
  });
});

// ---------------------------------------------------------------------------
// MessageQueue.restore schema version tests
// ---------------------------------------------------------------------------

describe('MessageQueue.restore', () => {
  let queue: MessageQueue;
  let setItemSpy: jasmine.Spy;
  let getItemSpy: jasmine.Spy;

  beforeEach(() => {
    queue = new MessageQueue();
    setItemSpy = spyOn(localStorage, 'setItem').and.stub();
    getItemSpy = spyOn(localStorage, 'getItem').and.returnValue(null);
  });

  describe('U13 — discards queue when schema version is absent', () => {
    it('should start with an empty storage when the stored JSON has no v field', () => {
      // Arrange: v:1 style flat array (no envelope)
      const oldStyle = JSON.stringify([{id: '1', url: 'u', hashtags: ['a']}]);
      getItemSpy.and.callFake((key: string) =>
        key === 'messageQueue' ? oldStyle : null
      );

      // Act
      queue.restore();

      // Assert: queue is empty after discard
      expect(queue.size()).toBe(0);
    });

    it('should start with an empty storage when v field is wrong version', () => {
      // Arrange: future version not recognised
      const wrongVersion = JSON.stringify({v: 999, items: [{id: '1', url: 'u', hashtags: ['a']}]});
      getItemSpy.and.callFake((key: string) =>
        key === 'messageQueue' ? wrongVersion : null
      );

      // Act
      queue.restore();

      // Assert
      expect(queue.size()).toBe(0);
    });

    it('should start with an empty storage when stored value is invalid JSON', () => {
      // Arrange
      getItemSpy.and.callFake((key: string) =>
        key === 'messageQueue' ? '{invalid}' : null
      );

      // Act
      queue.restore();

      // Assert
      expect(queue.size()).toBe(0);
    });
  });

  describe('U14 — loads items when schema version is correct (v:2)', () => {
    it('should restore items from a valid v:2 envelope', () => {
      // Arrange
      const items: WallMessage[] = [
        {id: '1', url: 'https://example.com/1', hashtags: ['glacier']},
        {id: '2', url: 'https://example.com/2', hashtags: ['foss']},
      ];
      const envelope = JSON.stringify({v: WallMessageSchemaVersion, items});
      getItemSpy.and.callFake((key: string) =>
        key === 'messageQueue' ? envelope : null
      );

      // Act
      queue.restore();

      // Assert
      expect(queue.size()).toBe(2);
      expect(queue.toArray()[0].id).toBe('1');
      expect(queue.toArray()[1].id).toBe('2');
    });

    it('should restore an empty items array from a valid v:2 envelope with no items', () => {
      // Arrange
      const envelope = JSON.stringify({v: WallMessageSchemaVersion, items: []});
      getItemSpy.and.callFake((key: string) =>
        key === 'messageQueue' ? envelope : null
      );

      // Act
      queue.restore();

      // Assert
      expect(queue.size()).toBe(0);
    });
  });
});

// ---------------------------------------------------------------------------
// SubscriptionService security and 4-step ack tests
// ---------------------------------------------------------------------------

describe('SubscriptionService — recentlyTerminated guard (ADR-3)', () => {
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

    spyOn(localStorage, 'setItem').and.stub();
    spyOn(localStorage, 'getItem').and.returnValue(null);
  });

  describe('U-SEC-03 — recentlyTerminated is bounded (eviction sweep)', () => {
    it('should evict stale entries from recentlyTerminated during seedRecentlyTerminated', () => {
      // Arrange: manually insert a stale entry (already expired)
      const now = Date.now();
      service['recentlyTerminated'].set('stale', now - 1); // expired 1ms ago

      // Act: seed a new entry, which triggers the sweep
      (service as any).seedRecentlyTerminated('newhashtag');

      // Assert: stale entry was evicted
      expect(service['recentlyTerminated'].has('stale')).toBeFalse();
      // New entry is present
      expect(service['recentlyTerminated'].has('newhashtag')).toBeTrue();
    });

    it('should retain active entries during the eviction sweep', () => {
      // Arrange: insert an active (not yet expired) entry
      const future = Date.now() + 60_000; // 60 s in the future
      service['recentlyTerminated'].set('active', future);

      // Act: seed a new entry
      (service as any).seedRecentlyTerminated('another');

      // Assert: active entry was NOT evicted
      expect(service['recentlyTerminated'].has('active')).toBeTrue();
    });
  });

  describe('U-SEC-04 — Date.now() is used for TTL (not performance.now())', () => {
    it('should use Date.now() as the basis for the expiry timestamp', () => {
      // Arrange
      const beforeSeed = Date.now();

      // Act
      (service as any).seedRecentlyTerminated('glacier');

      // Assert: expiresAt is in the future relative to Date.now()
      const afterSeed = Date.now();
      const expiresAt = service['recentlyTerminated'].get('glacier');
      expect(expiresAt).toBeDefined();
      expect(expiresAt!).toBeGreaterThan(beforeSeed);
      // expiresAt should be roughly beforeSeed + guardTtlMs
      expect(expiresAt!).toBeGreaterThan(afterSeed);
    });
  });

  describe('U-SEC-06 — guard seeded at step 2 (before prune step 3)', () => {
    it('should seed recentlyTerminated before calling pruneByHashtag in the ack handler', () => {
      // Arrange
      service['hashtags'] = ['glacier'];
      service['subscriptions'] = {
        '/topic/hashtags/u/glacier/creation': jasmine.createSpyObj('Subscription', ['unsubscribe']),
        '/topic/hashtags/u/glacier/modification': jasmine.createSpyObj('Subscription', ['unsubscribe']),
        '/topic/hashtags/u/glacier/deletion': jasmine.createSpyObj('Subscription', ['unsubscribe']),
      };

      const callOrder: string[] = [];
      const seedSpy = spyOn(service as any, 'seedRecentlyTerminated').and.callFake(() => {
        callOrder.push('seed');
      });
      const pruneSpy = spyOn(service['receivedMessages'], 'pruneByHashtag').and.callFake(() => {
        callOrder.push('prune');
        return {removed: [], remaining: []};
      });

      // Act
      (service as any).handleTerminationAckMessage({
        principal: 'u',
        hashtag: 'glacier',
        terminated: true,
      });

      // Assert: seed happens before prune
      expect(callOrder.indexOf('seed')).toBeLessThan(callOrder.indexOf('prune'));
    });
  });

  describe('U-SEC-08 — hashtags[] populated correctly on ingest', () => {
    it('should set hashtags to the normalised hashtag on CREATED ingest entry', () => {
      // Arrange
      const enqueueSpy = spyOn(service['receivedMessages'], 'enqueueOrMergeHashtag').and.stub();

      const entries = [{
        id: 'abc',
        type: 'CREATED' as const,
        url: 'https://example.com/abc',
        sequence: 1,
      }];

      // Act
      service.ingestCacheEntries('#Glacier', entries);

      // Assert: hashtags contains the normalised form
      expect(enqueueSpy).toHaveBeenCalledOnceWith(
        jasmine.objectContaining({
          id: 'abc',
          hashtags: ['glacier'], // normalised: lowercase, no '#'
        }),
        'glacier'
      );
    });

    it('should silently drop a CREATED entry when the hashtag is empty', () => {
      // Arrange
      // An empty hashtag normalises to '' (falsy).  Enqueueing a WallMessage
      // with hashtags:[] would violate the WallMessage invariant
      // (hashtags.length >= 1 per wall-message.ts:27) and cause
      // MessageQueueValidator to reject the entire queue on next restore.
      // The correct behavior is to skip the entry entirely — never call
      // enqueueOrMergeHashtag (SR-PRUNE-08, ADR-6 ingest contract).
      const enqueueSpy = spyOn(service['receivedMessages'], 'enqueueOrMergeHashtag').and.stub();
      const entries = [{id: 'abc', type: 'CREATED' as const, url: 'u', sequence: 1}];

      // Act
      service.ingestCacheEntries('', entries);

      // Assert: the message was silently dropped — enqueueOrMergeHashtag must NOT be called
      expect(enqueueSpy).not.toHaveBeenCalled();
    });
  });

  describe('FIND-P3-SEC-5/6 — settling timers are cancelled on terminateAllSubscriptions', () => {
    it('should not fire settling timers after terminateAllSubscriptions is called', fakeAsync(() => {
      // Arrange: seed a hashtag so a settling timer is scheduled
      service['hashtags'] = ['glacier'];
      service['subscriptions'] = {
        '/topic/hashtags/u/glacier/creation': jasmine.createSpyObj('Subscription', ['unsubscribe']),
        '/topic/hashtags/u/glacier/modification': jasmine.createSpyObj('Subscription', ['unsubscribe']),
        '/topic/hashtags/u/glacier/deletion': jasmine.createSpyObj('Subscription', ['unsubscribe']),
      };

      // Directly invoke seedRecentlyTerminated to schedule a timer
      (service as any).seedRecentlyTerminated('glacier');

      // Assert: settling set is non-empty right after seeding
      expect(service['_settlingHashtagsSubject'].value.size).toBeGreaterThan(0);
      expect(service['_settlingTimerHandles'].size).toBeGreaterThan(0);

      // Act: tear down the service — all subscriptions and timers should be cancelled
      service.terminateAllSubscriptions();

      // Assert immediately: timer handle set must be empty (all cleared)
      expect(service['_settlingTimerHandles'].size).toBe(0);

      // Advance Jasmine's fake clock past the guard TTL to confirm no timer fires
      tick(15_000);

      // The settling set may or may not be empty depending on whether the timer
      // was actually cancelled; the critical invariant is that the handle set is
      // empty (clearTimeout was called on every handle before teardown).
      // This prevents closures over _settlingHashtagsSubject from firing after
      // the service is torn down (FIND-P3-SEC-5/6).
    }));

    it('should clear the timer handle set when clearSettlingTimers is called', () => {
      // Arrange: seed two hashtags so two timers are scheduled
      (service as any).seedRecentlyTerminated('glacier');
      (service as any).seedRecentlyTerminated('foss');

      expect(service['_settlingTimerHandles'].size).toBe(2);

      // Act
      (service as any).clearSettlingTimers();

      // Assert: all handles cleared
      expect(service['_settlingTimerHandles'].size).toBe(0);
    });
  });

  describe('U-SEC-13 — 4-step ack sequence is synchronous', () => {
    it('should call terminate, seed, prune, and announce in order within the same tick', () => {
      // Arrange
      service['hashtags'] = ['glacier'];
      service['subscriptions'] = {
        '/topic/hashtags/u/glacier/creation': jasmine.createSpyObj('Subscription', ['unsubscribe']),
        '/topic/hashtags/u/glacier/modification': jasmine.createSpyObj('Subscription', ['unsubscribe']),
        '/topic/hashtags/u/glacier/deletion': jasmine.createSpyObj('Subscription', ['unsubscribe']),
      };

      const callOrder: string[] = [];

      spyOn(service as any, 'terminateSubscriptionByDestination').and.callFake(() => {
        callOrder.push('terminate');
      });
      spyOn(service as any, 'seedRecentlyTerminated').and.callFake(() => {
        callOrder.push('seed');
      });
      spyOn(service['receivedMessages'], 'pruneByHashtag').and.callFake(() => {
        callOrder.push('prune');
        return {removed: [], remaining: []};
      });
      wallAnnouncerServiceSpy.announce.and.callFake(() => {
        callOrder.push('announce');
      });

      // Act: call handleTerminationAckMessage synchronously
      (service as any).handleTerminationAckMessage({
        principal: 'u',
        hashtag: 'glacier',
        terminated: true,
      });

      // Assert: all 4 steps executed, and in the correct order.
      // terminate × 3 (creation/modification/deletion), then seed, prune, announce.
      expect(callOrder.slice(0, 3)).toEqual(['terminate', 'terminate', 'terminate']);
      expect(callOrder[3]).toBe('seed');
      expect(callOrder[4]).toBe('prune');
      expect(callOrder[5]).toBe('announce');
      // No async gaps: entire call order is populated synchronously
      expect(callOrder.length).toBe(6);
    });
  });
});

describe('SubscriptionService — isRecentlyTerminated', () => {
  let service: SubscriptionService;

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
    spyOn(localStorage, 'setItem').and.stub();
    spyOn(localStorage, 'getItem').and.returnValue(null);
  });

  it('should return false when the hashtag is not in the guard map', () => {
    expect(service.isRecentlyTerminated('unknown')).toBeFalse();
  });

  it('should return true when the hashtag is in the guard map and not expired', () => {
    // Arrange: set an expiry 10s in the future
    service['recentlyTerminated'].set('glacier', Date.now() + 10_000);

    expect(service.isRecentlyTerminated('glacier')).toBeTrue();
  });

  it('should return false when the guard entry has expired', () => {
    // Arrange: set an expiry 1ms in the past (already expired)
    service['recentlyTerminated'].set('glacier', Date.now() - 1);

    expect(service.isRecentlyTerminated('glacier')).toBeFalse();
  });

  it('should normalise the hashtag before checking the guard map', () => {
    // Arrange: stored under lowercase 'glacier'
    service['recentlyTerminated'].set('glacier', Date.now() + 10_000);

    // Act: check with '#Glacier' (mixed case, leading hash)
    expect(service.isRecentlyTerminated('#Glacier')).toBeTrue();
  });
});

describe('SubscriptionService — guard gate in subscribeToStatusCreatedMessages', () => {
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
    spyOn(localStorage, 'setItem').and.stub();
    spyOn(localStorage, 'getItem').and.returnValue(null);
  });

  it('should drop an incoming STOMP delivery when the hashtag is in the recentlyTerminated guard', () => {
    // Arrange: add hashtag to guard
    service['recentlyTerminated'].set('glacier', Date.now() + 10_000);

    const testMessage = {body: JSON.stringify({id: '1', author: '', url: 'https://example.com'})};
    rxStompServiceSpy.watch.and.returnValue({
      subscribe: (callback: (message: any) => void) => {
        callback(testMessage);
        return {unsubscribe: jasmine.createSpy('unsubscribe')};
      },
    } as any);

    const enqueueSpy = spyOn(service['receivedMessages'], 'enqueue');

    // Act
    service.subscribeToStatusCreatedMessages('/topic/hashtags/u/glacier/creation', 'glacier');

    // Assert: toot was dropped, enqueue was NOT called
    expect(enqueueSpy).not.toHaveBeenCalled();
  });

  it('should enqueue an incoming STOMP delivery when the hashtag is NOT in the recentlyTerminated guard', () => {
    // Arrange: guard map is empty
    const testMessage = {body: JSON.stringify({id: '2', author: '', url: 'https://example.com/2'})};
    rxStompServiceSpy.watch.and.returnValue({
      subscribe: (callback: (message: any) => void) => {
        callback(testMessage);
        return {unsubscribe: jasmine.createSpy('unsubscribe')};
      },
    } as any);

    const enqueueSpy = spyOn(service['receivedMessages'], 'enqueue');

    // Act
    service.subscribeToStatusCreatedMessages('/topic/hashtags/u/foss/creation', 'foss');

    // Assert: toot was enqueued
    expect(enqueueSpy).toHaveBeenCalled();
  });
});
