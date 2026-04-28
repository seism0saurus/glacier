package de.seism0saurus.glacier.webservice;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import de.seism0saurus.glacier.mastodon.SubscriptionManager;
import de.seism0saurus.glacier.util.LogScrubber;
import de.seism0saurus.glacier.webservice.cache.CacheCapacityException;
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
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;

import java.security.Principal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
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
 *   <li>D-11 / D-12: {@link CacheCapacityException} is caught and returned as a
 *       {@link RejectionCode#CAP_EXCEEDED} negative ack.</li>
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

    private SubscriptionManager subscriptionManager;

    private SubscriptionController subscriptionController;

    @BeforeEach
    public void setup() {
        this.subscriptionManager = mock(SubscriptionManager.class);
        // Merged constructor: (SubscriptionManager, Validator, int maxHashtagsPerPrincipal)
        this.subscriptionController = new SubscriptionController(this.subscriptionManager, beanValidator, 10);
    }

    // -------------------------------------------------------------------------
    // Happy-path subscription tests
    // -------------------------------------------------------------------------

    /**
     * Subscribes to a valid hashtag with an existing principal and expects a positive ack.
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
        assertThat(result.getRejection()).isNull();
    }

    /**
     * Regression: valid hashtags still reach the SubscriptionManager after the
     * validation guard was added (ADR-PT-03).
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
     * When no principal is present, subscription is rejected with a negative ack.
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
    // D-11 / D-12: CAP_EXCEEDED path
    // -------------------------------------------------------------------------

    /**
     * CAP_EXCEEDED path: emits a negative ack with rejection.code == CAP_EXCEEDED
     * and rejection.details.limit == 10 (D-11, D-12).
     * The exception must not propagate past @SendToUser.
     */
    @Test
    public void subscribe_cacheCapacityExceeded_returnsNegativeAckWithCapExceededRejection() {
        SubscriptionMessage subscriptionMessage = new SubscriptionMessage();
        subscriptionMessage.setHashtag("TooManyHashtags");

        Principal principal = () -> "123456789";
        SimpMessageHeaderAccessor headerAccessor = mock(SimpMessageHeaderAccessor.class);
        when(headerAccessor.getUser()).thenReturn(principal);

        doThrow(new CacheCapacityException("cap exceeded"))
                .when(subscriptionManager).subscribeToHashtag("123456789", "TooManyHashtags");

        SubscriptionAckMessage result = subscriptionController.subscribe(headerAccessor, subscriptionMessage);

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
        SubscriptionMessage subscriptionMessage = new SubscriptionMessage();
        subscriptionMessage.setHashtag("TooMany");

        Principal principal = () -> "abc";
        SimpMessageHeaderAccessor headerAccessor = mock(SimpMessageHeaderAccessor.class);
        when(headerAccessor.getUser()).thenReturn(principal);

        doThrow(new CacheCapacityException("cap")).when(subscriptionManager).subscribeToHashtag(any(), any());

        SubscriptionAckMessage result = subscriptionController.subscribe(headerAccessor, subscriptionMessage);

        assertThat(result).isNotNull();
        assertThat(result.isSubscribed()).isFalse();
    }

    /**
     * When CacheCapacityException is thrown, the AUDIT logger must emit a
     * {@code cache.capacity.exhausted} event with a hashed principal (D-13, SR-8).
     */
    @Test
    public void subscribe_cacheCapacityExceeded_emitsAuditEvent() {
        Logger auditLogger = (Logger) LoggerFactory.getLogger("AUDIT");
        ListAppender<ILoggingEvent> auditAppender = new ListAppender<>();
        auditAppender.start();
        auditLogger.addAppender(auditAppender);

        try {
            String rawPrincipal = UUID.randomUUID().toString();

            SubscriptionMessage subscriptionMessage = new SubscriptionMessage();
            subscriptionMessage.setHashtag("TooBusy");

            Principal principal = () -> rawPrincipal;
            SimpMessageHeaderAccessor headerAccessor = mock(SimpMessageHeaderAccessor.class);
            when(headerAccessor.getUser()).thenReturn(principal);

            doThrow(new CacheCapacityException("cap exceeded"))
                    .when(subscriptionManager).subscribeToHashtag(rawPrincipal, "TooBusy");

            subscriptionController.subscribe(headerAccessor, subscriptionMessage);

            assertThat(auditAppender.list)
                    .as("AUDIT logger must emit exactly one cache.capacity.exhausted event")
                    .hasSize(1);

            ILoggingEvent auditEvent = auditAppender.list.get(0);
            String auditMsg = auditEvent.getFormattedMessage();

            assertThat(auditMsg)
                    .as("AUDIT event message must start with cache.capacity.exhausted")
                    .startsWith("cache.capacity.exhausted");

            assertThat(LogScrubber.containsRawUuid(auditMsg))
                    .as("AUDIT event must not contain raw UUID principal.\nLine: %s", auditMsg)
                    .isFalse();

            assertThat(auditMsg)
                    .as("AUDIT event must include the configured limit")
                    .contains("limit=10");

        } finally {
            auditLogger.detachAppender(auditAppender);
            auditAppender.stop();
        }
    }

    // -------------------------------------------------------------------------
    // SR-PT-01: invalid hashtag → negative ack with INVALID_HASHTAG code
    // -------------------------------------------------------------------------

    /**
     * SR-PT-01: blank hashtag must be rejected before reaching the SubscriptionManager.
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

    /**
     * SR-PT-01 / SR-PT-02 combined: assertThatCode variant.
     */
    @Test
    public void subscribe_whenSubscriptionManagerThrows_doesNotPropagateException_assertThatCode() {
        SubscriptionMessage subscriptionMessage = new SubscriptionMessage();
        subscriptionMessage.setHashtag("glacier");

        Principal principal = () -> "123456789";
        SimpMessageHeaderAccessor headerAccessor = mock(SimpMessageHeaderAccessor.class);
        when(headerAccessor.getUser()).thenReturn(principal);

        doThrow(new RuntimeException("Unexpected downstream failure"))
                .when(subscriptionManager)
                .subscribeToHashtag("123456789", "glacier");

        assertThatCode(() -> subscriptionController.subscribe(headerAccessor, subscriptionMessage))
                .as("subscribe() must not propagate exceptions from subscriptionManager")
                .doesNotThrowAnyException();
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
     * This method tests the behavior of unsubscribing when the principal is incorrect.
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
     * This method tests the behavior of unsubscribing when the hashtag is unknown.
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
