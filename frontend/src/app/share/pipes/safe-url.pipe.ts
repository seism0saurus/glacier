import { Pipe, PipeTransform } from '@angular/core';
import { DomSanitizer, SafeUrl } from '@angular/platform-browser';

/**
 * Defence-in-depth URL re-validation pipe for the readonly share view.
 *
 * This pipe is the client-side layer of the two-layer URL validation
 * defence (ADR-SHARE-03).  The primary validation is server-side via
 * `DefaultSafeUrlValidator` (SafeUrlValidator); this pipe provides a
 * redundant check that prevents any bypass that might occur at the
 * server/transport boundary from reaching the DOM.
 *
 * Security contract:
 * - Returns a `SafeUrl` only for URLs whose scheme is exactly `https` or `http`.
 * - Rejects all other schemes (javascript:, data:, vbscript:, file:, blob:,
 *   about:, mailto:, tel:, and any unknown scheme).
 * - Rejects URLs that contain userinfo (phishing via user:pass@host).
 * - Rejects URLs containing Unicode bidirectional override characters.
 * - Returns null for any invalid, empty, or non-string input.
 *
 * ESLint rule `no-share-dangerous-html` forbids `bypassSecurityTrustHtml/
 * Script/Style/ResourceUrl` inside share/ — this pipe uses only
 * `bypassSecurityTrustUrl` (for `<a href>` binding) which is permitted.
 */
@Pipe({
  name: 'safeUrl',
  standalone: true,
  pure: true,
})
export class SafeUrlPipe implements PipeTransform {

  /** Bidi override code points that must not appear in validated URLs. */
  private static readonly BIDI_OVERRIDE_PATTERN =
    /[‪-‮⁦-⁩‏‎]/;

  /** Schemes that are explicitly allowed. Lower-cased for comparison. */
  private static readonly ALLOWED_SCHEMES = new Set(['https', 'http']);

  constructor(private sanitizer: DomSanitizer) {}

  /**
   * Validates `url` and returns a `SafeUrl` if it passes all checks,
   * or `null` otherwise.  The null return value signals to the template
   * that the `<a>` should be rendered without an `href`.
   */
  transform(url: string | null | undefined): SafeUrl | null {
    if (url === null || url === undefined || typeof url !== 'string' || url.trim() === '') {
      return null;
    }

    let parsed: URL;
    try {
      parsed = new URL(url.trim());
    } catch {
      return null;
    }

    // Scheme check — URL.protocol includes the trailing colon ("https:")
    const scheme = parsed.protocol.replace(/:$/, '').toLowerCase();
    if (!SafeUrlPipe.ALLOWED_SCHEMES.has(scheme)) {
      return null;
    }

    // Host must be non-empty
    if (!parsed.hostname || parsed.hostname.trim() === '') {
      return null;
    }

    // Reject userinfo (phishing: https://evil@legitimate.com)
    if (parsed.username || parsed.password) {
      return null;
    }

    // Reject bidi override characters anywhere in the URL
    if (SafeUrlPipe.BIDI_OVERRIDE_PATTERN.test(url)) {
      return null;
    }

    return this.sanitizer.bypassSecurityTrustUrl(parsed.toString());
  }
}
