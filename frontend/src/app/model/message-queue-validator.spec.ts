/**
 * Unit tests for MessageQueueValidator (SR-PRUNE-01, SR-PRUNE-02, SR-PRUNE-12)
 * and validateHashtagsList (FIND-P3-SEC-4).
 *
 * Security coverage:
 *   U-SEC-01 — valid v:2 queue passes validation and returns WallMessage[]
 *   U-SEC-02 — queue without version field returns null (schema version guard)
 *   U-SEC-12 — null / undefined / non-object input returns null without throwing
 *   U-SEC-14 — validateItem rejects javascript:/data:/vbscript: URLs (FIND-P3-SEC-7 / AC-11)
 *   FIND-P3-SEC-4 — validateHashtagsList rejects malformed/injected localStorage input
 *
 * Additional security invariants:
 *   - v:1 (flat array without envelope) returns null
 *   - Wrong schema version (v:1 envelope) returns null
 *   - __proto__ in hashtags returns null (prototype-pollution defence)
 *   - constructor as hashtag value returns null
 *   - prototype as hashtag value returns null
 *   - constructor as item key is rejected (prototype-pollution defence)
 *   - Oversized id (>100 chars) causes queue discard
 *   - Oversized url (>512 chars) causes queue discard
 *   - Empty hashtags[] causes queue discard
 *   - Single invalid item causes entire queue to be discarded (not just that item)
 *
 * OWASP A03 — Injection prevention: prototype-pollution via JSON.parse is a
 * real attack vector.  The validator ensures the parsed object does not carry
 * poisoned prototype keys before any queue entries touch application state.
 *
 * Note: no eval, no Function(), no JSON.parse inside the validator —
 * the caller already parsed; the validator receives the already-parsed value.
 */
import { validateMessageQueue, validateHashtagsList } from './message-queue-validator';
import { WallMessageSchemaVersion, WallMessage } from './wall-message';

