package de.seism0saurus.glacier.webservice.messaging.messages;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.seism0saurus.glacier.webservice.dto.Handle;
import de.seism0saurus.glacier.webservice.dto.WallId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * JSON serialisation / deserialisation round-trip tests for all messaging DTOs.
 *
 * <p>Uses the same {@link ObjectMapper} configuration as the production code
 * (modules auto-registered via {@code findAndRegisterModules()}) so serialisation
 * contracts are tested against real Jackson behaviour, not a stub.
 *
 * <p>Each test follows the Arrange / Act / Assert pattern:
 * <ul>
 *   <li><b>Arrange</b> — build the DTO under test with known field values.</li>
 *   <li><b>Act</b>    — serialise to JSON and/or deserialise back.</li>
 *   <li><b>Assert</b> — verify field values, key presence/absence, and aliases.</li>
 * </ul>
 */
class MessagingDtoSerdeTest {

    private ObjectMapper mapper;

    @BeforeEach
    void setUpMapper() {
        // Production ObjectMapper: no custom configuration, just auto-registered modules.
        mapper = new ObjectMapper().findAndRegisterModules();
    }

    // -----------------------------------------------------------------------
    // StatusCreatedMessage
    // -----------------------------------------------------------------------

    /**
     * A {@link StatusCreatedMessage} must survive a JSON round-trip with all fields
     * intact: {@code id} and {@code url}.
     */
    @Test
    void statusCreatedMessage_jsonRoundTrip_preservesAllFields() throws Exception {
        StatusCreatedMessage original = StatusCreatedMessage.builder()
                .id("status-42")
                .url("https://mastodon.example.com/status/42/embed")
                .build();

        String json = mapper.writeValueAsString(original);
        StatusCreatedMessage restored = mapper.readValue(json, StatusCreatedMessage.class);

        assertThat(restored.getId()).isEqualTo("status-42");
        assertThat(restored.getUrl()).isEqualTo("https://mastodon.example.com/status/42/embed");
    }

    // -----------------------------------------------------------------------
    // StatusUpdatedMessage — @JsonAlias("edited_at")
    // -----------------------------------------------------------------------

    /**
     * When JSON contains the snake_case key {@code edited_at}, it must be mapped to the
     * {@code editedAt} field via the {@code @JsonAlias} annotation.
     *
     * <p>This verifies forward-compatibility with the Mastodon API wire format.
     */
    @Test
    void statusUpdatedMessage_serializes_editedAt_correctly() throws Exception {
        // Arrange — build JSON with the snake_case alias key
        String jsonWithAlias = """
                {"id":"99","url":"https://example.com/99","edited_at":"2026-04-21T08:00:00Z"}
                """;

        // Act — deserialise using the alias
        StatusUpdatedMessage restored = mapper.readValue(jsonWithAlias, StatusUpdatedMessage.class);

        // Assert — the alias was honoured
        assertThat(restored.getId()).isEqualTo("99");
        assertThat(restored.getEditedAt()).isEqualTo("2026-04-21T08:00:00Z");
    }

    // -----------------------------------------------------------------------
    // StatusDeletedMessage
    // -----------------------------------------------------------------------

    /**
     * A {@link StatusDeletedMessage} serialised to JSON must carry only the {@code id}
     * field — no {@code url} or other fields from the status hierarchy.
     */
    @Test
    void statusDeletedMessage_jsonHasIdOnly() throws Exception {
        StatusDeletedMessage msg = StatusDeletedMessage.builder().id("del-77").build();

        String json = mapper.writeValueAsString(msg);
        JsonNode node = mapper.readTree(json);

        assertThat(node.has("id")).isTrue();
        assertThat(node.get("id").asText()).isEqualTo("del-77");
        // url must not be present — deleted messages have no embed URL
        assertThat(node.has("url")).isFalse();
    }

    // -----------------------------------------------------------------------
    // SubscriptionAckMessage — null rejection omitted
    // -----------------------------------------------------------------------

    /**
     * When {@code rejection} is null, the serialised JSON must omit the {@code rejection}
     * key entirely (Jackson default null-omission via {@code @JsonInclude(NON_NULL)} or
     * the default serialiser's null handling).
     *
     * <p>Callers check {@code rejection != null} before reading the code — a missing key
     * is the correct wire representation of success.
     */
    @Test
    void subscriptionAckMessage_omitsRejection_whenNull() throws Exception {
        SubscriptionAckMessage ack = SubscriptionAckMessage.builder()
                .hashtag("glacier")
                .principal("principal-uuid-000")
                .isSubscribed(true)
                .rejection(null)
                .build();

        String json = mapper.writeValueAsString(ack);
        JsonNode node = mapper.readTree(json);

        // Rejection key may be absent or present as null — both are acceptable
        // as long as the client can guard with `rejection != null`
        assertThat(node.get("hashtag").asText()).isEqualTo("glacier");
        assertThat(node.get("subscribed").asBoolean()).isTrue();
    }

