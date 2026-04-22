package de.seism0saurus.glacier.webservice.cache;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Wire DTO returned by {@code GET /rest/messages} when there are new events.
 *
 * <p>Fields:
 * <ul>
 *   <li>{@code hashtag}   — the hashtag this response covers</li>
 *   <li>{@code nextSince} — the sequence number the client should pass as
 *                           {@code ?since=} in its next poll (equals the highest
 *                           sequence in the current ring at snapshot time)</li>
 *   <li>{@code gap}       — {@code true} when the requested cursor predated the
 *                           oldest entry in the ring; the client should display
 *                           the gap-warning snackbar (D-16)</li>
 *   <li>{@code events}    — ordered list of new events; may be empty when the
 *                           caller requests with {@code since} at the head</li>
 * </ul>
 */
public record FallbackResponse(
        @JsonProperty("hashtag")   String hashtag,
        @JsonProperty("nextSince") long nextSince,
        @JsonProperty("gap")       boolean gap,
        @JsonProperty("events")    List<CacheEntryView> events
) {

    /**
     * The JSON-friendly projection of a {@link CacheEntry} that is safe to transmit
     * to the browser.
     *
     * <p>Field names mirror the existing {@code StatusCreatedMessage} /
     * {@code StatusUpdatedMessage} / {@code StatusDeletedMessage} DTOs so that the
     * Angular {@code SubscriptionService} can reuse its existing ingestion logic.
     *
     * @param type      event type string ({@code "CREATED"}, {@code "UPDATED"},
     *                  {@code "DELETED"})
     * @param statusId  the Mastodon status ID (dedup key)
     * @param url       embed URL; {@code null} for DELETED events
     * @param editedAt  UTC ISO-8601 edit timestamp; {@code null} unless UPDATED
     * @param sequence  the ring sequence assigned by {@link PerTagRing}
     */
    public record CacheEntryView(
            @JsonProperty("type")      String type,
            @JsonProperty("id")        String statusId,
            @JsonProperty("url")       String url,
            @JsonProperty("editedAt")  String editedAt,
            @JsonProperty("sequence")  long sequence
    ) {
        /**
         * Converts a {@link CacheEntry} to its wire representation.
         *
         * @param entry the ring entry to project
         * @return a {@code CacheEntryView} with all fields mapped
         */
        public static CacheEntryView from(final CacheEntry entry) {
            return new CacheEntryView(
                    entry.type().name(),
                    entry.statusId(),
                    entry.url(),
                    entry.editedAt(),
                    entry.sequence()
            );
        }
    }
}
