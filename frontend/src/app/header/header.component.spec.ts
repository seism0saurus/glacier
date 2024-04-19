import { ComponentFixture, TestBed } from '@angular/core/testing';

import { HeaderComponent } from './header.component';
import {AnimationService} from "../animation.service";
import {BehaviorSubject, Subscription} from "rxjs";

describe('HeaderComponent', () => {
  let component: HeaderComponent;
  let fixture: ComponentFixture<HeaderComponent>;
  let service: AnimationService;

  beforeEach(() => {
    TestBed.configureTestingModule({
      declarations: [HeaderComponent],
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
    const spy = spyOn(BehaviorSubject.prototype, 'subscribe');
    component.ngOnInit();
    expect(spy).toHaveBeenCalledTimes(1);
  });
});
