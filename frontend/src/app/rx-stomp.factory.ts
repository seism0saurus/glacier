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
  const brokerURL: string = protocolWebsocket + '://' + host + ':' + port + '/websocket';
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
