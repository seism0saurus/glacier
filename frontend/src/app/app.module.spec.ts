/**
 * Unit tests for the FIX-1 reduced-motion animation suppression in AppModule.
 *
 * Problem: BrowserAnimationsModule registers the WebAnimations API (WAAPI) driver.
 * CSS @media (prefers-reduced-motion: reduce) rules do NOT suppress WAAPI animations
 * because Angular's animation engine drives motion via JavaScript, not CSS keyframes.
 *
 * Fix: When window.matchMedia('(prefers-reduced-motion: reduce)').matches is true,
 * AppModule overrides AnimationDriver with NoopAnimationDriver and sets
 * ANIMATION_MODULE_TYPE to 'NoopAnimations'. This suppresses all Angular-driven
 * animations at the engine level — not just visually.
 *
 * Test strategy: We cannot easily override window.matchMedia at module-load time
 * in Karma because AppModule is already compiled. Instead we test the behaviour
 * directly: a TestBed configured with provideNoopAnimations() must result in
 * AnimationPlayer.play() never being called when an @trigger fires.
 *
 * This is the correct regression guard — the UX2 Playwright spec (50 ms timeout)
 * is the integration proof; this unit test is the engine-level contract.
 */

import {ComponentFixture, TestBed} from '@angular/core/testing';
import {Component} from '@angular/core';
import {provideNoopAnimations} from '@angular/platform-browser/animations';
import {AnimationPlayer, animate, style, transition, trigger} from '@angular/animations';
import {BrowserAnimationsModule, ANIMATION_MODULE_TYPE} from '@angular/platform-browser/animations';
import {AnimationDriver} from '@angular/animations/browser';

/**
 * Minimal host component with a @pruneLeave-style trigger.
 * Used to verify that AnimationPlayer.play() is suppressed when the
 * NoopAnimationDriver is active.
 */
@Component({
  standalone: false,
  selector: 'app-test-host',
  template: `
    <div *ngIf="visible" [@fadeOut]>test content</div>
  `,
  animations: [
    trigger('fadeOut', [
      transition(':leave', [
        animate('150ms ease-out', style({opacity: 0})),
      ]),
    ]),
  ],
})
class TestHostComponent {
  visible = true;
}

describe('FIX-1 — reduced-motion animation suppression', () => {

  // ---------------------------------------------------------------------------
  // Test A: provideNoopAnimations() results in no AnimationPlayer.play() calls.
  //
  // This mirrors what AppModule does when prefersReducedMotion === true:
  // it overrides AnimationDriver with NoopAnimationDriver (via provideNoopAnimations()
  // or equivalent). The result must be that no real WAAPI call is made.
  // ---------------------------------------------------------------------------
  describe('with provideNoopAnimations() (simulates prefersReducedMotion === true)', () => {
    let fixture: ComponentFixture<TestHostComponent>;
    let component: TestHostComponent;

    beforeEach(async () => {
      await TestBed.configureTestingModule({
        declarations: [TestHostComponent],
        providers: [provideNoopAnimations()],
      }).compileComponents();

      fixture = TestBed.createComponent(TestHostComponent);
      component = fixture.componentInstance;
      fixture.detectChanges();
    });

    it('should inject ANIMATION_MODULE_TYPE as NoopAnimations', () => {
      // When NoopAnimations is active, the ANIMATION_MODULE_TYPE token must
      // return 'NoopAnimations'. This is the contract the AnimationBuilder
      // reads to decide whether to use WAAPI.
      const animationType = TestBed.inject(ANIMATION_MODULE_TYPE, null);
      expect(animationType).toBe('NoopAnimations');
    });

    it('should not call Element.animate() (WAAPI) when a :leave transition fires', () => {
      // Arrange: spy on the platform WAAPI method Element.prototype.animate.
      // If a real WebAnimationsDriver is active, it calls element.animate(keyframes, options)
      // to drive motion. NoopAnimationDriver's NoopAnimationPlayer never calls this method.
      const waapiSpy = spyOn(Element.prototype, 'animate').and.callFake((() => {
        // Return a minimal stub so no error is thrown if the spy is accidentally called.
        return {
          play: () => {},
          cancel: () => {},
          finished: Promise.resolve({} as Animation),
          addEventListener: () => {},
          removeEventListener: () => {},
        } as unknown as Animation;
      }));

      // Act: trigger the :leave transition by hiding the element.
      component.visible = false;
      fixture.detectChanges();

      // Assert: Element.prototype.animate was NEVER called.
      // A NoopAnimationPlayer processes triggers synchronously without invoking WAAPI.
      expect(waapiSpy).not.toHaveBeenCalled();
    });
  });

  // ---------------------------------------------------------------------------
  // Test B: ANIMATION_MODULE_TYPE reads 'BrowserAnimations' when no override present.
  //
  // This confirms the non-reduced-motion path (normal operation) still uses the
  // full animation engine, so we don't accidentally suppress animations for
  // everyone.
  // ---------------------------------------------------------------------------
  describe('with BrowserAnimationsModule (simulates prefersReducedMotion === false)', () => {
    beforeEach(async () => {
      await TestBed.configureTestingModule({
        declarations: [TestHostComponent],
        imports: [BrowserAnimationsModule],
      }).compileComponents();
    });

    it('should inject ANIMATION_MODULE_TYPE as BrowserAnimations', () => {
      const animationType = TestBed.inject(ANIMATION_MODULE_TYPE, null);
      expect(animationType).toBe('BrowserAnimations');
    });

    it('should use a non-Noop AnimationDriver (WebAnimations or equivalent)', () => {
      const driver = TestBed.inject(AnimationDriver);
      // The full animations path must NOT use NoopAnimationDriver.
      expect(driver.constructor.name).not.toBe('NoopAnimationDriver');
    });
  });

});
