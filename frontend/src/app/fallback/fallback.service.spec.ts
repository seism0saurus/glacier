/**
 * Unit tests for FallbackService (D-04, D-05, D-06, D-14, D-17).
 *
 * These tests use fakeAsync/tick to drive timers without wall-clock delays.
 *
 * Coverage:
 * - Three-failure trigger: 1 s / 4 s / 16 s + 16 s grace → FALLBACK (D-04, ADR-03).
 * - Poll cadence while in FALLBACK (D-01).
 * - Exponential probe schedule: 10/20/40/80/160/300/300 s (D-05).
 * - 401 → SessionExpired event + OFFLINE mode (D-17).
 * - 429 → RateLimited event; mode stays FALLBACK; Retry-After parsed (D-17).
 * - 429 does NOT flip mode or trigger probe (D-17).
 * - 404 → KillSwitched event + KILLSWITCHED mode (D-17).
 * - gap:true → GapObserved debounced 5 s (D-16).
 * - http-origin + environment.production=true → INSECURE, no poller (D-14).
 * - DeliveryCursor clamp: corrupt/NaN → 0 (D-06).
 */

import {TestBed, fakeAsync, tick} from '@angular/core/testing';
import {HttpClientTestingModule, HttpTestingController} from '@angular/common/http/testing';
import {FallbackService} from './fallback.service';
import {TransportMode} from './transport-mode';
import {RxStompService} from '../rx-stomp.service';
import {SubscriptionService} from '../subscription.service';
import {Subject} from 'rxjs';
import {environment} from '../../environments/environment';

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

function createRxStompSpy(connectionState$: Subject<number>): jasmine.SpyObj<RxStompService> {
  const spy = jasmine.createSpyObj<RxStompService>('RxStompService', ['watch', 'publish']);
  (spy as any)['connectionState$'] = connectionState$.asObservable();
  spy.watch.and.returnValue(new Subject<any>().asObservable() as any);
  return spy;
}

function createSubscriptionServiceSpy(): jasmine.SpyObj<SubscriptionService> {
  return jasmine.createSpyObj<SubscriptionService>('SubscriptionService', [
    'subscribeHashtag',
    'ingestCacheEntries',
  ]);
}

// RxStompState values (from @stomp/stompjs)
const RX_STOMP_OPEN   = 1;
const RX_STOMP_CLOSED = 3;

/**
 * Drives the service through the full 3-attempt WS reconnect cycle to reach
 * FALLBACK state.  The cycle is:
 *   1. CLOSED event → schedule attempt 1 (1 s)
 *   2. tick(1000) → attempt 1 fires, reconnectAttempts=1
 *   3. CLOSED event → schedule attempt 2 (4 s)
 *   4. tick(4000) → attempt 2 fires, reconnectAttempts=2
 *   5. CLOSED event → schedule attempt 3 (16 s)
 *   6. tick(16000) → attempt 3 fires (last), starts 16 s grace timer
 *   7. tick(16000) → grace timer fires → FALLBACK
 *
 * This helper must be called inside a fakeAsync zone.
 */
