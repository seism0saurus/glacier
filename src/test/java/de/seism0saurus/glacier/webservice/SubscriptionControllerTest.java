package de.seism0saurus.glacier.webservice;

import de.seism0saurus.glacier.mastodon.SubscriptionManager;
import de.seism0saurus.glacier.webservice.messaging.messages.RejectionCode;
import de.seism0saurus.glacier.webservice.messaging.messages.SubscriptionAckMessage;
import de.seism0saurus.glacier.webservice.messaging.messages.SubscriptionMessage;
import de.seism0saurus.glacier.webservice.messaging.messages.TerminationAckMessage;
import de.seism0saurus.glacier.webservice.messaging.messages.TerminationMessage;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;

import java.security.Principal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link SubscriptionController}.
 *
 * <p>Covers the subscription and termination lifecycle as well as the security
 * guards added in ADR-PT-03:</p>
 * <ul>
 *   <li>SR-PT-01: invalid hashtags are rejected with {@link RejectionCode#INVALID_HASHTAG}
 *       before any Mastodon subscription is started.</li>
 *   <li>SR-PT-02: no exception must escape the {@code subscribe} handler.</li>
 * </ul>
 *
 * <p>A real {@link Validator} instance (not a mock) is used so that tests genuinely
 * exercise the Bean Validation annotations on {@link SubscriptionMessage#getHashtag()}.
 * No Spring context is required.</p>
 */
public class SubscriptionControllerTest {

    // -------------------------------------------------------------------------
    // Shared real Validator — built once for the whole test class
    // -------------------------------------------------------------------------

    private static Validator beanValidator;

    @BeforeAll
    static void setUpValidator() {
        ValidatorFactory factory = Validation.buildDefaultValidatorFactory();
        beanValidator = factory.getValidator();
    }

    // -------------------------------------------------------------------------
    // Per-test collaborators
    // -------------------------------------------------------------------------

    /**
     * The subscriptionManager variable represents an instance of the SubscriptionManager interface.
     * <p>
     * The manager handles subscriptions for hashtags on Mastodon.
     * You can use the subscriptionManager to subscribe to a hashtag or terminate a subscription with a given UUID.
     * The methods provided by the subscriptionManager are:
     * - subscribeToHashtag(final String principal, final String hashtag): Subscribes to a hashtag and returns the UUID of the subscription.
     * - terminateSubscription(final String principal, final String hashtag): Terminate a subscription with the given UUID.
     * - terminateAllSubscriptions(final String principal): Terminate all subscriptions for a given principal.
     * <p>
     * Please refer to the SubscriptionManager interface for more details on the available methods.
     */
    private SubscriptionManager subscriptionManager;

    /**
     * The SubscriptionController is responsible for the subscription management via WebSockets.
     * <p>
     * You can create or terminate a subscription for hashtags.
     * After the creation of a subscription the caller gets an acknowledgement with a subscription id.
     * With this id they can subscribe to message queues for toots with the given hashtag.
     * <p>
     * The management of the Mastodon part of the subscriptions is delegated to the {@link SubscriptionManager SubscriptionManager}.
     */
    private SubscriptionController subscriptionController;

    /**
     * Sets up the necessary dependencies for testing the SubscriptionController class.
     * Initializes the subscriptionManager field with a mock object of type SubscriptionManager.
     * Initializes the subscriptionController field with a new instance of SubscriptionController,
     * passing the mocked subscriptionManager and the shared real Validator.
     */
    @BeforeEach
    public void setup() {
        this.subscriptionManager = mock(SubscriptionManager.class);
        this.subscriptionController = new SubscriptionController(this.subscriptionManager, beanValidator);
    }

    // -------------------------------------------------------------------------
    // Happy-path subscription tests
    // -------------------------------------------------------------------------

    /**
     * Subscribes to a valid hashtag with an existing principal and expects a positive ack.
     *
     * <p>Arrange: a valid hashtag and a known principal.
     * Act: call subscribe.
     * Assert: ack is positive, principal and hashtag are reflected back.</p>
     *
     * @see SimpMessageHeaderAccessor
     * @see SubscriptionMessage
     * @see SubscriptionAckMessage
     */
    @Test
    public void subscribe_withExistingPrincipal_subscribesToHashtag() {
        SubscriptionMessage subscriptionMessage = new SubscriptionMessage();
        subscriptionMessage.setHashtag("TestHashtag");

        Principal principal = () -> "123456789";
        SimpMessageHeaderAccessor headerAccessor = mock(SimpMessageHeaderAccessor.class);
        when(headerAccessor.getUser()).thenReturn(principal);

        doNothing().when(subscriptionManager).subscribeToHashtag("123456789", "TestHashtag");

        SubscriptionAckMessage result = subscriptionController.subscribe(headerAccessor, subscriptionMessage);

        assertThat(result).isNotNull();
        assertThat(result.isSubscribed()).isTrue();
        assertThat(result.getPrincipal()).isEqualTo("123456789");
        assertThat(result.getHashtag()).isEqualTo(subscriptionMessage.getHashtag());
    }

    /**
     * Regression test: confirms valid hashtags still reach the SubscriptionManager after the
     * validation guard was added (ADR-PT-03).
     *
     * <p>Arrange: a valid hashtag "glacier" and a known principal.
     * Act: call subscribe.
     * Assert: SubscriptionManager.subscribeToHashtag is called exactly once and the ack is positive.</p>
     */
    @Test
    public void subscribe_withValidHashtag_subscribesToHashtagAndReturnsPositiveAck() {
        SubscriptionMessage subscriptionMessage = new SubscriptionMessage();
        subscriptionMessage.setHashtag("glacier");

        Principal principal = () -> "user-abc";
        SimpMessageHeaderAccessor headerAccessor = mock(SimpMessageHeaderAccessor.class);
        when(headerAccessor.getUser()).thenReturn(principal);

        SubscriptionAckMessage result = subscriptionController.subscribe(headerAccessor, subscriptionMessage);

        assertThat(result.isSubscribed()).isTrue();
        assertThat(result.getRejection()).isNull();
        verify(subscriptionManager, times(1)).subscribeToHashtag("user-abc", "glacier");
    }

    /**
     * This method tests the behavior of the {@code subscribe} method when no principal is provided.
     * It verifies that the method does not subscribe to the hashtag and returns a negative ack.
     */
    @Test
    public void subscribe_withoutExistingPrincipal_doesNotSubscribe() {
        SubscriptionMessage subscriptionMessage = new SubscriptionMessage();
        subscriptionMessage.setHashtag("TestHashtag");

        SimpMessageHeaderAccessor headerAccessor = mock(SimpMessageHeaderAccessor.class);
        when(headerAccessor.getUser()).thenReturn(null);

        SubscriptionAckMessage result = subscriptionController.subscribe(headerAccessor, subscriptionMessage);

        assertThat(result).isNotNull();
        assertThat(result.isSubscribed()).isFalse();
        assertThat(result.getPrincipal()).isEqualTo(null);
        assertThat(result.getHashtag()).isEqualTo(subscriptionMessage.getHashtag());
    }

    // -------------------------------------------------------------------------
    // SR-PT-01: invalid hashtag → negative ack with INVALID_HASHTAG code
    // -------------------------------------------------------------------------

    /**
     * SR-PT-01: blank hashtag must be rejected before reaching the SubscriptionManager.
     *
     * <p>Arrange: a SubscriptionMessage with a blank hashtag and a valid principal.
     * Act: call subscribe.
     * Assert: negative ack with {@link RejectionCode#INVALID_HASHTAG}; SubscriptionManager not called.</p>
     */
    @Test
    public void subscribe_withBlankHashtag_returnsNegativeAckWithInvalidHashtagCode() {
        SubscriptionMessage subscriptionMessage = new SubscriptionMessage();
        subscriptionMessage.setHashtag("   ");

        Principal principal = () -> "user-abc";
        SimpMessageHeaderAccessor headerAccessor = mock(SimpMessageHeaderAccessor.class);
        when(headerAccessor.getUser()).thenReturn(principal);

        SubscriptionAckMessage result = subscriptionController.subscribe(headerAccessor, subscriptionMessage);

        assertThat(result.isSubscribed()).isFalse();
        assertThat(result.getRejection()).isNotNull();
        assertThat(result.getRejection().getCode()).isEqualTo(RejectionCode.INVALID_HASHTAG);
        verifyNoInteractions(subscriptionManager);
    }

    /**
     * SR-PT-01: a hashtag with illegal characters must be rejected.
     *
     * <p>Arrange: a SubscriptionMessage with a hashtag containing a '#' prefix and a valid principal.
     * Act: call subscribe.
     * Assert: negative ack with {@link RejectionCode#INVALID_HASHTAG}; SubscriptionManager not called.</p>
     */
    @Test
    public void subscribe_withHashtagContainingIllegalCharacters_returnsNegativeAckWithInvalidHashtagCode() {
        SubscriptionMessage subscriptionMessage = new SubscriptionMessage();
        subscriptionMessage.setHashtag("#glacier");

        Principal principal = () -> "user-abc";
        SimpMessageHeaderAccessor headerAccessor = mock(SimpMessageHeaderAccessor.class);
        when(headerAccessor.getUser()).thenReturn(principal);

        SubscriptionAckMessage result = subscriptionController.subscribe(headerAccessor, subscriptionMessage);

        assertThat(result.isSubscribed()).isFalse();
        assertThat(result.getRejection()).isNotNull();
        assertThat(result.getRejection().getCode()).isEqualTo(RejectionCode.INVALID_HASHTAG);
        verifyNoInteractions(subscriptionManager);
    }

    /**
     * SR-PT-01 (parameterized): a range of invalid hashtag values are all rejected with
     * {@link RejectionCode#INVALID_HASHTAG}.
     *
     * <p>Arrange: a SubscriptionMessage with each of the listed invalid hashtags.
     * Act: call subscribe.
     * Assert: negative ack, INVALID_HASHTAG code, SubscriptionManager never called.</p>
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "",
            "   ",
            "#glacier",
            "hello world",
            "<script>alert(1)</script>",
            "'; DROP TABLE--",
            "../etc/passwd"
    })
    public void subscribe_withInvalidHashtag_returnsNegativeAckWithInvalidHashtagCode(String invalidHashtag) {
        SubscriptionMessage subscriptionMessage = new SubscriptionMessage();
        subscriptionMessage.setHashtag(invalidHashtag);

        Principal principal = () -> "user-abc";
        SimpMessageHeaderAccessor headerAccessor = mock(SimpMessageHeaderAccessor.class);
        when(headerAccessor.getUser()).thenReturn(principal);

        SubscriptionAckMessage result = subscriptionController.subscribe(headerAccessor, subscriptionMessage);

        assertThat(result.isSubscribed())
                .as("Expected isSubscribed=false for invalid hashtag: '%s'", invalidHashtag)
                .isFalse();
        assertThat(result.getRejection())
                .as("Expected rejection to be set for invalid hashtag: '%s'", invalidHashtag)
                .isNotNull();
        assertThat(result.getRejection().getCode())
                .as("Expected INVALID_HASHTAG code for: '%s'", invalidHashtag)
                .isEqualTo(RejectionCode.INVALID_HASHTAG);
        verifyNoInteractions(subscriptionManager);
    }

    // -------------------------------------------------------------------------
    // SR-PT-02: no exception must escape subscribe()
    // -------------------------------------------------------------------------

    /**
     * SR-PT-02: a null hashtag must not cause an exception to escape the handler.
     *
     * <p>Arrange: a SubscriptionMessage with a null hashtag and a valid principal.
     * Act: call subscribe inside assertDoesNotThrow.
     * Assert: no exception propagates.</p>
     */
    @Test
    public void subscribe_withNullHashtag_doesNotThrowException() {
        SubscriptionMessage subscriptionMessage = new SubscriptionMessage();
        subscriptionMessage.setHashtag(null);

        Principal principal = () -> "user-abc";
        SimpMessageHeaderAccessor headerAccessor = mock(SimpMessageHeaderAccessor.class);
        when(headerAccessor.getUser()).thenReturn(principal);

        assertDoesNotThrow(() -> subscriptionController.subscribe(headerAccessor, subscriptionMessage));
    }

    /**
     * SR-PT-02: an unexpected exception from the SubscriptionManager must not escape the handler.
     *
     * <p>Arrange: a valid hashtag and principal, but the SubscriptionManager throws a
     * {@link RuntimeException}.
     * Act: call subscribe inside assertDoesNotThrow.
     * Assert: no exception propagates; the returned ack is negative.</p>
     */
    @Test
    public void subscribe_whenSubscriptionManagerThrows_doesNotPropagateException() {
        SubscriptionMessage subscriptionMessage = new SubscriptionMessage();
        subscriptionMessage.setHashtag("glacier");

        Principal principal = () -> "user-abc";
        SimpMessageHeaderAccessor headerAccessor = mock(SimpMessageHeaderAccessor.class);
        when(headerAccessor.getUser()).thenReturn(principal);

        doThrow(new RuntimeException("Mastodon client failure"))
                .when(subscriptionManager).subscribeToHashtag("user-abc", "glacier");

        SubscriptionAckMessage result = assertDoesNotThrow(
                () -> subscriptionController.subscribe(headerAccessor, subscriptionMessage));

        assertThat(result.isSubscribed()).isFalse();
    }

    // -------------------------------------------------------------------------
    // Happy-path unsubscription tests
    // -------------------------------------------------------------------------

    /**
     * Test the successful unsubscribing from a subscription returns a positive TerminationAckMessage.
     */
    @Test
    public void unsubscribe_existingSubscription_withExistingPrincipal_unsubscibesFromHashtag() {
        TerminationMessage terminationMessage = new TerminationMessage();
        terminationMessage.setHashtag("TestHashtag");

        Principal principal = () -> "123456789";
        SimpMessageHeaderAccessor headerAccessor = mock(SimpMessageHeaderAccessor.class);
        when(headerAccessor.getUser()).thenReturn(principal);

        doNothing().when(subscriptionManager).terminateSubscription("123456789", "TestHashtag");

        TerminationAckMessage result = subscriptionController.unsubscribe(headerAccessor, terminationMessage);

        assertThat(result).isNotNull();
        assertThat(result.isTerminated()).isTrue();
        assertThat(result.getHashtag()).isEqualTo("TestHashtag");
        assertThat(result.getPrincipal()).isEqualTo("123456789");
    }

    /**
     * Test the failed unsubscribing from a subscription returns a negative TerminationAckMessage
     * when no principal is transmitted in the headers of the call.
     */
    @Test
    public void unsubscribe_existingSubscription_withoutExistingPrincipal_doesNotUnsubscibesFromHashtag() {
        TerminationMessage terminationMessage = new TerminationMessage();
        terminationMessage.setHashtag("TestHashtag");

        SimpMessageHeaderAccessor headerAccessor = mock(SimpMessageHeaderAccessor.class);
        when(headerAccessor.getUser()).thenReturn(null);

        TerminationAckMessage result = subscriptionController.unsubscribe(headerAccessor, terminationMessage);

        assertThat(result).isNotNull();
        assertThat(result.isTerminated()).isFalse();
        assertThat(result.getHashtag()).isEqualTo("TestHashtag");
        assertThat(result.getPrincipal()).isEqualTo(null);
    }

    /**
     * This method tests the behavior of unsubscribing from a subscription
     * when the provided principal is incorrect (unknown to the SubscriptionManager).
     *
     * @see SimpMessageHeaderAccessor
     * @see TerminationMessage
     */
    @Test
    public void unsubscribe_existingSubscription_withWrongPrincipal_doesNotUnsubscibesFromHashtag() {
        TerminationMessage terminationMessage = new TerminationMessage();
        terminationMessage.setHashtag("TestHashtag");

        Principal principal = () -> "987654321";
        SimpMessageHeaderAccessor headerAccessor = mock(SimpMessageHeaderAccessor.class);
        when(headerAccessor.getUser()).thenReturn(principal);

        doThrow(new IllegalArgumentException("The provided principal 987654321 is unknown"))
                .when(subscriptionManager)
                .terminateSubscription("987654321", "TestHashtag");

        TerminationAckMessage result = subscriptionController.unsubscribe(headerAccessor, terminationMessage);

        assertThat(result).isNotNull();
        assertThat(result.isTerminated()).isFalse();
        assertThat(result.getHashtag()).isEqualTo("TestHashtag");
        assertThat(result.getPrincipal()).isEqualTo("987654321");
    }

    /**
     * This method tests the behavior of unsubscribing from a subscription
     * when the hashtag is unknown to the SubscriptionManager.
     *
     * @see SimpMessageHeaderAccessor
     * @see TerminationMessage
     */
    @Test
    public void unsubscribe_nonexistingSubscription_withExistingPrincipal_doesNotUnsubscibesFromHashtag() {
        TerminationMessage terminationMessage = new TerminationMessage();
        terminationMessage.setHashtag("NonexistingTestHashtag");

        Principal principal = () -> "123456789";
        SimpMessageHeaderAccessor headerAccessor = mock(SimpMessageHeaderAccessor.class);
        when(headerAccessor.getUser()).thenReturn(principal);

        doThrow(new IllegalArgumentException("The provided hashtag NonexistingTestHashtag for principal 123456789 is unknown"))
                .when(subscriptionManager)
                .terminateSubscription("123456789", "NonexistingTestHashtag");

        TerminationAckMessage result = subscriptionController.unsubscribe(headerAccessor, terminationMessage);

        assertThat(result).isNotNull();
        assertThat(result.isTerminated()).isFalse();
        assertThat(result.getHashtag()).isEqualTo("NonexistingTestHashtag");
        assertThat(result.getPrincipal()).isEqualTo("123456789");
    }
}
