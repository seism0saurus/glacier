import {RxStompService} from './rx-stomp.service';
import {generateConfig} from './rx-stomp.config';
import { HttpClient, HttpErrorResponse } from "@angular/common/http";
import {lastValueFrom} from "rxjs";
import {environment} from "../environments/environment";

/**
 * Factory function to create and configure an RxStompService instance.
 *
 * @param {HttpClient} http - The Angular HttpClient instance used to make HTTP requests.
 * @param {Document} document - The DOM Document object used to access browser-specific details such as the hostname.
 * @return {RxStompService} - A configured and activated instance of RxStompService.
 */
export function rxStompServiceFactory(http: HttpClient, document: Document) {
  const rxStomp = new RxStompService();
  var protocolWebsocket = "ws";
  if (document.location.protocol == "https:") {
    protocolWebsocket = "wss";
  }
  var port = environment.backendPort;
  if (port == 'auto') {
    port = document.location.port;
  }
  var host = document.location.hostname
  // On the default ports (443 for HTTPS, 80 for HTTP) the browser reports an
  // empty location.port. Append ':port' ONLY when a port is actually present —
  // otherwise the authority becomes "host:" (a dangling colon), a malformed
  // WebSocket URL the browser refuses to connect to. This is the normal
  // production topology (Glacier served behind TLS on 443).
  const authority: string = port ? host + ':' + port : host;
  const brokerURL: string = protocolWebsocket + '://' + authority + '/websocket';
  const rxStompConfig = generateConfig(brokerURL);

  rxStompConfig.beforeConnect = (): Promise<void> =>
    lastValueFrom(http.get<void>('/rest/wall-id'))
      .catch((error: HttpErrorResponse) => {
        console.log("error: ", error);
        return;
      });
  rxStomp.configure(rxStompConfig);
  rxStomp.activate();
  return rxStomp;
}
