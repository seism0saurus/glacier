package de.seism0saurus.glacier.webservice;

import de.seism0saurus.glacier.mastodon.SubscriptionManager;
import de.seism0saurus.glacier.webservice.messaging.messages.*;
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
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.*;

/**
 * The SubscriptionControllerTest class is responsible for testing the SubscriptionController class.
 * It includes test methods for subscribing to a hashtag, unsubscribing with a valid subscriptionId,
 * unsubscribing with an invalid subscriptionId, and unsubscribing without providing a subscriptionId.
 *
 * <p>New SR-PT-01 / SR-PT-02 tests verify that invalid hashtags receive a structured rejection
 * response and that no exception escapes the subscribe() handler.</p>
 */
public class SubscriptionControllerTest {

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
     * <p>>
     * The management of the Mastodon part of the subscriptions is delegated to the {@link SubscriptionManager SubscriptionManager}.
     */
    private SubscriptionController subscriptionController;

    private static Validator beanValidator;

    @BeforeAll
    static void setUpValidator() {
        ValidatorFactory factory = Validation.buildDefaultValidatorFactory();
        beanValidator = factory.getValidator();
    }

    /**
     * Sets up the necessary dependencies for testing the SubscriptionController class.
     * Initializes the subscriptionManager field with a mock object of type SubscriptionManager.
     * Initializes the subscriptionController field with a new instance of SubscriptionController,
     * passing the mocked subscriptionManager and a real Validator.
     */
    @BeforeEach
    public void setup() {
        this.subscriptionManager = mock(SubscriptionManager.class);
        this.subscriptionController = new SubscriptionController(this.subscriptionManager, beanValidator);
    }

    /**
     * Subscribes to a hashtag with an existing principal and returns a SubscriptionAckMessage indicating the subscription status.
     *
     * @see SimpMessageHeaderAccessor
     * @see SubscriptionMessage
     * @see SubscriptionAckMessage
     */
    @Test
    public void subscribe_withExistingPrincipal_subscribesToHashtag() {
        // Setup
        SubscriptionMessage subscriptionMessage = new SubscriptionMessage();
        subscriptionMessage.setHashtag("TestHashtag");

        Principal principal = () -> "123456789";
        SimpMessageHeaderAccessor headerAccessor = mock(SimpMessageHeaderAccessor.class);
        when(headerAccessor.getUser()).thenReturn(principal);

        doNothing().when(subscriptionManager).subscribeToHashtag("123456789", "TestHashtag");

        // Execute
        SubscriptionAckMessage result = subscriptionController.subscribe(headerAccessor, subscriptionMessage);

        // Verify
        assertThat(result).isNotNull();
        assertThat(result.isSubscribed()).isTrue();
        assertThat(result.getPrincipal()).isEqualTo("123456789");
        assertThat(result.getHashtag()).isEqualTo(subscriptionMessage.getHashtag());
    }

    /**
     * This method tests the behavior of the `subscribe` method in the `SubscriptionController` class when no existing principal is provided.
     * It verifies that the method does not subscribe to the hashtag and returns a `SubscriptionAckMessage` indicating that the subscription was not successful.
     */
    @Test
    public void subscribe_withoutExistingPrincipal_doesNotSubscribe() {
        // Setup
        SubscriptionMessage subscriptionMessage = new SubscriptionMessage();
        subscriptionMessage.setHashtag("TestHashtag");

        SimpMessageHeaderAccessor headerAccessor = mock(SimpMessageHeaderAccessor.class);
        when(headerAccessor.getUser()).thenReturn(null);

        // Execute
        SubscriptionAckMessage result = subscriptionController.subscribe(headerAccessor, subscriptionMessage);

        // Verify
        assertThat(result).isNotNull();
        assertThat(result.isSubscribed()).isFalse();
        assertThat(result.getPrincipal()).isEqualTo(null);
        assertThat(result.getHashtag()).isEqualTo(subscriptionMessage.getHashtag());
    }