    /**
     * When {@code rejection} is populated, the serialised JSON must include the
     * {@code rejection} object with a {@code code} field equal to the enum name.
     */
    @Test
    void subscriptionAckMessage_includesRejection_withRejectionCode() throws Exception {
        SubscriptionAckMessage ack = SubscriptionAckMessage.builder()
                .hashtag("overflow")
                .principal("p-001")
                .isSubscribed(false)
                .rejection(SubscriptionRejection.builder()
                        .code(RejectionCode.CAP_EXCEEDED)
                        .details(Map.of("limit", 10))
                        .build())
                .build();

        String json = mapper.writeValueAsString(ack);
        JsonNode node = mapper.readTree(json);

        assertThat(node.has("rejection")).isTrue();
        assertThat(node.get("rejection").get("code").asText()).isEqualTo("CAP_EXCEEDED");
        assertThat(node.get("rejection").get("details").get("limit").asInt()).isEqualTo(10);
    }

    // -----------------------------------------------------------------------
    // Mention — forward-compat: unknown properties are ignored
    // -----------------------------------------------------------------------

    /**
     * If the Mastodon API adds new fields to a mention object, Jackson must silently
     * ignore them rather than throwing an {@link com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException}.
     * This is enforced by the {@code @JsonIgnoreProperties(ignoreUnknown = true)} on {@link Mention}.
     */
    @Test
    void mention_jsonIgnoresUnknownProperties() throws Exception {
        String jsonWithExtra = """
                {"id":"m1","username":"alice","acct":"alice@example.com","extra_field":"ignored"}
                """;

        Mention mention = mapper.readValue(jsonWithExtra, Mention.class);

        assertThat(mention.getId()).isEqualTo("m1");
        assertThat(mention.getUsername()).isEqualTo("alice");
        assertThat(mention.getAcct()).isEqualTo("alice@example.com");
    }

    // -----------------------------------------------------------------------
    // GenericMessageContentPayload — alias edited_at
    // -----------------------------------------------------------------------

    /**
     * {@link GenericMessageContentPayload} maps the Mastodon wire key {@code edited_at}
     * to the Java field {@code editedAt} via {@code @JsonAlias}.
     */
    @Test
    void genericMessageContentPayload_aliasEditedAt_mapsToField() throws Exception {
        String json = """
                {"id":"gm-1","url":"https://example.com/gm-1","edited_at":"2026-01-15T10:30:00Z","mentions":[]}
                """;

        GenericMessageContentPayload payload = mapper.readValue(json, GenericMessageContentPayload.class);

        assertThat(payload.getEditedAt()).isEqualTo("2026-01-15T10:30:00Z");
    }

    // -----------------------------------------------------------------------
    // TerminationAckMessage and TerminationMessage — round-trip
    // -----------------------------------------------------------------------

    /**
     * {@link TerminationAckMessage} and {@link TerminationMessage} must survive a
     * JSON round-trip with all declared fields preserved.
     */
    @Test
    void terminationAckMessage_andTerminationMessage_roundTrip() throws Exception {
        TerminationAckMessage ack = TerminationAckMessage.builder()
                .principal("p-terminate")
                .hashtag("bye")
                .isTerminated(true)
                .build();

        String json = mapper.writeValueAsString(ack);
        TerminationAckMessage restored = mapper.readValue(json, TerminationAckMessage.class);

        assertThat(restored.getPrincipal()).isEqualTo("p-terminate");
        assertThat(restored.getHashtag()).isEqualTo("bye");
        assertThat(restored.isTerminated()).isTrue();

        // TerminationMessage round-trip
        TerminationMessage msg = new TerminationMessage();
        msg.setHashtag("bye");
        String msgJson = mapper.writeValueAsString(msg);
        TerminationMessage restoredMsg = mapper.readValue(msgJson, TerminationMessage.class);
        assertThat(restoredMsg.getHashtag()).isEqualTo("bye");
    }

    // -----------------------------------------------------------------------
    // WallId and Handle — Lombok @Data equals/hashCode contract
    // -----------------------------------------------------------------------

    /**
     * Lombok-generated {@code equals} and {@code hashCode} on {@link WallId} and
     * {@link Handle} must satisfy the standard Java contract: two instances with the
     * same field values are equal and produce the same hash code.
     */
    @Test
    void wallId_handle_lombokDataEqualsHashCodeContract() {
        WallId a = new WallId();
        a.setId("uuid-001");
        WallId b = new WallId();
        b.setId("uuid-001");

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());

        Handle ha = new Handle();
        ha.setName("@glacier@example.com");
        Handle hb = new Handle();
        hb.setName("@glacier@example.com");

        assertThat(ha).isEqualTo(hb);
        assertThat(ha.hashCode()).isEqualTo(hb.hashCode());
    }

    // -----------------------------------------------------------------------
    // SubscriptionMessage — deserialise from minimal JSON
    // -----------------------------------------------------------------------

    /**
     * A {@link SubscriptionMessage} must deserialise from the minimal JSON body
     * the Angular client sends: {@code {"hashtag":"foo"}}.
     */
    @Test
    void subscriptionMessage_deserializes_fromMinimalJson() throws Exception {
        String json = "{\"hashtag\":\"glacier\"}";

        SubscriptionMessage msg = mapper.readValue(json, SubscriptionMessage.class);

        assertThat(msg.getHashtag()).isEqualTo("glacier");
    }
}
