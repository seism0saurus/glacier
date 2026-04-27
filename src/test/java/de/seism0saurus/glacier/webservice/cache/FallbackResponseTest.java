package de.seism0saurus.glacier.webservice.cache;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link FallbackResponse} and its nested {@link FallbackResponse.CacheEntryView}.
 *
 * <p>Verifies the API contract for the fallback polling endpoint
 * {@code GET /rest/messages}: key names, null handling, and type encoding in JSON.
 */
class FallbackResponseTest {

    private ObjectMapper mapper;

    @BeforeEach
    void setUpMapper() {
        mapper = new ObjectMapper().findAndRegisterModules();
    }

    // -----------------------------------------------------------------------
    // CacheEntryView.from — field mapping
    // -----------------------------------------------------------------------

    /**
     * {@code CacheEntryView.from} must map every field of a {@link CacheEntry} to the
     * corresponding record component in the view projection.
     *
     * <p>Arrange: a CREATED {@link CacheEntry} with all fields populated.
     * <p>Act:     call {@code CacheEntryView.from}.
     * <p>Assert:  all fields are mapped without loss.
     */
    @Test
    void from_mapsAllCacheEntryFields() {
        CacheEntry entry = new CacheEntry(EventType.CREATED, "s-001", "https://ex.com/s-001/embed", null, 17L);

        FallbackResponse.CacheEntryView view = FallbackResponse.CacheEntryView.from(entry);

        assertThat(view.type()).isEqualTo("CREATED");
        assertThat(view.statusId()).isEqualTo("s-001");
        assertThat(view.url()).isEqualTo("https://ex.com/s-001/embed");
        assertThat(view.editedAt()).isNull();
        assertThat(view.sequence()).isEqualTo(17L);
    }

    /**
     * For a DELETED event, {@code url} in the {@link CacheEntry} is null, and that null
     * must propagate to the view so the client can handle deleted-toot events correctly.
     *
     * <p>Arrange: a DELETED {@link CacheEntry} with null url.
     * <p>Act:     call {@code CacheEntryView.from}.
     * <p>Assert:  {@code url()} is null in the view.
     */
    @Test
    void from_handlesNullUrlForDeletedEvent() {
        CacheEntry deleted = new CacheEntry(EventType.DELETED, "del-42", null, null, 5L);

        FallbackResponse.CacheEntryView view = FallbackResponse.CacheEntryView.from(deleted);

        assertThat(view.type()).isEqualTo("DELETED");
        assertThat(view.statusId()).isEqualTo("del-42");
        assertThat(view.url()).isNull();
    }

    // -----------------------------------------------------------------------
    // FallbackResponse JSON shape
    // -----------------------------------------------------------------------

    /**
     * The JSON serialised form of a {@link FallbackResponse} must contain the keys
     * {@code hashtag}, {@code nextSince}, {@code gap}, and {@code events}, using the
     * exact names declared with {@code @JsonProperty}.
     *
     * <p>No extra keys should appear (such as the record's generated accessor names).
     */
    @Test
    void jsonShape_matchesExpectedKeys() throws Exception {
        FallbackResponse.CacheEntryView view = FallbackResponse.CacheEntryView.from(
                new CacheEntry(EventType.CREATED, "x", "https://ex.com/x/embed", null, 1L));
        FallbackResponse response = new FallbackResponse("glacier", 1L, false, List.of(view));

        String json = mapper.writeValueAsString(response);
        JsonNode node = mapper.readTree(json);

        assertThat(node.has("hashtag")).isTrue();
        assertThat(node.has("nextSince")).isTrue();
        assertThat(node.has("gap")).isTrue();
        assertThat(node.has("events")).isTrue();
    }

    /**
     * The {@code type} field in a serialised {@link FallbackResponse.CacheEntryView} must
     * be the string name of the {@link EventType} enum (e.g. {@code "CREATED"}), not an
     * integer ordinal or object.  Angular's ingestion logic performs a string comparison.
     *
     * <p>Arrange: a view of a CREATED entry.
     * <p>Act:     serialise to JSON.
     * <p>Assert:  {@code events[0].type} is a string equal to {@code "CREATED"}.
     */
    @Test
    void eventTypeIsString_notEnum_inJson() throws Exception {
        FallbackResponse.CacheEntryView view = FallbackResponse.CacheEntryView.from(
                new CacheEntry(EventType.CREATED, "e1", "https://ex.com/e1/embed", null, 2L));
        FallbackResponse response = new FallbackResponse("test", 2L, false, List.of(view));

        String json = mapper.writeValueAsString(response);
        JsonNode node = mapper.readTree(json);

        JsonNode typeNode = node.get("events").get(0).get("type");
        assertThat(typeNode.isTextual()).isTrue();
        assertThat(typeNode.asText()).isEqualTo("CREATED");
    }
}
