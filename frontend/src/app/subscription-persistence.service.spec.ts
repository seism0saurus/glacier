import {TestBed} from '@angular/core/testing';
import {SubscriptionPersistence} from './subscription-persistence.service';
import {MessageQueue} from './subscription.service';
import {WallMessage, WallMessageSchemaVersion} from './model/wall-message';

/**
 * Unit tests for SubscriptionPersistence service.
 *
 * Scope: localStorage access for 'hashtags' and 'messageQueue' keys,
 * MessageQueue factory, and try/catch behaviour on malformed JSON
 * (SR-SPLIT-01a, AC-19).
 *
 * T0 — loadHashtags() returns [] and calls console.warn (not logging the
 *        malformed value) when 'hashtags' localStorage value is malformed
 *        JSON (AC-19, CWE-117, SR-SPLIT-01a).
 *
 * T1/T2 structural invariants are enforced by ESLint rules, not Karma
 * (per the decision to use lint-time rather than runtime structural tests).
 */
describe('SubscriptionPersistence', () => {
  let service: SubscriptionPersistence;

  beforeEach(() => {
    TestBed.configureTestingModule({});
    service = TestBed.inject(SubscriptionPersistence);
    localStorage.clear();
  });

  afterEach(() => {
    localStorage.clear();
  });

  /**
   * T0 — Malformed JSON in 'hashtags' localStorage key.
   *
   * Arrange: set 'hashtags' to a string that is not valid JSON.
   * Act:     call loadHashtags().
   * Assert:  returns [] (fail-safe, SR-SPLIT-01a).
   *          calls console.warn with the prescribed message (AC-19).
   *          does NOT log the malformed value itself (CWE-117).
   */
  it('returns [] when hashtags localStorage is malformed JSON', () => {
    localStorage.setItem('hashtags', '{bad');
    const warnSpy = spyOn(console, 'warn');

    const result = service.loadHashtags();

    expect(result).toEqual([]);
    expect(warnSpy).toHaveBeenCalledWith('hashtags localStorage malformed, resetting');
    // Verify the malformed value itself is NOT passed to console.warn (CWE-117):
    // the warn call must have exactly one argument.
    expect(warnSpy.calls.mostRecent().args.length).toBe(1);
  });

  it('returns [] when hashtags key is absent from localStorage', () => {
    const result = service.loadHashtags();
    expect(result).toEqual([]);
  });

  it('returns validated hashtags when localStorage contains a valid list', () => {
    localStorage.setItem('hashtags', JSON.stringify(['angular', 'typescript']));

    const result = service.loadHashtags();

    expect(result).toEqual(['angular', 'typescript']);
  });

  it('returns [] when localStorage contains a non-array JSON value', () => {
    // validateHashtagsList rejects non-arrays; loadHashtags returns []
    localStorage.setItem('hashtags', JSON.stringify({not: 'an array'}));

    const result = service.loadHashtags();

    expect(result).toEqual([]);
  });

  it('saveHashtags persists the list to localStorage', () => {
    service.saveHashtags(['cats', 'dogs']);

    const stored = JSON.parse(localStorage.getItem('hashtags')!);
    expect(stored).toEqual(['cats', 'dogs']);
  });

  it('createMessageQueue returns a new MessageQueue instance', () => {
    const queue = service.createMessageQueue();
    expect(queue).toBeTruthy();
    expect(queue.size()).toBe(0);
  });

  it('createMessageQueue with custom capacity creates a bounded queue', () => {
    const queue = service.createMessageQueue(3);
    // Verify capacity by filling beyond the limit
    queue.enqueue({id: '1', url: 'u1', hashtags: ['a']});
    queue.enqueue({id: '2', url: 'u2', hashtags: ['a']});
    queue.enqueue({id: '3', url: 'u3', hashtags: ['a']});
    queue.enqueue({id: '4', url: 'u4', hashtags: ['a']});
    // Oldest entry (id:'1') should have been evicted
    expect(queue.size()).toBe(3);
    expect(queue.toArray().map(m => m.id)).not.toContain('1');
  });

  it('hasPersistedMessageQueue returns false when key is absent', () => {
    expect(service.hasPersistedMessageQueue()).toBeFalse();
  });

  it('hasPersistedMessageQueue returns true when key is present', () => {
    localStorage.setItem('messageQueue', JSON.stringify({v: 2, items: []}));
    expect(service.hasPersistedMessageQueue()).toBeTrue();
  });
});

// ---------------------------------------------------------------------------
// MessageQueue.pruneByHashtag — U7 / U8 / U9
// ---------------------------------------------------------------------------

/**
 * Unit tests for MessageQueue.pruneByHashtag.
 *
 * U7 — removes the named hashtag from all entries that carry it.
 * U8 — drops entries whose hashtags[] becomes empty after the prune.
 * U9 — leaves entries that do not carry the pruned hashtag intact.
 *
 * Migrated from subscription.service.prune.spec.ts (Phase 3, commit 6).
 */
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

// ---------------------------------------------------------------------------
// MessageQueue.pruneByHashtags — U10
// ---------------------------------------------------------------------------

/**
 * Unit tests for MessageQueue.pruneByHashtags.
 *
 * U10 — accumulates removed IDs across all hashtags in the input list.
 *
 * Migrated from subscription.service.prune.spec.ts (Phase 3, commit 6).
 */
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
// MessageQueue.enqueueOrMergeHashtag — U11 / U12
// ---------------------------------------------------------------------------

/**
 * Unit tests for MessageQueue.enqueueOrMergeHashtag.
 *
 * U11 — merges the hashtag into an existing entry when the toot ID is present.
 * U12 — adds a new entry when the toot ID is not present.
 *
 * Migrated from subscription.service.prune.spec.ts (Phase 3, commit 6).
 */
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
      expect(queue.toArray()[0].hashtags.filter((h: string) => h === 'glacier').length).toBe(1);
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
// MessageQueue.restore — U13 / U14
// ---------------------------------------------------------------------------

/**
 * Unit tests for MessageQueue.restore schema version gating.
 *
 * U13 — discards the queue when the stored envelope has no recognised schema version.
 * U14 — loads items when the schema version is the current WallMessageSchemaVersion (v:2).
 *
 * Migrated from subscription.service.prune.spec.ts (Phase 3, commit 6).
 */
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
