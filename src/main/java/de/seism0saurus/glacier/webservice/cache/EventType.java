package de.seism0saurus.glacier.webservice.cache;

/**
 * Classifies the Mastodon streaming event that produced a {@link CacheEntry}.
 *
 * <p>The three values map directly to the three STOMP destination suffixes used
 * in {@code /topic/hashtags/{principal}/{hashtag}/{suffix}}:
 * <ul>
 *   <li>{@code CREATED}  → suffix {@code creation}</li>
 *   <li>{@code UPDATED}  → suffix {@code modification}</li>
 *   <li>{@code DELETED}  → suffix {@code deletion}</li>
 * </ul>
 */
public enum EventType {

    /** A new toot appeared in the hashtag stream. */
    CREATED,

    /** An existing toot was edited. */
    UPDATED,

    /** A toot was deleted. */
    DELETED
}
