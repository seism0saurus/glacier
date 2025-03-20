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

  it('should configure iframe with SafeResourceUrl and UUID', () => {
    // use existing iframe
    const iframe = fixture.nativeElement.querySelector('iframe') as HTMLIFrameElement;

    expect(iframe).toBeTruthy();
    expect(iframe.src).toContain(testUrlString);
  });

  it('should register message event listener on the window when configuring iframe', () => {
    const spyAddEventListener = spyOn(window, 'addEventListener');
    const iframe = document.createElement('iframe');
    iframe.src = testUrlString;
    component.configureIframe(iframe);
    expect(spyAddEventListener).toHaveBeenCalledWith('message', jasmine.any(Function));
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
    expect(iframe.contentWindow!.postMessage).toHaveBeenCalledWith({
      type: 'setHeight',
      id: testUuid,
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
