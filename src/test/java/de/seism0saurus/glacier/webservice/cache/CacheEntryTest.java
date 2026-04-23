package de.seism0saurus.glacier.webservice.cache;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the {@link CacheEntry} record.
 */
class CacheEntryTest {

    @Test
    void record_created_preservesAllFields() {
        CacheEntry entry = new CacheEntry(EventType.CREATED, "status-1", "https://ex.com/1/embed", null, 42L);

        assertThat(entry.type()).isEqualTo(EventType.CREATED);
        assertThat(entry.statusId()).isEqualTo("status-1");
        assertThat(entry.url()).isEqualTo("https://ex.com/1/embed");
        assertThat(entry.editedAt()).isNull();
        assertThat(entry.sequence()).isEqualTo(42L);
    }

    @Test
    void record_updated_preservesEditedAt() {
        CacheEntry entry = new CacheEntry(EventType.UPDATED, "status-2", "https://ex.com/2/embed", "2026-04-21T10:00:00Z", 7L);

        assertThat(entry.type()).isEqualTo(EventType.UPDATED);
        assertThat(entry.editedAt()).isEqualTo("2026-04-21T10:00:00Z");
    }

    @Test
    void record_deleted_urlAndEditedAtAreNull() {
        CacheEntry entry = new CacheEntry(EventType.DELETED, "status-3", null, null, 1L);

        assertThat(entry.type()).isEqualTo(EventType.DELETED);
        assertThat(entry.url()).isNull();
        assertThat(entry.editedAt()).isNull();
    }

    @Test
    void eventType_allThreeValues_exist() {
        assertThat(EventType.values()).containsExactlyInAnyOrder(
                EventType.CREATED, EventType.UPDATED, EventType.DELETED);
    }

    @Test
    void record_equalityBasedOnAllFields() {
        CacheEntry a = new CacheEntry(EventType.CREATED, "id", "url", null, 1L);
        CacheEntry b = new CacheEntry(EventType.CREATED, "id", "url", null, 1L);
        CacheEntry c = new CacheEntry(EventType.CREATED, "id", "url", null, 2L);

        assertThat(a).isEqualTo(b);
        assertThat(a).isNotEqualTo(c);
    }
}
