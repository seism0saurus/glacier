import { RxStompService } from './rx-stomp.service';
import { glacierRxStompConfig } from './rx-stomp.config';
import {HttpClient} from "@angular/common/http";
import {lastValueFrom} from "rxjs";

export function rxStompServiceFactory(http: HttpClient) {
  const rxStomp = new RxStompService();
  let rxStompConfig = glacierRxStompConfig;
  rxStompConfig.beforeConnect = (): Promise<void> => {
      return lastValueFrom(http.get<void>('/rest/wall-id'));
  };
  rxStomp.configure(rxStompConfig);
  rxStomp.activate();
  return rxStomp;
}
