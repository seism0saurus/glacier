package de.seism0saurus.glacier.security;

import de.seism0saurus.glacier.mastodon.SubscriptionManager;
import de.seism0saurus.glacier.webservice.SubscriptionController;
import de.seism0saurus.glacier.webservice.messaging.messages.SubscriptionAckMessage;
import de.seism0saurus.glacier.webservice.messaging.messages.SubscriptionMessage;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;

import java.security.Principal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pentest finding (CLOSED — re-enabled after fix, ADR-PT-03 / SR-PT-01).
 *
 * <p>The STOMP path now validates {@code SubscriptionMessage.hashtag} via
 * programmatic Bean Validation inside {@link SubscriptionController#subscribe}.
 * {@link SubscriptionMessage} carries {@code @NotBlank} and
 * {@code @Pattern(regexp = "^[\\p{L}\\p{N}_]{1,50}$")} constraints. Invalid
 * hashtags receive a negative ack with {@link de.seism0saurus.glacier.webservice.messaging.messages.RejectionCode#INVALID_HASHTAG}
 * and the downstream {@link SubscriptionManager} is never invoked.</p>
 *
 * <p>Constructor adaptation: {@link SubscriptionController} now requires a
 * {@link jakarta.validation.Validator} as its second constructor argument.
 * This test uses {@link Validation#buildDefaultValidatorFactory()} to supply
 * the real production validator, ensuring the constraints on
 * {@link SubscriptionMessage} are actually evaluated (not mocked).</p>
 *
 * <p>Severity of the original finding: Medium. Now resolved.</p>
 */
class SubscriptionHashtagValidationFindingTest {

    @Test
    void subscribe_withMaliciouslyLongHashtag_returnsRejectionAckAndDoesNotInvokeManager() {
        // Setup — subscribe to a 5_000-char "hashtag" containing path separators.
        // Both shapes are pathological: length grows the topic-destination string
        // boundlessly and "/" segments would split the broker pattern.
        SubscriptionManager manager = mock(SubscriptionManager.class);
        // ADR-PT-03: use real Bean Validation so @Pattern/@NotBlank constraints fire.
        Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
        SubscriptionController controller = new SubscriptionController(manager, validator);

        SubscriptionMessage message = new SubscriptionMessage();
        message.setHashtag("/" + "x".repeat(5_000) + "/etc/passwd");

        Principal principal = () -> "wallId-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
        SimpMessageHeaderAccessor headerAccessor = mock(SimpMessageHeaderAccessor.class);
        when(headerAccessor.getUser()).thenReturn(principal);
        when(headerAccessor.getSessionId()).thenReturn("sess-1");

        // Act
        SubscriptionAckMessage ack = controller.subscribe(headerAccessor, message);

        // Post-fix invariant — SR-PT-01: invalid hashtag rejected with structured negative ack.
        assertThat(ack.isSubscribed())
                .as("malformed hashtag must be rejected before reaching SubscriptionManager")
                .isFalse();
        assertThat(ack.getRejection())
                .as("rejection block must be populated with INVALID_HASHTAG")
                .isNotNull();

        // SR-PT-01: the downstream Bigbone subscription must never be opened for invalid input.
        verify(manager, never()).subscribeToHashtag(anyString(), anyString());
    }
}