describe('validateMessageQueue', () => {

  // ---- U-SEC-12: null / undefined / non-object inputs ----

  describe('U-SEC-12: null/undefined/non-object input', () => {
    it('returns null when input is null', () => {
      expect(validateMessageQueue(null)).toBeNull();
    });

    it('returns null when input is undefined', () => {
      expect(validateMessageQueue(undefined)).toBeNull();
    });

    it('returns null when input is a primitive number', () => {
      expect(validateMessageQueue(42)).toBeNull();
    });

    it('returns null when input is a primitive string', () => {
      expect(validateMessageQueue('{"v":2,"items":[]}')).toBeNull();
    });

    it('returns null when input is a boolean', () => {
      expect(validateMessageQueue(false)).toBeNull();
    });

    it('does not throw on any input — returns null instead', () => {
      expect(() => validateMessageQueue(null)).not.toThrow();
      expect(() => validateMessageQueue(undefined)).not.toThrow();
      expect(() => validateMessageQueue([])).not.toThrow();
      expect(() => validateMessageQueue({})).not.toThrow();
      expect(() => validateMessageQueue('string')).not.toThrow();
    });
  });

  // ---- U-SEC-02: absent version field ----

  describe('U-SEC-02: absent or wrong schema version', () => {
    it('returns null when v field is absent', () => {
      const raw = { items: [{ id: '1', url: 'https://example.com', hashtags: ['glacier'] }] };
      expect(validateMessageQueue(raw)).toBeNull();
    });

    it('returns null when v is 1 (legacy schema without envelope)', () => {
      const raw = { v: 1, items: [{ id: '1', url: 'https://example.com', hashtags: ['glacier'] }] };
      expect(validateMessageQueue(raw)).toBeNull();
    });

    it('returns null when v is a string matching the version number', () => {
      const raw = { v: String(WallMessageSchemaVersion), items: [] };
      expect(validateMessageQueue(raw)).toBeNull();
    });

    it('returns null when v is null', () => {
      const raw = { v: null, items: [] };
      expect(validateMessageQueue(raw)).toBeNull();
    });

    it('returns null for a flat top-level array (v:1 format)', () => {
      const raw: unknown = [{ id: '1', url: 'https://example.com', hashtags: ['glacier'] }];
      expect(validateMessageQueue(raw)).toBeNull();
    });
  });

  // ---- U-SEC-01: valid v:2 queue ----

  describe('U-SEC-01: valid v:2 queue passes validation', () => {
    it('returns WallMessage[] for a well-formed v:2 queue', () => {
      const raw = {
        v: WallMessageSchemaVersion,
        items: [
          { id: 'abc123', url: 'https://mastodon.example/@alice/1', hashtags: ['glacier'] },
          { id: 'def456', url: 'https://mastodon.example/@bob/2', hashtags: ['foss', 'linux'] },
        ],
      };
      const result = validateMessageQueue(raw);
      expect(result).not.toBeNull();
      expect(result!.length).toBe(2);
      expect(result![0].id).toBe('abc123');
      expect(result![1].hashtags).toEqual(['foss', 'linux']);
    });

    it('returns an empty array for a valid v:2 queue with no items', () => {
      const raw = { v: WallMessageSchemaVersion, items: [] };
      const result = validateMessageQueue(raw);
      expect(result).not.toBeNull();
      expect(result!.length).toBe(0);
    });

    it('accepts WallMessage with optional editedAt field', () => {
      const raw = {
        v: WallMessageSchemaVersion,
        items: [
          {
            id: 'abc123',
            url: 'https://mastodon.example/@alice/1',
            editedAt: '2026-04-24T10:00:00Z',
            hashtags: ['glacier'],
          },
        ],
      };
      const result = validateMessageQueue(raw);
      expect(result).not.toBeNull();
      expect(result![0].editedAt).toBe('2026-04-24T10:00:00Z');
    });

    it('returns typed WallMessage[] (not just unknown[])', () => {
      const raw = {
        v: WallMessageSchemaVersion,
        items: [{ id: 'x', url: 'https://example.com', hashtags: ['a'] }],
      };
      const result: WallMessage[] | null = validateMessageQueue(raw);
      expect(result).not.toBeNull();
    });
  });

  // ---- items must be a non-null Array ----

  describe('items field validation', () => {
    it('returns null when items is absent', () => {
      const raw = { v: WallMessageSchemaVersion };
      expect(validateMessageQueue(raw)).toBeNull();
    });

    it('returns null when items is null', () => {
      const raw = { v: WallMessageSchemaVersion, items: null };
      expect(validateMessageQueue(raw)).toBeNull();
    });

    it('returns null when items is an object (not an array)', () => {
      const raw = { v: WallMessageSchemaVersion, items: { 0: { id: '1', url: 'a', hashtags: ['b'] } } };
      expect(validateMessageQueue(raw)).toBeNull();
    });

    it('returns null when items is a string', () => {
      const raw = { v: WallMessageSchemaVersion, items: '[]' };
      expect(validateMessageQueue(raw)).toBeNull();
    });
  });

  // ---- Per-item field validation — oversized id ----

  describe('item.id validation', () => {
    it('returns null when id is absent from an item', () => {
      const raw = {
        v: WallMessageSchemaVersion,
        items: [{ url: 'https://example.com', hashtags: ['glacier'] }],
      };
      expect(validateMessageQueue(raw)).toBeNull();
    });

    it('returns null when id is not a string', () => {
      const raw = {
        v: WallMessageSchemaVersion,
        items: [{ id: 12345, url: 'https://example.com', hashtags: ['glacier'] }],
      };
      expect(validateMessageQueue(raw)).toBeNull();
    });

    it('returns null when id exceeds 100 characters (oversized id)', () => {
      const oversizedId = 'a'.repeat(101);
      const raw = {
        v: WallMessageSchemaVersion,
        items: [{ id: oversizedId, url: 'https://example.com', hashtags: ['glacier'] }],
      };
      expect(validateMessageQueue(raw)).toBeNull();
    });

    it('accepts id with exactly 100 characters', () => {
      const exactId = 'a'.repeat(100);
      const raw = {
        v: WallMessageSchemaVersion,
        items: [{ id: exactId, url: 'https://example.com', hashtags: ['glacier'] }],
      };
      expect(validateMessageQueue(raw)).not.toBeNull();
    });
  });

  // ---- Per-item field validation — oversized url ----

  describe('item.url validation', () => {
    it('returns null when url is absent from an item', () => {
      const raw = {
        v: WallMessageSchemaVersion,
        items: [{ id: 'abc', hashtags: ['glacier'] }],
      };
      expect(validateMessageQueue(raw)).toBeNull();
    });

    it('returns null when url is not a string', () => {
      const raw = {
        v: WallMessageSchemaVersion,
        items: [{ id: 'abc', url: 42, hashtags: ['glacier'] }],
      };
      expect(validateMessageQueue(raw)).toBeNull();
    });

    it('returns null when url exceeds 512 characters (oversized url)', () => {
      const oversizedUrl = 'https://example.com/' + 'a'.repeat(493);
      expect(oversizedUrl.length).toBe(513);
      const raw = {
        v: WallMessageSchemaVersion,
        items: [{ id: 'abc', url: oversizedUrl, hashtags: ['glacier'] }],
      };
      expect(validateMessageQueue(raw)).toBeNull();
    });

    it('accepts url with exactly 512 characters', () => {
      const exactUrl = 'https://example.com/' + 'a'.repeat(492);
      expect(exactUrl.length).toBe(512);
      const raw = {
        v: WallMessageSchemaVersion,
        items: [{ id: 'abc', url: exactUrl, hashtags: ['glacier'] }],
      };
      expect(validateMessageQueue(raw)).not.toBeNull();
    });
  });

  // ---- Per-item field validation — hashtags[] ----

  describe('item.hashtags[] validation', () => {
    it('returns null when hashtags is absent', () => {
      const raw = {
        v: WallMessageSchemaVersion,
        items: [{ id: 'abc', url: 'https://example.com' }],
      };
      expect(validateMessageQueue(raw)).toBeNull();
    });

    it('returns null when hashtags is not an array', () => {
      const raw = {
        v: WallMessageSchemaVersion,
        items: [{ id: 'abc', url: 'https://example.com', hashtags: 'glacier' }],
      };
      expect(validateMessageQueue(raw)).toBeNull();
    });

    it('returns null when hashtags is empty (empty hashtags[] — SR-PRUNE-08 invariant)', () => {
      const raw = {
        v: WallMessageSchemaVersion,
        items: [{ id: 'abc', url: 'https://example.com', hashtags: [] }],
      };
      expect(validateMessageQueue(raw)).toBeNull();
    });

    it('returns null when a hashtag entry is not a string', () => {
      const raw = {
        v: WallMessageSchemaVersion,
        items: [{ id: 'abc', url: 'https://example.com', hashtags: [123] }],
      };
      expect(validateMessageQueue(raw)).toBeNull();
    });

    it('returns null when a hashtag entry exceeds 100 characters', () => {
      const oversizedTag = 'a'.repeat(101);
      const raw = {
        v: WallMessageSchemaVersion,
        items: [{ id: 'abc', url: 'https://example.com', hashtags: [oversizedTag] }],
      };
      expect(validateMessageQueue(raw)).toBeNull();
    });

    it('accepts a hashtag with exactly 100 characters', () => {
      const exactTag = 'a'.repeat(100);
      const raw = {
        v: WallMessageSchemaVersion,
        items: [{ id: 'abc', url: 'https://example.com', hashtags: [exactTag] }],
      };
      expect(validateMessageQueue(raw)).not.toBeNull();
    });
  });

  // ---- SR-PRUNE-02: Prototype-pollution defence ----

  describe('SR-PRUNE-02: prototype-pollution defence', () => {
    it('returns null when __proto__ appears as a hashtag value', () => {
      const raw = {
        v: WallMessageSchemaVersion,
        items: [{ id: 'abc', url: 'https://example.com', hashtags: ['__proto__'] }],
      };
      expect(validateMessageQueue(raw)).toBeNull();
    });

    it('returns null when constructor appears as a hashtag value', () => {
      const raw = {
        v: WallMessageSchemaVersion,
        items: [{ id: 'abc', url: 'https://example.com', hashtags: ['constructor'] }],
      };
      expect(validateMessageQueue(raw)).toBeNull();
    });

    it('returns null when prototype appears as a hashtag value', () => {
      const raw = {
        v: WallMessageSchemaVersion,
        items: [{ id: 'abc', url: 'https://example.com', hashtags: ['prototype'] }],
      };
      expect(validateMessageQueue(raw)).toBeNull();
    });

    it('returns null when __proto__ appears as an item key (constructor in key)', () => {
      // Simulate a crafted object that has a suspicious key alongside valid fields.
      // Object.keys() enumeration must reject items with reserved prototype-pollution keys.
      const craftedItem: Record<string, unknown> = {
        id: 'abc',
        url: 'https://example.com',
        hashtags: ['glacier'],
        constructor: 'malicious', // reserved key
      };
      const raw = {
        v: WallMessageSchemaVersion,
        items: [craftedItem],
      };
      expect(validateMessageQueue(raw)).toBeNull();
    });

    it('returns null when item has __proto__ as an enumerable key', () => {
      const craftedItem: Record<string, unknown> = Object.create(null);
      craftedItem['id'] = 'abc';
      craftedItem['url'] = 'https://example.com';
      craftedItem['hashtags'] = ['glacier'];
      // Define __proto__ as an own enumerable property (not the actual prototype).
      Object.defineProperty(craftedItem, '__proto__', {
        value: 'malicious',
        enumerable: true,
        writable: true,
        configurable: true,
      });
      const raw = { v: WallMessageSchemaVersion, items: [craftedItem] };
      expect(validateMessageQueue(raw)).toBeNull();
    });

    it('returns null when item has prototype as an enumerable key', () => {
      const craftedItem: Record<string, unknown> = {
        id: 'abc',
        url: 'https://example.com',
        hashtags: ['glacier'],
        prototype: 'malicious',
      };
      const raw = { v: WallMessageSchemaVersion, items: [craftedItem] };
      expect(validateMessageQueue(raw)).toBeNull();
    });
  });

  // ---- Single invalid item discards entire queue ----

  describe('entire-queue discard on single invalid item', () => {
    it('discards the entire queue when one of multiple items is invalid', () => {
      const raw = {
        v: WallMessageSchemaVersion,
        items: [
          // valid item
          { id: 'good-1', url: 'https://example.com/1', hashtags: ['glacier'] },
          // invalid item — empty hashtags[]
          { id: 'bad-1', url: 'https://example.com/2', hashtags: [] },
        ],
      };
      // SR-PRUNE-01: entire queue is discarded, not just the bad item
      expect(validateMessageQueue(raw)).toBeNull();
    });

    it('discards queue when last item has an oversized id', () => {
      const raw = {
        v: WallMessageSchemaVersion,
        items: [
          { id: 'good-1', url: 'https://example.com/1', hashtags: ['glacier'] },
          { id: 'good-2', url: 'https://example.com/2', hashtags: ['foss'] },
          { id: 'a'.repeat(101), url: 'https://example.com/3', hashtags: ['linux'] },
        ],
      };
      expect(validateMessageQueue(raw)).toBeNull();
    });

    it('discards queue when one item has __proto__ in hashtags among multiple valid items', () => {
      const raw = {
        v: WallMessageSchemaVersion,
        items: [
          { id: 'good-1', url: 'https://example.com/1', hashtags: ['glacier'] },
          { id: 'bad-1', url: 'https://example.com/2', hashtags: ['__proto__'] },
          { id: 'good-2', url: 'https://example.com/3', hashtags: ['foss'] },
        ],
      };
      expect(validateMessageQueue(raw)).toBeNull();
    });
  });

  // ---- U-SEC-14: URL scheme allowlist (FIND-P3-SEC-7 / AC-11) ----

  describe('U-SEC-14: URL scheme allowlist in validateItem', () => {
    // Helper: build a minimal valid queue envelope for a single URL under test.
    function queueWithUrl(url: string) {
      return {
        v: WallMessageSchemaVersion,
        items: [{ id: 'x', url, hashtags: ['glacier'] }],
      };
    }

    it('U-SEC-14a: rejects javascript: URL', () => {
      // OWASP A03 / AC-11: javascript: scheme bypasses Angular URL sanitiser when
      // combined with bypassSecurityTrustResourceUrl — must be blocked at source.
      expect(validateMessageQueue(queueWithUrl('javascript:alert(1)'))).toBeNull();
    });

    it('U-SEC-14b: rejects data: URL', () => {
      // data: URLs can carry arbitrary HTML/JS payloads and must not reach
      // the iframe src (OWASP A03, AC-11).
      expect(validateMessageQueue(queueWithUrl('data:text/html,<script>alert(1)</script>'))).toBeNull();
    });

    it('U-SEC-14c: rejects vbscript: URL', () => {
      expect(validateMessageQueue(queueWithUrl('vbscript:msgbox(1)'))).toBeNull();
    });

    it('U-SEC-14d: accepts https: URL', () => {
      expect(validateMessageQueue(queueWithUrl('https://mastodon.social/@alice/1'))).not.toBeNull();
    });

    it('U-SEC-14e: accepts http: URL', () => {
      // http: may appear in dev/test Mastodon instances without TLS.
      expect(validateMessageQueue(queueWithUrl('http://mastodon.local/@alice/1'))).not.toBeNull();
    });

    it('U-SEC-14f: rejects malformed URL (not parseable by new URL())', () => {
      expect(validateMessageQueue(queueWithUrl('not a url at all'))).toBeNull();
    });

    it('U-SEC-14g: rejects URL with no scheme', () => {
      expect(validateMessageQueue(queueWithUrl('//example.com/path'))).toBeNull();
    });
  });
});

