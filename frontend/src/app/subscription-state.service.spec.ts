import {TestBed} from '@angular/core/testing';
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
});
