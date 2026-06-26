import {TestBed, fakeAsync, tick} from '@angular/core/testing';
import {SubscriptionStateService} from './subscription-state.service';
import {SubscriptionPersistence} from './subscription-persistence.service';
import {WallMessage, WallMessageSchemaVersion} from './model/wall-message';

/**
 * Unit tests for SubscriptionStateService.
 *
 * Scope: single MessageQueue instance ownership (SR-SPLIT-03), observable
 * contracts, recentlyTerminated guard, ingestCacheEntries path convergence
 * (glacier-fallback-mode-discipline), and hasMigrated$ plain-Subject semantics
 * (SR-SPLIT-07, AC-10).
 *
 * T3 — Queue identity: enqueueWallMessage and ingestCacheEntries both write to
 *        the same MessageQueue instance, so messageObservable$ emits both
 *        entries (SR-SPLIT-03, AC-3).
 *
 * T7 — hasMigrated$ is a plain Subject: a new subscriber arriving after the
 *        emit does NOT receive the cached 'true' value (AC-10, ADR-4,
 *        SR-SPLIT-07).
 */
describe('SubscriptionStateService', () => {
  let service: SubscriptionStateService;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        SubscriptionStateService,
        SubscriptionPersistence,
      ],
    });
    service = TestBed.inject(SubscriptionStateService);
    localStorage.clear();
    spyOn(localStorage, 'setItem').and.stub();
    spyOn(localStorage, 'getItem').and.returnValue(null);
  });

  afterEach(() => {
    service.clearSettlingTimers();
  });

  /**
   * T3 — Queue identity test (SR-SPLIT-03, AC-3).
   *
   * Arrange: start with an empty state service.
   * Act:
   *   1. Call enqueueWallMessage({id:'A',...}) — the STOMP ingest path.
   *   2. Call ingestCacheEntries('x', [{type:'CREATED', id:'B',...}]) —
   *      the HTTP fallback ingest path.
   * Assert:
   *   messageObservable$ emits an array containing both A and B.
   *
   * This proves both ingest paths write to the SAME MessageQueue instance
   * (not two separate queues), satisfying the fallback-mode-discipline
   * requirement that live STOMP entries and fallback HTTP entries are
   * indistinguishable on the wall.
   */
  it('T3 — enqueueWallMessage and ingestCacheEntries write to the same queue instance', (done) => {
    const msgA: WallMessage = {id: 'A', url: 'https://example.com/A', hashtags: ['glacier']};

    // STOMP ingest path
    service.enqueueWallMessage(msgA);

    // HTTP fallback ingest path
    service.ingestCacheEntries('glacier', [
      {type: 'CREATED', id: 'B', url: 'https://example.com/B', sequence: 1},
    ]);

    service.messageObservable$.subscribe((messages) => {
      const ids = messages.map(m => m.id);
      expect(ids).toContain('A');
      expect(ids).toContain('B');
      done();
    });
  });

  /**
   * T7 — hasMigrated$ is a plain Subject, not a BehaviorSubject (AC-10, ADR-4).
   *
   * Arrange: trigger the migration scenario by calling restoreFromStorageAndEmit()
   *   when 'messageQueue' key is present but the stored queue is schema-v:1
   *   (which will fail validation and discard the queue).
   * Act:     Subscribe to hasMigrated$ AFTER restoreFromStorageAndEmit() returns.
   * Assert:  The late subscriber does NOT receive the 'true' emission.
   *
   * If hasMigrated$ were a BehaviorSubject seeded 'false', the late subscriber
   * would receive 'false' immediately.  If it were a BehaviorSubject seeded
   * 'true' after migration, the late subscriber would incorrectly see 'true'.
   * A plain Subject emits only to subscribers that are active at emission time.
   */
  it('T7 — hasMigrated$ is a plain Subject: late subscriber does not receive the migration true value', () => {
    // Arrange: set up a v:1 queue (schema mismatch — validator will discard it)
    // Use a real localStorage getItem so restore() can probe 'messageQueue'.
    // We need to override the stub for this test.
    (localStorage.getItem as jasmine.Spy).and.callFake((key: string) => {
      if (key === 'messageQueue') {
        // v:1 format — will be rejected by validateMessageQueue (ADR-4).
        return JSON.stringify({v: 1, items: []});
      }
      return null;
    });

    // Create a fresh service where the stub is active from construction
    // (the beforeEach service already had getItem stubbed to null, so
    // we need a fresh instance that calls persistence.hasPersistedMessageQueue()).
    // We restart DI to get a fresh SubscriptionStateService.
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [
        SubscriptionStateService,
        SubscriptionPersistence,
      ],
    });
    const freshService = TestBed.inject(SubscriptionStateService);

    let lateEmissionReceived = false;

    // Act: trigger the restore (which emits hasMigrated$ = true internally
    // because hadStoredData=true, sizeBeforeRestore=0, sizeAfterRestore=0)
    freshService.restoreFromStorageAndEmit();

    // Subscribe AFTER the emit has already happened
    freshService.hasMigrated$.subscribe(() => {
      lateEmissionReceived = true;
    });

    // Assert: the late subscriber must NOT have received the value
    expect(lateEmissionReceived).toBeFalse();
  });

  it('enqueueWallMessage adds a message to the queue', (done) => {
    const msg: WallMessage = {id: '1', url: 'https://example.com/1', hashtags: ['test']};

    service.enqueueWallMessage(msg);

    service.messageObservable$.subscribe((messages) => {
      expect(messages.length).toBe(1);
      expect(messages[0].id).toBe('1');
      done();
    });
  });

  it('clearAllToots empties the queue', (done) => {
    service.enqueueWallMessage({id: '1', url: 'u1', hashtags: ['a']});

    service.clearAllToots();

    service.messageObservable$.subscribe((messages) => {
      expect(messages.length).toBe(0);
      done();
    });
  });

  it('dequeueById removes the message with the matching id', (done) => {
    service.enqueueWallMessage({id: '1', url: 'u1', hashtags: ['a']});
    service.enqueueWallMessage({id: '2', url: 'u2', hashtags: ['a']});

    service.dequeueById('1');

    service.messageObservable$.subscribe((messages) => {
      expect(messages.map(m => m.id)).not.toContain('1');
      expect(messages.map(m => m.id)).toContain('2');
      done();
    });
  });

  it('isRecentlyTerminated returns false for unknown hashtag', () => {
    expect(service.isRecentlyTerminated('unknown')).toBeFalse();
  });

  it('isRecentlyTerminated returns true after seedRecentlyTerminated', () => {
    service.seedRecentlyTerminated('glacier');
    expect(service.isRecentlyTerminated('glacier')).toBeTrue();
  });

  it('pruneByHashtag removes messages belonging only to the named hashtag', (done) => {
    service.enqueueWallMessage({id: '1', url: 'u1', hashtags: ['glacier']});
    service.enqueueWallMessage({id: '2', url: 'u2', hashtags: ['foss']});

    const result = service.pruneByHashtag('glacier');

    expect(result.removed).toContain('1');
    service.messageObservable$.subscribe((messages) => {
      expect(messages.map(m => m.id)).not.toContain('1');
      expect(messages.map(m => m.id)).toContain('2');
      done();
    });
  });

  it('settlingHashtags$ emits the settling set after seedRecentlyTerminated', (done) => {
    service.seedRecentlyTerminated('glacier');

    service.settlingHashtags$.subscribe((settling) => {
      expect(settling.has('glacier')).toBeTrue();
      done();
    });
  });

  /**
   * U-SEC-03 — recentlyTerminated is bounded (eviction sweep removes stale entries).
   *
   * Arrange: manually insert a stale entry (already expired).
   * Act:     call seedRecentlyTerminated, which triggers the eviction sweep.
   * Assert:  stale entry evicted; new entry present.
   *
   * Migrated from subscription.service.prune.spec.ts (Phase 3, commit 6).
   */
  describe('U-SEC-03 — recentlyTerminated is bounded (eviction sweep)', () => {
    it('should evict stale entries from recentlyTerminated during seedRecentlyTerminated', () => {
      // Arrange: manually insert a stale entry (already expired)
      const now = Date.now();
      service['recentlyTerminated'].set('stale', now - 1); // expired 1 ms ago

      // Act: seed a new entry, which triggers the sweep
      service.seedRecentlyTerminated('newhashtag');

      // Assert: stale entry was evicted; new entry is present
      expect(service['recentlyTerminated'].has('stale')).toBeFalse();
      expect(service['recentlyTerminated'].has('newhashtag')).toBeTrue();
    });

    it('should retain active entries during the eviction sweep', () => {
      // Arrange: insert an active (not yet expired) entry
      const future = Date.now() + 60_000; // 60 s in the future
      service['recentlyTerminated'].set('active', future);

      // Act: seed a new entry
      service.seedRecentlyTerminated('another');

      // Assert: active entry was NOT evicted
      expect(service['recentlyTerminated'].has('active')).toBeTrue();
    });
  });

  /**
   * U-SEC-04 — Date.now() is used for TTL (not performance.now()).
   *
   * Arrange: capture Date.now() before the seed call.
   * Act:     call seedRecentlyTerminated.
   * Assert:  expiry timestamp is in the future relative to Date.now() (not a
   *          monotonic clock value that would be much smaller).
   *
   * Migrated from subscription.service.prune.spec.ts (Phase 3, commit 6).
   */
  it('U-SEC-04 — seedRecentlyTerminated uses Date.now() as the basis for the expiry timestamp', () => {
    // Arrange
    const beforeSeed = Date.now();

    // Act
    service.seedRecentlyTerminated('glacier');

    // Assert: expiresAt is in the future relative to Date.now()
    const afterSeed = Date.now();
    const expiresAt = service['recentlyTerminated'].get('glacier');
    expect(expiresAt).toBeDefined();
    expect(expiresAt!).toBeGreaterThan(beforeSeed);
    // expiresAt should be roughly beforeSeed + guardTtlMs (> afterSeed)
    expect(expiresAt!).toBeGreaterThan(afterSeed);
  });

  /**
   * U-SEC-08 — hashtags[] populated correctly on ingest (at least 1 entry per toot).
   *
   * Arrange: spy on the internal enqueueOrMergeHashtag to capture the WallMessage written.
   * Act:     call ingestCacheEntries with a CREATED entry.
   * Assert:  hashtags[] contains the normalised hashtag; empty hashtag is dropped entirely.
   *
   * Migrated from subscription.service.prune.spec.ts (Phase 3, commit 6).
   */
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
      // Arrange: an empty hashtag normalises to '' (falsy); SR-PRUNE-08 requires
      // the entry to be silently skipped — never call enqueueOrMergeHashtag.
      const enqueueSpy = spyOn(service['receivedMessages'], 'enqueueOrMergeHashtag').and.stub();
      const entries = [{id: 'abc', type: 'CREATED' as const, url: 'u', sequence: 1}];

      // Act
      service.ingestCacheEntries('', entries);

      // Assert: the message was silently dropped
      expect(enqueueSpy).not.toHaveBeenCalled();
    });
  });

  /**
   * FIND-P3-SEC-5/6 — settling timers are cancelled on clearSettlingTimers.
   *
   * Arrange: seed two hashtags so two settling timers are scheduled.
   * Act:     call clearSettlingTimers.
   * Assert:  the handle set is empty (all clearTimeout calls completed).
   *
   * Also verifies that timers do not fire after teardown (using fakeAsync/tick).
   *
   * Migrated from subscription.service.prune.spec.ts (Phase 3, commit 6).
   */
  describe('FIND-P3-SEC-5/6 — settling timers are cancelled on clearSettlingTimers', () => {
    it('should clear the timer handle set when clearSettlingTimers is called', () => {
      // Arrange: seed two hashtags so two timers are scheduled
      service.seedRecentlyTerminated('glacier');
      service.seedRecentlyTerminated('foss');

      expect(service['_settlingTimerHandles'].size).toBe(2);

      // Act
      service.clearSettlingTimers();

      // Assert: all handles cleared
      expect(service['_settlingTimerHandles'].size).toBe(0);
    });

    it('should not fire settling timers after clearSettlingTimers is called', fakeAsync(() => {
      // Arrange: seed a hashtag so a settling timer is scheduled
      service.seedRecentlyTerminated('glacier');

      expect(service['_settlingHashtagsSubject'].value.size).toBeGreaterThan(0);
      expect(service['_settlingTimerHandles'].size).toBeGreaterThan(0);

      // Act: cancel all timers
      service.clearSettlingTimers();

      // Assert immediately: handle set must be empty
      expect(service['_settlingTimerHandles'].size).toBe(0);

      // Advance Jasmine's fake clock past the guard TTL — no timer should fire
      tick(15_000);

      // The handle set stays empty (no new timers registered after clear)
      expect(service['_settlingTimerHandles'].size).toBe(0);
    }));
  });
});

