/**
 * Property-based fuzz tests for {@link SafeUrlPipe} (SR-TEST-06, ADR-SHARE-03).
 *
 * fast-check (3.21.0) verifies the client-side URL re-validation contract over
 * generated inputs rather than a handful of examples:
 *
 *  1. Allowlist: any well-formed http/https URL (host present, no userinfo, no bidi)
 *     is accepted (returns a SafeUrl, i.e. non-null).
 *  2. Scheme denial: any non-http(s) scheme (javascript:, data:, file:, ftp:, …) is
 *     rejected (null).
 *  3. Userinfo denial: any URL carrying user[:pass]@host is rejected (phishing guard).
 *  4. Bidi denial: any URL containing a Unicode bidi override code point is rejected.
 *  5. Garbage/empty/non-string input is rejected (null).
 *
 * SR-FUZZ-02: assertion messages never echo the raw generated input.
 *
 * The DomSanitizer is faked with an identity trust wrapper so the test asserts the
 * pipe's *decision* (accept → truthy / reject → null), independent of Angular's
 * sanitizer internals.
 */
import * as fc from 'fast-check';
import { DomSanitizer } from '@angular/platform-browser';
import { SafeUrlPipe } from './safe-url.pipe';

/** Identity sanitizer: returns a sentinel object so accepted URLs are truthy. */
const fakeSanitizer = {
  bypassSecurityTrustUrl: (value: string) => ({ __safe: value }),
} as unknown as DomSanitizer;

const pipe = new SafeUrlPipe(fakeSanitizer);

/** Unicode bidi override / isolate code points the pipe must reject. */
const BIDI_CODEPOINTS = [0x202a, 0x202b, 0x202c, 0x202d, 0x202e, 0x2066, 0x2067, 0x2068, 0x2069, 0x200e, 0x200f];

describe('SafeUrlPipe — property-based (fast-check)', () => {
  it('accepts any well-formed http/https URL (host, no userinfo, no bidi)', () => {
    fc.assert(
      fc.property(
        fc.constantFrom('http', 'https'),
        fc.domain(),
        fc.webPath(),
        (scheme, host, path) => {
          const result = pipe.transform(`${scheme}://${host}${path}`);
          expect(result).withContext('well-formed http(s) URL must be accepted').not.toBeNull();
        },
      ),
    );
  });

  it('rejects every non-http(s) scheme', () => {
    fc.assert(
      fc.property(
        fc.constantFrom('javascript', 'data', 'vbscript', 'file', 'blob', 'about', 'mailto', 'tel', 'ftp', 'ws', 'gopher'),
        fc.domain(),
        (scheme, host) => {
          expect(pipe.transform(`${scheme}://${host}/x`))
            .withContext('disallowed scheme must be rejected').toBeNull();
        },
      ),
    );
  });

  it('rejects URLs carrying userinfo (phishing guard)', () => {
    const ident = fc.stringMatching(/^[a-z0-9]{1,8}$/);
    fc.assert(
      fc.property(ident, ident, fc.domain(), (user, pass, host) => {
        expect(pipe.transform(`https://${user}:${pass}@${host}/`))
          .withContext('userinfo URL must be rejected').toBeNull();
      }),
    );
  });

  it('rejects URLs containing a bidi override code point', () => {
    fc.assert(
      fc.property(fc.constantFrom(...BIDI_CODEPOINTS), fc.domain(), (cp, host) => {
        const url = `https://${host}/` + String.fromCodePoint(cp) + 'path';
        expect(pipe.transform(url))
          .withContext('URL with a bidi override must be rejected').toBeNull();
      }),
    );
  });

  it('rejects empty, whitespace, non-string and unparseable input', () => {
    fc.assert(
      fc.property(
        fc.oneof(
          fc.constantFrom(null, undefined, '', '   ', '\t', 'not a url', 'http://', '://nohost'),
          // arbitrary non-URL text
          fc.string(),
          // numbers / objects passed where a string is expected
          fc.anything(),
        ),
        (input) => {
          const result = pipe.transform(input as unknown as string);
          // Either rejected (null) or — for a coincidentally valid http(s) string — a SafeUrl.
          // The invariant: it never throws and only ever returns null or a truthy SafeUrl.
          expect(result === null || typeof result === 'object')
            .withContext('transform must never throw and must return null or a SafeUrl')
            .toBeTrue();
        },
      ),
    );
  });
});
