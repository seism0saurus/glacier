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
  /**
   * Prune feature configuration (ADR-3, SR-PRUNE-03, SR-PRUNE-04).
   */
  prune: {
    /**
     * How long (in ms) a terminated hashtag is protected by the
     * recentlyTerminated guard after the 4-step ack sequence fires.
     * Late STOMP deliveries arriving within this window are silently dropped
     * to prevent pruned toots from reappearing.
     * Default: 10 000 ms (10 s).  Configurable per-environment.
     */
    guardTtlMs: 10_000,
  },
};