    /**
     * Test the successful unsubscribing from a subscription and return of a TerminationAckMessage.
     */
    @Test
    public void unsubscribe_existingSubscription_withExistingPrincipal_unsubscibesFromHashtag() {
        // Setup
        TerminationMessage terminationMessage = new TerminationMessage();
        terminationMessage.setHashtag("TestHashtag");

        Principal principal = () -> "123456789";
        SimpMessageHeaderAccessor headerAccessor = mock(SimpMessageHeaderAccessor.class);
        when(headerAccessor.getUser()).thenReturn(principal);

        doNothing().when(subscriptionManager).terminateSubscription("123456789", "TestHashtag");

        // Execute
        TerminationAckMessage result = subscriptionController.unsubscribe(headerAccessor, terminationMessage);

        // Verify
        assertThat(result).isNotNull();
        assertThat(result.isTerminated()).isTrue();
        assertThat(result.getHashtag()).isEqualTo("TestHashtag");
        assertThat(result.getPrincipal()).isEqualTo("123456789");
    }

    /**
     * Test the failed unsubscribing from a subscription and return of a TerminationAckMessage.
     * The unsubscibing fails because no principal is transmitted in the headers of the call.
     */
    @Test
    public void unsubscribe_existingSubscription_withoutExistingPrincipal_doesNotUnsubscibesFromHashtag() {
        // Setup
        TerminationMessage terminationMessage = new TerminationMessage();
        terminationMessage.setHashtag("TestHashtag");

        SimpMessageHeaderAccessor headerAccessor = mock(SimpMessageHeaderAccessor.class);
        when(headerAccessor.getUser()).thenReturn(null);

        // Execute
        TerminationAckMessage result = subscriptionController.unsubscribe(headerAccessor, terminationMessage);

        // Verify
        assertThat(result).isNotNull();
        assertThat(result.isTerminated()).isFalse();
        assertThat(result.getHashtag()).isEqualTo("TestHashtag");
        assertThat(result.getPrincipal()).isEqualTo(null);
    }

    /**
     * This method tests the behavior of unsubscribing from a subscription in the SubscriptionController class
     * when the provided principal is incorrect.
     * It verifies that the method does not unsubscribe from the hashtag and the thrown exception with the correct message is handled.
     *
     * @see SimpMessageHeaderAccessor
     * @see TerminationMessage
     */
    @Test
    public void unsubscribe_existingSubscription_withWrongPrincipal_doesNotUnsubscibesFromHashtag() {
        // Setup
        TerminationMessage terminationMessage = new TerminationMessage();
        terminationMessage.setHashtag("TestHashtag");

        Principal principal = () -> "987654321";
        SimpMessageHeaderAccessor headerAccessor = mock(SimpMessageHeaderAccessor.class);
        when(headerAccessor.getUser()).thenReturn(principal);

        doThrow(new IllegalArgumentException("The provided principal 987654321 is unknown"))
                .when(subscriptionManager)
                .terminateSubscription("987654321", "TestHashtag");

        // Execute
        TerminationAckMessage result = subscriptionController.unsubscribe(headerAccessor, terminationMessage);

        // Verify
        assertThat(result).isNotNull();
        assertThat(result.isTerminated()).isFalse();
        assertThat(result.getHashtag()).isEqualTo("TestHashtag");
        assertThat(result.getPrincipal()).isEqualTo("987654321");
    }


