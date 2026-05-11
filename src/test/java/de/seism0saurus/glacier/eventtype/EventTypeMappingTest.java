package de.seism0saurus.glacier.eventtype;

import de.seism0saurus.glacier.mastodon.StompEventType;
import de.seism0saurus.glacier.webservice.cache.EventType;
import de.seism0saurus.glacier.webservice.messaging.messages.StatusCreatedMessage;
import de.seism0saurus.glacier.webservice.messaging.messages.StatusDeletedMessage;
import de.seism0saurus.glacier.webservice.messaging.messages.StatusMessage;
import de.seism0saurus.glacier.webservice.messaging.messages.StatusUpdatedMessage;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link EventTypeMapping} — the single authority that cross-translates between
 * the three event-type vocabularies (ADR-P3A-4, ADR-F6-INFO-2-A).
 *
 * <p>The three vocabularies are:
 * <ol>
 *   <li>{@link StatusMessage} subclass hierarchy — STOMP message wire types</li>
 *   <li>{@link StompEventType} — STOMP topic-path suffix vocabulary</li>
 *   <li>{@link EventType} — cache event-lifecycle vocabulary</li>
 * </ol>
 *
 * <p>Deletion ({@link StatusDeletedMessage}) does NOT flow through {@code sendMessage};
 * its {@code stompFor(class)} and {@code cacheFor(class)} must return {@link Optional#empty()}.
 */
class EventTypeMappingTest {

    // -------------------------------------------------------------------------
    // stompFor(Class<? extends StatusMessage>)
    // -------------------------------------------------------------------------

    /**
     * Arrange: {@link StatusCreatedMessage} class.
     * Act: {@code stompFor(class)}.
     * Assert: returns {@link StompEventType#CREATION}.
     */
    @Test
    void stompFor_class_statusCreated_returnsCreation() {
        Optional<StompEventType> result = EventTypeMapping.stompFor(StatusCreatedMessage.class);

        assertThat(result).contains(StompEventType.CREATION);
    }

    /**
     * Arrange: {@link StatusUpdatedMessage} class.
     * Act: {@code stompFor(class)}.
     * Assert: returns {@link StompEventType#MODIFICATION}.
     */
    @Test
    void stompFor_class_statusUpdated_returnsModification() {
        Optional<StompEventType> result = EventTypeMapping.stompFor(StatusUpdatedMessage.class);

        assertThat(result).contains(StompEventType.MODIFICATION);
    }

    /**
     * Arrange: {@link StatusDeletedMessage} class.
     * Act: {@code stompFor(class)}.
     * Assert: returns empty — deletion does not flow through {@code sendMessage}.
     */
    @Test
    void stompFor_class_statusDeleted_returnsEmpty() {
        Optional<StompEventType> result = EventTypeMapping.stompFor(StatusDeletedMessage.class);

        assertThat(result).isEmpty();
    }

    /**
     * Arrange: {@code null} class.
     * Act: {@code stompFor(null)}.
     * Assert: returns empty — null-safe contract.
     */
    @Test
    void stompFor_class_null_returnsEmpty() {
        Optional<StompEventType> result = EventTypeMapping.stompFor((Class<? extends StatusMessage>) null);

        assertThat(result).isEmpty();
    }

    /**
     * Arrange: abstract {@link StatusMessage} base class — unknown concrete type.
     * Act: {@code stompFor(StatusMessage.class)}.
     * Assert: returns empty — base class is not a known concrete message type.
     */
    @Test
    void stompFor_class_unknown_returnsEmpty() {
        Optional<StompEventType> result = EventTypeMapping.stompFor(StatusMessage.class);

        assertThat(result).isEmpty();
    }

    // -------------------------------------------------------------------------
    // stompFor(EventType)
    // -------------------------------------------------------------------------

    /**
     * Arrange: {@link EventType#CREATED}.
     * Act: {@code stompFor(EventType)}.
     * Assert: returns {@link StompEventType#CREATION}.
     */
    @Test
    void stompFor_eventType_created_returnsCreation() {
        Optional<StompEventType> result = EventTypeMapping.stompFor(EventType.CREATED);

        assertThat(result).contains(StompEventType.CREATION);
    }

    /**
     * Arrange: {@link EventType#UPDATED}.
     * Act: {@code stompFor(EventType)}.
     * Assert: returns {@link StompEventType#MODIFICATION}.
     */
    @Test
    void stompFor_eventType_updated_returnsModification() {
        Optional<StompEventType> result = EventTypeMapping.stompFor(EventType.UPDATED);

        assertThat(result).contains(StompEventType.MODIFICATION);
    }

    /**
     * Arrange: {@link EventType#DELETED}.
     * Act: {@code stompFor(EventType)}.
     * Assert: returns {@link StompEventType#DELETION}.
     *
     * <p>Note: this path exists for completeness on the cache→STOMP translation axis
     * (e.g. cache replay); deletion still does NOT flow through {@code sendMessage}.
     */
    @Test
    void stompFor_eventType_deleted_returnsDeletion() {
        Optional<StompEventType> result = EventTypeMapping.stompFor(EventType.DELETED);

        assertThat(result).contains(StompEventType.DELETION);
    }

    /**
     * Arrange: {@code null} EventType.
     * Act: {@code stompFor((EventType) null)}.
     * Assert: returns empty — null-safe contract.
     */
    @Test
    void stompFor_eventType_null_returnsEmpty() {
        Optional<StompEventType> result = EventTypeMapping.stompFor((EventType) null);

        assertThat(result).isEmpty();
    }

    // -------------------------------------------------------------------------
    // cacheFor(Class<? extends StatusMessage>)
    // -------------------------------------------------------------------------

    /**
     * Arrange: {@link StatusCreatedMessage} class.
     * Act: {@code cacheFor(class)}.
     * Assert: returns {@link EventType#CREATED}.
     */
    @Test
    void cacheFor_class_statusCreated_returnsCreated() {
        Optional<EventType> result = EventTypeMapping.cacheFor(StatusCreatedMessage.class);

        assertThat(result).contains(EventType.CREATED);
    }

    /**
     * Arrange: {@link StatusUpdatedMessage} class.
     * Act: {@code cacheFor(class)}.
     * Assert: returns {@link EventType#UPDATED}.
     */
    @Test
    void cacheFor_class_statusUpdated_returnsUpdated() {
        Optional<EventType> result = EventTypeMapping.cacheFor(StatusUpdatedMessage.class);

        assertThat(result).contains(EventType.UPDATED);
    }

    /**
     * Arrange: {@link StatusDeletedMessage} class.
     * Act: {@code cacheFor(class)}.
     * Assert: returns empty — deletion does not flow through {@code sendMessage} (cache write path).
     */
    @Test
    void cacheFor_class_statusDeleted_returnsEmpty() {
        Optional<EventType> result = EventTypeMapping.cacheFor(StatusDeletedMessage.class);

        assertThat(result).isEmpty();
    }

    /**
     * Arrange: {@code null} class.
     * Act: {@code cacheFor(null)}.
     * Assert: returns empty — null-safe contract.
     */
    @Test
    void cacheFor_class_null_returnsEmpty() {
        Optional<EventType> result = EventTypeMapping.cacheFor((Class<? extends StatusMessage>) null);

        assertThat(result).isEmpty();
    }
}
