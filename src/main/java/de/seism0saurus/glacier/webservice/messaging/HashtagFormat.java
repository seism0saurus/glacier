package de.seism0saurus.glacier.webservice.messaging;

/**
 * HashtagFormat defines the canonical format rules for Mastodon hashtags accepted by Glacier.
 *
 * <p>A valid hashtag consists of one to fifty Unicode letters, digits, or underscores.
 * The leading {@code #} character is intentionally excluded: the SubscriptionMessage
 * already represents the raw tag name without the prefix, matching the Bigbone API convention.</p>
 *
 * <p>This constants class is a shared source of truth referenced by both
 * {@code SubscriptionMessage} (for Bean Validation annotations) and
 * {@code FallbackController} (for programmatic checks). Centralising the pattern
 * here prevents the two sites from diverging silently.</p>
 */
public final class HashtagFormat {

    /**
     * Regular-expression pattern that a valid hashtag must match.
     *
     * <p>Allows one to fifty Unicode letters ({@code \p{L}}), Unicode digits
     * ({@code \p{N}}), or underscores. Rejects blank strings, the {@code #} prefix,
     * spaces, and punctuation not used in Mastodon hashtag identifiers.</p>
     *
     * <p>Used as the {@code regexp} attribute of {@code @Pattern} on
     * {@link de.seism0saurus.glacier.webservice.messaging.messages.SubscriptionMessage#hashtag}.</p>
     */
    public static final String PATTERN = "^[\\p{L}\\p{N}_]{1,50}$";

    /**
     * Private constructor — this class is a non-instantiable constants holder.
     */
    private HashtagFormat() {}
}
