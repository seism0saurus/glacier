package de.seism0saurus.glacier.util;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import de.seism0saurus.glacier.mastodon.StompCallback;
import de.seism0saurus.glacier.mastodon.SubscriptionManager;
import de.seism0saurus.glacier.mastodon.SubscriptionManagerImpl;
import de.seism0saurus.glacier.share.application.SafeUrlValidator;
import de.seism0saurus.glacier.webservice.SubscriptionController;
import de.seism0saurus.glacier.webservice.cache.CacheCapacityException;
import de.seism0saurus.glacier.webservice.cache.MessageCache;
import de.seism0saurus.glacier.webservice.messaging.SubscriptionListener;
import de.seism0saurus.glacier.webservice.messaging.messages.SubscriptionMessage;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageHeaders;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.socket.messaging.SessionConnectedEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;
import social.bigbone.MastodonClient;
import social.bigbone.api.entity.streaming.TechnicalEvent;

import java.security.Principal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Log hygiene test (FIX A, D-13, SR-8, OWASP A09).
 *
 * <p>For each production class that handles the wallId / principal, this test attaches a
 * {@link ListAppender} to that class's Logback {@link Logger}, invokes a representative
 * production method with a known UUID principal, and asserts that NO emitted log line
 * contains the raw UUID via {@link LogScrubber#containsRawUuid}.
 *
 * <p>Run status before fixes: FAIL — raw UUIDs leak on INFO-level happy paths.
 * Run status after fixes: PASS — only hashed 8-char prefixes appear in logs.
 */
class RawWallIdLogHygieneTest {

    /**
     * UUID principal used as a "known canary" — if this value appears verbatim in any log
     * line, the test fails.
     */
    static final String CANARY_UUID = "550e8400-e29b-41d4-a716-446655440000";

    /**
     * Permissive SSRF validator that accepts every URL — the focus of this test class is
     * log hygiene, not SSRF blocking. SSRF behaviour is covered by dedicated security tests.
     */
    private static final SafeUrlValidator PERMISSIVE_VALIDATOR =
            rawUrl -> java.util.Optional.of(java.net.URI.create(rawUrl));

    private static Validator beanValidator;

    @BeforeAll
    static void setUpValidator() {
        beanValidator = Validation.buildDefaultValidatorFactory().getValidator();
    }

    // Appenders attached during each test — detached in @AfterEach
    private ListAppender<ILoggingEvent> stompCallbackAppender;
    private ListAppender<ILoggingEvent> subscriptionManagerAppender;
    private ListAppender<ILoggingEvent> subscriptionControllerAppender;
    private ListAppender<ILoggingEvent> subscriptionListenerAppender;

    @BeforeEach
    void attachAppenders() {
        stompCallbackAppender = attachAppender(StompCallback.class);
        subscriptionManagerAppender = attachAppender(SubscriptionManagerImpl.class);
        subscriptionControllerAppender = attachAppender(SubscriptionController.class);
        subscriptionListenerAppender = attachAppender(SubscriptionListener.class);
    }

    @AfterEach
    void detachAppenders() {
        detach(StompCallback.class, stompCallbackAppender);
        detach(SubscriptionManagerImpl.class, subscriptionManagerAppender);
        detach(SubscriptionController.class, subscriptionControllerAppender);
        detach(SubscriptionListener.class, subscriptionListenerAppender);
    }

    // -------------------------------------------------------------------------
    // StompCallback — constructor log (line 104) + logEvent (line 363)
    // -------------------------------------------------------------------------

    /**
     * Constructing a StompCallback logs principal in the constructor — must not leak raw UUID.
     */
    @Test
    void stompCallback_constructor_doesNotLogRawPrincipalUuid() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        MessageCache messageCache = mock(MessageCache.class);
        SubscriptionManager subscriptionManager = mock(SubscriptionManager.class);

        // Act — constructor emits the INFO line
        new StompCallback(subscriptionManager, messageCache, null, restTemplate, PERMISSIVE_VALIDATOR,
                CANARY_UUID, "java", "glacier@example.com", "glacier.example.com");

        // Assert — no raw UUID in any log line
        assertNoRawUuid(stompCallbackAppender.list, "StompCallback constructor");
    }

    /**
     * logEvent helper fires on every event — must not leak raw UUID (line 363 pattern).
     */
    @Test
    void stompCallback_logEvent_doesNotLogRawPrincipalUuid() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        MessageCache messageCache = mock(MessageCache.class);
        SubscriptionManager subscriptionManager = mock(SubscriptionManager.class);

        // Trigger logEvent via a TechnicalEvent.Closed
        StompCallback callback = new StompCallback(subscriptionManager, messageCache, null, restTemplate, PERMISSIVE_VALIDATOR,
                CANARY_UUID, "java", "glacier@example.com", "glacier.example.com");
        // Reset appender after constructor — focus on logEvent logs
        stompCallbackAppender.list.clear();

        TechnicalEvent.Closed closed = mock(TechnicalEvent.Closed.class);
        callback.onEvent(closed);

        assertNoRawUuid(stompCallbackAppender.list, "StompCallback logEvent");
    }

    // -------------------------------------------------------------------------
    // SubscriptionManagerImpl — subscribeToHashtag logs (lines 124, 136, 139)
    // -------------------------------------------------------------------------

    /**
     * subscribeToHashtag logs at INFO with the principal — must not leak raw UUID.
     */
    @Test
    void subscriptionManagerImpl_subscribeToHashtag_doesNotLogRawPrincipalUuid() {
        MastodonClient client = mock(MastodonClient.class);
        when(client.streaming()).thenReturn(mock(social.bigbone.api.method.StreamingMethods.class));
        MessageCache messageCache = mock(MessageCache.class);
        RestTemplate restTemplate = mock(RestTemplate.class);

        SubscriptionManagerImpl manager = new SubscriptionManagerImpl(
                "example.com", "glacier.example.com", "glacier@example.com",
                client, messageCache, restTemplate, null, PERMISSIVE_VALIDATOR);

        // Act
        manager.subscribeToHashtag(CANARY_UUID, "java");

        // Assert
        assertNoRawUuid(subscriptionManagerAppender.list, "SubscriptionManagerImpl.subscribeToHashtag");
    }

    // -------------------------------------------------------------------------
    // SubscriptionController — subscribe logs (lines 89, 105 happy path, and cap-exceeded WARN)
    // -------------------------------------------------------------------------

    /**
     * subscribe happy-path logs principal at INFO — must not leak raw UUID.
     */
    @Test
    void subscriptionController_subscribe_happyPath_doesNotLogRawPrincipalUuid() {
        SubscriptionManager subscriptionManager = mock(SubscriptionManager.class);
        SubscriptionController controller = new SubscriptionController(subscriptionManager, beanValidator, 10);

        Principal principal = () -> CANARY_UUID;
        SimpMessageHeaderAccessor headerAccessor = mock(SimpMessageHeaderAccessor.class);
        when(headerAccessor.getUser()).thenReturn(principal);

        SubscriptionMessage msg = new SubscriptionMessage();
        msg.setHashtag("java");

        // Act
        controller.subscribe(headerAccessor, msg);

        // Assert
        assertNoRawUuid(subscriptionControllerAppender.list, "SubscriptionController.subscribe happy path");
    }

    /**
     * subscribe cap-exceeded path logs principal at WARN — must not leak raw UUID.
     */
    @Test
    void subscriptionController_subscribe_capExceeded_doesNotLogRawPrincipalUuid() {
        SubscriptionManager subscriptionManager = mock(SubscriptionManager.class);
        doThrow(new CacheCapacityException("cap exceeded"))
                .when(subscriptionManager).subscribeToHashtag(CANARY_UUID, "java");

        SubscriptionController controller = new SubscriptionController(subscriptionManager, beanValidator, 10);

        Principal principal = () -> CANARY_UUID;
        SimpMessageHeaderAccessor headerAccessor = mock(SimpMessageHeaderAccessor.class);
        when(headerAccessor.getUser()).thenReturn(principal);

        SubscriptionMessage msg = new SubscriptionMessage();
        msg.setHashtag("java");

        // Act
        controller.subscribe(headerAccessor, msg);

        // Assert
        assertNoRawUuid(subscriptionControllerAppender.list, "SubscriptionController.subscribe cap-exceeded path");
    }

    /**
     * subscribe with no-principal path — logs via LOGGER.error — must not include raw UUID
     * (the header accessor itself could contain UUID session IDs).
     *
     * <p>NOTE (SR-MED-02-06): this test uses {@link #CANARY_UUID} as the session canary and relies
     * on {@link LogScrubber#containsRawUuid(String)} to detect leaks. STOMP session IDs are
     * <em>not</em> UUID-format (they are short opaque alphanumeric strings), so this
     * {@code assertNoRawUuid()} check is vacuous for the specific sessionId leak class.
     * The dedicated canary test for sessionId scrubbing is
     * {@code SubscriptionControllerSessionIdScrubbingTest} (non-UUID canary, dual appenders,
     * covers SR-MED-02-01 through SR-MED-02-06). This test remains as a coarser guard against
     * UUID-shaped values leaking through the accessor's {@code toString()}.
     */
    @Test
    void subscriptionController_subscribe_noPrincipal_doesNotLogRawHeaderAccessor() {
        SubscriptionManager subscriptionManager = mock(SubscriptionManager.class);
        SubscriptionController controller = new SubscriptionController(subscriptionManager, beanValidator, 10);

        SimpMessageHeaderAccessor headerAccessor = mock(SimpMessageHeaderAccessor.class);
        when(headerAccessor.getUser()).thenReturn(null);
        // toString() must not expose UUID — mock returns safe string
        when(headerAccessor.toString()).thenReturn("SimpMessageHeaderAccessor[sessionId=safe]");

        SubscriptionMessage msg = new SubscriptionMessage();
        msg.setHashtag("java");

        // Act
        controller.subscribe(headerAccessor, msg);

        // Assert — no canary UUID (if toString emitted it, this would catch it)
        assertNoRawUuid(subscriptionControllerAppender.list, "SubscriptionController.subscribe no-principal path");
    }

    // -------------------------------------------------------------------------
    // SubscriptionListener — onConnectedEvent / onDisconnectEvent logs
    // -------------------------------------------------------------------------

    /**
     * onConnectedEvent logs username — must not leak raw UUID.
     */
    @Test
    void subscriptionListener_onConnectedEvent_doesNotLogRawPrincipalUuid() {
        SubscriptionManager subscriptionManager = mock(SubscriptionManager.class);
        de.seism0saurus.glacier.webservice.cache.MessageCache messageCache = mock(de.seism0saurus.glacier.webservice.cache.MessageCache.class);
        SubscriptionListener listener = new SubscriptionListener(subscriptionManager, messageCache, 1L);

        SessionConnectedEvent event = mock(SessionConnectedEvent.class);
        MessageHeaders headers = new MessageHeaders(null);
        //noinspection unchecked
        Message<byte[]> message = mock(Message.class);
        when(message.getHeaders()).thenReturn(headers);
        when(event.getMessage()).thenReturn(message);

        Principal principal = () -> CANARY_UUID;
        when(event.getUser()).thenReturn(principal);

        // Act
        listener.onConnectedEvent(event);

        // Assert
        assertNoRawUuid(subscriptionListenerAppender.list, "SubscriptionListener.onConnectedEvent");
    }

    /**
     * onDisconnectEvent logs username — must not leak raw UUID.
     */
    @Test
    void subscriptionListener_onDisconnectEvent_doesNotLogRawPrincipalUuid() throws InterruptedException {
        SubscriptionManager subscriptionManager = mock(SubscriptionManager.class);
        de.seism0saurus.glacier.webservice.cache.MessageCache messageCache = mock(de.seism0saurus.glacier.webservice.cache.MessageCache.class);
        // Short timeout so the test does not hang
        SubscriptionListener listener = new SubscriptionListener(subscriptionManager, messageCache, 50L);

        SessionDisconnectEvent event = mock(SessionDisconnectEvent.class);
        MessageHeaders headers = new MessageHeaders(null);
        //noinspection unchecked
        Message<byte[]> message = mock(Message.class);
        when(message.getHeaders()).thenReturn(headers);
        when(event.getMessage()).thenReturn(message);

        Principal principal = () -> CANARY_UUID;
        when(event.getUser()).thenReturn(principal);

        // Act
        listener.onDisconnectEvent(event);

        // Wait for the timer thread to emit its log lines
        Thread.sleep(150L);

        // Assert — no raw UUID in any log line
        assertNoRawUuid(subscriptionListenerAppender.list, "SubscriptionListener.onDisconnectEvent");
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static ListAppender<ILoggingEvent> attachAppender(Class<?> clazz) {
        Logger logger = (Logger) LoggerFactory.getLogger(clazz);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    private static void detach(Class<?> clazz, ListAppender<ILoggingEvent> appender) {
        Logger logger = (Logger) LoggerFactory.getLogger(clazz);
        logger.detachAppender(appender);
        appender.stop();
    }

    private static void assertNoRawUuid(List<ILoggingEvent> events, String context) {
        for (ILoggingEvent event : events) {
            String msg = event.getFormattedMessage();
            assertThat(LogScrubber.containsRawUuid(msg))
                    .as("Log line from %s must not contain raw UUID.\nLine: %s", context, msg)
                    .isFalse();
        }
    }
}
