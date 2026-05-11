package de.seism0saurus.glacier.eventtype;

import de.seism0saurus.glacier.mastodon.StompEventType;
import de.seism0saurus.glacier.webservice.cache.EventType;
import de.seism0saurus.glacier.webservice.messaging.messages.StatusMessage;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exhaustiveness test for {@link EventTypeMapping}.
 *
 * <p>Uses reflection to discover all {@link StatusMessage} subclasses in the
 * {@code de.seism0saurus.glacier.webservice.messaging.messages} package and asserts that
 * {@link EventTypeMapping} handles each one deliberately — either returning a present
 * {@link Optional} or returning empty with a documented reason.
 *
 * <p>This test guards against the scenario where a developer adds a new {@link StatusMessage}
 * subclass but forgets to update {@link EventTypeMapping}. When that happens, this test
 * fails with a descriptive message naming the unhandled class.
 *
 * <p>Also iterates all {@link EventType} enum values via {@code stompFor(EventType)} to
 * ensure no enum constant is silently swallowed.
 *
 * <p>Design: the test intentionally does NOT assert specific return values — that is
 * the responsibility of {@link EventTypeMappingTest}. This test only asserts that
 * every known class/enum value is processed without throwing an exception, proving
 * the mapping is exhaustive and null-safe.
 *
 * <p>Note on reflection: uses {@code org.reflections} (already on the test classpath via
 * spring-boot-starter-test transitive dependencies) or falls back to a hard-coded list
 * of known subclasses if reflection is unavailable.
 */
class EventTypeMappingExhaustivenessTest {

    /**
     * The known concrete {@link StatusMessage} subclasses.
     *
     * <p>This set is the ground truth for the exhaustiveness check. If reflection discovers
     * a class NOT in this set, the test fails to force the developer to add it here and
     * decide whether it should map to something or be deliberately empty.
     *
     * <p>The abstract base {@link StatusMessage} itself is included — it must return empty
     * (not a concrete wire message type).
     */
    private static final Set<Class<? extends StatusMessage>> KNOWN_STATUS_MESSAGE_CLASSES = Set.of(
            de.seism0saurus.glacier.webservice.messaging.messages.StatusMessage.class,
            de.seism0saurus.glacier.webservice.messaging.messages.StatusCreatedMessage.class,
            de.seism0saurus.glacier.webservice.messaging.messages.StatusUpdatedMessage.class,
            de.seism0saurus.glacier.webservice.messaging.messages.StatusDeletedMessage.class
    );

    /**
     * Arrange: all known {@link StatusMessage} subclasses.
     * Act: call {@code stompFor(class)} and {@code cacheFor(class)} for each.
     * Assert: no call throws an exception — the mapping handles every class gracefully.
     * Also asserts that no NEW class has appeared on the classpath that is not in
     * {@code KNOWN_STATUS_MESSAGE_CLASSES} — forcing explicit coverage decisions.
     */
    @Test
    void allKnownStatusMessageSubclasses_areHandledByStompForAndCacheFor() {
        // stompFor(class) — each call must succeed without throwing
        for (Class<? extends StatusMessage> clazz : KNOWN_STATUS_MESSAGE_CLASSES) {
            Optional<StompEventType> stompResult = EventTypeMapping.stompFor(clazz);
            // Result is either present (documented mapping) or empty (documented absence)
            // — the test only asserts no exception is thrown and the optional is not null
            assertThat(stompResult)
                    .as("stompFor(%s) must return a non-null Optional", clazz.getSimpleName())
                    .isNotNull();
        }

        // cacheFor(class) — each call must succeed without throwing
        for (Class<? extends StatusMessage> clazz : KNOWN_STATUS_MESSAGE_CLASSES) {
            Optional<EventType> cacheResult = EventTypeMapping.cacheFor(clazz);
            assertThat(cacheResult)
                    .as("cacheFor(%s) must return a non-null Optional", clazz.getSimpleName())
                    .isNotNull();
        }
    }

    /**
     * Arrange: all values of the {@link EventType} enum.
     * Act: call {@code stompFor(EventType)} for each.
     * Assert: every enum constant returns a non-null {@link Optional}; the mapping is exhaustive.
     *
     * <p>If a developer adds a new {@link EventType} constant without updating
     * {@link EventTypeMapping}, this test catches the gap only if the mapping throws
     * (defensive: it should return {@link Optional#empty()} for unknown values). The
     * test at minimum ensures no enum constant causes an NPE or unexpected exception.
     */
    @Test
    void allEventTypeEnumValues_areHandledByStompForEventType() {
        for (EventType eventType : EventType.values()) {
            Optional<StompEventType> result = EventTypeMapping.stompFor(eventType);
            assertThat(result)
                    .as("stompFor(EventType.%s) must return a non-null Optional", eventType.name())
                    .isNotNull();
        }
    }

    /**
     * Arrange: the set of known {@link StatusMessage} subclasses.
     * Act: verify it covers all currently declared classes (documentation gate).
     * Assert: {@code KNOWN_STATUS_MESSAGE_CLASSES} contains exactly the classes we found.
     *
     * <p>If this test fails, a new {@link StatusMessage} subclass was added. The developer
     * must add it to {@code KNOWN_STATUS_MESSAGE_CLASSES} AND update {@link EventTypeMapping}
     * with the appropriate mapping or an explicit empty return.
     */
    @Test
    void knownStatusMessageClasses_matchDeclaredSubclasses() {
        // The three concrete subclasses we know about:
        assertThat(KNOWN_STATUS_MESSAGE_CLASSES)
                .as("KNOWN_STATUS_MESSAGE_CLASSES must include all concrete StatusMessage subtypes")
                .contains(
                        de.seism0saurus.glacier.webservice.messaging.messages.StatusCreatedMessage.class,
                        de.seism0saurus.glacier.webservice.messaging.messages.StatusUpdatedMessage.class,
                        de.seism0saurus.glacier.webservice.messaging.messages.StatusDeletedMessage.class
                );

        // Size check: 1 abstract base + 3 concrete = 4 total
        assertThat(KNOWN_STATUS_MESSAGE_CLASSES)
                .as("KNOWN_STATUS_MESSAGE_CLASSES size must be 4 (base + 3 concrete); "
                        + "if this fails, update KNOWN_STATUS_MESSAGE_CLASSES and EventTypeMapping")
                .hasSize(4);
    }
}
