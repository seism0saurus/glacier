import {ComponentFixture, TestBed} from '@angular/core/testing';
import {TootComponent} from './toot.component';
import {provideHttpClientTesting} from "@angular/common/http/testing";
import {provideHttpClient, withInterceptorsFromDi} from '@angular/common/http';
import {DomSanitizer, SafeResourceUrl} from "@angular/platform-browser";

describe('TootComponent', () => {
  let component: TootComponent;
  let fixture: ComponentFixture<TootComponent>;
  let sanitizer: DomSanitizer; // DomSanitizer-Variable
  let testSafeUrl: SafeResourceUrl; // Gesicherte URL für die Tests
  const testUrlString = 'https://example.com/'; // Beispiel-URL
  const testUuid = '12345'; // Beispiel-UUID

  beforeEach(() => {
    // TestBed Setup
    TestBed.configureTestingModule({
      declarations: [TootComponent],
      providers: [
        provideHttpClient(withInterceptorsFromDi()),
        provideHttpClientTesting()
      ]
    });

    // Fixture and Component creation
    fixture = TestBed.createComponent(TootComponent);
    component = fixture.componentInstance;

    // Inject DomSanitizer
    sanitizer = TestBed.inject(DomSanitizer);

    // Create SafeResourceUrl
    testSafeUrl = sanitizer.bypassSecurityTrustResourceUrl(testUrlString);

    // Define inputs for the test component
    component.url = testSafeUrl;
    component.uuid = testUuid;

    // Detect changes in fixture
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  /**
   * AC-02 (C8 — Browser Security; ASVS V50.x L1; WSTG-CLNT-09):
   * The iframe sandbox attribute must carry exactly the three required tokens and nothing else.
   *
   * Arrange: component rendered with a SafeResourceUrl.
   * Act: query the iframe element from the fixture.
   * Assert: sandbox equals the exact string "allow-scripts allow-popups allow-popups-to-escape-sandbox".
   *
   * Rationale: allow-same-origin is intentionally absent — granting it would give the embedded
   * Mastodon toot access to the embedding origin's storage (XSS escalation path).
   * Any addition or removal of sandbox tokens is a security-relevant change and must break this test.
   */
  it('should have sandbox attribute with exactly three required tokens', () => {
    const iframe = fixture.nativeElement.querySelector('iframe') as HTMLIFrameElement;
    expect(iframe).toBeTruthy();
    expect(iframe.getAttribute('sandbox')).toBe('allow-scripts allow-popups allow-popups-to-escape-sandbox');
  });

  /**
   * AC-02 (C8 — Browser Security; ASVS V50.x L1; WSTG-CLNT-09):
   * The iframe sandbox attribute must NOT contain dangerous tokens that would weaken isolation.
   *
   * Arrange: component rendered with a SafeResourceUrl.
   * Act: query the iframe element from the fixture.
   * Assert: sandbox value does not contain allow-same-origin, allow-top-navigation, or allow-forms.
   *
   * - allow-same-origin: would give the embed access to the parent origin's localStorage/cookies
   * - allow-top-navigation: would allow the embed to redirect the top-level frame (phishing risk)
   * - allow-forms: not needed for Mastodon embeds; denying it reduces the attack surface
   */
  it('should not have allow-same-origin in sandbox attribute', () => {
    const iframe = fixture.nativeElement.querySelector('iframe') as HTMLIFrameElement;
    expect(iframe).toBeTruthy();
    const sandboxValue = iframe.getAttribute('sandbox') ?? '';
    expect(sandboxValue).not.toContain('allow-same-origin');
    expect(sandboxValue).not.toContain('allow-top-navigation');
    expect(sandboxValue).not.toContain('allow-forms');
  });

  it('should configure iframe with SafeResourceUrl and UUID', () => {
    // use existing iframe
    const iframe = fixture.nativeElement.querySelector('iframe') as HTMLIFrameElement;

    expect(iframe).toBeTruthy();
    expect(iframe.src).toContain(testUrlString);
  });

  /**
   * A11Y-F-01 (WCAG 4.1.2 — Name, Role, Value):
   * Every iframe must have a non-empty `title` attribute so screen readers
   * can identify it and keyboard users know what the frame contains.
   *
   * Arrange: component rendered with a SafeResourceUrl and UUID.
   * Act: query the rendered iframe.
   * Assert: title attribute is present and non-empty.
   */
  it('should have a non-empty title attribute on the iframe (A11Y-F-01)', () => {
    const iframe = fixture.nativeElement.querySelector('iframe') as HTMLIFrameElement;
    expect(iframe).toBeTruthy();
    const title = iframe.getAttribute('title');
    expect(title).toBeTruthy();
    expect(title!.length).toBeGreaterThan(0);
  });

  /**
   * A11Y-F-01 — title falls back to UUID when no label is provided.
   *
   * The iframeTitle getter must always return something meaningful.
   * When no @Input label is supplied, the UUID is used.
   */
  it('should use UUID in iframe title when no label is provided (A11Y-F-01)', () => {
    const iframe = fixture.nativeElement.querySelector('iframe') as HTMLIFrameElement;
    expect(iframe).toBeTruthy();
    // No label was set in beforeEach, so title should include the UUID
    expect(iframe.getAttribute('title')).toContain(testUuid);
  });

  /**
   * A11Y-F-01 — title reflects the label input when provided.
   *
   * When the wall passes a label (e.g. "Toot #glacier"), the iframe title
   * must match that label so screen readers can announce it.
   */
  it('should use provided label as iframe title when label is set (A11Y-F-01)', () => {
    component.label = 'Toot #glacier';
    fixture.detectChanges();
    const iframe = fixture.nativeElement.querySelector('iframe') as HTMLIFrameElement;
    expect(iframe).toBeTruthy();
    expect(iframe.getAttribute('title')).toBe('Toot #glacier');
  });

  it('should register message event listener on the window when configuring iframe', () => {
    const spyAddEventListener = spyOn(window, 'addEventListener');
    const iframe = document.createElement('iframe');
    iframe.src = testUrlString;
    component.configureIframe(iframe);
    expect(spyAddEventListener).toHaveBeenCalledWith('message', jasmine.any(Function));
  });

  // ---- TOOT-12: listener-leak and duplicate-id fixes ----

  /**
   * TOOT-12 — Listener cleanup on destroy.
   *
   * Arrange: configure an iframe so the component registers a global message listener.
   * Act: destroy the component via ngOnDestroy.
   * Assert: window.removeEventListener is called with 'message' and the same handler
   *         reference that was registered via addEventListener, so the OS removes exactly
   *         the right listener.
   *
   * Red gate: before the fix, TootComponent has no ngOnDestroy and never calls
   * removeEventListener, so this test fails with "removeEventListener not called".
   */
  it('TOOT-12: should remove the message listener from window on ngOnDestroy (listener-leak fix)', () => {
    const addedListeners: { type: string; handler: EventListenerOrEventListenerObject }[] = [];
    const removedListeners: { type: string; handler: EventListenerOrEventListenerObject }[] = [];

    spyOn(window, 'addEventListener').and.callFake((type: string, handler: EventListenerOrEventListenerObject) => {
      addedListeners.push({ type, handler });
    });
    spyOn(window, 'removeEventListener').and.callFake((type: string, handler: EventListenerOrEventListenerObject) => {
      removedListeners.push({ type, handler });
    });

    const iframe = document.createElement('iframe') as HTMLIFrameElement;
    iframe.src = testUrlString;
    component.configureIframe(iframe);

    expect(addedListeners.length).toBe(1);
    expect(addedListeners[0].type).toBe('message');

    component.ngOnDestroy();

    expect(removedListeners.length).toBe(1);
    expect(removedListeners[0].type).toBe('message');
    // Critically: the same function reference must be removed, not a different closure.
    expect(removedListeners[0].handler).toBe(addedListeners[0].handler);
  });

  /**
   * TOOT-12 — Handler does not fire after ngOnDestroy.
   *
   * Arrange: configure an iframe so the component registers a global message listener,
   *          then destroy the component.
   * Act: dispatch a 'message' event that would have caused a height mutation.
   * Assert: the iframe height remains unchanged, confirming the handler was unregistered.
   *
   * Red gate: before the fix, the anonymous closure stays attached to window forever, so
   * the iframe would still be mutated after destroy.
   */
  it('TOOT-12: should not mutate iframe height after ngOnDestroy (handler truly removed)', () => {
    const iframe = document.createElement('iframe') as HTMLIFrameElement;
    // Give the iframe the id that matches the message the component sends.
    // After the fix, the iframe id will include an instance suffix, so we read
    // it back from the element after configureIframe sets/uses it.
    iframe.src = testUrlString;
    document.body.appendChild(iframe);

    component.configureIframe(iframe);
    const registeredId = iframe.id; // read the actual id after configuration

    component.ngOnDestroy();

    // Now send a message that would have set the height — after destroy it should be ignored.
    const msg = new MessageEvent('message', {
      data: { type: 'setHeight', id: registeredId, height: '999px' },
      source: iframe.contentWindow ?? undefined,
    });
    window.dispatchEvent(msg);

    expect(iframe.height).not.toBe('999px');

    document.body.removeChild(iframe);
  });

  /**
   * TOOT-12 — Unique iframe id per component instance.
   *
   * Arrange: create two TootComponent instances sharing the same uuid.
   * Act: configure an iframe for each.
   * Assert: the two iframe ids are distinct, so there are no duplicate DOM ids and AT
   *         tools can unambiguously address each frame.
   *
   * Red gate: before the fix, both iframes get id === uuid, causing duplicate DOM ids.
   */
  it('TOOT-12: should produce distinct iframe ids for two instances sharing the same uuid (duplicate-id fix)', () => {
    // Create a second component instance alongside the one from beforeEach.
    const fixture2 = TestBed.createComponent(TootComponent);
    const component2 = fixture2.componentInstance;
    component2.url = testSafeUrl;
    component2.uuid = testUuid; // deliberately same uuid as component
    fixture2.detectChanges();

    const iframe1 = document.createElement('iframe') as HTMLIFrameElement;
    iframe1.src = testUrlString;
    const iframe2 = document.createElement('iframe') as HTMLIFrameElement;
    iframe2.src = testUrlString;

    component.configureIframe(iframe1);
    component2.configureIframe(iframe2);

    expect(iframe1.id).toBeTruthy();
    expect(iframe2.id).toBeTruthy();
    expect(iframe1.id).not.toBe(iframe2.id);

    fixture2.destroy();
  });

  /**
   * TOOT-12 — Height listener respects its own iframe only (id-filter still works).
   *
   * After the unique-id fix the ids contain an instance suffix, so we must verify the
   * existing id-mismatch guard still works: a message addressed to instance A's id must
   * NOT mutate instance B's iframe.
   *
   * Arrange: two components, each with its own iframe and distinct id.
   * Act: dispatch a message with instance-A's id.
   * Assert: only iframe-A height is set; iframe-B remains unchanged.
   *
   * Red gate: if the id-filter were accidentally broken by the suffix change, both
   * iframes would get mutated.
   */
  it('TOOT-12: should only resize the matching iframe when ids differ across instances', () => {
    const fixture2 = TestBed.createComponent(TootComponent);
    const component2 = fixture2.componentInstance;
    component2.url = testSafeUrl;
    component2.uuid = testUuid;
    fixture2.detectChanges();

    const iframe1 = document.createElement('iframe') as HTMLIFrameElement;
    iframe1.src = testUrlString;
    const iframe2 = document.createElement('iframe') as HTMLIFrameElement;
    iframe2.src = testUrlString;

    component.configureIframe(iframe1);
    component2.configureIframe(iframe2);

    const idForIframe1 = iframe1.id;

    // Both listeners are now live; send a message matching iframe1's id.
    const listener1 = component['boundHeightListener'];
    const validMessage = {
      data: { type: 'setHeight', id: idForIframe1, height: '400px' },
      source: iframe1.contentWindow,
    } as unknown as MessageEvent;

    listener1(validMessage);

    expect(iframe1.height).toBe('400px');
    // iframe2's listener should reject this message because id !== iframe2.id
    const listener2 = component2['boundHeightListener'];
    listener2(validMessage);
    expect(iframe2.height).toBe('');

    fixture2.destroy();
  });

  it('should update the height of the iframe if data is valid', () => {
    const iframe = document.createElement('iframe') as HTMLIFrameElement;
    iframe.id = 'testIframe';

    const listener = component['getHeightListener'](iframe);

    // simulate correct MessageEvent
    const validMessage = {
      data: {
        type: 'setHeight',
        id: 'testIframe',
        height: '500px',
      },
      source: iframe.contentWindow,
    } as unknown as MessageEvent;

    listener(validMessage);

    // Iframe size should be adjusted
    expect(iframe.height).toBe('500px');
  });

  it('should not update the height if the event type is incorrect', () => {
    const iframe = document.createElement('iframe') as HTMLIFrameElement;
    iframe.id = 'testIframe';

    const listener = component['getHeightListener'](iframe);

    // Wrong message type
    const invalidTypeMessage = {
      data: {
        type: 'wrongType',
        id: 'testIframe',
      },
      source: iframe.contentWindow,
    } as unknown as MessageEvent;

    listener(invalidTypeMessage);

    // expect no change in size
    expect(iframe.height).toBe('');
  });

  it('should not update the height if the iframe ID does not match', () => {
    const iframe = document.createElement('iframe') as HTMLIFrameElement;
    iframe.id = 'testIframe';

    const listener = component['getHeightListener'](iframe);

    // message with wrong iframe id
    const mismatchedIdMessage = {
      data: {
        type: 'setHeight',
        id: 'differentId',
        height: '500px',
      },
      source: iframe.contentWindow,
    } as unknown as MessageEvent;

    listener(mismatchedIdMessage);

    // expect no change in size
    expect(iframe.height).toBe('');
  });

  it('should not update the height if the source does not match the iframe contentWindow', () => {
    const iframe = document.createElement('iframe') as HTMLIFrameElement;
    iframe.id = 'testIframe';

    const listener = component['getHeightListener'](iframe);

    // message with wrong source
    const mismatchedSourceMessage = {
      data: {
        type: 'setHeight',
        id: 'testIframe',
        height: '500px',
      },
      source: {},
    } as unknown as MessageEvent;

    listener(mismatchedSourceMessage);

    // expect no change in size
    expect(iframe.height).toBe('');
  });

  it('should handle cases where data is not an object', () => {
    const iframe = document.createElement('iframe') as HTMLIFrameElement;
    iframe.id = 'testIframe';

    const listener = component['getHeightListener'](iframe);

    // message with wrong data
    const invalidDataMessage = {
      data: null,
      source: iframe.contentWindow,
    } as unknown as MessageEvent;

    listener(invalidDataMessage);

    // expect no change in size
    expect(iframe.height).toBe('');
  });

  it('should send a postMessage to the iframe when configuring iframe', () => {
    const iframe = document.createElement('iframe');
    iframe.src = testUrlString;
    Object.defineProperty(iframe, 'contentWindow', {
      value: jasmine.createSpyObj('contentWindow', ['postMessage']),
      writable: false,
    });
    component.configureIframe(iframe);
    // After TOOT-12 the id sent in the postMessage is the collision-free iframeId
    // (uuid + instance suffix), not the bare uuid.  The recipient Mastodon embed
    // echoes back the same id in its response, so the height-listener filter works.
    expect(iframe.contentWindow!.postMessage).toHaveBeenCalledWith({
      type: 'setHeight',
      id: component.iframeId,
    }, testUrlString as WindowPostMessageOptions);
  });

  it('should handle absence of contentWindow gracefully', () => {
    const spyConsole = spyOn(console, 'debug');
    const iframe = document.createElement('iframe');
    iframe.src = testUrlString;
    component.configureIframe(iframe);
    expect(spyConsole).toHaveBeenCalledWith('Could not access contentWindow of iframe ', testUuid);
  });

  it('should call console.log with the correct iframe when handleError is called', () => {
    const spyConsoleLog = spyOn(console, 'log');
    const iframe = document.createElement('iframe');

      // Mocking `contentDocument` and `documentElement`
    const mockDocumentElement = {};
    const mockContentDocument = {
      documentElement: mockDocumentElement,
      removeChild: jasmine.createSpy('removeChild')
    };
    Object.defineProperty(iframe, 'contentDocument', {value: mockContentDocument, writable: true});

    // Call the method
    component.handleError(iframe);

    // Expect console.log to be called with appropriate arguments
    expect(spyConsoleLog).toHaveBeenCalledWith('error at iframe', iframe);

      // Ensure removeChild is called on documentElement
    expect(mockContentDocument.removeChild).toHaveBeenCalledWith(mockDocumentElement);
  });

    it('should call console.warn when documentElement is not available in handleError', () => {
        const spyConsoleWarn = spyOn(console, 'warn');
        const iframe = document.createElement('iframe');

        // Mocking `contentDocument` without documentElement
        const mockContentDocument = {};
        Object.defineProperty(iframe, 'contentDocument', {value: mockContentDocument, writable: true});

        // Call the method
        component.handleError(iframe);

        // Expect console.warn to be triggered
        expect(spyConsoleWarn).toHaveBeenCalledWith('Cannot handle error: documentElement is not available');
    });

    it('should not throw error if contentDocument or contentWindow is not available', () => {
        const spyConsoleLog = spyOn(console, 'log');
        const spyConsoleWarn = spyOn(console, 'warn');
        const iframe = document.createElement('iframe');

        // Call handleError with an iframe without contentDocument and contentWindow
        component.handleError(iframe);

        // Expect console.log to be called
        expect(spyConsoleLog).toHaveBeenCalledWith('error at iframe', iframe);

        // Expect console.warn to be triggered
        expect(spyConsoleWarn).toHaveBeenCalledWith('Cannot handle error: documentElement is not available');
    });

});