// ---------------------------------------------------------------------------
// isRecentlyTerminated — expiry and normalisation edge cases
// ---------------------------------------------------------------------------

/**
 * Additional isRecentlyTerminated tests covering the expired-entry and
 * hashtag-normalisation paths not present in the main SubscriptionStateService
 * describe block above.
 *
 * Migrated from subscription.service.prune.spec.ts (Phase 3, commit 6).
 */
describe('SubscriptionStateService — isRecentlyTerminated edge cases', () => {
  let service: SubscriptionStateService;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        SubscriptionStateService,
        SubscriptionPersistence,
      ],
    });
    service = TestBed.inject(SubscriptionStateService);
    localStorage.clear();
    spyOn(localStorage, 'setItem').and.stub();
    spyOn(localStorage, 'getItem').and.returnValue(null);
  });

  afterEach(() => {
    service.clearSettlingTimers();
  });

  it('should return false when the guard entry has expired', () => {
    // Arrange: set an expiry 1 ms in the past (already expired)
    service['recentlyTerminated'].set('glacier', Date.now() - 1);

    expect(service.isRecentlyTerminated('glacier')).toBeFalse();
  });

  it('should normalise the hashtag before checking the guard map', () => {
    // Arrange: stored under lowercase 'glacier'
    service['recentlyTerminated'].set('glacier', Date.now() + 10_000);

    // Act: check with '#Glacier' (mixed case, leading hash)
    expect(service.isRecentlyTerminated('#Glacier')).toBeTrue();
  });

  // -------------------------------------------------------------------------
  // ingestCacheEntries: UPDATED / DELETED paths (existing tests only cover CREATED)
  // -------------------------------------------------------------------------

  describe('ingestCacheEntries — UPDATED / DELETED', () => {
    it('UPDATED with url + editedAt applies an update to the queue', () => {
      const updateSpy = spyOn(service['receivedMessages'], 'update').and.stub();

      service.ingestCacheEntries('glacier', [
        {id: 'x', type: 'UPDATED' as const, url: 'https://e/x', editedAt: '2026-01-01T00:00:00Z', sequence: 2},
      ]);

      expect(updateSpy).toHaveBeenCalledOnceWith(
        jasmine.objectContaining({id: 'x', url: 'https://e/x', editedAt: '2026-01-01T00:00:00Z'}));
    });

    it('UPDATED missing editedAt is skipped (the url && editedAt guard)', () => {
      const updateSpy = spyOn(service['receivedMessages'], 'update').and.stub();

      service.ingestCacheEntries('glacier', [
        {id: 'x', type: 'UPDATED' as const, url: 'https://e/x', sequence: 2}, // no editedAt
      ]);

      expect(updateSpy).not.toHaveBeenCalled();
    });

    it('DELETED dequeues the message by id', () => {
      const dequeueSpy = spyOn(service['receivedMessages'], 'dequeue').and.stub();

      service.ingestCacheEntries('glacier', [
        {id: 'gone', type: 'DELETED' as const, sequence: 3},
      ]);

      expect(dequeueSpy).toHaveBeenCalledOnceWith('gone');
    });
  });

  // -------------------------------------------------------------------------
  // Settling-state auto-clear timer (FIND-P3-SEC-5/6)
  // -------------------------------------------------------------------------

  it('settling state auto-clears after the guard TTL elapses', fakeAsync(() => {
    const seen: Set<string>[] = [];
    service.settlingHashtags$.subscribe(s => seen.push(s));

    service.seedRecentlyTerminated('glacier');
    expect(seen[seen.length - 1].has('glacier'))
      .withContext('settling set must contain the hashtag right after seeding').toBeTrue();

    // guardTtlMs (10_000) + 50 ms safety margin → the scheduled clear fires.
    tick(10_100);

    expect(seen[seen.length - 1].has('glacier'))
      .withContext('settling spinner must clear automatically once the TTL elapses').toBeFalse();
  }));
});
