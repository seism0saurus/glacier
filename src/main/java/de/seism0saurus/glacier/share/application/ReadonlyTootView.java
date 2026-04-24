package de.seism0saurus.glacier.share.application;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Readonly DTO for a single toot on the share wall.
 *
 * <p>All fields are safe for Angular interpolation ({@code {{ }}}) — no {@code [innerHTML]}.
 * URLs are scheme-validated ({@link SafeUrlValidator}) or rewritten to the image proxy.
 * Text content is Jsoup-extracted (text nodes only; no attribute values).
 * Avatar/emoji/media URLs are rewritten to the server-side image proxy.
 *
 * <p>Security: ADR-SHARE-03 (structured native rendering), ADR-SHARE-07 (image proxy).
 * OWASP A03 (XSS prevention via structural defence, not sanitizer allowlists).
 *
 * <p>If {@code bidiStripped} is {@code true}, the Angular component should surface
 * an info tooltip indicating that invisible bidi characters were neutralised.
 */
public record ReadonlyTootView(
        String id,
        String authorDisplayName,
        String authorAcct,
        String authorProfileUrl,
        String authorAvatarProxyUrl,
        Instant createdAt,
        String textContent,
        String spoilerText,
        boolean sensitive,
        boolean bidiStripped,
        String language,
        List<LinkRef> links,
        List<MentionRef> mentions,
        List<HashtagRef> hashtags,
        List<EmojiRef> customEmojis,
        List<MediaRef> media,
        Optional<PollView> poll
) {

    /** A hyperlink extracted from the toot content. URL is scheme-validated. */
    public record LinkRef(String displayText, String url) {}

    /** A mention extracted from the toot. */
    public record MentionRef(String acct, String profileUrl) {}

    /** A hashtag extracted from the toot. */
    public record HashtagRef(String tag, String searchUrl) {}

    /** A custom emoji from the instance. The src URL is rewritten to the image proxy. */
    public record EmojiRef(String shortcode, String proxyUrl) {}

    /**
     * A media attachment. The src URL is rewritten to the image proxy.
     * {@code altText} is the {@code description} field — Jsoup-cleaned.
     */
    public record MediaRef(String proxyUrl, String altText, String type) {}

    /** A poll option. */
    public record PollOption(String title, int votesCount) {}

    /** An embedded poll. */
    public record PollView(
            String id,
            boolean expired,
            boolean multiple,
            int votesCount,
            List<PollOption> options
    ) {}
}
