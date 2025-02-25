import {RxStompConfig} from '@stomp/rx-stomp';

/**
 * Generates a configuration object for RxStomp with specified broker URL and default settings.
 *
 * @param {string} brokerURL - The WebSocket URL of the message broker to connect to.
 * @return {RxStompConfig} An object containing configuration settings for RxStomp.
 */
export function generateConfig(brokerURL: string): RxStompConfig {
  return {

    brokerURL: brokerURL,

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
  }
}