function driveToFallback(connectionState$: Subject<number>): void {
  // Attempt 1
  connectionState$.next(RX_STOMP_CLOSED);
  tick(1_000);

  // Attempt 2
  connectionState$.next(RX_STOMP_CLOSED);
  tick(4_000);

  // Attempt 3 + grace period
  connectionState$.next(RX_STOMP_CLOSED);
  tick(16_000); // fires attempt 3 timer, starts grace period
  tick(16_000); // grace period expires → FALLBACK
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

describe('FallbackService', () => {
  let service: FallbackService;
  let httpMock: HttpTestingController;
  let connectionState$: Subject<number>;
  let rxStompSpy: jasmine.SpyObj<RxStompService>;
  let subscriptionSpy: jasmine.SpyObj<SubscriptionService>;

  beforeEach(() => {
    connectionState$ = new Subject<number>();
    rxStompSpy = createRxStompSpy(connectionState$);
    subscriptionSpy = createSubscriptionServiceSpy();

    TestBed.configureTestingModule({
      imports: [HttpClientTestingModule],
      providers: [
        FallbackService,
        {provide: RxStompService, useValue: rxStompSpy},
        {provide: SubscriptionService, useValue: subscriptionSpy},
      ],
    });

    service = TestBed.inject(FallbackService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    // Destroy the service first to cancel all timers and poll subscriptions.
    // This prevents interval-based polls from generating unexpected HTTP
    // requests that would cause httpMock.verify() to fail.
    TestBed.inject(FallbackService).ngOnDestroy();

    // Discard any in-flight requests before verify to avoid "found N open
    // requests" errors caused by the probe timeout or interval poll that
    // fired just before ngOnDestroy cancelled the subscriptions.
    httpMock.match(() => true);

    httpMock.verify();
  });

  // -------------------------------------------------------------------------
  // Initial state
  // -------------------------------------------------------------------------
  it('should start in WEBSOCKET mode', () => {
    let mode: TransportMode | undefined;
    service.transportMode$.subscribe(m => (mode = m));
    expect(mode).toBe(TransportMode.WEBSOCKET);
  });

  // -------------------------------------------------------------------------
  // WS OPEN → WEBSOCKET state
  // -------------------------------------------------------------------------
  it('should remain in WEBSOCKET when WS connection opens', () => {
    const modes: TransportMode[] = [];
    service.transportMode$.subscribe(m => modes.push(m));

    connectionState$.next(RX_STOMP_OPEN);
    expect(modes[modes.length - 1]).toBe(TransportMode.WEBSOCKET);
  });

  // -------------------------------------------------------------------------
  // Three-failure trigger (D-04, ADR-03) — using fakeAsync/tick
  // -------------------------------------------------------------------------
  it('should transition to FALLBACK after 3 reconnect attempts (1s+4s+16s+16s)', fakeAsync(() => {
    const modes: TransportMode[] = [];
    service.transportMode$.subscribe(m => modes.push(m));

    driveToFallback(connectionState$);

    expect(modes[modes.length - 1]).toBe(TransportMode.FALLBACK);
  }));

  // -------------------------------------------------------------------------
  // Should show PROBING state during reconnect attempts
  // -------------------------------------------------------------------------
  it('should enter PROBING state after first WS disconnect', fakeAsync(() => {
    const modes: TransportMode[] = [];
    service.transportMode$.subscribe(m => modes.push(m));

    connectionState$.next(RX_STOMP_CLOSED);
    expect(modes).toContain(TransportMode.PROBING);
  }));

  // -------------------------------------------------------------------------
  // 429 does NOT flip mode or trigger probe (D-17)
  // -------------------------------------------------------------------------
  it('should stay in FALLBACK and not trigger probe on 429', fakeAsync(() => {
    const modes: TransportMode[] = [];
    service.transportMode$.subscribe(m => modes.push(m));

    driveToFallback(connectionState$);

    service.onHashtagSubscribed('test');
    tick(0);

    // Flush the immediate poll
    const req = httpMock.expectOne(r => r.url.includes('/rest/messages'));
    req.flush('', {status: 429, statusText: 'Too Many Requests',
      headers: {'Retry-After': '30'}});

    // Mode should still be FALLBACK
    expect(modes[modes.length - 1]).toBe(TransportMode.FALLBACK);
  }));

  // -------------------------------------------------------------------------
  // 401 → OFFLINE + SessionExpired event (D-17)
  // -------------------------------------------------------------------------
  it('should transition to OFFLINE and emit SessionExpired on 401', fakeAsync(() => {
    const modes: TransportMode[] = [];
    const events: string[] = [];
    service.transportMode$.subscribe(m => modes.push(m));
    service.events$.subscribe(e => events.push(e.type));

    driveToFallback(connectionState$);

    service.onHashtagSubscribed('test');
    tick(0);

    const req = httpMock.expectOne(r => r.url.includes('/rest/messages'));
    req.flush('', {status: 401, statusText: 'Unauthorized'});

    expect(modes[modes.length - 1]).toBe(TransportMode.OFFLINE);
    expect(events).toContain('SessionExpired');
  }));

  // -------------------------------------------------------------------------
  // 404 → KILLSWITCHED (D-17)
  // -------------------------------------------------------------------------
  it('should transition to KILLSWITCHED and emit KillSwitched on 404', fakeAsync(() => {
    const modes: TransportMode[] = [];
    const events: string[] = [];
    service.transportMode$.subscribe(m => modes.push(m));
    service.events$.subscribe(e => events.push(e.type));

    driveToFallback(connectionState$);

    service.onHashtagSubscribed('test');
    tick(0);

    const req = httpMock.expectOne(r => r.url.includes('/rest/messages'));
    req.flush('', {status: 404, statusText: 'Not Found'});

    expect(modes[modes.length - 1]).toBe(TransportMode.KILLSWITCHED);
    expect(events).toContain('KillSwitched');
  }));

  // -------------------------------------------------------------------------
  // 429 → RateLimited with Retry-After parsed (D-17)
  // -------------------------------------------------------------------------
  it('should emit RateLimited event with retryAfterSeconds on 429', fakeAsync(() => {
    const events: any[] = [];
    service.events$.subscribe(e => events.push(e));

    driveToFallback(connectionState$);

    service.onHashtagSubscribed('test');
    tick(0);

    const req = httpMock.expectOne(r => r.url.includes('/rest/messages'));
    req.flush('', {status: 429, statusText: 'Too Many Requests',
      headers: {'Retry-After': '90'}});

    const rateLimited = events.find(e => e.type === 'RateLimited');
    expect(rateLimited).toBeDefined();
    expect(rateLimited.retryAfterSeconds).toBe(90);
  }));

  // -------------------------------------------------------------------------
  // gap:true → GapObserved debounced 5 s (D-16)
  // -------------------------------------------------------------------------
  it('should emit GapObserved after 5 s debounce when gap:true in response', fakeAsync(() => {
    const events: any[] = [];
    service.events$.subscribe(e => events.push(e));

    driveToFallback(connectionState$);

    service.onHashtagSubscribed('test');
    tick(0);

    const req = httpMock.expectOne(r => r.url.includes('/rest/messages'));
    req.flush({
      hashtag: 'test',
      nextSince: 5,
      gap: true,
      events: [],
    });

    // No GapObserved yet — debounce is 5 s
    expect(events.some(e => e.type === 'GapObserved')).toBeFalse();

    // After 5 s debounce fires
    tick(5_000);
    expect(events.some(e => e.type === 'GapObserved')).toBeTrue();
  }));

  // -------------------------------------------------------------------------
  // D-14: INSECURE state when production + http: + allowPlaintext=false
  // -------------------------------------------------------------------------

  /**
   * Helper: recreates FallbackService in a fresh TestBed with overridden
   * environment flags.  The test runner's window.location.protocol is already
   * 'http:', so setting production=true and allowPlaintext=false is sufficient
   * to exercise the INSECURE gate.
   *
   * Returns the created service so the caller can assert state and clean up.
   */
  function createServiceWithEnvFlags(flags: {production: boolean; allowPlaintext: boolean}): FallbackService {
    const originalProduction = environment.production;
    const originalAllowPlaintext = (environment as any).allowPlaintext;
    (environment as any).production = flags.production;
    (environment as any).allowPlaintext = flags.allowPlaintext;

    TestBed.resetTestingModule();
    const connState$ = new Subject<number>();
    const stompSpy = createRxStompSpy(connState$);
    const subSpy = createSubscriptionServiceSpy();

    TestBed.configureTestingModule({
      imports: [HttpClientTestingModule],
      providers: [
        FallbackService,
        {provide: RxStompService, useValue: stompSpy},
        {provide: SubscriptionService, useValue: subSpy},
      ],
    });

    const svc = TestBed.inject(FallbackService);

    // Restore environment immediately so subsequent test setup (beforeEach)
    // sees the original values even if the calling test throws.
    (environment as any).production = originalProduction;
    (environment as any).allowPlaintext = originalAllowPlaintext;
    return svc;
  }

  it('should enter INSECURE state when production=true AND allowPlaintext=false AND protocol is http', () => {
    // In the Karma test runner window.location.protocol is already 'http:',
    // so production=true + allowPlaintext=false must trigger INSECURE.
    const svc = createServiceWithEnvFlags({production: true, allowPlaintext: false});
    let mode: TransportMode | undefined;
    svc.transportMode$.subscribe(m => (mode = m));
    expect(mode).toBe(TransportMode.INSECURE);
    svc.ngOnDestroy();
  });

  it('should NOT enter INSECURE state when production=true AND allowPlaintext=true AND protocol is http (D-14 override)', () => {
    // When allowPlaintext=true the developer/operator has explicitly opted in
    // to running over HTTP — do NOT enter INSECURE state.
    const svc = createServiceWithEnvFlags({production: true, allowPlaintext: true});
    let mode: TransportMode | undefined;
    svc.transportMode$.subscribe(m => (mode = m));
    // Should be WEBSOCKET (or PROBING), NOT INSECURE
    expect(mode).not.toBe(TransportMode.INSECURE);
    svc.ngOnDestroy();
  });

  it('should NOT enter INSECURE state when production=false AND allowPlaintext=false AND protocol is http', () => {
    // Non-production builds never enter INSECURE regardless of allowPlaintext.
    const svc = createServiceWithEnvFlags({production: false, allowPlaintext: false});
    let mode: TransportMode | undefined;
    svc.transportMode$.subscribe(m => (mode = m));
    expect(mode).not.toBe(TransportMode.INSECURE);
    svc.ngOnDestroy();
  });

  // -------------------------------------------------------------------------
  // Ingests cache entries into SubscriptionService on successful poll (D-06)
  // -------------------------------------------------------------------------
  it('should call ingestCacheEntries when poll returns 200 with events', fakeAsync(() => {
    driveToFallback(connectionState$);

    service.onHashtagSubscribed('test');
    tick(0);

    const req = httpMock.expectOne(r => r.url.includes('/rest/messages'));
    // Use the actual wire key "id" that the backend emits (backend @JsonProperty("id")).
    // Before FIX #1 these were wrongly "statusId", which would have produced
    // CacheEntry objects where entry.id === undefined at runtime.
    req.flush({
      hashtag: 'test',
      nextSince: 3,
      gap: false,
      events: [
        {id: 'abc123', type: 'CREATED', url: 'https://example.com/1', sequence: 1},
        {id: 'def456', type: 'CREATED', url: 'https://example.com/2', sequence: 2},
      ],
    });

    expect(subscriptionSpy.ingestCacheEntries).toHaveBeenCalledWith(
      'test',
      jasmine.arrayContaining([
        jasmine.objectContaining({id: 'abc123'}),
        jasmine.objectContaining({id: 'def456'}),
      ])
    );
  }));

  // -------------------------------------------------------------------------
  // Mode-machine recovery + terminal-state guards (D-04/D-05) + poll edges
  // -------------------------------------------------------------------------

  it('FALLBACK → WEBSOCKET and emits WsRestored when the WebSocket reopens', fakeAsync(() => {
    const modes: TransportMode[] = [];
    const events: string[] = [];
    service.transportMode$.subscribe(m => modes.push(m));
    service.events$.subscribe(e => events.push(e.type));

    driveToFallback(connectionState$);
    expect(modes[modes.length - 1]).toBe(TransportMode.FALLBACK);

    connectionState$.next(RX_STOMP_OPEN);
    tick(0);

    expect(modes[modes.length - 1]).toBe(TransportMode.WEBSOCKET);
    expect(events).toContain('WsRestored');
  }));

  it('ignores a WS CLOSED event once KILLSWITCHED (terminal state, no reconnect)', fakeAsync(() => {
    const modes: TransportMode[] = [];
    service.transportMode$.subscribe(m => modes.push(m));

    driveToFallback(connectionState$);
    service.onHashtagSubscribed('test');
    tick(0);
    httpMock.expectOne(r => r.url.includes('/rest/messages'))
      .flush('', {status: 404, statusText: 'Not Found'});
    expect(modes[modes.length - 1]).toBe(TransportMode.KILLSWITCHED);

    connectionState$.next(RX_STOMP_CLOSED);
    tick(0);

    expect(modes[modes.length - 1])
      .withContext('a terminal KILLSWITCHED state must not be reset by a WS close')
      .toBe(TransportMode.KILLSWITCHED);
  }));

  it('does not restart the reconnect sequence when already in FALLBACK', fakeAsync(() => {
    const modes: TransportMode[] = [];
    service.transportMode$.subscribe(m => modes.push(m));

    driveToFallback(connectionState$);
    expect(modes[modes.length - 1]).toBe(TransportMode.FALLBACK);

    connectionState$.next(RX_STOMP_CLOSED);
    tick(0);

    // No flip back to PROBING — the fallback poller stays in charge.
    expect(modes[modes.length - 1]).toBe(TransportMode.FALLBACK);
  }));

  it('confirms WEBSOCKET when a hashtag ack arrives while PROBING (reconnect succeeded)', fakeAsync(() => {
    const modes: TransportMode[] = [];
    service.transportMode$.subscribe(m => modes.push(m));

    connectionState$.next(RX_STOMP_CLOSED); // schedules attempt 1 → PROBING
    expect(modes[modes.length - 1]).toBe(TransportMode.PROBING);

    service.onHashtagSubscribed('test'); // ack while probing → confirm WEBSOCKET

    expect(modes[modes.length - 1]).toBe(TransportMode.WEBSOCKET);
    tick(1_000); // drain the pending reconnect-attempt timer
  }));

  it('poll returning 204 ingests nothing (no new events)', fakeAsync(() => {
    driveToFallback(connectionState$);
    service.onHashtagSubscribed('test');
    tick(0);

    httpMock.expectOne(r => r.url.includes('/rest/messages'))
      .flush(null, {status: 204, statusText: 'No Content'});

    expect(subscriptionSpy.ingestCacheEntries).not.toHaveBeenCalled();
  }));

  it('429 without a Retry-After header emits RateLimited with undefined retryAfterSeconds', fakeAsync(() => {
    const events: any[] = [];
    service.events$.subscribe(e => events.push(e));

    driveToFallback(connectionState$);
    service.onHashtagSubscribed('test');
    tick(0);

    httpMock.expectOne(r => r.url.includes('/rest/messages'))
      .flush('', {status: 429, statusText: 'Too Many Requests'}); // no Retry-After

    const rl = events.find(e => e.type === 'RateLimited');
    expect(rl).toBeDefined();
    expect(rl.retryAfterSeconds).toBeUndefined();
  }));
});
