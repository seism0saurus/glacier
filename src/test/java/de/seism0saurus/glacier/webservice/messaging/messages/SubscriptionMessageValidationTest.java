package de.seism0saurus.glacier.webservice.messaging.messages;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the Bean Validation constraints on {@link SubscriptionMessage}.
 *
 * <p>SR-PT-03: verifies that the {@code hashtag} field carries {@code @NotBlank}
 * and {@code @Pattern} constraints that reject invalid input before it reaches
 * the Mastodon streaming layer.</p>
 *
 * <p>These tests exercise the validation annotations directly using a plain
 * {@link Validator} instance — no Spring context is required.</p>
 */
public class SubscriptionMessageValidationTest {

    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        ValidatorFactory factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    // -------------------------------------------------------------------------
    // Valid hashtags — should produce no violations
    // -------------------------------------------------------------------------

    /**
     * Arrange: a typical ASCII hashtag.
     * Act: validate.
     * Assert: no constraint violations.
     */
    @Test
    public void hashtag_simpleAsciiWord_passesValidation() {
        SubscriptionMessage message = messageWithHashtag("glacier");
        Set<ConstraintViolation<SubscriptionMessage>> violations = validator.validate(message);
        assertThat(violations).isEmpty();
    }

    /**
     * Arrange: hashtags with letters, digits, and underscores.
     * Act: validate each.
     * Assert: no violations for any of them.
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "Java",
            "java23",
            "spring_boot",
            "ABC",
            "test_123",
            "a",
            "A1_",
            "Unicodeétag"  // Unicode letter é
    })
    public void hashtag_validFormats_passesValidation(String hashtag) {
        SubscriptionMessage message = messageWithHashtag(hashtag);
        Set<ConstraintViolation<SubscriptionMessage>> violations = validator.validate(message);
        assertThat(violations)
                .as("Expected no violations for hashtag: %s", hashtag)
                .isEmpty();
    }

    /**
     * Arrange: a hashtag that is exactly 50 characters long (boundary value).
     * Act: validate.
     * Assert: no violations.
     */
    @Test
    public void hashtag_exactly50Characters_passesValidation() {
        String hashtag = "a".repeat(50);
        SubscriptionMessage message = messageWithHashtag(hashtag);
        Set<ConstraintViolation<SubscriptionMessage>> violations = validator.validate(message);
        assertThat(violations).isEmpty();
    }

    // -------------------------------------------------------------------------
    // Invalid hashtags — should produce violations (SR-PT-03)
    // -------------------------------------------------------------------------

    /**
     * SR-PT-03 (@NotBlank): blank hashtag.
     *
     * Arrange: a SubscriptionMessage with a blank hashtag.
     * Act: validate.
     * Assert: at least one constraint violation is reported on the hashtag field.
     */
    @Test
    public void hashtag_blank_failsValidation() {
        SubscriptionMessage message = messageWithHashtag("   ");
        Set<ConstraintViolation<SubscriptionMessage>> violations = validator.validate(message);
        assertThat(violations).isNotEmpty();
        assertThat(violations).anyMatch(v -> "hashtag".equals(v.getPropertyPath().toString()));
    }

    /**
     * SR-PT-03 (@NotBlank): null hashtag.
     *
     * Arrange: a SubscriptionMessage with a null hashtag.
     * Act: validate.
     * Assert: constraint violation on the hashtag field.
     */
    @Test
    public void hashtag_null_failsValidation() {
        SubscriptionMessage message = messageWithHashtag(null);
        Set<ConstraintViolation<SubscriptionMessage>> violations = validator.validate(message);
        assertThat(violations).isNotEmpty();
        assertThat(violations).anyMatch(v -> "hashtag".equals(v.getPropertyPath().toString()));
    }

    /**
     * SR-PT-03 (@Pattern): hashtag with a leading '#' character.
     *
     * Arrange: a SubscriptionMessage with "#glacier" as the hashtag value.
     * Act: validate.
     * Assert: @Pattern violation — the '#' prefix is forbidden in the raw tag name.
     */
    @Test
    public void hashtag_withLeadingHash_failsValidation() {
        SubscriptionMessage message = messageWithHashtag("#glacier");
        Set<ConstraintViolation<SubscriptionMessage>> violations = validator.validate(message);
        assertThat(violations).isNotEmpty();
        assertThat(violations).anyMatch(v -> "hashtag".equals(v.getPropertyPath().toString()));
    }

    /**
     * SR-PT-03 (@Pattern): hashtag containing a space.
     *
     * Arrange: a hashtag with embedded whitespace.
     * Act: validate.
     * Assert: @Pattern violation.
     */
    @Test
    public void hashtag_containsSpace_failsValidation() {
        SubscriptionMessage message = messageWithHashtag("hello world");
        Set<ConstraintViolation<SubscriptionMessage>> violations = validator.validate(message);
        assertThat(violations).isNotEmpty();
        assertThat(violations).anyMatch(v -> "hashtag".equals(v.getPropertyPath().toString()));
    }

    /**
     * SR-PT-03 (@Pattern): hashtag exceeding 50 characters.
     *
     * Arrange: a 51-character hashtag.
     * Act: validate.
     * Assert: @Pattern violation.
     */
    @Test
    public void hashtag_exceeds50Characters_failsValidation() {
        String hashtag = "a".repeat(51);
        SubscriptionMessage message = messageWithHashtag(hashtag);
        Set<ConstraintViolation<SubscriptionMessage>> violations = validator.validate(message);
        assertThat(violations).isNotEmpty();
        assertThat(violations).anyMatch(v -> "hashtag".equals(v.getPropertyPath().toString()));
    }

    /**
     * SR-PT-03 (@Pattern): hashtags with injection-relevant special characters.
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "<script>alert(1)</script>",
            "'; DROP TABLE--",
            "../etc/passwd",
            "hashtag with\nnewline",
            "tag null"
    })
    public void hashtag_injectionAttempts_failValidation(String maliciousHashtag) {
        SubscriptionMessage message = messageWithHashtag(maliciousHashtag);
        Set<ConstraintViolation<SubscriptionMessage>> violations = validator.validate(message);
        assertThat(violations)
                .as("Expected violations for malicious hashtag: %s", maliciousHashtag)
                .isNotEmpty();
    }

    // -------------------------------------------------------------------------
    // Helper
    // -------------------------------------------------------------------------

    private static SubscriptionMessage messageWithHashtag(String hashtag) {
        SubscriptionMessage message = new SubscriptionMessage();
        message.setHashtag(hashtag);
        return message;
    }
}