// ---- FIND-P3-SEC-4: validateHashtagsList ----

describe('validateHashtagsList', () => {
  // OWASP A03 / FIND-P3-SEC-4: localStorage is attacker-controlled when XSS or
  // a compromised browser extension is present.  validateHashtagsList guards
  // the hashtags[] array before any entry is forwarded to the STOMP server.

  it('returns null for non-array input (string)', () => {
    expect(validateHashtagsList('glacier')).toBeNull();
  });

  it('returns null for non-array input (number)', () => {
    expect(validateHashtagsList(42)).toBeNull();
  });

  it('returns null for non-array input (object)', () => {
    expect(validateHashtagsList({ 0: 'glacier' })).toBeNull();
  });

  it('returns null for non-array input (null)', () => {
    expect(validateHashtagsList(null)).toBeNull();
  });

  it('returns null when array length > 100', () => {
    // Upper-bound guard: no legitimate wall would have 101 simultaneous hashtags.
    const tooMany = Array.from({ length: 101 }, (_, i) => `tag${i}`);
    expect(validateHashtagsList(tooMany)).toBeNull();
  });

  it('returns null when any element is not a string', () => {
    expect(validateHashtagsList(['glacier', 42])).toBeNull();
  });

  it('returns null when any element is not a string (object)', () => {
    expect(validateHashtagsList(['glacier', { tag: 'foss' }])).toBeNull();
  });

  it('returns null for __proto__ (prototype-pollution defence)', () => {
    // SR-PRUNE-02 extended to the hashtags list key: __proto__ must never
    // be forwarded as a STOMP subscription request.
    expect(validateHashtagsList(['__proto__'])).toBeNull();
  });

  it('returns null for constructor (prototype-pollution defence)', () => {
    expect(validateHashtagsList(['constructor'])).toBeNull();
  });

  it('returns null for prototype (prototype-pollution defence)', () => {
    expect(validateHashtagsList(['prototype'])).toBeNull();
  });

  it('returns null for string with length 0 (empty string)', () => {
    expect(validateHashtagsList([''])).toBeNull();
  });

  it('returns null for string exceeding 100 chars', () => {
    const longTag = 'a'.repeat(101);
    expect(validateHashtagsList([longTag])).toBeNull();
  });

  it('returns null for string with injection characters (<)', () => {
    // Injection characters must be rejected — OWASP A03.
    expect(validateHashtagsList(['glacier<script>'])).toBeNull();
  });

  it('returns null for string with injection characters (>)', () => {
    expect(validateHashtagsList(['glacier>alert'])).toBeNull();
  });

  it('returns null for string with injection characters (/)', () => {
    expect(validateHashtagsList(['glacier/path'])).toBeNull();
  });

  it('returns null for string with injection characters (backslash)', () => {
    expect(validateHashtagsList(['glacier\\path'])).toBeNull();
  });

  it('returns null for string with injection characters (double quote)', () => {
    expect(validateHashtagsList(['glacier"inject'])).toBeNull();
  });

  it('returns null for string with injection characters (backtick)', () => {
    expect(validateHashtagsList(['glacier`inject'])).toBeNull();
  });

  it('returns null for string with space character', () => {
    // Spaces are not valid in a normalised Mastodon hashtag.
    expect(validateHashtagsList(['glacier foss'])).toBeNull();
  });

  it('returns the string[] when all elements are valid ASCII lowercase', () => {
    const input = ['glacier', 'foss', 'linux'];
    expect(validateHashtagsList(input)).toEqual(['glacier', 'foss', 'linux']);
  });

  it('returns the string[] for a valid single-element array', () => {
    expect(validateHashtagsList(['glacier'])).toEqual(['glacier']);
  });

  it('returns the string[] for a valid array with digits and underscores', () => {
    expect(validateHashtagsList(['tag_42', 'foss2024'])).toEqual(['tag_42', 'foss2024']);
  });

  it('returns an empty array for an empty input array', () => {
    // An empty list is valid — the user may have cleared all hashtags.
    expect(validateHashtagsList([])).toEqual([]);
  });

  it('accepts exactly 100 elements', () => {
    const maxList = Array.from({ length: 100 }, (_, i) => `tag${i}`);
    expect(validateHashtagsList(maxList)).toEqual(maxList);
  });

  it('accepts hashtag with exactly 100 characters', () => {
    const exact = 'a'.repeat(100);
    expect(validateHashtagsList([exact])).toEqual([exact]);
  });
});
