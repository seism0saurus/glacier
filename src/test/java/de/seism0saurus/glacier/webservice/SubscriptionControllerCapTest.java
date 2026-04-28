package de.seism0saurus.glacier.webservice;

import de.seism0saurus.glacier.mastodon.SubscriptionManager;
import de.seism0saurus.glacier.webservice.cache.CacheCapacityException;
import de.seism0saurus.glacier.webservice.messaging.messages.RejectionCode;
import de.seism0saurus.glacier.webservice.messaging.messages.SubscriptionAckMessage;
import de.seism0saurus.glacier.webservice.messaging.messages.SubscriptionMessage;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;

import java.security.Principal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Cap-enforcement unit tests for {@link SubscriptionController}.
 *
 * <p>Separated from {@link SubscriptionControllerTest} so the cap-focused tests are
 * easy to locate and extend when the cap policy changes.
 *
 * <p>Each test verifies that the STOMP handler gracefully converts a server-side
 * capacity rejection into a structured negative acknowledgement — the exception
 * must never propagate past the {@code @SendToUser} handler.
 */
class SubscriptionControllerCapTest {

    private static final int CAP = 10;

    private static Validator beanValidator;

    @BeforeAll
    static void setUpValidator() {
        beanValidator = Validation.buildDefaultValidatorFactory().getValidator();
    }

    private SubscriptionManager subscriptionManager;
    private SubscriptionController subscriptionController;

    @BeforeEach
    void setUp() {
        subscriptionManager = mock(SubscriptionManager.class);
        subscriptionController = new SubscriptionController(subscriptionManager, beanValidator, CAP);
    }

    // -----------------------------------------------------------------------
    // CAP_EXCEEDED — cache/hashtag cap hit
    // -----------------------------------------------------------------------

    /**
     * When {@code subscribeToHashtag} throws {@link CacheCapacityException}, the
     * controller must return a negative ack with {@code rejection.code == CAP_EXCEEDED}
     * and {@code rejection.details.limit} matching the configured cap.
     *
     * <p>Arrange: stubbed subscriptionManager throws for the requested hashtag.
     * <p>Act:     call {@code subscribe}.
     * <p>Assert:  negative ack with CAP_EXCEEDED rejection; exception does not propagate.
     */
    @Test
    void subscribe_returnsRejectionAck_whenCapExceeded() {
        // "overflowHashtag" is a valid hashtag string (letters only, no hyphens)
        SubscriptionMessage msg = new SubscriptionMessage();
        msg.setHashtag("overflowHashtag");

        Principal principal = () -> "wall-uuid-cap-test";
        SimpMessageHeaderAccessor accessor = mock(SimpMessageHeaderAccessor.class);
        when(accessor.getUser()).thenReturn(principal);

        doThrow(new CacheCapacityException("cap exceeded"))
                .when(subscriptionManager).subscribeToHashtag("wall-uuid-cap-test", "overflowHashtag");

        SubscriptionAckMessage result = subscriptionController.subscribe(accessor, msg);

        assertThat(result).isNotNull();
        assertThat(result.isSubscribed()).isFalse();
        assertThat(result.getRejection()).isNotNull();
        assertThat(result.getRejection().getCode()).isEqualTo(RejectionCode.CAP_EXCEEDED);
        assertThat(result.getRejection().getDetails()).containsEntry("limit", CAP);
    }

    /**
     * When the already-subscribed case occurs (subscriptionManager is idempotent and
     * returns successfully), the controller returns a positive ack — idempotent subscription
     * is treated as success.
     *
     * <p>This test documents the current production contract: there is no rejection for
     * duplicate subscriptions; the second subscribe is silently accepted.
     *
     * <p>Arrange: subscriptionManager does nothing on subscribe (idempotent).
     * <p>Act:     call {@code subscribe} with the same hashtag twice.
     * <p>Assert:  both calls return {@code isSubscribed = true}.
     */
    @Test
    void subscribe_returnsPositiveAck_whenAlreadySubscribed() {
        // "sameHashtag" is a valid hashtag string (letters only, no hyphens)
        SubscriptionMessage msg = new SubscriptionMessage();
        msg.setHashtag("sameHashtag");

        Principal principal = () -> "wall-uuid-dup-test";
        SimpMessageHeaderAccessor accessor = mock(SimpMessageHeaderAccessor.class);
        when(accessor.getUser()).thenReturn(principal);

        // First subscribe
        SubscriptionAckMessage first = subscriptionController.subscribe(accessor, msg);
        // Second subscribe — idempotent, no exception
        SubscriptionAckMessage second = subscriptionController.subscribe(accessor, msg);

        assertThat(first.isSubscribed()).isTrue();
        assertThat(second.isSubscribed()).isTrue();
        assertThat(second.getRejection()).isNull();
    }
}
