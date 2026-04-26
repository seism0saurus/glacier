/**
 * MessageQueueValidator — strict schema validation for the v:2 WallMessage queue
 * restored from localStorage (SR-PRUNE-01, SR-PRUNE-02, SR-PRUNE-12).
 *
 * Security context (OWASP A03 — Injection; OWASP A08 — Software and Data
 * Integrity Failures):
 *
 *   localStorage is a user-controlled storage area.  An attacker who can write
 *   to localStorage (via XSS, a compromised extension, or shared-origin content)
 *   could inject a crafted queue with prototype-pollution payloads, oversized
 *   strings, or missing required fields.  Accepting such a queue without
 *   validation would allow:
 *     - Prototype-pollution attacks: `__proto__`, `constructor`, `prototype`
 *       as property names can silently alter Object.prototype in older JS engines
 *       or in libraries that iterate object keys without hasOwnProperty checks.
 *     - DoS via oversized strings: a 1 MB id or url would not crash the app but
 *       could cause noticeable slowdowns in rendering / comparison loops.
 *     - State corruption: a queue that does not satisfy the WallMessage invariants
 *       (hashtags.length >= 1) would cause prune operations to produce incorrect
 *       results or expose undefined behaviour.
 *
 * Design invariants:
 *   - Returns null (never throws) on all malformed inputs so callers can
 *     safely discard and reset without error handling.
 *   - Accepts only the already-parsed value — no JSON.parse, no eval,
 *     no Function() inside this function.
 *   - On ANY item failure, the entire queue is discarded (SR-PRUNE-01).
 *     Partial acceptance would allow an attacker to sneak in bad items by
 *     surrounding them with valid ones.
 *   - Prototype-pollution check covers both item key names AND hashtag string
 *     values (SR-PRUNE-02).
 */
import { WallMessage, WallMessageSchemaVersion } from './wall-message';

/** Reserved object keys that, if present in an item, indicate prototype pollution. */
const RESERVED_KEYS = new Set(['__proto__', 'constructor', 'prototype']);

/** Maximum allowed byte/char length for a toot id. */
const MAX_ID_LENGTH = 100;

/** Maximum allowed byte/char length for a toot url. */
const MAX_URL_LENGTH = 512;

/** Maximum allowed byte/char length for a single hashtag string. */
const MAX_HASHTAG_LENGTH = 100;

/**
 * Validates a parsed (already JSON.parse'd) queue object and returns the
 * contained WallMessage[] on success, or null if the input does not satisfy
 * the v:2 schema or contains any invalid / suspicious value.
 *
 * Callers (MessageQueue.restore) pass the result of JSON.parse — NOT the
 * raw localStorage string — so this function never needs to parse JSON.
 *
 * @param raw - The already-parsed value from localStorage; type unknown.
 * @returns WallMessage[] if the queue is valid, null otherwise.
 */
export function validateMessageQueue(raw: unknown): WallMessage[] | null {
  // Step 1: top-level type check — must be a plain, non-null, non-array object
  // with v === WallMessageSchemaVersion (SR-PRUNE-01, U-SEC-02).
  if (raw === null || raw === undefined) {
    return null;
  }
  if (typeof raw !== 'object' || Array.isArray(raw)) {
    return null;
  }

  const envelope = raw as Record<string, unknown>;

  // Version field must equal the exact numeric constant (not a string, not v:1)
  if (envelope['v'] !== WallMessageSchemaVersion) {
    return null;
  }

  // Step 2: items must be a non-null Array.
  const items = envelope['items'];
  if (!Array.isArray(items) || items === null) {
    return null;
  }

  // Step 3: validate each item — any failure discards the entire queue (SR-PRUNE-01).
  for (const item of items) {
    if (!validateItem(item)) {
      return null;
    }
  }

  // All items passed — cast is safe because validateItem guarantees the shape.
  return items as WallMessage[];
}

