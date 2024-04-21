import {RxStompConfig} from '@stomp/rx-stomp';
import { environment } from '../environments/environment'

export const glacierRxStompConfig: RxStompConfig = {
  brokerURL: 'ws://' + environment.host +':' + environment.port + '/websocket',

  // Headers
  connectHeaders: {},

  // Heartbeat interval in milliseconds,
  // set to 0 to disable
  heartbeatIncoming: 0, // Default disabled
  heartbeatOutgoing: 20000, // Default every 20 seconds

  // Wait in milliseconds before attempting to auto reconnect
  // Set to 0 to disable
  reconnectDelay: 500, // Default 500 milliseconds

  // Will log diagnostics on console
  debug: (msg: string): void => {
    console.log(new Date(), msg);
  },
};
