package de.seism0saurus.glacier.mastodon;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.TextNode;
import de.seism0saurus.glacier.webservice.messaging.messages.*;
import lombok.Getter;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mockito;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.web.client.RestTemplate;
import social.bigbone.MastodonClient;
import social.bigbone.api.entity.Account;
import social.bigbone.api.entity.Notification;
import social.bigbone.api.entity.Status;
import social.bigbone.api.entity.streaming.MastodonApiEvent;
import social.bigbone.api.entity.streaming.ParsedStreamEvent;
import social.bigbone.api.entity.streaming.TechnicalEvent;
import social.bigbone.api.entity.streaming.WebSocketEvent;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * The StompCallbackTest class is used to test the functionality of the StompCallback class.
 */
public class StompCallbackTest {

    /**
     * The SubscriptionManager interface represents a manager that handles subscriptions for hashtags on Mastodon.
     * <p>
     * You can subscribe to a hashtag or terminate a subscription with a UUID.
     */
    SubscriptionManager subscriptionManager;

    /**
     * The variable "client" is an instance of the MastodonClient class from the social.bigbone package.
     * <p>
     * This class represents a client that interacts with the Mastodon social network. It provides
     * methods for subscribing to hashtags and terminating subscriptions.
     * <p>
     * You can use the "client" object to perform operations related to subscriptions on Mastodon.
     * <p>
     * Example usage:
     * <p>
     * // Create a new Mastodon client
     * MastodonClient client = new MastodonClient();
     * <p>
     * // Subscribe to a hashtag
     * client.subscribeToHashtag("user@example.com", "#java");
     * <p>
     * // Terminate a subscription
     * client.terminateSubscription("user@example.com", "#java");
     * <p>
     * // Terminate all subscriptions
     * client.terminateAllSubscriptions("user@example.com");
     */
    social.bigbone.MastodonClient client;

    /**
     * The mockTemplate variable is an instance of the SimpMessagingTemplate class.
     * It is used for testing purposes to simulate sending messages via a messaging template.
     * <p>
     * SimpMessagingTemplate is a class provided by Spring Framework for sending messages to WebSocket clients.
     * In this case, the mockTemplate is used to simulate sending messages to WebSocket clients during unit testing.
     * <p>
     * This variable is declared in the class StompCallbackTest.
     * <p>
     * Example usage:
     * <p>
     * // Create a StatusCreatedMessage
     * StatusCreatedMessage message = StatusCreatedMessage.builder()
     * .id("12345")
     * .author("peter.kropotkin@example.com")
     * .url("https://mastodon.example.com/1234")
     * .build();
     * <p>
     * // Convert the message to JSON String
     * String jsonMessage = new ObjectMapper().writeValueAsString(message);
     * <p>
     * // Simulate sending the message to WebSocket clients
     * mockTemplate.convertAndSend("/topic/statuses", jsonMessage);
     */
    @SuppressWarnings("JavadocLinkAsPlainText")
    SimpMessagingTemplate mockTemplate;

    /**
     * A RestTemplate object for making HTTP requests.
     */
    RestTemplate restTemplate;

    /**
     * The mockStatus variable represents a mock instance of the StatusCreatedMessage class.
     * It is used for testing purposes in the StompCallbackTest class.
     * This variable is not intended for production use.
     */
    Status mockStatus;

    /**
     * Set up method for the StompCallbackTest class.
     * Initializes the necessary mocks and objects for testing.
     * Called before each test case.
     */
    @BeforeEach
    public void setup() {
        this.subscriptionManager = mock(SubscriptionManager.class);
        this.client = mock(MastodonClient.class);
        this.mockTemplate = mock(SimpMessagingTemplate.class);
        this.restTemplate = mock(RestTemplate.class);
        this.mockStatus = mock(Status.class);
    }

    /**
     * Tests if the event handler processes a Status Created event correctly
     */
    @Test
    public void onEvent_statusCreated_sendStatusCreatedToSubscriber() {
        // Setup
        String principal = UUID.randomUUID().toString();
        String hashtag = "hashtag";
        String expectedDestination = "/topic/hashtags/" + principal + "/" + hashtag + "/creation";
        StatusCreatedMessage expectedMessage = StatusCreatedMessage.builder()
                .id("12345")
                .url("https://mastodon.example.com/12345/embed")
                .build();

        doNothing().when(mockTemplate).convertAndSend(eq(expectedDestination), any(StatusCreatedMessage.class));
        Account account = mock(Account.class);
        when(account.getDisplayName()).thenReturn("peter.kropotkin@example.com");
        when(mockStatus.getId()).thenReturn("12345");
        when(mockStatus.getUrl()).thenReturn("https://mastodon.example.com/12345");
        when(mockStatus.getAccount()).thenReturn(account);

        HttpHeaders allowHeader = getHeaders("ALLOWALL", null);
        when(restTemplate.headForHeaders("https://mastodon.example.com/12345" + "/embed")).thenReturn(allowHeader);

        StompCallback callback = new StompCallback(subscriptionManager, mockTemplate, restTemplate, principal, hashtag, "glacier@example.com", "glacier.example.com");
        ParsedStreamEvent.StatusCreated event = new ParsedStreamEvent.StatusCreated(mockStatus);
        MastodonApiEvent.StreamEvent streamEvent = new MastodonApiEvent.StreamEvent(event, List.of());

        // Execute
        callback.onEvent(streamEvent);

        // Verify
        Mockito.verify(mockTemplate).convertAndSend(
                eq(expectedDestination),
                eq(expectedMessage)
        );
    }

    /**
     * Tests if missing handle is handled with an exception, since we cannot work without one
     */
    @Test
    public void handle_isNotProvided_throwsException() {
        // Setup
        String handle = null;

        // Execute
        Exception exception = assertThrows(IllegalArgumentException.class, () ->
                new StompCallback(subscriptionManager, mockTemplate, restTemplate, UUID.randomUUID().toString(), "hashtag", handle, "glacier.example.com")
        );

        // Verify
        assertEquals("A mastodon handle is needed", exception.getMessage());
    }

    /**
     * Tests if partial handle is handled with an exception, since we cannot work without one
     */
    @Test
    public void handle_isPartiallyProvided_throwsException() {
        // Setup
        String handle = "peter.kropotkin";

        // Execute
        Exception exception = assertThrows(IllegalArgumentException.class, () ->
                new StompCallback(subscriptionManager, mockTemplate, restTemplate, UUID.randomUUID().toString(), "hashtag", handle, "glacier.example.com")
        );

        // Verify
        assertEquals("The mastodon handle does not contain an @ so either the name or the server is missing", exception.getMessage());
    }

