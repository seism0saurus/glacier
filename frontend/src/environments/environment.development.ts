export const environment = {
  environmentName: 'dev',
  production: false,
  backendPort: '8080',
  /** Poll interval in ms while FallbackService is in FALLBACK mode (D-01). */
  fallbackPollIntervalMs: 5_000,
  /** Allow plaintext in development so http://localhost works (D-14). */
  allowPlaintext: true,
};