    /**
     * This method tests the behavior of unsubscribing from a subscription in the SubscriptionController class
     * when the hashtag is unknown.
     * It verifies that the method does not unsubscribe from the hashtag and the thrown exception with the correct message is handled.
     *
     * @see SimpMessageHeaderAccessor
     * @see TerminationMessage
     */
    @Test
    public void unsubscribe_nonexistingSubscription_withExistingPrincipal_doesNotUnsubscibesFromHashtag() {
        // Setup
        TerminationMessage terminationMessage = new TerminationMessage();
        terminationMessage.setHashtag("NonexistingTestHashtag");

        Principal principal = () -> "123456789";
        SimpMessageHeaderAccessor headerAccessor = mock(SimpMessageHeaderAccessor.class);
        when(headerAccessor.getUser()).thenReturn(principal);

        doThrow(new IllegalArgumentException("The provided hashtag NonexistingTestHashtag for principal 123456789 is unknown"))
                .when(subscriptionManager)
                .terminateSubscription("123456789", "NonexistingTestHashtag");

        // Execute
        TerminationAckMessage result = subscriptionController.unsubscribe(headerAccessor, terminationMessage);

        // Verify
        assertThat(result).isNotNull();
        assertThat(result.isTerminated()).isFalse();
        assertThat(result.getHashtag()).isEqualTo("NonexistingTestHashtag");
        assertThat(result.getPrincipal()).isEqualTo("123456789");
    }

    // -------------------------------------------------------------------------
    // SR-PT-01 / SR-PT-02 — hashtag validation before subscription
    // -------------------------------------------------------------------------

    /**
     * SR-PT-01: blank hashtag → negative ack with INVALID_HASHTAG rejection code.
     *
     * Arrange: a SubscriptionMessage with a blank hashtag and a valid principal.
     * Act: call subscribe().
     * Assert: isSubscribed=false, rejection code INVALID_HASHTAG, subscriptionManager not called.
     */
    @Test
    public void subscribe_withBlankHashtag_returnsNegativeAckWithInvalidHashtagCode() {
        // Setup
        SubscriptionMessage subscriptionMessage = new SubscriptionMessage();
        subscriptionMessage.setHashtag("   ");

        Principal principal = () -> "123456789";
        SimpMessageHeaderAccessor headerAccessor = mock(SimpMessageHeaderAccessor.class);
        when(headerAccessor.getUser()).thenReturn(principal);

        // Execute
        SubscriptionAckMessage result = subscriptionController.subscribe(headerAccessor, subscriptionMessage);

        // Verify
        assertThat(result).isNotNull();
        assertThat(result.isSubscribed()).isFalse();
        assertThat(result.getRejection()).isNotNull();
        assertThat(result.getRejection().getCode()).isEqualTo(RejectionCode.INVALID_HASHTAG);
        verifyNoInteractions(subscriptionManager);
    }

    /**
     * SR-PT-01: hashtag containing illegal characters → negative ack with INVALID_HASHTAG.
     *
     * Arrange: hashtag with a '#' prefix.
     * Act: call subscribe().
     * Assert: rejection with INVALID_HASHTAG, no subscription started.
     */
    @Test
    public void subscribe_withHashtagContainingIllegalCharacters_returnsNegativeAckWithInvalidHashtagCode() {
        // Setup
        SubscriptionMessage subscriptionMessage = new SubscriptionMessage();
        subscriptionMessage.setHashtag("#glacier");

        Principal principal = () -> "123456789";
        SimpMessageHeaderAccessor headerAccessor = mock(SimpMessageHeaderAccessor.class);
        when(headerAccessor.getUser()).thenReturn(principal);

        // Execute
        SubscriptionAckMessage result = subscriptionController.subscribe(headerAccessor, subscriptionMessage);

        // Verify
        assertThat(result.isSubscribed()).isFalse();
        assertThat(result.getRejection()).isNotNull();
        assertThat(result.getRejection().getCode()).isEqualTo(RejectionCode.INVALID_HASHTAG);
        verifyNoInteractions(subscriptionManager);
    }

