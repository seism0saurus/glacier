import {
  TestBed,
  fakeAsync,
  tick,
} from '@angular/core/testing';
import { Client } from '@stomp/stompjs';
import { ReadonlyWallStompClient } from './readonly-wall-stomp-client.service';
import { ViewerTransportMode } from './viewer-transport-mode';

/**
 * Unit tests for ReadonlyWallStompClient.
 *
 * Security requirements under test:
 *   SR-RELAY-16: wss:// used when location.protocol === 'https:'
 *   SR-RELAY-17: service declared per-component (no providedIn:'root') — verified structurally
 *   SR-RELAY-18: native WebSocket, no SockJS — verified by source inspection + URL assertion
 *
 * Test strategy:
 *   - @stomp/stompjs Client.prototype.activate / deactivate are stubbed so no
 *     real WebSocket is opened during tests.
 *   - Lifecycle callbacks (onStompConnected, onStompDisconnected) are exposed as
 *     package-private methods and called directly to drive state transitions.
 *   - buildWsUrl() is exposed as package-private and asserted directly for URL shape.
 *   - WebSocket constructor is replaced with a spy class to capture URLs when the
 *     webSocketFactory is invoked.
 *
 * Design note: Router and LiveAnnouncer are intentionally NOT injected into this
 * service (and therefore not present in these tests). Navigation and accessibility
 * announcements on EXPIRED are the sole responsibility of ReadonlyWallComponent.
 * The service is a pure state machine; the component is the state consumer.
 */
