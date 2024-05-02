import { RxStompService } from './rx-stomp.service';
import { glacierRxStompConfig } from './rx-stomp.config';
import {HttpClient} from "@angular/common/http";
import {lastValueFrom} from "rxjs";
import {InstanceOperator} from "./footer/instance-operator";
import {environment} from "../environments/environment";

export function rxStompServiceFactory(http: HttpClient) {
  const rxStomp = new RxStompService();
  let rxStompConfig = glacierRxStompConfig;

  http.get<InstanceOperator>('/rest/operator')
    .subscribe( operator => {
      let brokerURL: string = environment.protocolWebsocket + '://' + operator.domain +':' + environment.backendPort + '/websocket';
      rxStompConfig.brokerURL = brokerURL;
    });
  rxStompConfig.beforeConnect = (): Promise<void> => {
      return lastValueFrom(http.get<void>('/rest/wall-id'));
  };
  rxStomp.configure(rxStompConfig);
  rxStomp.activate();
  return rxStomp;
}
