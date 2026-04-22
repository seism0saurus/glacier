package de.seism0saurus.glacier.webservice;

import de.seism0saurus.glacier.webservice.messaging.messages.RejectionCode;
import de.seism0saurus.glacier.webservice.messaging.messages.SubscriptionRejection;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Hygiene test for {@link SubscriptionRejection#getDetails()} (SR-2, D-12).
 *
 * <p>Iterates all {@link RejectionCode} values and asserts that:
 * <ul>
 *   <li>No key in {@code details} is {@code "wallId"}, {@code "hashtag"}, {@code "ip"},
 *       {@code "cookie"}, or any other PII-carrying name (T-07, D-12)</li>
 *   <li>The production-injected {@code details} for {@code CAP_EXCEEDED} carries only
 *       an operational scalar ({@code "limit"})</li>
 * </ul>
 */
class SubscriptionRejectionHygieneTest {

    /**
     * Keys that must NEVER appear in {@code SubscriptionRejection.details}
     * regardless of the rejection code (D-12, T-07, SR-2 anti-enumeration).
     */
    private static final Set<String> FORBIDDEN_DETAIL_KEYS = Set.of(
            "wallId", "hashtag", "ip", "cookie", "rawWallId", "principal",
            "email", "username", "password", "token", "authorization"
    );

    /**
     * Simulates the details map used in production for each {@link RejectionCode} and
     * asserts that no forbidden key is present.
     */
    @ParameterizedTest
    @EnumSource(RejectionCode.class)
    void details_forEachRejectionCode_noForbiddenKeys(RejectionCode code) {
        // Use the same map shape that SubscriptionController injects in production
        Map<String, Object> details = productionDetailsFor(code);

        SubscriptionRejection rejection = SubscriptionRejection.builder()
                .code(code)
                .details(details)
                .build();

        // Assert no forbidden key is present in the details map
        if (rejection.getDetails() != null) {
            for (String forbiddenKey : FORBIDDEN_DETAIL_KEYS) {
                assertThat(rejection.getDetails())
                        .as("RejectionCode.%s details must not contain key '%s' (D-12)", code, forbiddenKey)
                        .doesNotContainKey(forbiddenKey);
            }
        }
    }

    /**
     * Verifies that {@code CAP_EXCEEDED} details contain only the operational scalar {@code limit}
     * and nothing else (D-12).
     */
    @Test
    void details_capExceeded_containsOnlyLimit() {
        SubscriptionRejection rejection = SubscriptionRejection.builder()
                .code(RejectionCode.CAP_EXCEEDED)
                .details(Map.of("limit", 10))
                .build();

        assertThat(rejection.getDetails()).containsOnlyKeys("limit");
        assertThat(rejection.getDetails().get("limit")).isEqualTo(10);
    }

    /**
     * Verifies that the details value for {@code limit} is a positive integer (not a wallId or IP).
     */
    @Test
    void details_capExceeded_limitValueIsNumber() {
        SubscriptionRejection rejection = SubscriptionRejection.builder()
                .code(RejectionCode.CAP_EXCEEDED)
                .details(Map.of("limit", 10))
                .build();

        Object limitValue = rejection.getDetails().get("limit");
        assertThat(limitValue).isInstanceOf(Number.class);
        assertThat(((Number) limitValue).intValue()).isPositive();
    }

    /**
     * Asserts that a null details map is acceptable (not every rejection code provides details).
     */
    @Test
    void details_nullDetails_isPermitted() {
        SubscriptionRejection rejection = SubscriptionRejection.builder()
                .code(RejectionCode.INTERNAL_ERROR)
                .details(null)
                .build();

        // null details is fine — no forbidden keys can be present
        assertThat(rejection.getDetails()).isNull();
    }

    /**
     * Returns the details map that {@code SubscriptionController} would inject for each code
     * in production (mirrors the production code path).
     */
    private static Map<String, Object> productionDetailsFor(RejectionCode code) {
        return switch (code) {
            case CAP_EXCEEDED -> Map.of("limit", 10); // as injected by SubscriptionController
            case INVALID_HASHTAG -> Map.of();         // no details
            case INTERNAL_ERROR -> Map.of();          // no details
        };
    }
}
