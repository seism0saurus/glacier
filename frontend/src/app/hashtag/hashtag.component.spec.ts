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

describe('HashtagComponent', () => {
  let component: HashtagComponent;
  let fixture: ComponentFixture<HashtagComponent>;
  let mockSubscriptionService: jasmine.SpyObj<SubscriptionService>;

  beforeEach(() => {
    mockSubscriptionService = jasmine.createSpyObj<SubscriptionService>(['subscribeHashtag', 'unsubscribeHashtag', 'clearAllToots']);
    TestBed.configureTestingModule({
      declarations: [HashtagComponent],
      imports: [MatFormField,
        MatInputModule,
        MatChipInput,
        MatChipGrid,
        MatChipRow,
        MatChipRemove,
        MatIcon,
        BrowserAnimationsModule],
      providers: [{
        provide: SubscriptionService,
        useValue: mockSubscriptionService
      }, provideHttpClient(withInterceptorsFromDi()), provideHttpClientTesting()]
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
});
