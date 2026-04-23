/**
 * Unit tests for ConnectionStatusComponent (D-15).
 *
 * Coverage:
 * - Per-state DOM class, icon, and label rendering.
 * - ARIA: role="status" aria-live="polite" narration.
 * - Focus: chip button is focusable.
 * - Reduced-motion: rotating-icon class applied only in PROBING.
 * - Initial WEBSOCKET render suppresses live-region narration.
 * - Reload action is visible only for OFFLINE and KILLSWITCHED states.
 */

import {ComponentFixture, TestBed} from '@angular/core/testing';
import {ConnectionStatusComponent} from './connection-status.component';
import {FallbackService} from '../fallback/fallback.service';
import {TransportMode} from '../fallback/transport-mode';
import {BehaviorSubject, Subject} from 'rxjs';
import {MatMenuModule} from '@angular/material/menu';
import {MatIconModule} from '@angular/material/icon';
import {MatButtonModule} from '@angular/material/button';
import {BrowserAnimationsModule} from '@angular/platform-browser/animations';
import {NgIf} from '@angular/common';

describe('ConnectionStatusComponent', () => {
  let component: ConnectionStatusComponent;
  let fixture: ComponentFixture<ConnectionStatusComponent>;
  let modeSubject: BehaviorSubject<TransportMode>;
  let eventsSubject: Subject<any>;
  let fallbackServiceSpy: jasmine.SpyObj<FallbackService>;

  beforeEach(() => {
    modeSubject   = new BehaviorSubject<TransportMode>(TransportMode.WEBSOCKET);
    eventsSubject = new Subject<any>();

    fallbackServiceSpy = jasmine.createSpyObj<FallbackService>(
      'FallbackService',
      [],
      {
        transportMode$: modeSubject.asObservable(),
        events$: eventsSubject.asObservable(),
      }
    );

    TestBed.configureTestingModule({
      declarations: [ConnectionStatusComponent],
      imports: [
        MatMenuModule,
        MatIconModule,
        MatButtonModule,
        BrowserAnimationsModule,
        NgIf,
      ],
      providers: [
        {provide: FallbackService, useValue: fallbackServiceSpy},
      ],
    });

    fixture = TestBed.createComponent(ConnectionStatusComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  // -------------------------------------------------------------------------
  // Basic creation
  // -------------------------------------------------------------------------
  it('should create', () => {
    expect(component).toBeTruthy();
  });

  // -------------------------------------------------------------------------
  // WEBSOCKET state
  // -------------------------------------------------------------------------
  it('should have websocket CSS class on initial render', () => {
    const btn = fixture.nativeElement.querySelector('[data-testid="connection-status"]');
    expect(btn.classList).toContain('connection-chip--websocket');
  });

  it('should suppress live-region on initial WEBSOCKET render', () => {
    const liveRegion = fixture.nativeElement.querySelector('[role="status"]');
    expect(liveRegion.textContent.trim()).toBe('');
  });

  it('should NOT show reload CTA in WEBSOCKET state', () => {
    const cta = fixture.nativeElement.querySelector('.connection-menu__cta');
    expect(cta).toBeNull();
  });

  // -------------------------------------------------------------------------
  // PROBING state
  // -------------------------------------------------------------------------
  it('should have probing CSS class in PROBING state', () => {
    modeSubject.next(TransportMode.PROBING);
    fixture.detectChanges();
    const btn = fixture.nativeElement.querySelector('[data-testid="connection-status"]');
    expect(btn.classList).toContain('connection-chip--probing');
  });

  it('should apply rotating-icon class to icon in PROBING state', () => {
    modeSubject.next(TransportMode.PROBING);
    fixture.detectChanges();
    const icon = fixture.nativeElement.querySelector('.rotating-icon');
    expect(icon).toBeTruthy();
  });

  it('should populate live-region text in PROBING state', () => {
    modeSubject.next(TransportMode.PROBING);
    fixture.detectChanges();
    const liveRegion = fixture.nativeElement.querySelector('[role="status"]');
    expect(liveRegion.textContent.trim()).not.toBe('');
  });

  // -------------------------------------------------------------------------
  // FALLBACK state
  // -------------------------------------------------------------------------
  it('should have fallback CSS class in FALLBACK state', () => {
    modeSubject.next(TransportMode.FALLBACK);
    fixture.detectChanges();
    const btn = fixture.nativeElement.querySelector('[data-testid="connection-status"]');
    expect(btn.classList).toContain('connection-chip--fallback');
  });

  it('should NOT apply rotating-icon class in FALLBACK state', () => {
    modeSubject.next(TransportMode.FALLBACK);
    fixture.detectChanges();
    const icon = fixture.nativeElement.querySelector('.rotating-icon');
    expect(icon).toBeNull();
  });

  // -------------------------------------------------------------------------
  // OFFLINE state
  // -------------------------------------------------------------------------
  it('should have offline CSS class in OFFLINE state', () => {
    modeSubject.next(TransportMode.OFFLINE);
    fixture.detectChanges();
    const btn = fixture.nativeElement.querySelector('[data-testid="connection-status"]');
    expect(btn.classList).toContain('connection-chip--offline');
  });

  it('should show reload CTA in OFFLINE state', () => {
    modeSubject.next(TransportMode.OFFLINE);
    fixture.detectChanges();

    // The reload CTA is rendered inside the MatMenu.
    // In unit tests, we check the component property directly since MatMenu
    // contents are portal-rendered outside the component's DOM.
    expect(component.showReloadCta).toBeTrue();
  });

  // -------------------------------------------------------------------------
  // KILLSWITCHED state
  // -------------------------------------------------------------------------
  it('should have killswitched CSS class in KILLSWITCHED state', () => {
    modeSubject.next(TransportMode.KILLSWITCHED);
    fixture.detectChanges();
    const btn = fixture.nativeElement.querySelector('[data-testid="connection-status"]');
    expect(btn.classList).toContain('connection-chip--killswitched');
  });

  it('should show reload CTA in KILLSWITCHED state', () => {
    modeSubject.next(TransportMode.KILLSWITCHED);
    fixture.detectChanges();
    expect(component.showReloadCta).toBeTrue();
  });

  // -------------------------------------------------------------------------
  // INSECURE state
  // -------------------------------------------------------------------------
  it('should have insecure CSS class in INSECURE state', () => {
    modeSubject.next(TransportMode.INSECURE);
    fixture.detectChanges();
    const btn = fixture.nativeElement.querySelector('[data-testid="connection-status"]');
    expect(btn.classList).toContain('connection-chip--insecure');
  });

  it('should NOT show reload CTA in INSECURE state', () => {
    modeSubject.next(TransportMode.INSECURE);
    fixture.detectChanges();
    expect(component.showReloadCta).toBeFalse();
  });

  // -------------------------------------------------------------------------
  // Focus / accessibility
  // -------------------------------------------------------------------------
  it('chip button is keyboard-focusable (has no tabindex="-1")', () => {
    const btn: HTMLButtonElement = fixture.nativeElement.querySelector('[data-testid="connection-status"]');
    expect(btn.tabIndex).not.toBe(-1);
  });

  it('chip button has aria-label', () => {
    const btn = fixture.nativeElement.querySelector('[data-testid="connection-status"]');
    expect(btn.getAttribute('aria-label')).toBeTruthy();
  });

  it('live region has role="status" and aria-live="polite"', () => {
    const region = fixture.nativeElement.querySelector('[role="status"]');
    expect(region).toBeTruthy();
    expect(region.getAttribute('aria-live')).toBe('polite');
  });

  it('data-testid attribute is set on the chip button', () => {
    const btn = fixture.nativeElement.querySelector('[data-testid="connection-status"]');
    expect(btn).toBeTruthy();
    expect(btn.getAttribute('data-testid')).toBe('connection-status');
  });

  // -------------------------------------------------------------------------
  // isProbing property reflects PROBING mode only
  // -------------------------------------------------------------------------
  it('isProbing is true only in PROBING state', () => {
    modeSubject.next(TransportMode.WEBSOCKET);
    fixture.detectChanges();
    expect(component.isProbing).toBeFalse();

    modeSubject.next(TransportMode.PROBING);
    fixture.detectChanges();
    expect(component.isProbing).toBeTrue();

    modeSubject.next(TransportMode.FALLBACK);
    fixture.detectChanges();
    expect(component.isProbing).toBeFalse();
  });
});
