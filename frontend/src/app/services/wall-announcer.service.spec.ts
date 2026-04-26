import {TestBed, fakeAsync, tick} from '@angular/core/testing';
import {WallAnnouncerService} from './wall-announcer.service';
import {PruneResult} from '../model/wall-message';

/**
 * Unit tests for WallAnnouncerService (ADR-5).
 *
 * FIX-2: Tests rewritten to assert on announcements$ Observable emissions
 * instead of direct DOM textContent mutations. The service no longer holds a
 * reference to a live region DOM element — it emits coalesced announcement
 * strings on announcements$ and WallComponent assigns them to announceText,
 * keeping Angular change detection as the sole writer to the live region DOM.
 *
 * U4  — WallAnnouncerService debounces multiple prune events within 250 ms
 *        and emits a single coalesced announcement on announcements$.
 * U5  — A cancelAll event in the window takes precedence over any prune events.
 * U6  — announcements$ emits the resolved text after the debounce + setTimeout.
 */
describe('WallAnnouncerService', () => {
  let service: WallAnnouncerService;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [WallAnnouncerService],
    });
    service = TestBed.inject(WallAnnouncerService);
    service.setMessages('Removed: {count} posts.', 'All subscriptions ended. Wall is empty.');
  });

  afterEach(() => {
    service.ngOnDestroy();
  });

  describe('U4 — debounced coalescing of prune events', () => {
    it('should not emit text on announcements$ before the debounce window closes', fakeAsync(() => {
      // Arrange
      const result: PruneResult = {removed: ['1', '2'], remaining: []};
      const emitted: string[] = [];
      // Subscribe after setup so we only capture new emissions (skip the BehaviorSubject initial '')
      const initialEmission = service.announcements$.getValue();
      const sub = service.announcements$.subscribe(t => emitted.push(t));
      emitted.length = 0; // discard initial BehaviorSubject emission

      // Act: announce two events within the 250 ms window
      service.announce({type: 'prune', result});
      service.announce({type: 'prune', result});
      tick(100); // within the debounce window

      // Assert: no new emission yet (debounce has not fired)
      expect(emitted).toEqual([]);

      tick(300); // allow debounce + setTimeout(0) to fire
      sub.unsubscribe();
      expect(initialEmission).toBe('');
    }));

    it('should coalesce two prune events and emit once after 250 ms', fakeAsync(() => {
      // Arrange
      const result1: PruneResult = {removed: ['1', '2'], remaining: []};
      const result2: PruneResult = {removed: ['3'], remaining: []};
      const emitted: string[] = [];
      const sub = service.announcements$.subscribe(t => emitted.push(t));
      emitted.length = 0; // discard initial BehaviorSubject emission

      // Act
      service.announce({type: 'prune', result: result1});
      service.announce({type: 'prune', result: result2});

      tick(250); // debounce fires → emits '' (clear), then queues setTimeout
      tick(0);   // setTimeout fires → emits the resolved text

      sub.unsubscribe();

      // Assert: live region receives a clear ('') then the coalesced text.
      // The count is 3 (2 + 1) substituted into the message template.
      expect(emitted).toEqual(['', 'Removed: 3 posts.']);
    }));

    it('should announce again when a second prune event arrives after the window', fakeAsync(() => {
      // Arrange: fire first event and let the window close
      const result: PruneResult = {removed: ['1'], remaining: []};
      const emitted: string[] = [];
      const sub = service.announcements$.subscribe(t => emitted.push(t));
      emitted.length = 0; // discard initial BehaviorSubject emission

      service.announce({type: 'prune', result});
      tick(250);
      tick(0);

      // Record first-batch emissions and reset for second batch
      const firstBatch = [...emitted];
      emitted.length = 0;

      // Act: second event in a new window
      const result2: PruneResult = {removed: ['2', '3'], remaining: []};
      service.announce({type: 'prune', result: result2});
      tick(250);
      tick(0);

      sub.unsubscribe();

      // Assert: first announcement arrived
      expect(firstBatch).toEqual(['', 'Removed: 1 posts.']);
      // Assert: second announcement arrived
      expect(emitted).toEqual(['', 'Removed: 2 posts.']);
    }));
  });

  describe('U5 — cancelAll event takes precedence', () => {
    it('should emit the cancelAll message when the batch contains a cancelAll event', fakeAsync(() => {
      // Arrange
      const result: PruneResult = {removed: ['1'], remaining: []};
      const emitted: string[] = [];
      const sub = service.announcements$.subscribe(t => emitted.push(t));
      emitted.length = 0; // discard initial BehaviorSubject emission

      // Act: one prune + one cancelAll in the same window
      service.announce({type: 'prune', result});
      service.announce({type: 'cancelAll'});

      tick(250);
      tick(0);

      sub.unsubscribe();

      // Assert: cancelAll message wins
      expect(emitted).toContain('All subscriptions ended. Wall is empty.');
    }));

    it('should emit the cancelAll message when only a cancelAll event is in the window', fakeAsync(() => {
      // Arrange
      const emitted: string[] = [];
      const sub = service.announcements$.subscribe(t => emitted.push(t));
      emitted.length = 0; // discard initial BehaviorSubject emission

      // Act
      service.announce({type: 'cancelAll'});

      tick(250);
      tick(0);

      sub.unsubscribe();

      // Assert
      expect(emitted).toContain('All subscriptions ended. Wall is empty.');
    }));
  });

  describe('U6 — announcements$ Observable emissions', () => {
    it('should emit the announcement text after debounce (regression guard for FIX-2)', fakeAsync(() => {
      // Arrange: this is the primary regression guard for the dual-write race fix.
      // The service must emit on announcements$ (not write textContent directly).
      const emitted: string[] = [];
      const sub = service.announcements$.subscribe(t => emitted.push(t));
      emitted.length = 0; // discard initial BehaviorSubject emission

      const result: PruneResult = {removed: ['a'], remaining: []};

      // Act
      service.announce({type: 'prune', result});
      tick(250); // debounce fires → clear emission, then queues setTimeout
      tick(0);   // setTimeout fires → text emission

      sub.unsubscribe();

      // Assert: the clear emission came first, then the resolved text
      expect(emitted[0]).toBe('');
      expect(emitted[1]).toMatch(/1/); // count '1' is in the resolved message
    }));

    it('should not throw when announce() is called with no subscribers', fakeAsync(() => {
      // No subscriber attached — service must not throw
      const result: PruneResult = {removed: ['1'], remaining: []};

      expect(() => {
        service.announce({type: 'prune', result});
        tick(250);
        tick(0);
      }).not.toThrow();
    }));

    it('should not emit text when the resolved message is empty', fakeAsync(() => {
      // Arrange: set empty message strings
      service.setMessages('', '');
      const emitted: string[] = [];
      const sub = service.announcements$.subscribe(t => emitted.push(t));
      emitted.length = 0; // discard initial BehaviorSubject emission

      const result: PruneResult = {removed: ['1'], remaining: []};

      // Act
      service.announce({type: 'prune', result});
      tick(250);
      tick(0);

      sub.unsubscribe();

      // Assert: no emission because the resolved text is empty ('')
      expect(emitted).toEqual([]);
    }));

    it('should provide the current value immediately to new subscribers via BehaviorSubject', () => {
      // BehaviorSubject semantics: new subscribers receive the current value synchronously.
      const received: string[] = [];
      const sub = service.announcements$.subscribe(t => received.push(t));
      sub.unsubscribe();

      // The initial value is '' (empty — no announcement pending).
      expect(received).toEqual(['']);
    });
  });
});
