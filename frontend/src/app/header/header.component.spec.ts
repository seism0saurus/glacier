import {ComponentFixture, TestBed} from '@angular/core/testing';
import {NO_ERRORS_SCHEMA} from '@angular/core';

import {HeaderComponent} from './header.component';
import {AnimationService} from "../animation.service";
import {Subscription} from "rxjs";
import {MatDialog} from "@angular/material/dialog";

describe('HeaderComponent', () => {
  let component: HeaderComponent;
  let fixture: ComponentFixture<HeaderComponent>;
  let service: AnimationService;
  let mockDialog: jasmine.SpyObj<MatDialog>;

  beforeEach(() => {
    mockDialog = jasmine.createSpyObj<MatDialog>('MatDialog', ['open']);

    TestBed.configureTestingModule({
      declarations: [HeaderComponent],
      schemas: [NO_ERRORS_SCHEMA],
      providers: [
        {provide: MatDialog, useValue: mockDialog},
      ],
    });
    fixture = TestBed.createComponent(HeaderComponent);
    component = fixture.componentInstance;
    service = TestBed.inject(AnimationService);
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  describe('animation', () => {
    it('should start extended', () => {
      expect(component.extended).toBeTruthy();
    });
    describe('extended', () => {
      it('should be set on header', () => {
        const compiled = fixture.nativeElement as HTMLElement;
        expect(compiled.querySelector('header')?.getAttribute('class'))
          .toBe('extended')
      });

      it('should be set on image', () => {
        const compiled = fixture.nativeElement as HTMLElement;
        expect(compiled.querySelector('img')?.getAttribute('class'))
          .toBe('extended')
      });
    });

    it('should be small after subscription', () => {
      service.setSubscribed();
      expect(component.extended).toBeFalsy();
    });
    describe('small after subscription', () => {
      it('should be set on header', () => {
        service.setSubscribed();
        fixture.detectChanges();
        const compiled = fixture.nativeElement as HTMLElement;
        expect(compiled.querySelector('header')?.getAttribute('class'))
          .toBe('small')
      });

      it('should be set on image', () => {
        service.setSubscribed();
        fixture.detectChanges();
        const compiled = fixture.nativeElement as HTMLElement;
        expect(compiled.querySelector('img')?.getAttribute('class'))
          .toBe('small')
      });
    });
  });

  it('should unsubscribe from animation service on destroy', () => {
    const spy = spyOn(Subscription.prototype, 'unsubscribe');
    component.ngOnDestroy();
    expect(spy).toHaveBeenCalledTimes(1);
  });

  it('should subscribe to the animation service on init', () => {
    const spy = spyOn(service.getHeaderExtended(), 'subscribe');
    component.ngOnInit();
    expect(spy).toHaveBeenCalled();
  });

  it('should update extended when observable emits a value', () => {
    const testValue = false;
    spyOn(service, 'getHeaderExtended').and.returnValue({
      subscribe: (observer: any) => observer.next(testValue),
    } as any);
    component.ngOnInit();
    expect(component.extended).toBe(testValue);
  });

  it('should log error when observable emits an error', () => {
    const consoleSpy = spyOn(console, 'error');
    const testError = 'Test error';
    spyOn(service, 'getHeaderExtended').and.returnValue({
      subscribe: (observer: any) => observer.error(testError),
    } as any);
    component.ngOnInit();
    expect(consoleSpy).toHaveBeenCalledWith('Observable emitted an error: ' + testError);
  });

  it('should log complete notification when observable completes', () => {
    const consoleSpy = spyOn(console, 'log');
    spyOn(service, 'getHeaderExtended').and.returnValue({
      subscribe: (observer: any) => observer.complete(),
    } as any);
    component.ngOnInit();
    expect(consoleSpy).toHaveBeenCalledWith('Observable emitted the complete notification');
  });

  // D.2 i18n: h1 text and i18n attribute
  it('should render the h1 with the application title', () => {
    const compiled = fixture.nativeElement as HTMLElement;
    const h1 = compiled.querySelector('h1');
    expect(h1).withContext('h1 must exist').toBeTruthy();
    // In Karma (German source, no catalog loaded) the text is the source text.
    expect(h1?.textContent?.trim())
      .withContext('h1 must contain the application title')
      .toBe('Glacier - The Mastodon Social Wall');
  });

  // D.3 P1-17: Share button
  it('should render a share button with data-testid="share-button"', () => {
    const compiled = fixture.nativeElement as HTMLElement;
    const btn = compiled.querySelector('[data-testid="share-button"]');
    expect(btn).withContext('share button must exist').toBeTruthy();
  });

  it('should call openShareDialog when the share button is clicked', () => {
    const spy = spyOn(component, 'openShareDialog');
    const compiled = fixture.nativeElement as HTMLElement;
    const btn = compiled.querySelector<HTMLButtonElement>('[data-testid="share-button"]');
    btn?.click();
    expect(spy).toHaveBeenCalledTimes(1);
  });

  it('should open the share dialog via MatDialog when openShareDialog is called', () => {
    component.openShareDialog();
    expect(mockDialog.open).toHaveBeenCalledTimes(1);
  });

  // TOOT-04: share button must announce it opens a dialog
  it('TOOT-04: share button must have aria-haspopup="dialog" (WCAG 4.1.2)', () => {
    const compiled = fixture.nativeElement as HTMLElement;
    const btn = compiled.querySelector('[data-testid="share-button"]');
    expect(btn).withContext('share button must exist').toBeTruthy();
    expect(btn?.getAttribute('aria-haspopup'))
      .withContext('share button must declare aria-haspopup="dialog" so AT announces modal opening')
      .toBe('dialog');
  });
});
