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

    // Fixture und Component erstellen
    fixture = TestBed.createComponent(TootComponent);
    component = fixture.componentInstance;

    // DomSanitizer aus TestBed injizieren
    sanitizer = TestBed.inject(DomSanitizer);

    // SafeResourceUrl für Tests erstellen
    testSafeUrl = sanitizer.bypassSecurityTrustResourceUrl(testUrlString);

    // Definiere die Inputs der Komponente
    component.url = testSafeUrl;
    component.uuid = testUuid;

    // Fixture-Änderungen erkennen lassen
    fixture.detectChanges();
  });

  it('should configure iframe with SafeResourceUrl and UUID', () => {
    // Existierendes iframe suchen
    const iframe = fixture.nativeElement.querySelector('iframe') as HTMLIFrameElement;

    expect(iframe).toBeTruthy(); // Sicherstellen, dass das iframe existiert
    expect(iframe.src).toContain(testUrlString); // Überprüfen, ob die URL korrekt gesetzt wurde
  });

  it('should register message event listener on the window when configuring iframe', () => {
    const spyAddEventListener = spyOn(window, 'addEventListener');
    const iframe = document.createElement('iframe');
    iframe.src = testUrlString;
    component.configureIframe(iframe);
    expect(spyAddEventListener).toHaveBeenCalledWith('message', jasmine.any(Function));
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

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  it('should call console.log with the correct iframe when handleError is called', () => {
    const spyConsoleLog = spyOn(console, 'log');
    const iframe = document.createElement('iframe');

    // Mocking `contentDocument` und `documentElement`
    const mockDocumentElement = {};
    const mockContentDocument = {
      documentElement: mockDocumentElement,
      removeChild: jasmine.createSpy('removeChild')
    };
    Object.defineProperty(iframe, 'contentDocument', { value: mockContentDocument, writable: true });

    // Call the method
    component.handleError(iframe);

    // Assertion: Prüfen, dass console.log aufgerufen wurde
    expect(spyConsoleLog).toHaveBeenCalledWith('error at iframe', iframe);

    // Assertion: Prüfen, ob removeChild mit documentElement aufgerufen wurde
    expect(mockContentDocument.removeChild).toHaveBeenCalledWith(mockDocumentElement);
  });

});