    /**
     * Tests if complete handle is correctly parsed
     */
    @Test
    public void handle_completeHandle_doesNotThrowException() throws NoSuchFieldException, IllegalAccessException {
        // Setup
        String handle = "peter.kropotkin@localhost";

        // Execute
        StompCallback stompCallback = new StompCallback(subscriptionManager, mockTemplate, restTemplate, UUID.randomUUID().toString(), "hashtag", handle, "glacier.example.com");

        // Get the private field 'shortHandle' using reflection
        Field shortHandleField = StompCallback.class.getDeclaredField("shortHandle");
        shortHandleField.setAccessible(true); // Make the private field accessible
        String shortHandle = (String) shortHandleField.get(stompCallback); // Read the value

        // Assert
        assertEquals("peter.kropotkin", shortHandle);
    }

    /**
     * Tests if complete handle with leading @ is correctly parsed
     */
    @Test
    public void handle_completeHandleWithLeadingAt_doesNotThrowException() throws NoSuchFieldException, IllegalAccessException {
        // Setup
        String handle = "@peter.kropotkin@localhost";

        // Execute
        StompCallback stompCallback = new StompCallback(subscriptionManager, mockTemplate, restTemplate, UUID.randomUUID().toString(), "hashtag", handle, "glacier.example.com");

        // Get the private field 'shortHandle' using reflection
        Field shortHandleField = StompCallback.class.getDeclaredField("shortHandle");
        shortHandleField.setAccessible(true); // Make the private field accessible
        String shortHandle = (String) shortHandleField.get(stompCallback); // Read the value

        // Assert
        assertEquals("peter.kropotkin", shortHandle);
    }

    /**
     * Tests if the event handler processes a Status Edited event correctly
     */
    @Test
    public void onEvent_statusEdited_sendStatusUpdatedToSubscriber() {
        // Setup
        String principal = UUID.randomUUID().toString();
        String hashtag = "hashtag";
        String expectedDestination = "/topic/hashtags/" + principal + "/" + hashtag + "/modification";
        StatusUpdatedMessage expectedMessage = StatusUpdatedMessage.builder()
                .id("12345")
                .url("https://mastodon.example.com/12345/embed")
                .build();

        doNothing().when(mockTemplate).convertAndSend(eq(expectedDestination), any(StatusUpdatedMessage.class));
        Account account = mock(Account.class);
        when(account.getDisplayName()).thenReturn("peter.kropotkin@example.com");
        when(mockStatus.getId()).thenReturn("12345");
        when(mockStatus.getUrl()).thenReturn("https://mastodon.example.com/12345");
        when(mockStatus.getAccount()).thenReturn(account);

        HttpHeaders allowHeader = getHeaders("ALLOWALL", null);
        when(restTemplate.headForHeaders("https://mastodon.example.com/12345" + "/embed")).thenReturn(allowHeader);

        StompCallback callback = new StompCallback(subscriptionManager, mockTemplate, restTemplate, principal, hashtag, "glacier@example.com", "glacier.example.com");
        ParsedStreamEvent.StatusEdited event = new ParsedStreamEvent.StatusEdited(mockStatus);
        MastodonApiEvent.StreamEvent streamEvent = new MastodonApiEvent.StreamEvent(event, List.of());

        // Execute
        callback.onEvent(streamEvent);

        // Verify
        Mockito.verify(mockTemplate).convertAndSend(
                eq(expectedDestination),
                eq(expectedMessage)
        );
    }

    /**
     * Tests if the event handler processes a Status Deleted event correctly
     */
    @Test
    public void onEvent_statusDeleted_sendStatusDeletedToSubscriber() {
        // Setup
        String principal = UUID.randomUUID().toString();
        String hashtag = "hashtag";
        String expectedDestination = "/topic/hashtags/" + principal + "/" + hashtag + "/deletion";
        StatusDeletedMessage expectedMessage = StatusDeletedMessage.builder()
                .id("12345")
                .build();

        doNothing().when(mockTemplate).convertAndSend(eq(expectedDestination), any(StatusUpdatedMessage.class));

        HttpHeaders allowHeader = getHeaders("ALLOWALL", null);
        when(restTemplate.headForHeaders("https://mastodon.example.com/12345" + "/embed")).thenReturn(allowHeader);

        StompCallback callback = new StompCallback(subscriptionManager, mockTemplate, restTemplate, principal, hashtag, "glacier@example.com", "glacier.example.com");
        ParsedStreamEvent.StatusDeleted event = new ParsedStreamEvent.StatusDeleted("12345");
        MastodonApiEvent.StreamEvent streamEvent = new MastodonApiEvent.StreamEvent(event, List.of());

        // Execute
        callback.onEvent(streamEvent);

        // Verify
        Mockito.verify(mockTemplate).convertAndSend(
                eq(expectedDestination),
                eq(expectedMessage)
        );
    }

    /**
     * Tests if the event handler processes an unknown StreamEvent correctly
     * and does not send a message to the subscriber.
     */
    @Test
    public void onEvent_unknownStreamEvent_dontSendMessageToSubscriber() {
        // Setup
        String principal = UUID.randomUUID().toString();
        String hashtag = "hashtag";

        StompCallback callback = new StompCallback(subscriptionManager, mockTemplate, restTemplate, principal, hashtag, "glacier@example.com", "glacier.example.com");
        Notification notification = new Notification();
        ParsedStreamEvent.NewNotification event = new ParsedStreamEvent.NewNotification(notification);
        MastodonApiEvent.StreamEvent streamEvent = new MastodonApiEvent.StreamEvent(event, List.of());

        // Execute
        callback.onEvent(streamEvent);

        // Verify
        Mockito.verify(mockTemplate, times(0)).convertAndSend(any(String.class), any(Object.class));
    }

