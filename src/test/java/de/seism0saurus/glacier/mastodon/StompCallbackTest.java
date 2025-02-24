package de.seism0saurus.glacier.mastodon;

import de.seism0saurus.glacier.webservice.messaging.messages.StatusCreatedMessage;
import de.seism0saurus.glacier.webservice.messaging.messages.StatusDeletedMessage;
import de.seism0saurus.glacier.webservice.messaging.messages.StatusUpdatedMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mockito;
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

import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

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
     * You can subscribe to a hashtag or terminate a subscription with an UUID.
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
    public void onEvent_statusCreated_sendStatusCreatedToSubsciber() {
        // Setup
        String principal = UUID.randomUUID().toString();
        String hashtag = "hashtag";
        String expectedDestination = "/topic/hashtags/" + principal + "/" + hashtag + "/creation";
        StatusCreatedMessage expectedMessage = StatusCreatedMessage.builder()
                .id("12345")
                .author("peter.kropotkin@example.com")
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

        StompCallback callback = new StompCallback(subscriptionManager, mockTemplate, restTemplate, principal, hashtag, "glacier@example.com",  "glacier.example.com");
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
     * Tests if the event handler processes a Status Edited event correctly
     */
    @Test
    public void onEvent_statusEdited_sendStatusUpdatedToSubsciber() {
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

        StompCallback callback = new StompCallback(subscriptionManager, mockTemplate, restTemplate, principal, hashtag, "glacier@example.com",  "glacier.example.com");
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
    public void onEvent_statusDeleted_sendStatusDeletedToSubsciber() {
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

        StompCallback callback = new StompCallback(subscriptionManager, mockTemplate, restTemplate, principal, hashtag, "glacier@example.com",  "glacier.example.com");
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
    public void onEvent_unknownStreamEvent_dontSendMessageToSubsciber() {
        // Setup
        String principal = UUID.randomUUID().toString();
        String hashtag = "hashtag";

        StompCallback callback = new StompCallback(subscriptionManager, mockTemplate, restTemplate, principal, hashtag, "glacier@example.com",  "glacier.example.com");
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
                .author("peter.kropotkin@example.com")
                .url("https://mastodon.example.com/12345/embed")
                .build();

        doNothing().when(mockTemplate).convertAndSend(eq(expectedDestination), any(StatusCreatedMessage.class));
        Account account = mock(Account.class);
        when(account.getDisplayName()).thenReturn("peter.kropotkin@example.com");
        when(mockStatus.getId()).thenReturn("12345");
        when(mockStatus.getUrl()).thenReturn("https://mastodon.example.com/12345");
        when(mockStatus.getAccount()).thenReturn(account);

        when(restTemplate.headForHeaders("https://mastodon.example.com/12345" + "/embed")).thenReturn(headers);

        StompCallback callback = new StompCallback(subscriptionManager, mockTemplate, restTemplate, principal, hashtag, "glacier@example.com",  "glacier.example.com");
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
     * Tests if the event handler processes a Technical Failure event correctly
     */
    @SuppressWarnings("ThrowableNotThrown")
    @Test
    public void onEvent_EventTechnicalFailure() {
        // Setup
        String errorMessage = "Error Message";
        StompCallback callback = new StompCallback(subscriptionManager, mockTemplate, restTemplate, UUID.randomUUID().toString(), "hashtag", "glacier@example.com",  "example.com");
        TechnicalEvent.Failure mockEvent = mock(TechnicalEvent.Failure.class);
        Throwable mockException = mock(Throwable.class);
        when(mockEvent.getError()).thenReturn(mockException);
        when(mockException.getMessage()).thenReturn(errorMessage);

        // Execute
        callback.onEvent(mockEvent);

        // Verify
        Mockito.verify(mockEvent, Mockito.atLeastOnce()).getError();
    }
}