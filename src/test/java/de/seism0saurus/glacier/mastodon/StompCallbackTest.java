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
     * Tests if the event handler processes a GenericMessage event, that's not a update or delete message, correctly.
     *
     * <p>D-13/SR-8 / Fix #3 (ADR-F6-03): the warn message must use the new structured format
     * {@code stream.generic.unhandled streams-size=N event=...} — never the raw
     * {@code genericMessageContent.toString()} which embeds raw URLs, hashtags and payload.
     * Unknown event names are rendered as {@code unknown(len=N)} by
     * {@link de.seism0saurus.glacier.util.LogScrubber#safeEventName(String)}.
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

        // Verify: new structured log format — never raw genericMessageContent dump
        assertThat(logAppender.getLoggedMessages())
                .anySatisfy(msg -> assertThat(msg).contains("stream.generic.unhandled"));
        // Ensure raw event name from unknown value is not present verbatim (CWE-117 guard)
        assertThat(logAppender.getLoggedMessages())
                .anySatisfy(msg -> assertThat(msg).contains("unknown(len=11)")); // "other_event" has 11 chars
        // Ensure the old unguarded format is gone
        assertThat(logAppender.getLoggedMessages())
                .noneSatisfy(msg -> assertThat(msg).contains("Not an update event for the subscribed hashtag"));
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

        // Verify
        assertThat(logAppender.getLoggedMessages())
                .anySatisfy(msg -> assertThat(msg).contains("Could not parse GenericMessage"));
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

    // -------------------------------------------------------------------------
    // D-13 / SR-8 log-shape tests (Fix #1–#6)
    // -------------------------------------------------------------------------

    /**
     * Fix #1 (ADR-F6-01): constructor must log hashtag-len= instead of the raw hashtag,
     * and principal-hash= instead of the raw UUID principal.
     *
     * <p>Arrange: construct StompCallback with a known hashtag "glacier".
     * Act: construction itself triggers the INFO log.
     * Assert: log contains "hashtag-len=7" (len of "glacier"), never raw "glacier";
     *         log contains "principal-hash=" (hashed), never the raw UUID.
     */
    @Test
    public void constructor_logsHashtagLen_notRawHashtag() {
        // Arrange
        TestLogAppender logAppender = getTestLogAppender();
        String principal = UUID.randomUUID().toString();
        String hashtag = "glacier";

        // Act — construction triggers the log
        new StompCallback(subscriptionManager, mockTemplate, restTemplate, principal, hashtag, "glacier@example.com", "example.com");

        // Assert — Fix #1: structured fields present, raw values absent
        assertThat(logAppender.getLoggedMessages())
                .anySatisfy(msg -> {
                    assertThat(msg).contains("hashtag-len=7");
                    assertThat(msg).contains("principal-hash=");
                    assertThat(msg).doesNotContain(hashtag);
                    assertThat(msg).doesNotContain(principal);
                });
    }

    /**
     * Fix #2 (ADR-F6-02): event.toString() must NOT appear at INFO level.
     * The replacement is a DEBUG log with type-only rendering — not observable at INFO.
     *
     * <p>Arrange: construct StompCallback, attach appender, send any WebSocketEvent.
     * Act: onEvent(streamEvent) for a StatusCreated event.
     * Assert: no INFO-level message contains a raw event.toString() dump;
     *         the appender must NOT capture any INFO message with the raw event class name
     *         as produced by event.toString().
     */
    @Test
    public void onEvent_streamEvent_doesNotLogEventDumpAtInfo() {
        // Arrange
        LevelAwareTestLogAppender logAppender = getLevelAwareTestLogAppender();
        String principal = UUID.randomUUID().toString();
        StompCallback callback = new StompCallback(subscriptionManager, mockTemplate, restTemplate, principal, "hashtag", "glacier@example.com", "glacier.example.com");

        Account account = mock(Account.class);
        when(account.getDisplayName()).thenReturn("user@example.com");
        when(mockStatus.getId()).thenReturn("12345");
        when(mockStatus.getUrl()).thenReturn("https://mastodon.example.com/12345");
        when(mockStatus.getAccount()).thenReturn(account);
        HttpHeaders allowHeader = getHeaders("ALLOWALL", null);
        when(restTemplate.headForHeaders("https://mastodon.example.com/12345/embed")).thenReturn(allowHeader);

        ParsedStreamEvent.StatusCreated parsedEvent = new ParsedStreamEvent.StatusCreated(mockStatus);
        MastodonApiEvent.StreamEvent streamEvent = new MastodonApiEvent.StreamEvent(parsedEvent, List.of());

        // Act
        callback.onEvent(streamEvent);

        // Assert: no INFO message should be a raw event.toString() dump
        assertThat(logAppender.getInfoMessages())
                .noneSatisfy(msg -> assertThat(msg).contains("MastodonApiEvent$StreamEvent"));
        // The DEBUG log with type-only rendering may be present — but must not be at INFO
        assertThat(logAppender.getInfoMessages())
                .noneSatisfy(msg -> assertThat(msg).matches(".*StreamEvent.*StatusCreated.*toString.*"));
    }

    /**
     * Fix #3 (ADR-F6-03): unhandled generic message must log structured fields, never raw dump.
     *
     * <p>This duplicates the existing {@code onEvent_UnrelatedGenericMessageEvent_isIgnored}
     * but focuses on the positive shape of the new log: "stream.generic.unhandled streams-size=N event=unknown(len=M)".
     */
    @Test
    public void onEvent_unhandledGenericEvent_logsStructuredFieldsNotRawDump() throws JsonProcessingException {
        // Arrange
        TestLogAppender logAppender = getTestLogAppender();
        StompCallback callback = new StompCallback(subscriptionManager, mockTemplate, restTemplate, UUID.randomUUID().toString(), "hashtag", "glacier@example.com", "example.com");
        MastodonApiEvent.GenericMessage mockEvent = mock(MastodonApiEvent.GenericMessage.class);

        ObjectMapper mapper = new ObjectMapper();
        JsonNode jsonNode = TextNode.valueOf("{}");
        // Use a stream name that is NOT "hashtag" so neither update/delete branch fires
        GenericMessageContent content = GenericMessageContent.builder()
                .event("notification")   // known event, but stream is "user" not "hashtag"
                .stream(List.of("user"))
                .payload(jsonNode)
                .build();
        String serializedContent = mapper.writeValueAsString(content);
        when(mockEvent.getText()).thenReturn(serializedContent);

        // Act
        callback.onEvent(mockEvent);

        // Assert Fix #3: structured key present
        assertThat(logAppender.getLoggedMessages())
                .anySatisfy(msg -> assertThat(msg).contains("stream.generic.unhandled"));
        // Known event name passes through allowlist verbatim (not "unknown(len=N)")
        assertThat(logAppender.getLoggedMessages())
                .anySatisfy(msg -> assertThat(msg).contains("event=notification"));
        // streams-size is present and correct
        assertThat(logAppender.getLoggedMessages())
                .anySatisfy(msg -> assertThat(msg).contains("streams-size=1"));
    }

    /**
     * Fix #5 (ADR-F6-03): after successful sendMessage, log must use structured triple
     * instead of raw STOMP destination string.
     *
     * <p>Arrange: a full GenericMessage "update" event with loadable toot and opt-in mention.
     *             Uses a unique hashtag value "glacier2025" to avoid false matches on the key "hashtag-len".
     * Act: onEvent processes the message and sends it.
     * Assert: the INFO log says "stomp.message.published" with principal-hash=, hashtag-len=, event-type=
     *         and does NOT contain the raw STOMP destination path or the raw UUID principal.
     */
    @Test
    public void sendMessage_logsStructuredPublishedEvent_notRawDestination() throws JsonProcessingException {
        // Arrange
        TestLogAppender logAppender = getTestLogAppender();
        SimpMessagingTemplate spyMessagingTemplate = spy(new SimpMessagingTemplate((message, timeout) -> true));
        HttpHeaders allowHeader = getHeaders("ALLOWALL", null);
        when(restTemplate.headForHeaders("https://example.com/4567/embed")).thenReturn(allowHeader);

        String principal = UUID.randomUUID().toString();
        // Use a distinctive hashtag that won't accidentally match log key names like "hashtag-len"
        String hashtag = "glacier2025";
        StompCallback callback = new StompCallback(subscriptionManager, spyMessagingTemplate, restTemplate, principal, hashtag, "glacier@example.com", "example.com");

        MastodonApiEvent.GenericMessage mockEvent = mock(MastodonApiEvent.GenericMessage.class);
        ObjectMapper mapper = new ObjectMapper();
        Mention mention = Mention.builder().id("4567").username("@glacier").acct("glacier").build();
        GenericMessageContentPayload payload = GenericMessageContentPayload.builder()
                .mentions(List.of(mention)).url("https://example.com/4567").id("4567").build();
        String payloadAsText = mapper.writeValueAsString(payload);
        JsonNode jsonNode = TextNode.valueOf(payloadAsText);
        // Stream must contain "hashtag" (the literal string) to pass the branch check in processGenericEvent
        GenericMessageContent content = GenericMessageContent.builder()
                .event("update").stream(List.of("hashtag")).payload(jsonNode).build();
        String serializedContent = mapper.writeValueAsString(content);
        when(mockEvent.getText()).thenReturn(serializedContent);

        // Act
        callback.onEvent(mockEvent);

        // Assert Fix #5: structured log fields present, raw destination absent
        assertThat(logAppender.getLoggedMessages())
                .anySatisfy(msg -> {
                    assertThat(msg).contains("stomp.message.published");
                    assertThat(msg).contains("principal-hash=");
                    assertThat(msg).contains("hashtag-len=" + hashtag.length());
                    assertThat(msg).contains("event-type=creation");
                });
        // Raw STOMP destination must not appear in ANY logged message
        assertThat(logAppender.getLoggedMessages())
                .noneSatisfy(msg -> assertThat(msg).contains("/topic/hashtags/"));
        // Raw UUID principal must not appear in ANY logged message
        assertThat(logAppender.getLoggedMessages())
                .noneSatisfy(msg -> assertThat(msg).contains(principal));
        // Raw hashtag value must not appear in ANY logged message
        assertThat(logAppender.getLoggedMessages())
                .noneSatisfy(msg -> assertThat(msg).contains(hashtag));
    }

    @NotNull
    private static TestLogAppender getTestLogAppender() {
        TestLogAppender logAppender = new TestLogAppender();
        Logger logger = (Logger) LoggerFactory.getLogger(StompCallback.class);
        logAppender.start();
        logger.addAppender(logAppender);
        return logAppender;
    }

    @NotNull
    private static LevelAwareTestLogAppender getLevelAwareTestLogAppender() {
        LevelAwareTestLogAppender logAppender = new LevelAwareTestLogAppender();
        Logger logger = (Logger) LoggerFactory.getLogger(StompCallback.class);
        logAppender.start();
        logger.addAppender(logAppender);
        return logAppender;
    }

    @Getter
    static class TestLogAppender extends AppenderBase<ILoggingEvent> {
        private final List<String> loggedMessages = new ArrayList<>();

        @Override
        protected void append(ILoggingEvent eventObject) {
            loggedMessages.add(eventObject.getFormattedMessage());
        }
    }

    /**
     * Level-aware log appender that separates INFO (and above) messages from DEBUG messages.
     * Used for Fix #2 verification: event.toString() must not appear at INFO level.
     */
    @Getter
    static class LevelAwareTestLogAppender extends AppenderBase<ILoggingEvent> {
        private final List<String> infoMessages = new ArrayList<>();
        private final List<String> debugMessages = new ArrayList<>();
        private final List<String> allMessages = new ArrayList<>();

        @Override
        protected void append(ILoggingEvent eventObject) {
            String msg = eventObject.getFormattedMessage();
            allMessages.add(msg);
            if (eventObject.getLevel().isGreaterOrEqual(Level.INFO)) {
                infoMessages.add(msg);
            } else if (eventObject.getLevel() == Level.DEBUG) {
                debugMessages.add(msg);
            }
        }
    }
}
