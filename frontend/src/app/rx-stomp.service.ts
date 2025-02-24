import { Injectable } from '@angular/core';
import { RxStomp } from '@stomp/rx-stomp';

/**
 * Service that extends RxStomp to provide WebSocket messaging functionality.
 *
 * RxStompService is an injectable service designed for use within Angular
 * applications. It acts as a wrapper around the RxStomp library, enabling
 * reactive communication with STOMP-based WebSocket servers.
 *
 * This service is provided at the root level, making it a singleton across
 * the entire application. It can be used as-is or further extended to
 * implement application-specific messaging logic.
 *
 * It inherits all the configuration, connection, and messaging capabilities
 * of the base RxStomp class.
 */
@Injectable({
  providedIn: 'root',
})
export class RxStompService extends RxStomp {
  constructor() {
    super();
  }
}