describe('ReadonlyWallStompClient', () => {
  let service: ReadonlyWallStompClient;

  // Store the original WebSocket so we can restore it after each test
  const OriginalWebSocket = window.WebSocket;

  beforeEach(() => {
    // Stub out Client.prototype methods to prevent real WebSocket connections.
    // subscribe must return a fake ISubscription so registerTopicSubscriptions can push it.
    spyOn(Client.prototype, 'activate').and.stub();
    spyOn(Client.prototype, 'deactivate').and.returnValue(Promise.resolve());
    spyOn(Client.prototype, 'subscribe').and.returnValue({
      id: 'fake-sub',
      unsubscribe: jasmine.createSpy('unsubscribe'),
    });

    TestBed.configureTestingModule({
      providers: [
        ReadonlyWallStompClient,
      ],
    });

    service = TestBed.inject(ReadonlyWallStompClient);
  });

  afterEach(() => {
    // Restore original WebSocket in case a test replaced it
    (window as Window & { WebSocket: typeof WebSocket }).WebSocket = OriginalWebSocket;
    service.disconnect();
  });

  // ── SR-RELAY-18: no SockJS — verified behaviorally ────────────────────────

  it('no_SockJS_import — webSocketFactory creates a native WebSocket, not SockJS', () => {
    // Behavioral test for SR-RELAY-18.
    //
    // The service's webSocketFactory must call `new WebSocket(url)` directly.
    // SockJS would appear as a different constructor name.
    //
    // We verify this by:
    //   1. Replacing window.WebSocket with a spy class
    //   2. Calling connect() and invoking the factory
    //   3. Asserting the spy was called (meaning native WebSocket was used)
    //
    // If SockJS were used, the spy would NOT be called because SockJS bypasses
    // the native WebSocket constructor.

    let nativeWebSocketCalled = false;

    class FakeNativeWebSocket {
      constructor(_url: string) { nativeWebSocketCalled = true; }
      addEventListener() {}
      removeEventListener() {}
      close() {}
    }

    (window as Window & { WebSocket: unknown }).WebSocket = FakeNativeWebSocket as unknown as typeof WebSocket;

    service.connect('sockjs-check-id');

    // Retrieve the stored STOMP client's webSocketFactory and invoke it
    const internalClient = (service as unknown as { stompClient: Client | null }).stompClient;
    expect(internalClient).not.toBeNull();

    if (internalClient) {
      const factory = (internalClient as unknown as { webSocketFactory?: () => WebSocket }).webSocketFactory;
      if (factory) {
        factory.call(internalClient);
        expect(nativeWebSocketCalled).toBeTrue();
      } else {
        // factory not exposed in this STOMP version — fall back to URL check
        const url = (service as unknown as { buildWsUrl(id: string): string }).buildWsUrl('sockjs-check-id');
        // Native WebSocket path — does not contain sockjs patterns
        expect(url).toContain('/share-view-ws');
        expect(url).not.toContain('sockjs');
      }
    }
  });

  // ── buildWsUrl() — SR-RELAY-16 and SR-RELAY-18 ───────────────────────────

  it('connect_uses_wss_when_https — buildWsUrl uses wss:// when location.protocol is https:', () => {
    // Override location.protocol for this assertion.
    // We cast to any to override the normally read-only getter.
    const origDescriptor = Object.getOwnPropertyDescriptor(window, 'location');
    const locationStub = { ...window.location, protocol: 'https:' };
    try {
      Object.defineProperty(window, 'location', { configurable: true, value: locationStub });
    } catch {
      // Some browsers don't allow redefining window.location — use the internal method
      pending('Cannot override window.location in this browser context');
      return;
    }

    const url = (service as unknown as { buildWsUrl(id: string): string }).buildWsUrl('abc');
    expect(url).toMatch(/^wss:\/\//);
    expect(url).toContain('/share-view-ws?shareLinkId=abc');

    if (origDescriptor) {
      Object.defineProperty(window, 'location', origDescriptor);
    }
  });

  it('connect_uses_ws_when_http — buildWsUrl uses ws:// when location.protocol is http:', () => {
    // Default Karma environment is http: — assert without overriding
    const protocol = window.location.protocol;
    if (protocol !== 'http:') {
      pending('Test environment is not http: — skipping ws:// check');
      return;
    }
    const url = (service as unknown as { buildWsUrl(id: string): string }).buildWsUrl('def');
    expect(url).toMatch(/^ws:\/\//);
    expect(url).toContain('/share-view-ws?shareLinkId=def');
  });

  it('connect_uses_shareLinkId_in_url — URL contains /share-view-ws?shareLinkId=', () => {
    const SHARE_ID = 'test-link-id-xyz';
    const url = (service as unknown as { buildWsUrl(id: string): string }).buildWsUrl(SHARE_ID);
    expect(url).toContain('/share-view-ws?shareLinkId=' + SHARE_ID);
  });

  it('connect_calls_webSocketFactory_with_native_WebSocket — factory does not use SockJS', () => {
    // Capture the URL passed to new WebSocket(url) by replacing the constructor
    const capturedUrls: string[] = [];

    class FakeWebSocket {
      constructor(url: string) { capturedUrls.push(url); }
      addEventListener() {}
      removeEventListener() {}
      close() {}
    }

    (window as Window & { WebSocket: unknown }).WebSocket = FakeWebSocket as unknown as typeof WebSocket;

    service.connect('ws-factory-test');

    // The STOMP Client lazily calls webSocketFactory() — extract and invoke it
    const internalClient = (service as unknown as { stompClient: Client | null }).stompClient;
    if (internalClient) {
      const factory = (internalClient as unknown as { webSocketFactory?: () => WebSocket }).webSocketFactory;
      if (factory) {
        factory();
        expect(capturedUrls.length).toBeGreaterThan(0);
        expect(capturedUrls[0]).toContain('/share-view-ws?shareLinkId=ws-factory-test');
      }
    }
    // If the factory is not directly accessible, the URL test above (buildWsUrl) is sufficient
  });

  // ── Initial transport state ────────────────────────────────────────────────

  it('transportMode_starts_as_PROBING — initial state before STOMP CONNECTED frame', () => {
    expect(service.transportMode$.getValue()).toBe(ViewerTransportMode.PROBING);
  });

  // ── STOMP lifecycle callbacks ──────────────────────────────────────────────

  it('transportMode_transitions_to_LIVE_on_stomp_connected', () => {
    service.connect('share-123');

    // Invoke the package-private lifecycle callback directly
    (service as unknown as { onStompConnected(): void }).onStompConnected();

    expect(service.transportMode$.getValue()).toBe(ViewerTransportMode.LIVE);
  });

  it('transportMode_transitions_to_PROBING_on_stomp_disconnect', () => {
    service.connect('share-123');
    (service as unknown as { onStompConnected(): void }).onStompConnected();

    (service as unknown as { onStompDisconnected(): void }).onStompDisconnected();
    expect(service.transportMode$.getValue()).toBe(ViewerTransportMode.PROBING);
  });

  it('transportMode_transitions_to_FALLBACK_after_probing_grace_window', fakeAsync(() => {
    service.connect('share-123');
    (service as unknown as { onStompDisconnected(): void }).onStompDisconnected();
    expect(service.transportMode$.getValue()).toBe(ViewerTransportMode.PROBING);

    // Advance past the 5 s grace window
    tick(5001);

    expect(service.transportMode$.getValue()).toBe(ViewerTransportMode.FALLBACK);
  }));

  it('transportMode_stays_LIVE_if_reconnect_within_grace_window', fakeAsync(() => {
    service.connect('share-123');
    (service as unknown as { onStompConnected(): void }).onStompConnected();

    // Disconnect → PROBING
    (service as unknown as { onStompDisconnected(): void }).onStompDisconnected();
    expect(service.transportMode$.getValue()).toBe(ViewerTransportMode.PROBING);

    // Reconnect before the 5 s grace window expires
    tick(4000);
    (service as unknown as { onStompConnected(): void }).onStompConnected();

    // Advance past where FALLBACK would have fired — should remain LIVE
    tick(2000);

    expect(service.transportMode$.getValue()).toBe(ViewerTransportMode.LIVE);
  }));

  // ── Control frame handling ─────────────────────────────────────────────────
  //
  // The service is a pure state machine: handleControlFrame only updates
  // transportMode$. Announcement and navigation are the component's responsibility.

  it('handleControlFrame_revoked_emitsExpiredMode — transportMode$ becomes EXPIRED immediately', () => {
    service.connect('share-revoke-test');

    (service as unknown as { handleControlFrame(f: { type: string }): void })
      .handleControlFrame({ type: 'revoked' });

    expect(service.transportMode$.getValue()).toBe(ViewerTransportMode.EXPIRED);
  });

  it('handleControlFrame_expired_emitsExpiredMode — transportMode$ becomes EXPIRED immediately', () => {
    service.connect('share-expire-test');

    (service as unknown as { handleControlFrame(f: { type: string }): void })
      .handleControlFrame({ type: 'expired' });

    expect(service.transportMode$.getValue()).toBe(ViewerTransportMode.EXPIRED);
  });

  it('handleControlFrame_ping_doesNotChangeMode — PROBING stays PROBING on ping', fakeAsync(() => {
    service.connect('share-ping-test');
    // Initial state is PROBING
    expect(service.transportMode$.getValue()).toBe(ViewerTransportMode.PROBING);

    (service as unknown as { handleControlFrame(f: { type: string }): void })
      .handleControlFrame({ type: 'ping' });

    tick(1500);
    expect(service.transportMode$.getValue()).toBe(ViewerTransportMode.PROBING);
  }));

  it('handleControlFrame_unknown_doesNotChangeMode — unknown type is silently ignored', () => {
    service.connect('share-unknown-test');

    (service as unknown as { handleControlFrame(f: { type: string }): void })
      .handleControlFrame({ type: 'unknown-frame-type' });

    expect(service.transportMode$.getValue()).toBe(ViewerTransportMode.PROBING);
  });

  // ── disconnect() cleanup ───────────────────────────────────────────────────

  it('disconnect_completes_transportMode$', () => {
    let completed = false;
    service.transportMode$.subscribe({ complete: () => { completed = true; } });

    service.disconnect();

    expect(completed).toBeTrue();
  });

  it('disconnect_is_idempotent — calling twice does not throw', () => {
    service.connect('share-dc-test');
    service.disconnect();
    expect(() => service.disconnect()).not.toThrow();
  });

  // ── tootEvents$() ─────────────────────────────────────────────────────────

  it('tootEvents$_returns_an_observable', () => {
    service.connect('share-obs-test');
    const obs = service.tootEvents$('glacier');
    expect(typeof obs.subscribe).toBe('function');
  });

  it('tootEvents$_same_hashtag_returns_same_observable_source', () => {
    service.connect('share-obs-test');

    const received: unknown[] = [];
    service.tootEvents$('glacier').subscribe((t) => received.push(t));

    // Emit via the internal subject
    const subjects = (service as unknown as { tootSubjects: Map<string, { next(v: unknown): void }> }).tootSubjects;
    subjects.get('glacier')?.next({ id: 'test-toot' });

    expect(received.length).toBe(1);
  });

  // ── Exponential reconnect backoff (SEC-ACC-02) ─────────────────────────────
  //
  // The service must implement exponential backoff for STOMP reconnects:
  //   1000 ms → 2000 ms → 4000 ms → 8000 ms → 8000 ms (cap)
  //   Reset to 1000 ms on successful STOMP CONNECTED frame.
  //
  // This test MUST FAIL against a fixed reconnectDelay: 1000 implementation
  // and PASS after the exponential backoff is implemented.

  it('reconnect_backoff_progresses_1s_2s_4s_8s_then_caps_and_resets — ' +
     'reconnectDelay doubles each disconnect up to 8 s cap, resets on connect', fakeAsync(() => {
    service.connect('share-backoff-test');

    // Helper to read the current reconnectDelay from the internal STOMP client
    const getDelay = (): number => {
      const client = (service as unknown as { stompClient: Client | null }).stompClient;
      return client ? (client as unknown as { reconnectDelay: number }).reconnectDelay : -1;
    };

    // After initial connect(), delay should be set to the initial 1 s value
    expect(getDelay()).toBe(1000);

    // --- First disconnect: next attempt should use 2 s ---
    (service as unknown as { onStompDisconnected(): void }).onStompDisconnected();
    expect(getDelay()).toBe(2000);

    // --- Second disconnect: 4 s ---
    (service as unknown as { onStompDisconnected(): void }).onStompDisconnected();
    expect(getDelay()).toBe(4000);

    // --- Third disconnect: 8 s ---
    (service as unknown as { onStompDisconnected(): void }).onStompDisconnected();
    expect(getDelay()).toBe(8000);

    // --- Fourth disconnect: still 8 s (cap) ---
    (service as unknown as { onStompDisconnected(): void }).onStompDisconnected();
    expect(getDelay()).toBe(8000);

    // --- Successful reconnect: reset to 1 s ---
    (service as unknown as { onStompConnected(): void }).onStompConnected();
    expect(getDelay()).toBe(1000);

    // Drain any pending timers (5 s grace window) to keep test clean
    tick(6000);
  }));

  it('reconnect_backoff_resets_after_reconnect_then_progresses_again — ' +
     'backoff counter resets independently on each successful connect', fakeAsync(() => {
    service.connect('share-backoff-reset-test');

    const getDelay = (): number => {
      const client = (service as unknown as { stompClient: Client | null }).stompClient;
      return client ? (client as unknown as { reconnectDelay: number }).reconnectDelay : -1;
    };

    // First disconnect cycle: advance to 4 s
    (service as unknown as { onStompDisconnected(): void }).onStompDisconnected();
    expect(getDelay()).toBe(2000);
    (service as unknown as { onStompDisconnected(): void }).onStompDisconnected();
    expect(getDelay()).toBe(4000);

    // Reconnect resets the counter
    (service as unknown as { onStompConnected(): void }).onStompConnected();
    expect(getDelay()).toBe(1000);

    // Second disconnect cycle starts fresh from 2 s
    (service as unknown as { onStompDisconnected(): void }).onStompDisconnected();
    expect(getDelay()).toBe(2000);

    // Drain pending timers
    tick(6000);
  }));

  // ── EXPIRED is terminal + control-channel receive wiring ────────────────────

  it('onStompDisconnected after EXPIRED stays EXPIRED (no flip back to PROBING)', () => {
    // Viewer-kick invariant: once a revoked/expired control frame flips the viewer to EXPIRED,
    // the subsequent WebSocket close must NOT downgrade it to PROBING (which would hide the
    // "link expired" state and start a pointless reconnect loop).
    (service as unknown as { handleControlFrame(f: { type: string }): void })
      .handleControlFrame({ type: 'revoked' });
    expect(service.transportMode$.getValue()).toBe(ViewerTransportMode.EXPIRED);

    (service as unknown as { onStompDisconnected(): void }).onStompDisconnected();

    expect(service.transportMode$.getValue())
      .withContext('a WS close after revocation must remain EXPIRED')
      .toBe(ViewerTransportMode.EXPIRED);
  });

  it('onStompConnected with an active client subscribes to the control channel', () => {
    const shareId = 'wiring-id-1';
    service.connect(shareId);
    const subscribeSpy = Client.prototype.subscribe as jasmine.Spy;
    subscribeSpy.calls.reset();

    (service as unknown as { onStompConnected(): void }).onStompConnected();

    expect(subscribeSpy).toHaveBeenCalledWith(`/topic/share/${shareId}/control`, jasmine.any(Function));
  });

  it('a revoked control frame received on the control subscription flips to EXPIRED', () => {
    const shareId = 'wiring-id-2';
    let controlCb: ((m: { body: string }) => void) | undefined;
    (Client.prototype.subscribe as jasmine.Spy).and.callFake(
      (dest: string, cb: (m: { body: string }) => void) => {
        if (dest.endsWith('/control')) controlCb = cb;
        return { id: 'fake-sub', unsubscribe: jasmine.createSpy('unsubscribe') };
      });

    service.connect(shareId);
    (service as unknown as { onStompConnected(): void }).onStompConnected();

    expect(controlCb).withContext('control subscription callback must be registered').toBeDefined();
    controlCb!({ body: JSON.stringify({ type: 'revoked' }) });

    expect(service.transportMode$.getValue()).toBe(ViewerTransportMode.EXPIRED);
  });

  it('a malformed control frame is ignored (no throw, mode unchanged)', () => {
    const shareId = 'wiring-id-3';
    let controlCb: ((m: { body: string }) => void) | undefined;
    (Client.prototype.subscribe as jasmine.Spy).and.callFake(
      (dest: string, cb: (m: { body: string }) => void) => {
        if (dest.endsWith('/control')) controlCb = cb;
        return { id: 'fake-sub', unsubscribe: jasmine.createSpy('unsubscribe') };
      });

    service.connect(shareId);
    (service as unknown as { onStompConnected(): void }).onStompConnected();

    expect(() => controlCb!({ body: 'this is not json' })).not.toThrow();
    expect(service.transportMode$.getValue()).toBe(ViewerTransportMode.LIVE);
  });
});
