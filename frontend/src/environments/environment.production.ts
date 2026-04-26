export const environment = {
  environmentName: 'prod',
  production: true,
  backendPort: 'auto',
  /** Poll interval in ms while FallbackService is in FALLBACK mode (D-01). */
  fallbackPollIntervalMs: 5_000,
  /**
   * Must remain false in production.  Allowing plaintext in production would
   * expose the wallId cookie over an insecure channel (D-14).
   * CodebaseConstraintTest on the backend enforces that glacier.devmode is
   * never read outside mastodon/*; this flag is the Angular equivalent guard.
   */
  allowPlaintext: false,
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