    /**
     * Tests if the headers from the embedded url are correctly parsed and unloadable urls are not send to the subscriber.
     */
    @ParameterizedTest
    @MethodSource("httpHeadersForIframes")
    public void onEvent_statusCreated_testRemoteLoadableByHeaders(final HttpHeaders headers, boolean isLoadable) {
        // Setup
        String principal = UUID.randomUUID().toString();
        String hashtag = "hashtag";
        String expectedDestination = "/topic/hashtags/" + principal + "/" + hashtag + "/creation";
        StatusCreatedMessage expectedMessage = StatusCreatedMessage.builder()
                .id("12345")
                .url("https://mastodon.example.com/12345/embed")
                .build();

        doNothing().when(mockTemplate).convertAndSend(eq(expectedDestination), any(StatusCreatedMessage.class));
        Account account = mock(Account.class);
        when(account.getDisplayName()).thenReturn("peter.kropotkin@example.com");
        when(mockStatus.getId()).thenReturn("12345");
        when(mockStatus.getUrl()).thenReturn("https://mastodon.example.com/12345");
        when(mockStatus.getAccount()).thenReturn(account);

        when(restTemplate.headForHeaders("https://mastodon.example.com/12345" + "/embed")).thenReturn(headers);

        StompCallback callback = new StompCallback(subscriptionManager, mockTemplate, restTemplate, principal, hashtag, "glacier@example.com", "glacier.example.com");
        ParsedStreamEvent.StatusCreated event = new ParsedStreamEvent.StatusCreated(mockStatus);
        MastodonApiEvent.StreamEvent streamEvent = new MastodonApiEvent.StreamEvent(event, List.of());

        // Execute
        callback.onEvent(streamEvent);

        // Verify
        if (isLoadable) {
            Mockito.verify(mockTemplate).convertAndSend(
                    eq(expectedDestination),
                    eq(expectedMessage)
            );
        } else {
            Mockito.verify(mockTemplate, times(0)).convertAndSend(any(String.class), any(Object.class));
        }
    }

    public static Stream<Arguments> httpHeadersForIframes() {
        return Stream.of(
                Arguments.of(getHeaders(null, null), true) // Default allow
                , Arguments.of(getHeaders("ALLOWALL", null), true) // Explicit allow
                , Arguments.of(getHeaders("DENY", null), false) // Explicitly not allowed
                , Arguments.of(getHeaders("SAMEORIGIN", null), false) // Explicitly not allowed
                , Arguments.of(getHeaders("SAMEORIGIN; ALLOWALL", null), false) // Multiple headers are not allowed
                , Arguments.of(getHeaders("GNU Terry Pratchett", null), false) // Wrong headers
                , Arguments.of(getHeaders(null, "default-src 'self'; img-src 'self'"), true) // Other stuff not frame-ancestors
                , Arguments.of(getHeaders(null, "frame-ancestors glacier.example.com; default-src 'self'; img-src 'self';"), true) // Other stuff with frame-ancestors at the beginning
                , Arguments.of(getHeaders(null, "default-src 'self'; frame-ancestors glacier.example.com; img-src 'self';"), true) // Other stuff with frame-ancestors in the middle
                , Arguments.of(getHeaders(null, "default-src 'self';  img-src 'self'; frame-ancestors glacier.example.com;"), true) // Other stuff with frame-ancestors at the end
                , Arguments.of(getHeaders(null, "frame-ancestors 'none'"), false) // Disallow frame-ancestors
                , Arguments.of(getHeaders(null, "frame-ancestors othersite.example.com;"), false) // Wrong frame-ancestors
                , Arguments.of(getHeaders(null, "frame-ancestors glacier.example.com;"), true) // Allow the test instance as frame-ancestor
                , Arguments.of(getHeaders(null, "frame-ancestors othersite.example.com glacier.example.com;"), true) // Allow the test instance as frame-ancestor with other unrelated ancestor
                , Arguments.of(getHeaders(null, "frame-ancestors glacier.example.com:80;"), true) // Allow the test instance with http port as frame-ancestor
                , Arguments.of(getHeaders(null, "frame-ancestors glacier.example.com:443;"), true) // Allow the test instance with https port as frame-ancestor
                , Arguments.of(getHeaders(null, "frame-ancestors http://glacier.example.com;"), true) // Allow the test instance with http port as frame-ancestor
                , Arguments.of(getHeaders(null, "frame-ancestors https://glacier.example.com;"), true) // Allow the test instance with https port as frame-ancestor
                , Arguments.of(getHeaders(null, "frame-ancestors http://glacier.example.com:80;"), true) // Allow the test instance with http port as frame-ancestor
                , Arguments.of(getHeaders(null, "frame-ancestors https://glacier.example.com:443;"), true) // Allow the test instance with https port as frame-ancestor
                , Arguments.of(getHeaders(null, "frame-ancestors http:;"), true) // Allow all http ancestors
                , Arguments.of(getHeaders(null, "frame-ancestors https:;"), true) // Allow all https ancestors
                , Arguments.of(getHeaders(null, ""), true) // empty csp header
        );
    }

    private static HttpHeaders getHeaders(final String xFrameOptions, final String csp) {
        HttpHeaders httpHeaders = new HttpHeaders();
        if (xFrameOptions != null) {
            httpHeaders.set("X-Frame-Options", xFrameOptions);
        }
        if (csp != null) {
            httpHeaders.set("Content-Security-Policy", csp);
        }
        return httpHeaders;
    }

    /**
     * Tests if the event handler processes a Technical Open event correctly
     */
    @Test
    public void onEvent_EventTechnicalOpen() {
        // Setup
        TestLogAppender logAppender = getTestLogAppender();

        StompCallback callback = new StompCallback(subscriptionManager, mockTemplate, restTemplate, UUID.randomUUID().toString(), "hashtag", "glacier@example.com", "example.com");
        TechnicalEvent.Open mockEvent = mock(TechnicalEvent.Open.class);

        // Execute
        callback.onEvent(mockEvent);

        // Verify
        assertThat(logAppender.getLoggedMessages())
                .anySatisfy(msg -> assertThat(msg).contains("got an Open event: Mock for Open"));
    }

    /**
     * Tests if the event handler processes a Technical Closing event correctly
     */
    @Test
    public void onEvent_EventTechnicalClosing() {
        // Setup
        TestLogAppender logAppender = getTestLogAppender();

        StompCallback callback = new StompCallback(subscriptionManager, mockTemplate, restTemplate, UUID.randomUUID().toString(), "hashtag", "glacier@example.com", "example.com");
        TechnicalEvent.Closing mockEvent = mock(TechnicalEvent.Closing.class);

        // Execute
        callback.onEvent(mockEvent);

        // Verify
        assertThat(logAppender.getLoggedMessages())
                .anySatisfy(msg -> assertThat(msg).contains("got a Closing event: Mock for Closing"));
    }

