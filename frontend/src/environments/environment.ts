export const environment = {
  environmentName: 'default',
  production: false,
  backendPort: 'auto',
  /** Poll interval in ms while FallbackService is in FALLBACK mode (D-01). */
  fallbackPollIntervalMs: 5_000,
  /**
   * When true, FallbackService will enter INSECURE state on plain-HTTP pages
   * instead of starting the poller (D-14).
   * Always false in development so that local testing over http:// works.
   */
  allowPlaintext: true,
};
