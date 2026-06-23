import { TestBed } from '@angular/core/testing';
import {
  HttpClientTestingModule,
  HttpTestingController,
} from '@angular/common/http/testing';
import { HttpClient } from '@angular/common/http';
import { RxStompService } from './rx-stomp.service';
import { rxStompServiceFactory } from './rx-stomp.factory';

/**
 * Unit tests for {@link rxStompServiceFactory}.
 *
 * <p>The factory encapsulates two concerns that are difficult to verify from
 * outside the production code path:
 * <ol>
 *   <li>The broker URL scheme ({@code ws} vs {@code wss}) must track the page
 *       protocol so browsers do not block a mixed-content upgrade attempt.
 *   <li>The {@code beforeConnect} hook must obtain a {@code wallId} cookie from
 *       the server before any STOMP frame is sent, so the cookie is set when
 *       the CONNECT frame arrives at the broker.
 * </ol>
 *
 * <p>Test strategy: spy on {@link RxStompService#configure} and
 * {@link RxStompService#activate} to prevent a real WebSocket connection being
 * opened during the Karma browser run.  Capture the generated
 * {@link RxStompConfig} from the {@code configure} spy and assert on its
 * fields directly, rather than indirectly through a running STOMP client.
 */
describe('rxStompServiceFactory', () => {
  let httpClient: HttpClient;
  let httpController: HttpTestingController;
  let configureSpy: jasmine.Spy;
  let activateSpy: jasmine.Spy;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [HttpClientTestingModule],
      providers: [RxStompService],
    });

    httpClient = TestBed.inject(HttpClient);
    httpController = TestBed.inject(HttpTestingController);

    // Prevent a real WebSocket from being opened — we test config, not transport.
    configureSpy = spyOn(RxStompService.prototype, 'configure');
    activateSpy = spyOn(RxStompService.prototype, 'activate');
  });

  afterEach(() => {
    httpController.verify();
  });

  // -----------------------------------------------------------------------
  // Broker URL — scheme selection based on page protocol
  // -----------------------------------------------------------------------

  /**
   * When the page is served over HTTPS, the broker URL must use the {@code wss}
   * scheme to avoid mixed-content blocks in browsers that enforce TLS.
   *
   * <p>Arrange: fake document with {@code location.protocol === 'https:'} and
   *             {@code location.hostname === 'glacier.example.com'}.
   * <p>Act:     call {@code rxStompServiceFactory}.
   * <p>Assert:  {@code configure} receives a config whose {@code brokerURL}
   *             starts with {@code wss://}.
   */
  it('factory_buildsWssUrl_underHttps', () => {
    const fakeDocument = {
      location: {
        protocol: 'https:',
        hostname: 'glacier.example.com',
        port: '443',
      },
    } as unknown as Document;

    rxStompServiceFactory(httpClient, fakeDocument);

    expect(configureSpy).toHaveBeenCalledTimes(1);
    const capturedConfig = configureSpy.calls.first().args[0];
    expect(capturedConfig.brokerURL).toMatch(/^wss:\/\//);
    expect(capturedConfig.brokerURL).toContain('glacier.example.com');
    expect(activateSpy).toHaveBeenCalledTimes(1);
  });

  /**
   * When the page is served over HTTPS on the default port 443, the browser
   * reports {@code location.port === ''}. The broker URL must then OMIT the port
   * entirely — emitting {@code wss://host/websocket}, never {@code wss://host:/websocket}
   * (a malformed authority with a dangling colon that the browser refuses to
   * connect to). This is the normal production topology (Glacier behind TLS on
   * 443), so getting it wrong breaks live streaming in production.
   *
   * <p>Arrange: fake document with {@code protocol === 'https:'}, a hostname, and
   *             an EMPTY port (as browsers report for the default 443).
   * <p>Act:     call {@code rxStompServiceFactory} (backendPort defaults to 'auto').
   * <p>Assert:  brokerURL is exactly {@code wss://<host>/websocket} — no colon, no port.
   */
  it('factory_omitsPort_whenDefaultHttpsPortEmpty', () => {
    const fakeDocument = {
      location: {
        protocol: 'https:',
        hostname: 'glacier.proxy',
        port: '',
      },
    } as unknown as Document;

    rxStompServiceFactory(httpClient, fakeDocument);

    const capturedConfig = configureSpy.calls.first().args[0];
    expect(capturedConfig.brokerURL).toBe('wss://glacier.proxy/websocket');
  });

  /**
   * When the page is served over plain HTTP (e.g. local development), the broker
   * URL must use the {@code ws} scheme — using {@code wss} on an HTTP page
   * would fail because the WebSocket upgrade target is not TLS.
   *
   * <p>Arrange: fake document with {@code location.protocol === 'http:'} and
   *             {@code location.hostname === 'localhost'}.
   * <p>Act:     call {@code rxStompServiceFactory}.
   * <p>Assert:  {@code configure} receives a config whose {@code brokerURL}
   *             starts with {@code ws://} (not {@code wss://}).
   */
  it('factory_buildsWsUrl_underHttp', () => {
    const fakeDocument = {
      location: {
        protocol: 'http:',
        hostname: 'localhost',
        port: '8080',
      },
    } as unknown as Document;

    rxStompServiceFactory(httpClient, fakeDocument);

    expect(configureSpy).toHaveBeenCalledTimes(1);
    const capturedConfig = configureSpy.calls.first().args[0];
    expect(capturedConfig.brokerURL).toMatch(/^ws:\/\//);
    expect(capturedConfig.brokerURL).not.toMatch(/^wss:\/\//);
    expect(capturedConfig.brokerURL).toContain('localhost');
    expect(activateSpy).toHaveBeenCalledTimes(1);
  });

  // -----------------------------------------------------------------------
  // beforeConnect — wallId cookie retrieval
  // -----------------------------------------------------------------------

  /**
   * The {@code beforeConnect} hook must call {@code GET /rest/wall-id} so that
   * the server has the opportunity to set the {@code wallId} cookie before the
   * STOMP CONNECT frame is sent.  Without this, the first CONNECT frame arrives
   * without a principal and all downstream subscription-authorisation checks fail.
   *
   * <p>Arrange: call the factory and extract the captured config.
   * <p>Act:     invoke the {@code beforeConnect} callback.
   * <p>Assert:  exactly one {@code GET /rest/wall-id} request is outstanding.
   */
  it('factory_callsRestWallId_inBeforeConnect', async () => {
    const fakeDocument = {
      location: {
        protocol: 'http:',
        hostname: 'localhost',
        port: '8080',
      },
    } as unknown as Document;

    rxStompServiceFactory(httpClient, fakeDocument);

    expect(configureSpy).toHaveBeenCalledTimes(1);
    const capturedConfig = configureSpy.calls.first().args[0];

    // The beforeConnect hook is a function on the captured config.
    expect(capturedConfig.beforeConnect).toBeDefined();

    // Invoke beforeConnect — this triggers the GET /rest/wall-id call.
    const beforeConnectPromise = capturedConfig.beforeConnect();

    // There must now be exactly one pending GET /rest/wall-id request.
    const req = httpController.expectOne('/rest/wall-id');
    expect(req.request.method).toBe('GET');

    // Flush the request to resolve the promise.
    req.flush(null);
    await beforeConnectPromise;
  });
});