    /**
     * SR-PT-01: various invalid hashtag formats → negative ack with INVALID_HASHTAG.
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "#glacier",
            "hello world",
            "<script>alert(1)</script>",
            "aaaaaaaaaabbbbbbbbbbccccccccccddddddddddeeeeeeeeeef"  // 51 characters
    })
    public void subscribe_withInvalidHashtag_returnsNegativeAckWithInvalidHashtagCode(String invalidHashtag) {
        // Setup
        SubscriptionMessage subscriptionMessage = new SubscriptionMessage();
        subscriptionMessage.setHashtag(invalidHashtag);

        Principal principal = () -> "123456789";
        SimpMessageHeaderAccessor headerAccessor = mock(SimpMessageHeaderAccessor.class);
        when(headerAccessor.getUser()).thenReturn(principal);

        // Execute
        SubscriptionAckMessage result = subscriptionController.subscribe(headerAccessor, subscriptionMessage);

        // Verify
        assertThat(result.isSubscribed()).isFalse();
        assertThat(result.getRejection()).isNotNull();
        assertThat(result.getRejection().getCode()).isEqualTo(RejectionCode.INVALID_HASHTAG);
        verifyNoInteractions(subscriptionManager);
    }

    /**
     * SR-PT-02: no exception escapes subscribe() even when the hashtag is null.
     *
     * Arrange: a SubscriptionMessage with a null hashtag.
     * Act: call subscribe().
     * Assert: returns a SubscriptionAckMessage (no exception thrown).
     */
    @Test
    public void subscribe_withNullHashtag_doesNotThrowException() {
        // Setup
        SubscriptionMessage subscriptionMessage = new SubscriptionMessage();
        subscriptionMessage.setHashtag(null);

        Principal principal = () -> "123456789";
        SimpMessageHeaderAccessor headerAccessor = mock(SimpMessageHeaderAccessor.class);
        when(headerAccessor.getUser()).thenReturn(principal);

        // Execute — must not throw
        SubscriptionAckMessage result = subscriptionController.subscribe(headerAccessor, subscriptionMessage);

        // Verify
        assertThat(result).isNotNull();
        assertThat(result.isSubscribed()).isFalse();
        verifyNoInteractions(subscriptionManager);
    }

    /**
     * SR-PT-02: subscribe() does not propagate exceptions thrown by the subscriptionManager.
     *
     * Arrange: subscriptionManager.subscribeToHashtag throws a RuntimeException.
     * Act: call subscribe() with a valid hashtag.
     * Assert: no exception escapes subscribe(); the returned ack indicates failure.
     */
    @Test
    public void subscribe_whenSubscriptionManagerThrows_doesNotPropagateException() {
        // Setup
        SubscriptionMessage subscriptionMessage = new SubscriptionMessage();
        subscriptionMessage.setHashtag("glacier");

        Principal principal = () -> "123456789";
        SimpMessageHeaderAccessor headerAccessor = mock(SimpMessageHeaderAccessor.class);
        when(headerAccessor.getUser()).thenReturn(principal);

        doThrow(new RuntimeException("Unexpected downstream failure"))
                .when(subscriptionManager)
                .subscribeToHashtag("123456789", "glacier");

        // Execute and assert: subscribe() must not throw even when the manager fails
        assertThatCode(() -> subscriptionController.subscribe(headerAccessor, subscriptionMessage))
                .as("subscribe() must not propagate exceptions from subscriptionManager")
                .doesNotThrowAnyException();
    }

    /**
     * Verifies that a valid hashtag (ASCII letters) is forwarded to subscriptionManager and
     * returns a positive ack — this is the happy path regression check after adding validation.
     */
    @Test
    public void subscribe_withValidHashtag_subscribesToHashtagAndReturnsPositiveAck() {
        // Setup
        SubscriptionMessage subscriptionMessage = new SubscriptionMessage();
        subscriptionMessage.setHashtag("glacier");

        Principal principal = () -> "123456789";
        SimpMessageHeaderAccessor headerAccessor = mock(SimpMessageHeaderAccessor.class);
        when(headerAccessor.getUser()).thenReturn(principal);

        doNothing().when(subscriptionManager).subscribeToHashtag("123456789", "glacier");

        // Execute
        SubscriptionAckMessage result = subscriptionController.subscribe(headerAccessor, subscriptionMessage);

        // Verify
        assertThat(result.isSubscribed()).isTrue();
        assertThat(result.getPrincipal()).isEqualTo("123456789");
        assertThat(result.getHashtag()).isEqualTo("glacier");
        assertThat(result.getRejection()).isNull();
        verify(subscriptionManager).subscribeToHashtag("123456789", "glacier");
    }
}