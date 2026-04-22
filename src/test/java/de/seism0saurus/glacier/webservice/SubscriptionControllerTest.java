package de.seism0saurus.glacier.webservice;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import de.seism0saurus.glacier.mastodon.SubscriptionManager;
import de.seism0saurus.glacier.util.LogScrubber;
import de.seism0saurus.glacier.webservice.cache.CacheCapacityException;
import de.seism0saurus.glacier.webservice.messaging.messages.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;

import java.security.Principal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * The SubscriptionControllerTest class is responsible for testing the SubscriptionController class.
 * It includes test methods for subscribing to a hashtag, unsubscribing with a valid subscriptionId,
 * unsubscribing with an invalid subscriptionId, and unsubscribing without providing a subscriptionId.
 */
public class SubscriptionControllerTest {

    private SubscriptionManager subscriptionManager;

    private SubscriptionController subscriptionController;

    @BeforeEach
    public void setup() {
        this.subscriptionManager = mock(SubscriptionManager.class);
        this.subscriptionController = new SubscriptionController(this.subscriptionManager, 10);
    }

    /**
     * Subscribes to a hashtag with an existing principal and returns a SubscriptionAckMessage indicating the subscription status.
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
        assertThat(result.getRejection()).isNull();
    }

    /**
     * This method tests the behavior of the `subscribe` method in the `SubscriptionController` class when no existing principal is provided.
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
     * CAP_EXCEEDED path: emits a negative ack with rejection.code == CAP_EXCEEDED
     * and rejection.details.limit == 10 (D-11, D-12).
     * The exception must not propagate past @SendToUser.
     */
    @Test
    public void subscribe_cacheCapacityExceeded_returnsNegativeAckWithCapExceededRejection() {
        // Arrange
        SubscriptionMessage subscriptionMessage = new SubscriptionMessage();
        subscriptionMessage.setHashtag("TooManyHashtags");

        Principal principal = () -> "123456789";
        SimpMessageHeaderAccessor headerAccessor = mock(SimpMessageHeaderAccessor.class);
        when(headerAccessor.getUser()).thenReturn(principal);

        doThrow(new CacheCapacityException("cap exceeded"))
                .when(subscriptionManager).subscribeToHashtag("123456789", "TooManyHashtags");

        // Act
        SubscriptionAckMessage result = subscriptionController.subscribe(headerAccessor, subscriptionMessage);

        // Assert
        assertThat(result).isNotNull();
        assertThat(result.isSubscribed()).isFalse();
        assertThat(result.getPrincipal()).isEqualTo("123456789");
        assertThat(result.getHashtag()).isEqualTo("TooManyHashtags");
        assertThat(result.getRejection()).isNotNull();
        assertThat(result.getRejection().getCode()).isEqualTo(RejectionCode.CAP_EXCEEDED);
        assertThat(result.getRejection().getDetails()).containsEntry("limit", 10);
    }

    /**
     * CAP_EXCEEDED exception must not escape — a thrown exception would corrupt the STOMP frame.
     */
    @Test
    public void subscribe_cacheCapacityExceeded_exceptionDoesNotPropagateFromHandler() {
        // Arrange
        SubscriptionMessage subscriptionMessage = new SubscriptionMessage();
        subscriptionMessage.setHashtag("TooMany");

        Principal principal = () -> "abc";
        SimpMessageHeaderAccessor headerAccessor = mock(SimpMessageHeaderAccessor.class);
        when(headerAccessor.getUser()).thenReturn(principal);

        doThrow(new CacheCapacityException("cap")).when(subscriptionManager).subscribeToHashtag(any(), any());

        // Act — must not throw
        SubscriptionAckMessage result = subscriptionController.subscribe(headerAccessor, subscriptionMessage);

        // Assert — handler returned a message, exception did not propagate
        assertThat(result).isNotNull();
        assertThat(result.isSubscribed()).isFalse();
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
     * Test the failed unsubscribing from a subscription without a principal.
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
     * This method tests the behavior of unsubscribing when the principal is incorrect.
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
     * This method tests the behavior of unsubscribing when the hashtag is unknown.
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
    // FIX C — D-13: cache.capacity.exhausted AUDIT event must be emitted
    // -------------------------------------------------------------------------

    /**
     * When subscribeToHashtag throws {@link CacheCapacityException}, the controller must emit
     * a {@code cache.capacity.exhausted} event to the AUDIT logger at INFO level, carrying
     * a hashed principal (never raw UUID) and the configured limit (D-13, SR-8).
     *
     * <p>Test FAILS before FIX C: no AUDIT event is emitted at all.
     */
    @Test
    public void subscribe_cacheCapacityExceeded_emitsAuditEvent() {
        // Attach appender to AUDIT logger
        Logger auditLogger = (Logger) LoggerFactory.getLogger("AUDIT");
        ListAppender<ILoggingEvent> auditAppender = new ListAppender<>();
        auditAppender.start();
        auditLogger.addAppender(auditAppender);

        try {
            // Arrange — UUID-format principal so containsRawUuid can detect leakage
            String rawPrincipal = UUID.randomUUID().toString();

            SubscriptionMessage subscriptionMessage = new SubscriptionMessage();
            subscriptionMessage.setHashtag("TooBusy");

            Principal principal = () -> rawPrincipal;
            SimpMessageHeaderAccessor headerAccessor = mock(SimpMessageHeaderAccessor.class);
            when(headerAccessor.getUser()).thenReturn(principal);

            doThrow(new CacheCapacityException("cap exceeded"))
                    .when(subscriptionManager).subscribeToHashtag(rawPrincipal, "TooBusy");

            // Act
            subscriptionController.subscribe(headerAccessor, subscriptionMessage);

            // Assert — exactly one AUDIT event fired
            assertThat(auditAppender.list)
                    .as("AUDIT logger must emit exactly one cache.capacity.exhausted event")
                    .hasSize(1);

            ILoggingEvent auditEvent = auditAppender.list.get(0);
            String auditMsg = auditEvent.getFormattedMessage();

            // Must start with the canonical event name (D-13)
            assertThat(auditMsg)
                    .as("AUDIT event message must start with cache.capacity.exhausted")
                    .startsWith("cache.capacity.exhausted");

            // Must carry a hashed principal — not the raw UUID
            assertThat(LogScrubber.containsRawUuid(auditMsg))
                    .as("AUDIT event must not contain raw UUID principal.\nLine: %s", auditMsg)
                    .isFalse();

            // Must carry the configured limit (10 in setup)
            assertThat(auditMsg)
                    .as("AUDIT event must include the configured limit")
                    .contains("limit=10");

        } finally {
            auditLogger.detachAppender(auditAppender);
            auditAppender.stop();
        }
    }
}
