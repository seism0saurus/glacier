import {RxStompService} from './rx-stomp.service';
import {generateConfig} from './rx-stomp.config';
import {HttpClient} from "@angular/common/http";
import {lastValueFrom} from "rxjs";
import {environment} from "../environments/environment";

export function rxStompServiceFactory(http: HttpClient, document: Document) {
  const rxStomp = new RxStompService();

  const brokerURL: string = environment.protocolWebsocket + '://' + document.location.hostname + ':' + environment.backendPort + '/websocket';
  const rxStompConfig = generateConfig(brokerURL);

  rxStompConfig.beforeConnect = (): Promise<void> => {
    return lastValueFrom(http.get<void>('/rest/wall-id'));
  };
  rxStomp.configure(rxStompConfig);
  rxStomp.activate();
  return rxStomp;
};
