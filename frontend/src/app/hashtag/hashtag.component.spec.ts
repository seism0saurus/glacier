import {ComponentFixture, TestBed} from '@angular/core/testing';

import {HashtagComponent} from './hashtag.component';
import {MatFormField} from "@angular/material/form-field";
import {SubscriptionService} from '../subscription.service';
import {MatInputModule} from "@angular/material/input";
import {MatChipGrid, MatChipInput, MatChipRemove, MatChipRow} from "@angular/material/chips";
import {MatIcon} from "@angular/material/icon";
import {HttpClientTestingModule} from "@angular/common/http/testing";
import {BrowserAnimationsModule} from "@angular/platform-browser/animations";

describe('HashtagComponent', () => {
  let component: HashtagComponent;
  let fixture: ComponentFixture<HashtagComponent>;
  let mockSubscriptionService: jasmine.SpyObj<SubscriptionService>;

  beforeEach(() => {
    mockSubscriptionService = jasmine.createSpyObj<SubscriptionService>(['subscribeHashtag', 'unsubscribeHashtag', 'clearAllToots']);
    TestBed.configureTestingModule({
      declarations: [HashtagComponent],
      imports: [
        HttpClientTestingModule,
        MatFormField,
        MatInputModule,
        MatChipInput,
        MatChipGrid,
        MatChipRow,
        MatChipRemove,
        MatIcon,
        BrowserAnimationsModule
      ],
      providers: [{provide: SubscriptionService, useValue: mockSubscriptionService}]
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
    it('should sanitize and edit hashtags', () => {
      component.hashtags = ['test'];
      const mockEvent = {value: '#Updated'} as any;
      component.edit('test', mockEvent);
      expect(component.hashtags).toContain('updated');
      expect(mockSubscriptionService.unsubscribeHashtag).toHaveBeenCalledWith('test');
      expect(mockSubscriptionService.subscribeHashtag).toHaveBeenCalledWith('updated');
    });

    it('should remove tags if sanitized value is empty', () => {
      component.hashtags = ['test'];
      const mockEvent = {value: ''} as any;
      component.edit('test', mockEvent);
      expect(component.hashtags).not.toContain('test');
      expect(mockSubscriptionService.unsubscribeHashtag).toHaveBeenCalledWith('test');
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