    /**
     * Tests if the event handler processes a Technical Closed event correctly
     */
    @Test
    public void onEvent_EventTechnicalClosed() {
        // Setup
        TestLogAppender logAppender = getTestLogAppender();

        StompCallback callback = new StompCallback(subscriptionManager, mockTemplate, restTemplate, UUID.randomUUID().toString(), "hashtag", "glacier@example.com", "example.com");
        TechnicalEvent.Closed mockEvent = mock(TechnicalEvent.Closed.class);

        // Execute
        callback.onEvent(mockEvent);

        // Verify
        assertThat(logAppender.getLoggedMessages())
                .anySatisfy(msg -> assertThat(msg).contains("got a Closed event: Mock for Closed"));
    }

    /**
     * Tests if the event handler processes an unknown Technical event correctly
     */
    @Test
    public void onEvent_EventTechnicalUnknown() {
        // Setup
        TestLogAppender logAppender = getTestLogAppender();

        StompCallback callback = new StompCallback(subscriptionManager, mockTemplate, restTemplate, UUID.randomUUID().toString(), "hashtag", "glacier@example.com", "example.com");
        TechnicalEvent mockEvent = mock(TechnicalEvent.class);

        // Execute
        callback.onEvent(mockEvent);

        // Verify
        assertThat(logAppender.getLoggedMessages())
                .anySatisfy(msg -> assertThat(msg).contains("got an unknown WebSocketEvent:"));
    }

    /**
     * Tests if the event handler processes a GenericMessage status.update event with unloadable toot and optin correctly
     */
    @Test
    public void onEvent_EventGenericMessage_UpdateWithUnloadableTootAndOptInIsHandled() throws JsonProcessingException {
        // Setup
        TestLogAppender logAppender = getTestLogAppender();
        SimpMessagingTemplate spyMessagingTemplate = spy(new SimpMessagingTemplate((message, timeout) -> {
            System.out.println(message);
            return true;
        }));

        HttpHeaders allowHeader = getHeaders("DENY", null);
        when(restTemplate.headForHeaders("https://example.com/4567" + "/embed")).thenReturn(allowHeader);

        StompCallback callback = new StompCallback(subscriptionManager, spyMessagingTemplate, restTemplate, UUID.randomUUID().toString(), "hashtag", "glacier@example.com", "example.com");

        MastodonApiEvent.GenericMessage mockEvent = mock(MastodonApiEvent.GenericMessage.class);
        ObjectMapper mapper = new ObjectMapper();
        Mention mention = Mention.builder().id("4567").username("@peter.kropotkin").acct("glacier").build();
        GenericMessageContentPayload payload = GenericMessageContentPayload.builder().mentions(List.of(mention)).url("https://example.com/4567").id("4567").build();
        String payloadAsText = mapper.writeValueAsString(payload);
        JsonNode jsonNode = TextNode.valueOf(payloadAsText);
        GenericMessageContent content = GenericMessageContent.builder().event("status.update").stream(List.of("hashtag")).payload(jsonNode).build();
        String serializedContent = mapper.writeValueAsString(content);
        when(mockEvent.getText()).thenReturn(serializedContent);

        // Execute
        callback.onEvent(mockEvent);

        // Verify
        verify(spyMessagingTemplate, times(0)).convertAndSend(any(String.class), any(StatusCreatedMessage.class));
        assertThat(logAppender.getLoggedMessages())
                .anySatisfy(msg -> assertThat(msg).contains("Toot not loadable by this glacier instance. Ignoring"));
    }

    /**
     * Tests if the event handler processes a GenericMessage status.update event with loadable toot but without optin correctly
     */
    @Test
    public void onEvent_EventGenericMessage_UpdateWithLoadableTootButMissingOptInIsHandled() throws JsonProcessingException {
        // Setup
        TestLogAppender logAppender = getTestLogAppender();
        SimpMessagingTemplate spyMessagingTemplate = spy(new SimpMessagingTemplate((message, timeout) -> {
            System.out.println(message);
            return true;
        }));

        HttpHeaders allowHeader = getHeaders("ALLOWALL", null);
        when(restTemplate.headForHeaders("https://example.com/4567" + "/embed")).thenReturn(allowHeader);

        StompCallback callback = new StompCallback(subscriptionManager, spyMessagingTemplate, restTemplate, UUID.randomUUID().toString(), "hashtag", "glacier@example.com", "example.com");

        MastodonApiEvent.GenericMessage mockEvent = mock(MastodonApiEvent.GenericMessage.class);
        ObjectMapper mapper = new ObjectMapper();
        Mention mention = Mention.builder().id("4567").username("@peter.kropotkin").acct("other_handle").build();
        GenericMessageContentPayload payload = GenericMessageContentPayload.builder().mentions(List.of(mention)).url("https://example.com/4567").id("4567").build();
        String payloadAsText = mapper.writeValueAsString(payload);
        JsonNode jsonNode = TextNode.valueOf(payloadAsText);
        GenericMessageContent content = GenericMessageContent.builder().event("status.update").stream(List.of("hashtag")).payload(jsonNode).build();
        String serializedContent = mapper.writeValueAsString(content);
        when(mockEvent.getText()).thenReturn(serializedContent);

        // Execute
        callback.onEvent(mockEvent);

        // Verify
        verify(spyMessagingTemplate, times(0)).convertAndSend(any(String.class), any(StatusCreatedMessage.class));
        assertThat(logAppender.getLoggedMessages())
                .anySatisfy(msg -> assertThat(msg).contains("No opt in. Ignoring"));
    }

    /**
     * Tests if the event handler processes a GenericMessage status.update event with loadable toot and optin correctly
     */
    @Test
    public void onEvent_EventGenericMessage_StatusUpdateWithLoadableTootAndOptInIsHandled() throws JsonProcessingException {
        // Setup
        SimpMessagingTemplate spyMessagingTemplate = spy(new SimpMessagingTemplate((message, timeout) -> {
            System.out.println(message);
            return true;
        }));
        StatusUpdatedMessage createdMessage = StatusUpdatedMessage.builder().id("4567").url("https://example.com/4567" + "/embed").editedAt("2025-01-017").build();

        HttpHeaders allowHeader = getHeaders("ALLOWALL", null);
        when(restTemplate.headForHeaders("https://example.com/4567" + "/embed")).thenReturn(allowHeader);

        StompCallback callback = new StompCallback(subscriptionManager, spyMessagingTemplate, restTemplate, UUID.randomUUID().toString(), "hashtag", "glacier@example.com", "example.com");

        MastodonApiEvent.GenericMessage mockEvent = mock(MastodonApiEvent.GenericMessage.class);
        ObjectMapper mapper = new ObjectMapper();
        Mention mention = Mention.builder().id("4567").username("@peter.kropotkin").acct("glacier").build();
        GenericMessageContentPayload payload = GenericMessageContentPayload.builder().mentions(List.of(mention)).url("https://example.com/4567").id("4567").editedAt("2025-01-017").build();
        String payloadAsText = mapper.writeValueAsString(payload);
        JsonNode jsonNode = TextNode.valueOf(payloadAsText);
        GenericMessageContent content = GenericMessageContent.builder().event("status.update").stream(List.of("hashtag")).payload(jsonNode).build();
        String serializedContent = mapper.writeValueAsString(content);
        when(mockEvent.getText()).thenReturn(serializedContent);

        // Execute
        callback.onEvent(mockEvent);

        // Verify
        verify(spyMessagingTemplate, times(1)).convertAndSend(matches("/topic/hashtags/.*/hashtag/modification"), eq(createdMessage));
    }

