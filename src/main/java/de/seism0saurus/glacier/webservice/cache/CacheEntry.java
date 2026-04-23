package de.seism0saurus.glacier.webservice.cache;

/**
 * An immutable snapshot of a single Mastodon streaming event stored in the ring buffer.
 *
 * <p>Each entry is uniquely identified within its {@code (principal, hashtag)} ring by
 * its {@code sequence} number, which is assigned monotonically by {@link PerTagRing#append}.
 * Clients use {@code sequence} as a cursor for the {@code since} parameter of
 * {@code GET /rest/messages}.
 *
 * <p>Field nullability:
 * <ul>
 *   <li>{@code url}      — populated for {@link EventType#CREATED} and {@link EventType#UPDATED};
 *                          {@code null} for {@link EventType#DELETED}.</li>
 *   <li>{@code editedAt} — populated for {@link EventType#UPDATED} only; always {@code null}
 *                          for the other two types.</li>
 * </ul>
 *
 * @param type      the kind of event that produced this entry
 * @param statusId  the Mastodon status ID; the dedup key used end-to-end (D-06)
 * @param url       the embed URL of the toot; nullable
 * @param editedAt  UTC ISO-8601 timestamp of the last edit; nullable (D-07)
 * @param sequence  monotonically increasing counter assigned by {@link PerTagRing}
 */
public record CacheEntry(
        EventType type,
        String statusId,
        String url,
        String editedAt,
        long sequence
) {
}
