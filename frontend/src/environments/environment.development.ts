export const environment = {
  environmentName: 'dev',
  production: false,
  backendPort: '8080',
  /** Poll interval in ms while FallbackService is in FALLBACK mode (D-01). */
  fallbackPollIntervalMs: 5_000,
  /** Allow plaintext in development so http://localhost works (D-14). */
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
