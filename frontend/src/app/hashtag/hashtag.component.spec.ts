import {ComponentFixture, TestBed} from '@angular/core/testing';

import {HashtagComponent} from './hashtag.component';
import {MatFormField} from "@angular/material/form-field";
import {SubscriptionService} from '../subscription.service';
import {MatInputModule} from "@angular/material/input";
import {MatChipGrid, MatChipInput, MatChipRemove, MatChipRow} from "@angular/material/chips";
import {MatIcon} from "@angular/material/icon";
import {provideHttpClientTesting} from "@angular/common/http/testing";
import {BrowserAnimationsModule} from "@angular/platform-browser/animations";
import {provideHttpClient, withInterceptorsFromDi} from '@angular/common/http';
import {MatSnackBar, MatSnackBarModule} from '@angular/material/snack-bar';
import {RxStompService} from '../rx-stomp.service';
import {BehaviorSubject, Subject} from 'rxjs';
import {Message} from '@stomp/stompjs';
import {MatProgressSpinner, MatProgressSpinnerModule} from '@angular/material/progress-spinner';
import {MatTooltipModule} from '@angular/material/tooltip';
import {By} from '@angular/platform-browser';

describe('HashtagComponent', () => {
  let component: HashtagComponent;
  let fixture: ComponentFixture<HashtagComponent>;
  let mockSubscriptionService: jasmine.SpyObj<SubscriptionService>;
  let stompMessageSubject: Subject<Message>;
  let mockRxStompService: jasmine.SpyObj<RxStompService>;
  let settlingHashtagsSubject: BehaviorSubject<Set<string>>;

  beforeEach(() => {
    stompMessageSubject = new Subject<Message>();
    settlingHashtagsSubject = new BehaviorSubject<Set<string>>(new Set());
    mockSubscriptionService = jasmine.createSpyObj<SubscriptionService>(
      'SubscriptionService',
      ['subscribeHashtag', 'unsubscribeHashtag', 'clearAllToots'],
      // Expose settlingHashtags$ as a property on the spy object
      {settlingHashtags$: settlingHashtagsSubject.asObservable()},
    );
    mockRxStompService = jasmine.createSpyObj<RxStompService>(['watch', 'publish']);
    mockRxStompService.watch.and.returnValue(stompMessageSubject.asObservable() as any);

    TestBed.configureTestingModule({
      declarations: [HashtagComponent],
      imports: [
        MatFormField,
        MatInputModule,
        MatChipInput,
        MatChipGrid,
        MatChipRow,
        MatChipRemove,
        MatIcon,
        BrowserAnimationsModule,
        MatSnackBarModule,
        MatProgressSpinnerModule,
        MatTooltipModule,
      ],
      providers: [
        {provide: SubscriptionService, useValue: mockSubscriptionService},
        {provide: RxStompService, useValue: mockRxStompService},
        provideHttpClient(withInterceptorsFromDi()),
        provideHttpClientTesting(),
      ],
    });
    fixture = TestBed.createComponent(HashtagComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  describe('add()', () => {
    it('should add sanitized hashtags and subscribe', () => {
      const mockEvent = {value: '#Test', chipInput: {clear: jasmine.createSpy('clear')}} as any;
      component.add(mockEvent);
      expect(component.hashtags).toContain('test');
      expect(mockEvent.chipInput.clear).toHaveBeenCalled();
      expect(mockSubscriptionService.subscribeHashtag).toHaveBeenCalledWith('test');
    });

    it('should not add empty or duplicate hashtags', () => {
      component.hashtags = ['test'];
      const valuelessMockEvent = {chipInput: {clear: jasmine.createSpy('clear')}} as any;
      component.add(valuelessMockEvent);
      expect(component.hashtags).not.toContain(' ');
      const mockEvent = {value: '  ', chipInput: {clear: jasmine.createSpy('clear')}} as any;
      component.add(mockEvent);
      expect(component.hashtags).not.toContain(' ');
      const duplicateEvent = {value: '#Test', chipInput: {clear: jasmine.createSpy('clear')}} as any;
      component.add(duplicateEvent);
      expect(component.hashtags.length).toBe(1);
    });
  });

  describe('remove()', () => {
    it('should remove hashtags and unsubscribe', () => {
      component.hashtags = ['test'];
      component.remove('test');
      expect(component.hashtags).not.toContain('test');
      expect(mockSubscriptionService.unsubscribeHashtag).toHaveBeenCalledWith('test');
    });
  });

  describe('edit()', () => {
    it('should sanitize the input hashtag and update the list', () => {
      component.hashtags = ['test'];
      const mockEvent = {value: '#Updated'} as any;
      component.edit('test', mockEvent);
      expect(component.hashtags).toContain('updated');
      expect(mockSubscriptionService.unsubscribeHashtag).toHaveBeenCalledWith('test');
      expect(mockSubscriptionService.subscribeHashtag).toHaveBeenCalledWith('updated');
    });

    it('should remove the tag if sanitized value is empty', () => {
      component.hashtags = ['test'];
      const mockEvent = {value: ''} as any;
      component.edit('test', mockEvent);
      expect(component.hashtags).not.toContain('test');
      expect(mockSubscriptionService.unsubscribeHashtag).toHaveBeenCalledWith('test');
    });

    it('should not modify the tag if it has not been changed', () => {
      component.hashtags = ['unchanged'];
      const mockEvent = {value: '#Unchanged'} as any;
      component.edit('unchanged', mockEvent);
      expect(component.hashtags).toContain('unchanged');
      expect(mockSubscriptionService.unsubscribeHashtag).not.toHaveBeenCalled();
      expect(mockSubscriptionService.subscribeHashtag).not.toHaveBeenCalled();
    });

    it('should remove the tag being modified if a sanitized duplicate tag exists', () => {
      component.hashtags = ['original', 'duplicate'];
      const mockEvent = {value: '#Duplicate'} as any;
      component.edit('original', mockEvent);
      expect(component.hashtags).not.toContain('original');
      expect(component.hashtags).toEqual(['duplicate']);
      expect(mockSubscriptionService.unsubscribeHashtag).toHaveBeenCalledWith('original');
      expect(mockSubscriptionService.subscribeHashtag).not.toHaveBeenCalledWith('duplicate');
    });
  });

  describe('clearTags()', () => {
    it('should clear all hashtags and unsubscribe', () => {
      component.hashtags = ['test1', 'test2'];
      component.clearTags();
      expect(component.hashtags.length).toBe(0);
      expect(mockSubscriptionService.unsubscribeHashtag).toHaveBeenCalledWith('test1');
      expect(mockSubscriptionService.unsubscribeHashtag).toHaveBeenCalledWith('test2');
    });
  });

  describe('clearToots()', () => {
    it('should call clearAllToots', () => {
      component.clearToots();
      expect(mockSubscriptionService.clearAllToots).toHaveBeenCalled();
    });
  });

  describe('sanitize()', () => {
    it('should sanitize hashtags by trimming, lowercasing, and removing "#" prefix', () => {
      const sanitized = component['sanitize'](' #TestTest ');
      expect(sanitized).toBe('testtest');
    });

    it('should return an empty string for null or empty input', () => {
      const sanitized = component['sanitize']('');
      expect(sanitized).toBe('');
    });
  });

  // -------------------------------------------------------------------------
  // A11Y-F-02 — Cancel All and Clear Toots must be <button> elements (WCAG 2.1.1)
  // -------------------------------------------------------------------------
  describe('accessibility — keyboard-accessible action buttons (A11Y-F-02)', () => {
    /**
     * A11Y-F-02 (WCAG 2.1.1 — Keyboard):
     * The "Cancel All" action must be rendered as a <button> element so keyboard
     * users can activate it with Enter/Space and screen readers announce it correctly.
     */
    it('should render Cancel All as a <button> element', () => {
      const btn = fixture.nativeElement.querySelector('#cancel-all') as HTMLElement;
      expect(btn).toBeTruthy();
      expect(btn.tagName.toLowerCase()).toBe('button');
    });

    it('should have a non-empty aria-label on the Cancel All button', () => {
      const btn = fixture.nativeElement.querySelector('#cancel-all') as HTMLButtonElement;
      expect(btn).toBeTruthy();
      const label = btn.getAttribute('aria-label');
      expect(label).toBeTruthy();
      expect(label!.length).toBeGreaterThan(0);
    });

    /**
     * A11Y-F-02 (WCAG 2.1.1 — Keyboard):
     * The "Clear Toots" action must be rendered as a <button> element so keyboard
     * users can activate it with Enter/Space and screen readers announce it correctly.
     */
    it('should render Clear Toots as a <button> element', () => {
      const btn = fixture.nativeElement.querySelector('#clear-toots') as HTMLElement;
      expect(btn).toBeTruthy();
      expect(btn.tagName.toLowerCase()).toBe('button');
    });

    it('should have a non-empty aria-label on the Clear Toots button', () => {
      const btn = fixture.nativeElement.querySelector('#clear-toots') as HTMLButtonElement;
      expect(btn).toBeTruthy();
      const label = btn.getAttribute('aria-label');
      expect(label).toBeTruthy();
      expect(label!.length).toBeGreaterThan(0);
    });
  });

  // -------------------------------------------------------------------------
  // CAP_EXCEEDED rejection — optimistic rollback + snackbar (D-12, D-17)
  // -------------------------------------------------------------------------
  describe('CAP_EXCEEDED rejection', () => {

    it('should remove the optimistic chip when server rejects with CAP_EXCEEDED', () => {
      // Simulate user adding a hashtag (optimistic add)
      const mockEvent = {value: 'overflow', chipInput: {clear: jasmine.createSpy('clear')}} as any;
      component.add(mockEvent);
      expect(component.hashtags).toContain('overflow');

      // Server responds with CAP_EXCEEDED rejection
      const ackMessage = {
        body: JSON.stringify({
          hashtag: 'overflow',
          principal: 'user1',
          subscribed: false,
          rejection: {code: 'CAP_EXCEEDED', details: {limit: 10}},
        }),
      } as Message;
      stompMessageSubject.next(ackMessage);
      fixture.detectChanges();

      // Chip should be rolled back
      expect(component.hashtags).not.toContain('overflow');
    });

    it('should show a snackbar with limit info on CAP_EXCEEDED', () => {
      const snackBar = TestBed.inject(MatSnackBar);
      const openSpy = spyOn(snackBar, 'open').and.callThrough();

      const mockEvent = {value: 'toomany', chipInput: {clear: jasmine.createSpy('clear')}} as any;
      component.add(mockEvent);

      const ackMessage = {
        body: JSON.stringify({
          hashtag: 'toomany',
          principal: 'user1',
          subscribed: false,
          rejection: {code: 'CAP_EXCEEDED', details: {limit: 5}},
        }),
      } as Message;
      stompMessageSubject.next(ackMessage);

      expect(openSpy).toHaveBeenCalled();
      const callArgs = openSpy.calls.mostRecent().args;
      // First argument is the message text — should contain limit info
      expect(callArgs[0]).toContain('5');
    });

    it('should show a snackbar WITHOUT limit when rejection has no details', () => {
      // Regression guard for the cap.reached.snackbar.no.limit branch (D-12).
      // When the server sends CAP_EXCEEDED without a details.limit, the
      // no-limit snackbar branch must fire and the chip must still be rolled back.
      const snackBar = TestBed.inject(MatSnackBar);
      const openSpy = spyOn(snackBar, 'open').and.callThrough();

      const mockEvent = {value: 'nolimit', chipInput: {clear: jasmine.createSpy('clear')}} as any;
      component.add(mockEvent);

      const ackMessage = {
        body: JSON.stringify({
          hashtag: 'nolimit',
          principal: 'user1',
          subscribed: false,
          rejection: {code: 'CAP_EXCEEDED'},  // no details field
        }),
      } as Message;
      stompMessageSubject.next(ackMessage);
      fixture.detectChanges();

      // Chip should be rolled back
      expect(component.hashtags).not.toContain('nolimit');

      // Snackbar must have fired
      expect(openSpy).toHaveBeenCalled();

      // The message should mention the hashtag name
      const callArgs = openSpy.calls.mostRecent().args;
      expect(typeof callArgs[0]).toBe('string');
      expect(callArgs[0]).toContain('nolimit');
    });

    it('should not roll back chip when server acks with subscribed:true', () => {
      const mockEvent = {value: 'valid', chipInput: {clear: jasmine.createSpy('clear')}} as any;
      component.add(mockEvent);

      const ackMessage = {
        body: JSON.stringify({
          hashtag: 'valid',
          principal: 'user1',
          subscribed: true,
        }),
      } as Message;
      stompMessageSubject.next(ackMessage);
      fixture.detectChanges();

      expect(component.hashtags).toContain('valid');
    });
  });

  // -------------------------------------------------------------------------
  // Settling spinner — chip shows spinner during recentlyTerminated window
  // -------------------------------------------------------------------------
  describe('settling spinner (chip.settling.*)', () => {
    it('should start with no settling hashtags', () => {
      expect(component.settlingHashtags.size).toBe(0);
    });

    it('isSettling() should return false for non-settling hashtags', () => {
      expect(component.isSettling('test')).toBeFalse();
    });

    it('should update settlingHashtags when settlingHashtags$ emits', () => {
      // Arrange: add a hashtag chip
      component.hashtags = ['glacier'];
      fixture.detectChanges();

      // Act: SubscriptionService signals that 'glacier' is settling
      settlingHashtagsSubject.next(new Set(['glacier']));
      fixture.detectChanges();

      // Assert: isSettling returns true for the settling hashtag
      expect(component.isSettling('glacier')).toBeTrue();
    });

    it('should return false for a hashtag that is no longer settling', () => {
      // Arrange: initially settling
      settlingHashtagsSubject.next(new Set(['glacier']));
      fixture.detectChanges();
      expect(component.isSettling('glacier')).toBeTrue();

      // Act: guard window expired — empty set emitted
      settlingHashtagsSubject.next(new Set());
      fixture.detectChanges();

      // Assert: no longer settling
      expect(component.isSettling('glacier')).toBeFalse();
    });

    it('isSettling() should normalise the hashtag before checking', () => {
      // The settlingHashtags set uses normalised keys (lowercase, no #).
      // isSettling() must normalise the input tag before lookup.
      settlingHashtagsSubject.next(new Set(['glacier']));
      fixture.detectChanges();

      // Raw tag with uppercase — should still match the normalised key
      expect(component.isSettling('Glacier')).toBeTrue();
      expect(component.isSettling('#Glacier')).toBeTrue();
    });

    it('should show progress spinner in chip when hashtag is settling', () => {
      // Arrange: add a chip and signal settling
      component.hashtags = ['spinnertest'];
      fixture.detectChanges();
      settlingHashtagsSubject.next(new Set(['spinnertest']));
      fixture.detectChanges();

      // Assert: mat-progress-spinner appears in the chip
      const spinner = fixture.debugElement.query(By.directive(MatProgressSpinner));
      expect(spinner).not.toBeNull();
    });

    it('should not show progress spinner when hashtag is not settling', () => {
      // Arrange: chip present, not settling
      component.hashtags = ['nospinner'];
      fixture.detectChanges();

      // Assert: no spinner
      const spinner = fixture.debugElement.query(By.directive(MatProgressSpinner));
      expect(spinner).toBeNull();
    });
  });

  // -------------------------------------------------------------------------
  // i18n catalog completeness — regression guard for missing @@id keys (FIX B)
  // -------------------------------------------------------------------------
  describe('i18n catalog completeness (messages.en.json)', () => {
    /**
     * The set of @@id values used in hashtag.component.ts that MUST exist as
     * top-level keys in the English translation catalog.  A missing key means
     * English users see untranslated German text.
     *
     * This list is the required subset for this component.  A broader per-repo
     * lint that greps all @@ids across the entire codebase is tracked as a
     * follow-up CI improvement in the angular-i18n-localize skill.
     */
    const REQUIRED_CATALOG_KEYS = [
      'cap.reached.snackbar',
      'cap.reached.snackbar.no.limit',  // FIX B: was missing before this PR
      'gap.snackbar.dismiss',
      'chip.settling.aria',
      'chip.settling.visual.tooltip',
      'hashtag.cancel.all.aria',        // A11Y-F-02: Cancel All button aria-label
      'hashtag.cancel.all.label',       // A11Y-F-02: Cancel All button visible text
      'hashtag.clear.toots.aria',       // A11Y-F-02: Clear Toots button aria-label
      'hashtag.clear.toots.label',      // A11Y-F-02: Clear Toots button visible text
    ] as const;

    for (const key of REQUIRED_CATALOG_KEYS) {
      it(`messages.en.json must contain key '${key}'`, async () => {
        // Load the catalog at test time via fetch to avoid TypeScript module
        // resolution issues with JSON imports across different bundler configs.
        // Karma serves the test host on the same origin, so /assets/ is reachable.
        const response = await fetch('/assets/i18n/messages.en.json');
        expect(response.ok)
          .withContext('messages.en.json must be fetchable by the Karma test runner')
          .toBeTrue();

        const catalog: Record<string, string> = await response.json();
        expect(catalog[key])
          .withContext(
            `messages.en.json is missing key '${key}'. ` +
            `English users will see the untranslated German default. ` +
            `Add the key and an English translation to fix this.`
          )
          .toBeDefined();
        // Also assert the value is a non-empty string
        expect(typeof catalog[key]).toBe('string');
        expect(catalog[key].length).toBeGreaterThan(0);
      });
    }
  });
});
