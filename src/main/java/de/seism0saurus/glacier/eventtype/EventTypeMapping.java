package de.seism0saurus.glacier.eventtype;

import de.seism0saurus.glacier.mastodon.StompEventType;
import de.seism0saurus.glacier.webservice.cache.EventType;
import de.seism0saurus.glacier.webservice.messaging.messages.StatusCreatedMessage;
import de.seism0saurus.glacier.webservice.messaging.messages.StatusMessage;
import de.seism0saurus.glacier.webservice.messaging.messages.StatusUpdatedMessage;

import java.util.Optional;

/**
 * Single authority for cross-vocabulary translation between the three event-type vocabularies
 * used in the Glacier fan-out pipeline (ADR-P3A-4, ADR-F6-INFO-2-A).
 *
 * <p>The three vocabularies are:
 * <ol>
 *   <li>{@link StatusMessage} subclass hierarchy — STOMP message wire types; identifies the
 *       concrete Java type that carries the toot payload over the generic-message path.</li>
 *   <li>{@link StompEventType} — STOMP topic-path suffix vocabulary; the trailing segment of
 *       {@code /topic/hashtags/{wallId}/{hashtag}/{suffix}} that the frontend subscribes to.</li>
 *   <li>{@link EventType} — cache event-lifecycle vocabulary; used by {@code MessageCache}
 *       to classify entries in the ring buffer.</li>
 * </ol>
 *
 * <p>Design invariant: this class is the ONLY place in {@code src/main} that simultaneously
 * references both {@link StompEventType} and any of the message or cache vocabularies.
 * All other classes that need cross-vocabulary translation must call methods on this class.
 * The {@code EventTypeMappingExclusivityTest} ArchUnit gate enforces this constraint.
 *
 * <p>Deletion semantics: {@link StatusMessage} subclasses and {@link EventType#DELETED} /
 * {@link EventType#CREATED} / {@link EventType#UPDATED} are distinct concepts. Deletion
 * does not flow through the {@code sendMessage} path (which performs SSRF checks and cache
 * writes), so {@code stompFor(StatusDeletedMessage.class)} and
 * {@code cacheFor(StatusDeletedMessage.class)} return {@link Optional#empty()}. The
 * {@code stompFor(EventType.DELETED)} overload returns {@link StompEventType#DELETION} for
 * cache-replay scenarios.
 *
 * <p>Null-safe: all methods return {@link Optional#empty()} for {@code null} inputs rather
 * than throwing {@link NullPointerException}, because the callers in the streaming hot path
 * ({@link de.seism0saurus.glacier.mastodon.StompCallback}) already handle the empty case
 * with a log and early return.
 *
 * <p>This class is {@code final} with a private constructor — it is a pure static-method
 * helper, not a Spring bean. Do not make it a {@code @Component}.
 */
public final class EventTypeMapping {

    private EventTypeMapping() {
        // Static helper — not instantiable
    }

    /**
     * Maps a {@link StatusMessage} subclass to its {@link StompEventType}.
     *
     * <p>Used by the generic-message path in
     * {@link de.seism0saurus.glacier.mastodon.StompCallback#sendMessage} to resolve the
     * STOMP event type from the concrete Java class that carries the payload.
     *
     * <p>Returns {@link Optional#empty()} for:
     * <ul>
     *   <li>{@code null}</li>
     *   <li>Unknown or unregistered subclasses</li>
     *   <li>{@link de.seism0saurus.glacier.webservice.messaging.messages.StatusDeletedMessage}
     *       — deletion does not flow through {@code sendMessage}</li>
     * </ul>
     *
     * @param clazz the concrete {@link StatusMessage} subclass; may be {@code null}
     * @return the corresponding {@link StompEventType}, or empty if not applicable
     */
    public static Optional<StompEventType> stompFor(Class<? extends StatusMessage> clazz) {
        if (clazz == null) return Optional.empty();
        if (StatusCreatedMessage.class.equals(clazz)) return Optional.of(StompEventType.CREATION);
        if (StatusUpdatedMessage.class.equals(clazz)) return Optional.of(StompEventType.MODIFICATION);
        return Optional.empty();
    }

    /**
     * Maps a cache {@link EventType} to its {@link StompEventType}.
     *
     * <p>Used for cache-replay scenarios where a stored {@link EventType} must be translated
     * to the STOMP wire suffix. All three {@link EventType} values map to a non-empty result:
     * <ul>
     *   <li>{@link EventType#CREATED} &rarr; {@link StompEventType#CREATION}</li>
     *   <li>{@link EventType#UPDATED} &rarr; {@link StompEventType#MODIFICATION}</li>
     *   <li>{@link EventType#DELETED} &rarr; {@link StompEventType#DELETION}</li>
     * </ul>
     *
     * @param cacheType the cache event type; may be {@code null}
     * @return the corresponding {@link StompEventType}, or empty if {@code cacheType} is {@code null}
     */
    public static Optional<StompEventType> stompFor(EventType cacheType) {
        if (cacheType == null) return Optional.empty();
        return switch (cacheType) {
            case CREATED -> Optional.of(StompEventType.CREATION);
            case UPDATED -> Optional.of(StompEventType.MODIFICATION);
            case DELETED -> Optional.of(StompEventType.DELETION);
        };
    }

    /**
     * Maps a {@link StatusMessage} subclass to its cache {@link EventType}.
     *
     * <p>Used by the generic-message path to determine the {@link EventType} when writing
     * to the {@code MessageCache}. Returns {@link Optional#empty()} for deletion and unknown
     * classes because deletion does not go through the cache-write path of
     * {@code sendMessage}.
     *
     * @param clazz the concrete {@link StatusMessage} subclass; may be {@code null}
     * @return the corresponding {@link EventType}, or empty if not applicable
     */
    public static Optional<EventType> cacheFor(Class<? extends StatusMessage> clazz) {
        if (clazz == null) return Optional.empty();
        if (StatusCreatedMessage.class.equals(clazz)) return Optional.of(EventType.CREATED);
        if (StatusUpdatedMessage.class.equals(clazz)) return Optional.of(EventType.UPDATED);
        return Optional.empty();
    }
}