    /**
     * Tests if the event handler processes a GenericMessage update event with loadable toot and optin correctly
     */
    @Test
    public void onEvent_EventGenericMessage_UpdateWithLoadableTootAndOptInIsHandled() throws JsonProcessingException {
        // Setup
        SimpMessagingTemplate spyMessagingTemplate = spy(new SimpMessagingTemplate((message, timeout) -> {
            System.out.println(message);
            return true;
        }));
        StatusCreatedMessage createdMessage = StatusCreatedMessage.builder().id("4567").url("https://example.com/4567" + "/embed").build();

        HttpHeaders allowHeader = getHeaders("ALLOWALL", null);
        when(restTemplate.headForHeaders("https://example.com/4567" + "/embed")).thenReturn(allowHeader);

        StompCallback callback = new StompCallback(subscriptionManager, spyMessagingTemplate, restTemplate, UUID.randomUUID().toString(), "hashtag", "glacier@example.com", "example.com");

        MastodonApiEvent.GenericMessage mockEvent = mock(MastodonApiEvent.GenericMessage.class);
        ObjectMapper mapper = new ObjectMapper();
        Mention mention = Mention.builder().id("4567").username("@peter.kropotkin").acct("glacier").build();
        GenericMessageContentPayload payload = GenericMessageContentPayload.builder().mentions(List.of(mention)).url("https://example.com/4567").id("4567").build();
        String payloadAsText = mapper.writeValueAsString(payload);
        JsonNode jsonNode = TextNode.valueOf(payloadAsText);
        GenericMessageContent content = GenericMessageContent.builder().event("update").stream(List.of("hashtag")).payload(jsonNode).build();
        String serializedContent = mapper.writeValueAsString(content);
        when(mockEvent.getText()).thenReturn(serializedContent);

        // Execute
        callback.onEvent(mockEvent);

        // Verify
        verify(spyMessagingTemplate, times(1)).convertAndSend(matches("/topic/hashtags/.*/hashtag/creation"), eq(createdMessage));
    }

    /**
     * Tests if the event handler processes a GenericMessage delete event correctly
     */
    @Test
    public void onEvent_EventGenericMessage_DeleteIsHandled() throws JsonProcessingException {
        // Setup
        SimpMessagingTemplate spyMessagingTemplate = spy(new SimpMessagingTemplate((message, timeout) -> {
            System.out.println(message);
            return true;
        }));
        StatusDeletedMessage deletedMessage = StatusDeletedMessage.builder().id("4567").build();

        StompCallback callback = new StompCallback(subscriptionManager, spyMessagingTemplate, restTemplate, UUID.randomUUID().toString(), "hashtag", "glacier@example.com", "example.com");
        MastodonApiEvent.GenericMessage mockEvent = mock(MastodonApiEvent.GenericMessage.class);

        ObjectMapper mapper = new ObjectMapper();
        String payloadAsText = mapper.writeValueAsString(4567);
        JsonNode jsonNode = TextNode.valueOf(payloadAsText);
        GenericMessageContent content = GenericMessageContent.builder().event("delete").stream(List.of("hashtag")).payload(jsonNode).build();
        String serializedContent = mapper.writeValueAsString(content);
        when(mockEvent.getText()).thenReturn(serializedContent);

        // Execute
        callback.onEvent(mockEvent);

        // Verify
        verify(spyMessagingTemplate, times(1)).convertAndSend(matches("/topic/hashtags/.*/hashtag/deletion"), eq(deletedMessage));
    }

    /**
     * Tests if the event handler processes a GenericMessage status.delete event correctly
     */
    @Test
    public void onEvent_EventGenericMessage_StatusDeleteIsHandled() throws JsonProcessingException {
        // Setup
        SimpMessagingTemplate spyMessagingTemplate = spy(new SimpMessagingTemplate((message, timeout) -> {
            System.out.println(message);
            return true;
        }));
        StatusDeletedMessage deletedMessage = StatusDeletedMessage.builder().id("4567").build();

        StompCallback callback = new StompCallback(subscriptionManager, spyMessagingTemplate, restTemplate, UUID.randomUUID().toString(), "hashtag", "glacier@example.com", "example.com");
        MastodonApiEvent.GenericMessage mockEvent = mock(MastodonApiEvent.GenericMessage.class);

        ObjectMapper mapper = new ObjectMapper();
        String payloadAsText = mapper.writeValueAsString(4567);
        JsonNode jsonNode = TextNode.valueOf(payloadAsText);
        GenericMessageContent content = GenericMessageContent.builder().event("status.delete").stream(List.of("hashtag")).payload(jsonNode).build();
        String serializedContent = mapper.writeValueAsString(content);
        when(mockEvent.getText()).thenReturn(serializedContent);

        // Execute
        callback.onEvent(mockEvent);

        // Verify
        verify(spyMessagingTemplate, times(1)).convertAndSend(matches("/topic/hashtags/.*/hashtag/deletion"), eq(deletedMessage));
    }

