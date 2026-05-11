package de.seism0saurus.glacier.mastodon;

/**
 * STOMP wire-suffix vocabulary for toot fan-out events (F-6-INFO-2).
 *
 * <p>These three values are the only valid trailing segments of the STOMP topic path
 * {@code /topic/hashtags/{wallId}/{hashtag}/{suffix}} and the only valid {@code eventType}
 * arguments accepted by {@link de.seism0saurus.glacier.share.application.ShareViewStompRelay}.
 *
 * <p>This enum is intentionally separate from {@code webservice.cache.EventType} — they share
 * the same three concepts but belong to different bounded contexts (STOMP wire format vs.
 * cache event lifecycle). Unifying them would couple the two contexts for incidental shape
 * similarity (ADR-F6-INFO-2-A).
 */
public enum StompEventType {
    CREATION("creation"),
    MODIFICATION("modification"),
    DELETION("deletion");

    private final String suffix;

    StompEventType(String suffix) {
        this.suffix = suffix;
    }

    /** Returns the STOMP topic-path suffix and relay event-type string for this event. */
    public String suffix() {
        return suffix;
    }
}
