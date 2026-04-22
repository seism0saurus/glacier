package de.seism0saurus.glacier.webservice.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.seism0saurus.glacier.webservice.messaging.messages.RejectionCode;
import de.seism0saurus.glacier.webservice.messaging.messages.SubscriptionRejection;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link SubscriptionRejection} and {@link RejectionCode}.
 */
class SubscriptionRejectionTest {

    @Test
    void builder_roundTrip_preservesAllFields() {
        SubscriptionRejection rejection = SubscriptionRejection.builder()
                .code(RejectionCode.CAP_EXCEEDED)
                .details(Map.of("limit", 10))
                .build();

        assertThat(rejection.getCode()).isEqualTo(RejectionCode.CAP_EXCEEDED);
        assertThat(rejection.getDetails()).containsEntry("limit", 10);
    }

    @Test
    void builder_allRejectionCodes_canBeBuilt() {
        for (RejectionCode code : RejectionCode.values()) {
            SubscriptionRejection r = SubscriptionRejection.builder()
                    .code(code)
                    .build();
            assertThat(r.getCode()).isEqualTo(code);
        }
    }

    @Test
    void jacksonSerialization_capExceeded_producesExpectedJson() throws Exception {
        SubscriptionRejection rejection = SubscriptionRejection.builder()
                .code(RejectionCode.CAP_EXCEEDED)
                .details(Map.of("limit", 10))
                .build();

        ObjectMapper mapper = new ObjectMapper();
        String json = mapper.writeValueAsString(rejection);

        assertThat(json).contains("CAP_EXCEEDED");
        assertThat(json).contains("limit");
        assertThat(json).contains("10");
    }

    @Test
    void jacksonDeserialization_roundTrip_preservesCodeAndDetails() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        SubscriptionRejection original = SubscriptionRejection.builder()
                .code(RejectionCode.INVALID_HASHTAG)
                .details(Map.of("reason", "format"))
                .build();

        String json = mapper.writeValueAsString(original);
        SubscriptionRejection restored = mapper.readValue(json, SubscriptionRejection.class);

        assertThat(restored.getCode()).isEqualTo(RejectionCode.INVALID_HASHTAG);
        assertThat(restored.getDetails()).containsKey("reason");
    }

    @Test
    void details_hygiene_noSensitiveKeysInAnyRejectionCode() {
        // D-12: details must never contain raw wallId, raw hashtag, or IP
        for (RejectionCode code : RejectionCode.values()) {
            SubscriptionRejection r = SubscriptionRejection.builder()
                    .code(code)
                    .details(Map.of("limit", 10))
                    .build();
            assertThat(r.getDetails()).doesNotContainKey("wallId");
            assertThat(r.getDetails()).doesNotContainKey("hashtag");
            assertThat(r.getDetails()).doesNotContainKey("ip");
        }
    }

    @Test
    void noArgsConstructor_setters_work() {
        SubscriptionRejection r = new SubscriptionRejection();
        r.setCode(RejectionCode.INTERNAL_ERROR);
        r.setDetails(Map.of());

        assertThat(r.getCode()).isEqualTo(RejectionCode.INTERNAL_ERROR);
    }
}