    /**
     * Tests if the event handler processes a GenericMessage event, that's not a update or delete message, correctly
     */
    @Test
    public void onEvent_UnrelatedGenericMessageEvent_isIgnored() throws JsonProcessingException {
        // Setup
        TestLogAppender logAppender = getTestLogAppender();
        StompCallback callback = new StompCallback(subscriptionManager, mockTemplate, restTemplate, UUID.randomUUID().toString(), "hashtag", "glacier@example.com", "example.com");
        MastodonApiEvent.GenericMessage mockEvent = mock(MastodonApiEvent.GenericMessage.class);

        ObjectMapper mapper = new ObjectMapper();
        Mention mention = Mention.builder().id("12345").username("peter.kropotkin").acct("@karl.marx").build();
        GenericMessageContentPayload payload = GenericMessageContentPayload.builder().mentions(List.of(mention)).url("https://example.com/12345").id("4567").build();
        String payloadAsText = mapper.writeValueAsString(payload);
        JsonNode jsonNode = TextNode.valueOf(payloadAsText);
        GenericMessageContent content = GenericMessageContent.builder().event("other_event").stream(List.of("something_unrelated")).payload(jsonNode).build();
        String serializedContent = mapper.writeValueAsString(content);
        when(mockEvent.getText()).thenReturn(serializedContent);

        // Execute
        callback.onEvent(mockEvent);

        // Verify
        assertThat(logAppender.getLoggedMessages())
                .anySatisfy(msg -> assertThat(msg).contains("Not an update event for the subscribed hashtag"));
    }

    /**
     * Tests if the event handler handles a deserialization error in a GenericMessage event
     */
    @Test
    public void onEvent_EventGenericMessageWithInvalidContent_handlesExceptionGracefully() {
        // Setup
        TestLogAppender logAppender = getTestLogAppender();
        StompCallback callback = new StompCallback(subscriptionManager, mockTemplate, restTemplate, UUID.randomUUID().toString(), "hashtag", "glacier@example.com", "example.com");
        MastodonApiEvent.GenericMessage mockEvent = mock(MastodonApiEvent.GenericMessage.class);

        when(mockEvent.getText()).thenReturn("not a json");

        // Execute
        callback.onEvent(mockEvent);

        // Verify — prefix preserved; the new format includes "— exception=" after it
        assertThat(logAppender.getLoggedMessages())
                .anySatisfy(msg -> assertThat(msg).contains("Could not parse GenericMessage"));
    }

    // -------------------------------------------------------------------------
    // T-A constants shared by the TD-1 parse-error log hygiene tests
    // -------------------------------------------------------------------------

    /**
     * Simple names of Jackson exceptions that may legitimately appear in the log message.
     * Any name outside this set appearing as a Throwable is a leak candidate.
     */
    private static final Set<String> ACCEPTABLE_PARSE_EXCEPTIONS = Set.of(
            "JsonParseException",
            "JsonEOFException",        // truncated/incomplete JSON (subclass of JsonParseException)
            "JsonMappingException",    // type-mismatch during object mapping
            "MismatchedInputException",
            "UnrecognizedPropertyException"
    );

    /**
     * Canary value injected into malformed JSON so that if Jackson embeds source text
     * from {@link com.fasterxml.jackson.core.JsonProcessingException#getMessage()} into
     * the log line, the sentinel will appear and the test will detect the leak.
     * <p>
     * The truncated JSON {@code {"broken": "<canary>"} — missing the closing brace —
     * causes Jackson to throw mid-parse with the canary visible in the source location
     * fragment of the exception message.
     */
    private static final String CANARY_FRAGMENT_TD1 =
            "__GLACIER_TD1_CANARY_" + UUID.randomUUID() + "__";

    // -------------------------------------------------------------------------
    // TD-1 — Lane A tests  (T-A1 through T-A5)
    //
    // Each test exercises the JsonProcessingException catch block in
    // StompCallback#processGenericEvent to verify that the new log format
    // (ADR-TD1-01) does not leak attacker-controlled input into log records.
    // -------------------------------------------------------------------------

    /**
     * T-A1 — Verifies that a JSON parse error produces at least one ERROR-level log event.
     * <p>
     * Arrange: a GenericMessage whose text is truncated JSON containing the canary sentinel.
     * Act:     invoke the callback's event handler.
     * Assert:  at least one captured ILoggingEvent has Level.ERROR.
     */
    @Test
    public void parseError_logsErrorLevel() {
        // Arrange
        TestLogAppender logAppender = getTestLogAppender();
        StompCallback callback = new StompCallback(
                subscriptionManager, mockTemplate, restTemplate,
                UUID.randomUUID().toString(), "hashtag", "glacier@example.com", "example.com");
        MastodonApiEvent.GenericMessage mockGenericMessage = mock(MastodonApiEvent.GenericMessage.class);
        // Truncated JSON — missing closing brace causes Jackson to throw with canary in source fragment
        when(mockGenericMessage.getText())
                .thenReturn("{\"broken\": \"" + CANARY_FRAGMENT_TD1 + "\"");

        // Act
        callback.onEvent(mockGenericMessage);

        // Assert: at least one ERROR-level event was emitted for the parse failure
        assertThat(logAppender.getLoggedEvents())
                .anySatisfy(event -> assertThat(event.getLevel()).isEqualTo(Level.ERROR));
    }

    /**
     * T-A2 — Verifies that the log message for a parse error contains one of the
     * allowlisted Jackson exception simple names, proving the exception type is surfaced
     * without leaking the exception's full message (which contains attacker-controlled text).
     * <p>
     * Arrange: same truncated-JSON canary input.
     * Act:     invoke the callback.
     * Assert:  getFormattedMessage() on at least one captured event contains a name
     *          from ACCEPTABLE_PARSE_EXCEPTIONS.
     */
    @Test
    public void parseError_includesAllowlistedExceptionSimpleName() {
        // Arrange
        TestLogAppender logAppender = getTestLogAppender();
        StompCallback callback = new StompCallback(
                subscriptionManager, mockTemplate, restTemplate,
                UUID.randomUUID().toString(), "hashtag", "glacier@example.com", "example.com");
        MastodonApiEvent.GenericMessage mockGenericMessage = mock(MastodonApiEvent.GenericMessage.class);
        when(mockGenericMessage.getText())
                .thenReturn("{\"broken\": \"" + CANARY_FRAGMENT_TD1 + "\"");

        // Act
        callback.onEvent(mockGenericMessage);

        // Assert: the formatted message names one allowlisted exception class
        assertThat(logAppender.getLoggedEvents())
                .anySatisfy(event ->
                        assertThat(ACCEPTABLE_PARSE_EXCEPTIONS)
                                .anyMatch(name -> event.getFormattedMessage().contains(name)));
    }

