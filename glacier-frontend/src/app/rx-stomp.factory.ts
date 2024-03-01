import { RxStompService } from './rx-stomp.service';
import { glacierRxStompConfig } from './rx-stomp.config';

export function rxStompServiceFactory() {
  const rxStomp = new RxStompService();
  rxStomp.configure(glacierRxStompConfig);
  rxStomp.activate();
  return rxStomp;
}
