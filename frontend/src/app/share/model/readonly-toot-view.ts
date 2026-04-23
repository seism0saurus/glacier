/**
 * TypeScript mirror of the backend `ReadonlyTootView` record.
 *
 * Every string field has been server-side sanitised (Jsoup text extraction +
 * SafeUrlValidator).  The Angular template renders each field via {{ }}
 * interpolation only — NEVER via [innerHTML].
 *
 * Coordinate changes with `secure-tdd-implementer` who owns the Java record.
 * ADR-SHARE-03.
 */

/** A validated external URL (scheme ∈ {http,https}). */
export type SafeHttpUrl = string;

export interface LinkRef {
  /** Validated absolute URL — rendered via [attr.href] through safeUrlPipe. */
  url: SafeHttpUrl;
  /** Plain-text display text for the link (no HTML). */
  displayText: string;
}

export interface MentionRef {
  /** @user@server format — text only. */
  acct: string;
  /** Validated profile URL. */
  profileUrl: SafeHttpUrl;
}

export interface HashtagRef {
  /** Hashtag name without leading #. */
  tag: string;
  /** Validated hashtag URL on the source instance. */
  searchUrl: SafeHttpUrl;
}

export interface EmojiRef {
  /** :shortcode: */
  shortcode: string;
  /** Validated image source URL (proxied). */
  proxyUrl: SafeHttpUrl;
}

export type MediaType = 'image' | 'gifv' | 'video' | 'audio' | 'unknown';

export interface MediaRef {
  /** Validated, proxied media URL. */
  proxyUrl: SafeHttpUrl;
  type: MediaType;
  /** Accessibility alt text / description. May be empty — template warns when blank. */
  altText: string;
}

export interface PollOption {
  /** Poll option text — plain text only. */
  title: string;
  /** Vote count. */
  votesCount: number;
}

export interface PollView {
  /** Poll ID. */
  id: string;
  options: PollOption[];
  multiple: boolean;
  expired: boolean;
  votesCount: number;
}

/**
 * Structured DTO for one toot in the readonly viewer.
 *
 * `bidiStripped` indicates the backend removed Unicode bidirectional override
 * characters from `textContent`.  The template shows an inline info badge
 * when true (UX plan §2, bidi stripping notice).
 */
export interface ReadonlyTootView {
  /** Mastodon status ID (opaque string, never used as HTML). */
  id: string;
  /** Author display name — plain text, no HTML tags. */
  authorDisplayName: string;
  /** @user@server — validated format, no HTML. */
  authorAcct: string;
  /** Validated author profile URL. */
  authorProfileUrl: SafeHttpUrl;
  /** Validated, proxied avatar URL. */
  authorAvatarProxyUrl: SafeHttpUrl;
  /** ISO-8601 string (matches Instant.toString()). */
  createdAt: string;
  /** Jsoup-extracted plain text body. Paragraph breaks represented as \n\n. */
  textContent: string;
  /** Content-warning / spoiler text — plain text. Empty string when no CW. */
  spoilerText: string;
  /** Whether the toot is marked sensitive (content-warning / NSFW). */
  sensitive: boolean;
  /** Whether the backend stripped bidi override characters from textContent. */
  bidiStripped: boolean;
  /** Inline link references extracted from the toot body. */
  links: LinkRef[];
  /** @mention references. */
  mentions: MentionRef[];
  /** #hashtag references. */
  hashtags: HashtagRef[];
  /** Custom emoji shortcodes used in the toot. */
  customEmojis: EmojiRef[];
  /** Media attachments (no auto-embed; rendered with controls). */
  media: MediaRef[];
  /** Poll attached to the toot (readonly). Null when no poll. */
  poll: PollView | null;
  /** BCP-47 language code for the lang= attribute. */
  language: string;
}

/** Response shape of GET /rest/share/{shareId}/catalog */
export interface ShareCatalog {
  shareId: string;
  /** The share-link lifecycle state. */
  state: 'active' | 'expired' | 'revoked';
  hashtags: string[];
  initialToots: ReadonlyTootView[];
  /** ISO-8601 expiry timestamp. */
  expiresAt: string;
}

/** Response shape of POST /rest/share-links */
export interface ShareLinkCreated {
  shareLinkId: string;
  /** ISO-8601 expiry timestamp. */
  expiresAt: string;
  readonlyUrl: string;
}

/** Entry in the list returned by GET /rest/share-links */
export interface ShareLinkEntry {
  shareLinkId: string;
  expiresAt: string;
  readonlyUrl: string;
}