/**
 * Validates the raw value restored from the `hashtags` localStorage key and
 * returns the contained string[] on success, or null if the input is invalid.
 *
 * Security context (FIND-P3-SEC-4 / OWASP A03 — Injection):
 *   An attacker who can write to localStorage (XSS, browser extension,
 *   co-origin shared storage) could inject crafted hashtag strings that are
 *   then forwarded to the STOMP server as subscription requests.  While
 *   server-side validation is the primary defence, the client must not be a
 *   willing relay for injection payloads.
 *
 * Invariants enforced:
 *   - Input must be an array (not null, not object, not primitive).
 *   - Array length must be ≤ 100 (reasonable upper bound; no legitimate wall
 *     needs 100+ simultaneous hashtags — upper bound prevents DoS via large
 *     iteration on the STOMP subscription loop).
 *   - Each element must be a non-empty string of length ≤ 100.
 *   - Each element must match the normalised Mastodon hashtag charset:
 *     letters, digits, underscore (Unicode-aware via the /u flag) — the same
 *     characters that normaliseHashtag() produces after stripping '#' and
 *     lower-casing.  Characters outside this set (< > / \ " ` space) are
 *     injection-risk and are rejected.
 *   - Reserved prototype-pollution names (__proto__, constructor, prototype)
 *     are rejected (SR-PRUNE-02 extended to the hashtags list).
 *
 * @param raw - The already-parsed value from localStorage; type unknown.
 * @returns string[] if all elements are valid, null otherwise.
 */
export function validateHashtagsList(raw: unknown): string[] | null {
  if (!Array.isArray(raw)) return null;
  if (raw.length > 100) return null; // reasonable upper bound
  for (const t of raw) {
    if (typeof t !== 'string') return null;
    if (t.length === 0 || t.length > 100) return null;
    // SR-PRUNE-02 extended: prototype-pollution names must not reach STOMP
    if (RESERVED_KEYS.has(t)) return null;
    // Only allow characters valid in a normalised Mastodon hashtag
    // (letters, digits, underscore — after normaliseHashtag strips leading # and lowercases).
    // The /u flag enables Unicode property escapes (\p{L}\p{N}) so international
    // hashtags with non-ASCII letters remain valid.
    if (!/^[a-z0-9_\p{L}\p{N}]+$/u.test(t)) return null;
  }
  return raw as string[];
}

/**
 * Returns true only when the item fully satisfies the WallMessage invariants
 * and contains no prototype-pollution keys or oversized values.
 *
 * A false return causes the entire queue to be discarded by the caller.
 *
 * @param item - A single element from the parsed items array.
 */
function validateItem(item: unknown): boolean {
  if (item === null || typeof item !== 'object' || Array.isArray(item)) {
    return false;
  }

  const obj = item as Record<string, unknown>;

  // SR-PRUNE-02: reject items whose own enumerable keys include reserved names.
  // Object.keys() returns own enumerable keys only.  We check this BEFORE
  // accessing any field to avoid letting a crafted prototype chain influence
  // the field reads below.
  for (const key of Object.keys(obj)) {
    if (RESERVED_KEYS.has(key)) {
      return false;
    }
  }

  // id: must be a string, length <= 100
  const id = obj['id'];
  if (typeof id !== 'string' || id.length > MAX_ID_LENGTH) {
    return false;
  }

  // url: must be a string, length <= 512
  const url = obj['url'];
  if (typeof url !== 'string' || url.length > MAX_URL_LENGTH) {
    return false;
  }

  // AC-11 / SR-PRUNE-* / OWASP A03: URL must use http or https scheme.
  // javascript:, data:, vbscript:, etc. must be blocked here because
  // bypassSecurityTrustResourceUrl (used by the iframe renderer) disables
  // Angular's built-in URL sanitiser — the allowlist check is the last defence.
  try {
    const parsed = new URL(url);
    if (parsed.protocol !== 'https:' && parsed.protocol !== 'http:') {
      return false;
    }
  } catch {
    return false; // malformed URL — cannot be a legitimate Mastodon toot URL
  }

  // hashtags: must be a non-null array with at least one entry
  const hashtags = obj['hashtags'];
  if (!Array.isArray(hashtags) || hashtags.length < 1) {
    return false;
  }

  // Each hashtag: string, length <= 100, not a reserved prototype-pollution name
  for (const h of hashtags) {
    if (typeof h !== 'string' || h.length > MAX_HASHTAG_LENGTH) {
      return false;
    }
    // SR-PRUNE-02: hashtag values must not be reserved prototype-pollution strings
    if (RESERVED_KEYS.has(h)) {
      return false;
    }
  }

  return true;
}