    /**
     * T-A3 — Verifies that the legacy message prefix "Could not parse GenericMessage"
     * is preserved in the new log format, ensuring backwards-compatible log monitoring.
     * <p>
     * Arrange: truncated-JSON canary input.
     * Act:     invoke the callback.
     * Assert:  at least one formatted message starts with "Could not parse GenericMessage".
     */
    @Test
    public void parseError_messagePrefixMatches() {
        // Arrange
        TestLogAppender logAppender = getTestLogAppender();
        StompCallback callback = new StompCallback(
                subscriptionManager, mockTemplate, restTemplate,
                UUID.randomUUID().toString(), "hashtag", "glacier@example.com", "example.com");
        MastodonApiEvent.GenericMessage mockGenericMessage = mock(MastodonApiEvent.GenericMessage.class);
        when(mockGenericMessage.getText())
                .thenReturn("{\"broken\": \"" + CANARY_FRAGMENT_TD1 + "\"");

        // Act
        callback.onEvent(mockGenericMessage);

        // Assert: legacy prefix is intact so existing log-monitoring rules continue to fire
        assertThat(logAppender.getLoggedMessages())
                .anySatisfy(msg -> assertThat(msg).startsWith("Could not parse GenericMessage"));
    }

    /**
     * T-A4 — Verifies that no Throwable is attached to any captured log event.
     * <p>
     * This is the primary regression guard for ADR-TD1-01: the old code passed the
     * exception as a Throwable argument to LOGGER.error(), causing Logback to render
     * {@link JsonProcessingException#getMessage()} — which embeds attacker-controlled
     * source text — into the structured JSON log record.
     * <p>
     * Arrange: truncated-JSON canary input.
     * Act:     invoke the callback.
     * Assert:  every captured ILoggingEvent has getThrowableProxy() == null.
     */
    @Test
    public void parseError_attachesNoThrowable() {
        // Arrange
        TestLogAppender logAppender = getTestLogAppender();
        StompCallback callback = new StompCallback(
                subscriptionManager, mockTemplate, restTemplate,
                UUID.randomUUID().toString(), "hashtag", "glacier@example.com", "example.com");
        MastodonApiEvent.GenericMessage mockGenericMessage = mock(MastodonApiEvent.GenericMessage.class);
        when(mockGenericMessage.getText())
                .thenReturn("{\"broken\": \"" + CANARY_FRAGMENT_TD1 + "\"");

        // Act
        callback.onEvent(mockGenericMessage);

        // Assert: no event carries a Throwable proxy — the exception message cannot be serialised
        // into the log record by Logback's ThrowableProxyConverter
        assertThat(logAppender.getLoggedEvents())
                .allSatisfy(event -> assertThat(event.getThrowableProxy()).isNull());
    }

    /**
     * T-A5 — Verifies that a JSON parse error neither pollutes the SLF4J MDC context
     * nor emits events to the dedicated AUDIT logger.
     * <p>
     * MDC pollution could leak the canary sentinel (attacker-controlled) as a structured
     * field in subsequent log records. AUDIT events for a parse error would be incorrect
     * routing: parse errors are operational failures, not security audit events.
     * <p>
     * Arrange: truncated-JSON canary input.
     * Act:     invoke the callback.
     * Assert:  MDC is empty after the call; AUDIT logger received zero events.
     */
    @Test
    public void parseError_doesNotPolluteMdcOrAudit() {
        // Arrange
        TestLogAppender logAppender = getTestLogAppender();
        TestLogAppender auditAppender = getAuditLogAppender();
        MDC.clear(); // ensure clean MDC state before the test
        StompCallback callback = new StompCallback(
                subscriptionManager, mockTemplate, restTemplate,
                UUID.randomUUID().toString(), "hashtag", "glacier@example.com", "example.com");
        MastodonApiEvent.GenericMessage mockGenericMessage = mock(MastodonApiEvent.GenericMessage.class);
        when(mockGenericMessage.getText())
                .thenReturn("{\"broken\": \"" + CANARY_FRAGMENT_TD1 + "\"");

        // Act
        callback.onEvent(mockGenericMessage);

        // Assert: MDC does not contain the canary fragment (not set during error handling)
        assertThat(MDC.getCopyOfContextMap()).satisfiesAnyOf(
                map -> assertThat(map).isNull(),
                map -> assertThat(map).doesNotContainValue(CANARY_FRAGMENT_TD1)
        );
        // Assert: AUDIT logger received no events — parse errors are not security audit events
        assertThat(auditAppender.getLoggedEvents()).isEmpty();
    }


    // -------------------------------------------------------------------------
    // TD-1 — Lane B test  (T-B1)
    //
    // Parameterized injection canary: four adversarial fragment variants are
    // embedded in malformed JSON and fed through the parse-error path. The test
    // verifies that none of the attacker-controlled bytes surface in any
    // ILoggingEvent (formatted message, argument array, or Throwable proxy).
    //
    // SR coverage: SR-TD1-01 (wire-payload byte), SR-TD1-09 (no Throwable),
    //              SR-TD1-10 (CRLF injection), SR-TD1-11 (ANSI injection).
    // -------------------------------------------------------------------------

    /**
     * Provides four adversarial injection variants for T-B1.
     * <p>
     * Each variant represents a distinct attack surface:
     * <ul>
     *   <li>ASCII sentinel — baseline canary that Jackson echoes in exception messages</li>
     *   <li>CRLF — log-injection via embedded newline (SR-TD1-10)</li>
     *   <li>ANSI escape — terminal colour injection (SR-TD1-11)</li>
     *   <li>Null-byte prefix — separates real message from appended content</li>
     * </ul>
     */
    private static Stream<Arguments> injectionVariants() {
        return Stream.of(
                Arguments.of("__ASCII_CANARY_TD1__"),
                Arguments.of("\r\nFAKE_LOG_LINE: evil=injected"),
                Arguments.of("[31mANSI_RED_INJECT[0m"),
                Arguments.of(" NULL_BYTE_INJECT")
        );
    }

