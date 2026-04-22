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
};