    /**
     * T-B1 — Verifies that no attacker-controlled fragment leaks into any log record
     * when Jackson fails to parse a malformed GenericMessage payload containing that fragment.
     * <p>
     * Four injection variants are tested: plain ASCII, CRLF, ANSI escape, and null-byte prefix.
     * For each variant the test asserts:
     * <ol>
     *   <li>No {@link ILoggingEvent#getFormattedMessage()} contains the fragment.</li>
     *   <li>No argument in {@link ILoggingEvent#getArgumentArray()} resolves to a string
     *       containing the fragment.</li>
     *   <li>Every event has {@link ILoggingEvent#getThrowableProxy()} {@code == null} (strict —
     *       the exception must not be attached to the log record).</li>
     *   <li>At least one event's formatted message starts with {@code "Could not parse GenericMessage"}
     *       to confirm the parse-failure path actually fired (positive signal).</li>
     * </ol>
     *
     * @param fragment the adversarial string injected into the malformed JSON payload
     */
    @ParameterizedTest(name = "fragment={0}")
    @MethodSource("injectionVariants")
    public void parseError_doesNotLeakAttackerControlledFragment(String fragment) {
        // Arrange
        TestLogAppender logAppender = getTestLogAppender();
        TestLogAppender auditAppender = getAuditLogAppender();
        StompCallback callback = new StompCallback(
                subscriptionManager, mockTemplate, restTemplate,
                UUID.randomUUID().toString(), "hashtag", "glacier@example.com", "example.com");
        MastodonApiEvent.GenericMessage mockGenericMessage = mock(MastodonApiEvent.GenericMessage.class);
        // Truncated JSON — missing closing brace causes Jackson to throw JsonParseException
        // with the fragment visible in the source context of the exception message.
        // SR-TD1-01: wire-payload bytes must not appear in any log field.
        String malformedJson = "{\"event\": \"" + fragment + "\"";  // missing closing brace
        when(mockGenericMessage.getText()).thenReturn(malformedJson);

        // Act
        callback.onEvent(mockGenericMessage);

        // Assert — collect events from both the StompCallback logger and the AUDIT logger
        List<ILoggingEvent> allEvents = new ArrayList<>(logAppender.getLoggedEvents());
        allEvents.addAll(auditAppender.getLoggedEvents());

        // Positive signal: the parse-failure path must have fired
        assertThat(logAppender.getLoggedEvents())
                .anySatisfy(event ->
                        assertThat(event.getFormattedMessage())
                                .startsWith("Could not parse GenericMessage"));

        // SR-TD1-01 / SR-TD1-10 / SR-TD1-11: no event leaks the fragment in its formatted message
        assertThat(allEvents)
                .allSatisfy(event ->
                        assertThat(event.getFormattedMessage())
                                .doesNotContain(fragment));

        // SR-TD1-01: no argument in the argument array resolves to a string containing the fragment
        assertThat(allEvents)
                .allSatisfy(event -> {
                    Object[] args = event.getArgumentArray();
                    if (args != null) {
                        for (Object arg : args) {
                            assertThat(String.valueOf(arg))
                                    .doesNotContain(fragment);
                        }
                    }
                });

        // SR-TD1-09: no Throwable may be attached — prevents Logback from rendering the full
        // JsonProcessingException message (which embeds attacker-controlled source bytes)
        assertThat(allEvents)
                .allSatisfy(event ->
                        assertThat(event.getThrowableProxy()).isNull());
    }

    /**
     * Tests if the event handler processes an unknown Websocket event correctly
     */
    @Test
    public void onEvent_WebsocketEventUnknown() {
        // Setup
        TestLogAppender logAppender = getTestLogAppender();

        StompCallback callback = new StompCallback(subscriptionManager, mockTemplate, restTemplate, UUID.randomUUID().toString(), "hashtag", "glacier@example.com", "example.com");
        WebSocketEvent mockEvent = mock(WebSocketEvent.class);

        // Execute
        callback.onEvent(mockEvent);

        // Verify
        assertThat(logAppender.getLoggedMessages())
                .anySatisfy(msg -> assertThat(msg).contains("got an unknown event: class social.bigbone.api.entity.streaming.WebSocketEvent$"));
    }

    /**
     * Tests if the event handler processes a Technical Failure event correctly
     */
    @Test
    public void onEvent_EventTechnicalFailure() {
        // Setup
        TestLogAppender logAppender = getTestLogAppender();
        String errorMessage = "Error Message";
        StompCallback callback = new StompCallback(subscriptionManager, mockTemplate, restTemplate, UUID.randomUUID().toString(), "hashtag", "glacier@example.com", "example.com");
        TechnicalEvent.Failure mockEvent = mock(TechnicalEvent.Failure.class);
        Throwable mockException = mock(Throwable.class);
        when(mockEvent.getError()).thenReturn(mockException);
        when(mockException.getMessage()).thenReturn(errorMessage);

        // Execute
        callback.onEvent(mockEvent);

        // Verify
        Mockito.verify(mockEvent, Mockito.atLeastOnce()).getError();
        assertThat(logAppender.getLoggedMessages())
                .anySatisfy(msg -> assertThat(msg).contains("got a Failure event. Restarting subscription. The error is: Error Message"));
    }

    /**
     * Creates a {@link TestLogAppender} wired to the {@link StompCallback} logger.
     * <p>
     * Captures both formatted-message strings (for existing tests) and raw
     * {@link ILoggingEvent} objects (for T-A1 through T-A5 level/throwable assertions).
     *
     * @return a started appender already attached to the StompCallback logger
     */
    @NotNull
    private static TestLogAppender getTestLogAppender() {
        TestLogAppender logAppender = new TestLogAppender();
        Logger logger = (Logger) LoggerFactory.getLogger(StompCallback.class);
        logAppender.start();
        logger.addAppender(logAppender);
        return logAppender;
    }

    /**
     * Creates a {@link TestLogAppender} wired to the dedicated {@code "AUDIT"} logger.
     * <p>
     * Used by T-A5 to verify that parse errors are not incorrectly routed to the
     * security audit channel. Returns a started appender ready to capture events.
     *
     * @return a started appender already attached to the AUDIT logger
     */
    @NotNull
    private static TestLogAppender getAuditLogAppender() {
        TestLogAppender auditAppender = new TestLogAppender();
        Logger auditLogger = (Logger) LoggerFactory.getLogger("AUDIT");
        auditAppender.start();
        auditLogger.addAppender(auditAppender);
        return auditAppender;
    }

    /**
     * In-memory Logback appender used by tests to capture log output from
     * {@link StompCallback} without writing to stdout or any real transport.
     * <p>
     * Provides two views of captured output:
     * <ul>
     *   <li>{@link #getLoggedMessages()} — formatted message strings, for backward-compatible
     *       assertions in existing tests.</li>
     *   <li>{@link #getLoggedEvents()} — raw {@link ILoggingEvent} objects, for assertions on
     *       level, throwable proxy, and MDC state (T-A1 through T-A5).</li>
     * </ul>
     */
    @Getter
    static class TestLogAppender extends AppenderBase<ILoggingEvent> {
        private final List<String> loggedMessages = new ArrayList<>();
        private final List<ILoggingEvent> loggedEvents = new ArrayList<>();

        @Override
        protected void append(ILoggingEvent eventObject) {
            loggedMessages.add(eventObject.getFormattedMessage());
            loggedEvents.add(eventObject);
        }
    }
}
