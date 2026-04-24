package de.seism0saurus.glacier.mastodon;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.TextNode;
import de.seism0saurus.glacier.webservice.cache.CacheEntry;
import de.seism0saurus.glacier.webservice.cache.EventType;
import de.seism0saurus.glacier.webservice.cache.MessageCache;
import de.seism0saurus.glacier.webservice.messaging.PrincipalKey;
import de.seism0saurus.glacier.webservice.messaging.PrincipalKind;
import de.seism0saurus.glacier.webservice.messaging.messages.*;
import lombok.Getter;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
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

import org.springframework.web.client.ResourceAccessException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * The StompCallbackTest class is used to test the functionality of the StompCallback class.
 *
 * <p>Updated per Phase 1 D-03: all assertions now verify interactions with
 * {@link MessageCache#recordThenPublish} instead of {@code SimpMessagingTemplate.convertAndSend}.
 */
public class StompCallbackTest {

    /** Convenience factory: wraps a wallId string in a WALL PrincipalKey. */
    private static PrincipalKey wall(String wallId) {
        return new PrincipalKey(PrincipalKind.WALL, wallId);
    }

    /**
     * The SubscriptionManager interface represents a manager that handles subscriptions for hashtags on Mastodon.
     */
    SubscriptionManager subscriptionManager;

    /**
     * The variable "client" is an instance of the MastodonClient class.
     */
    social.bigbone.MastodonClient client;

    /**
     * Mock of the MessageCache that replaces SimpMessagingTemplate in the updated architecture (D-03).
     */
    MessageCache messageCache;

    /**
     * A RestTemplate object for making HTTP requests.
     */
    RestTemplate restTemplate;

    /**
     * The mockStatus variable represents a mock instance of the Status class.
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
        this.messageCache = mock(MessageCache.class);
        this.restTemplate = mock(RestTemplate.class);
        this.mockStatus = mock(Status.class);
    }

    /**
     * Tests if the event handler processes a Status Created event correctly.
     * Updated per Phase 1 D-03: verifies recordThenPublish instead of convertAndSend.
     */
    @Test
    public void onEvent_statusCreated_sendStatusCreatedToSubscriber() {
        // Setup
        String principal = UUID.randomUUID().toString();
        String hashtag = "hashtag";

        doNothing().when(messageCache).evictHashtag(any(), any());
        Account account = mock(Account.class);
        when(account.getDisplayName()).thenReturn("peter.kropotkin@example.com");
        when(mockStatus.getId()).thenReturn("12345");
        when(mockStatus.getUrl()).thenReturn("https://mastodon.example.com/12345");
        when(mockStatus.getAccount()).thenReturn(account);

        HttpHeaders allowHeader = getHeaders("ALLOWALL", null);
        when(restTemplate.headForHeaders("https://mastodon.example.com/12345" + "/embed")).thenReturn(allowHeader);

        StompCallback callback = new StompCallback(subscriptionManager, messageCache, null, restTemplate, principal, hashtag, "glacier@example.com", "glacier.example.com");
        ParsedStreamEvent.StatusCreated event = new ParsedStreamEvent.StatusCreated(mockStatus);
        MastodonApiEvent.StreamEvent streamEvent = new MastodonApiEvent.StreamEvent(event, List.of());

        // Execute
        callback.onEvent(streamEvent);

        // Verify — updated per Phase 1 D-03
        ArgumentCaptor<CacheEntry> entryCaptor = ArgumentCaptor.forClass(CacheEntry.class);
        verify(messageCache).recordThenPublish(eq(wall(principal)), eq(hashtag), entryCaptor.capture());
        CacheEntry partial = entryCaptor.getValue();
        assertThat(partial.type()).isEqualTo(EventType.CREATED);
        assertThat(partial.statusId()).isEqualTo("12345");
        assertThat(partial.url()).isEqualTo("https://mastodon.example.com/12345/embed");
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
                new StompCallback(subscriptionManager, messageCache, null, restTemplate, UUID.randomUUID().toString(), "hashtag", handle, "glacier.example.com")
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
                new StompCallback(subscriptionManager, messageCache, null, restTemplate, UUID.randomUUID().toString(), "hashtag", handle, "glacier.example.com")
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
        StompCallback stompCallback = new StompCallback(subscriptionManager, messageCache, null, restTemplate, UUID.randomUUID().toString(), "hashtag", handle, "glacier.example.com");

        // Get the private field 'shortHandle' using reflection
        Field shortHandleField = StompCallback.class.getDeclaredField("shortHandle");
        shortHandleField.setAccessible(true);
        String shortHandle = (String) shortHandleField.get(stompCallback);

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
        StompCallback stompCallback = new StompCallback(subscriptionManager, messageCache, null, restTemplate, UUID.randomUUID().toString(), "hashtag", handle, "glacier.example.com");

        // Get the private field 'shortHandle' using reflection
        Field shortHandleField = StompCallback.class.getDeclaredField("shortHandle");
        shortHandleField.setAccessible(true);
        String shortHandle = (String) shortHandleField.get(stompCallback);

        // Assert
        assertEquals("peter.kropotkin", shortHandle);
    }

    /**
     * Tests if the event handler processes a Status Edited event correctly.
     * Updated per Phase 1 D-03: verifies recordThenPublish instead of convertAndSend.
     */
    @Test
    public void onEvent_statusEdited_sendStatusUpdatedToSubscriber() {
        // Setup
        String principal = UUID.randomUUID().toString();
        String hashtag = "hashtag";

        Account account = mock(Account.class);
        when(account.getDisplayName()).thenReturn("peter.kropotkin@example.com");
        when(mockStatus.getId()).thenReturn("12345");
        when(mockStatus.getUrl()).thenReturn("https://mastodon.example.com/12345");
        when(mockStatus.getAccount()).thenReturn(account);

        HttpHeaders allowHeader = getHeaders("ALLOWALL", null);
        when(restTemplate.headForHeaders("https://mastodon.example.com/12345" + "/embed")).thenReturn(allowHeader);

        StompCallback callback = new StompCallback(subscriptionManager, messageCache, null, restTemplate, principal, hashtag, "glacier@example.com", "glacier.example.com");
        ParsedStreamEvent.StatusEdited event = new ParsedStreamEvent.StatusEdited(mockStatus);
        MastodonApiEvent.StreamEvent streamEvent = new MastodonApiEvent.StreamEvent(event, List.of());

        // Execute
        callback.onEvent(streamEvent);

        // Verify — updated per Phase 1 D-03
        ArgumentCaptor<CacheEntry> captor = ArgumentCaptor.forClass(CacheEntry.class);
        verify(messageCache).recordThenPublish(eq(wall(principal)), eq(hashtag), captor.capture());
        assertThat(captor.getValue().type()).isEqualTo(EventType.UPDATED);
    }

    /**
     * Tests if the event handler processes a Status Deleted event correctly.
     * Updated per Phase 1 D-03: verifies recordThenPublish instead of convertAndSend.
     */
    @Test
    public void onEvent_statusDeleted_sendStatusDeletedToSubscriber() {
        // Setup
        String principal = UUID.randomUUID().toString();
        String hashtag = "hashtag";

        StompCallback callback = new StompCallback(subscriptionManager, messageCache, null, restTemplate, principal, hashtag, "glacier@example.com", "glacier.example.com");
        ParsedStreamEvent.StatusDeleted event = new ParsedStreamEvent.StatusDeleted("12345");
        MastodonApiEvent.StreamEvent streamEvent = new MastodonApiEvent.StreamEvent(event, List.of());

        // Execute
        callback.onEvent(streamEvent);

        // Verify — updated per Phase 1 D-03
        ArgumentCaptor<CacheEntry> captor = ArgumentCaptor.forClass(CacheEntry.class);
        verify(messageCache).recordThenPublish(eq(wall(principal)), eq(hashtag), captor.capture());
        CacheEntry partial = captor.getValue();
        assertThat(partial.type()).isEqualTo(EventType.DELETED);
        assertThat(partial.statusId()).isEqualTo("12345");
    }

    /**
     * Tests if the event handler processes an unknown StreamEvent correctly
     * and does not call recordThenPublish.
     * Updated per Phase 1 D-03.
     */
    @Test
    public void onEvent_unknownStreamEvent_dontCallRecordThenPublish() {
        // Setup
        String principal = UUID.randomUUID().toString();
        String hashtag = "hashtag";

        StompCallback callback = new StompCallback(subscriptionManager, messageCache, null, restTemplate, principal, hashtag, "glacier@example.com", "glacier.example.com");
        Notification notification = new Notification();
        ParsedStreamEvent.NewNotification event = new ParsedStreamEvent.NewNotification(notification);
        MastodonApiEvent.StreamEvent streamEvent = new MastodonApiEvent.StreamEvent(event, List.of());

        // Execute
        callback.onEvent(streamEvent);

        // Verify — updated per Phase 1 D-03
        verify(messageCache, times(0)).recordThenPublish(any(), any(), any());
    }

    /**
     * Tests if the headers from the embedded url are correctly parsed and
     * unloadable urls produce zero recordThenPublish calls.
     * Updated per Phase 1 D-03.
     */
    @ParameterizedTest
    @MethodSource("httpHeadersForIframes")
    public void onEvent_statusCreated_testRemoteLoadableByHeaders(final HttpHeaders headers, boolean isLoadable) {
        // Setup
        String principal = UUID.randomUUID().toString();
        String hashtag = "hashtag";

        Account account = mock(Account.class);
        when(account.getDisplayName()).thenReturn("peter.kropotkin@example.com");
        when(mockStatus.getId()).thenReturn("12345");
        when(mockStatus.getUrl()).thenReturn("https://mastodon.example.com/12345");
        when(mockStatus.getAccount()).thenReturn(account);

        when(restTemplate.headForHeaders("https://mastodon.example.com/12345" + "/embed")).thenReturn(headers);

        StompCallback callback = new StompCallback(subscriptionManager, messageCache, null, restTemplate, principal, hashtag, "glacier@example.com", "glacier.example.com");
        ParsedStreamEvent.StatusCreated event = new ParsedStreamEvent.StatusCreated(mockStatus);
        MastodonApiEvent.StreamEvent streamEvent = new MastodonApiEvent.StreamEvent(event, List.of());

        // Execute
        callback.onEvent(streamEvent);

        // Verify — updated per Phase 1 D-03
        if (isLoadable) {
            verify(messageCache, times(1)).recordThenPublish(eq(wall(principal)), eq(hashtag), any(CacheEntry.class));
        } else {
            verify(messageCache, times(0)).recordThenPublish(any(), any(), any());
        }
    }

    public static Stream<Arguments> httpHeadersForIframes() {
        return Stream.of(
                Arguments.of(getHeaders(null, null), true)
                , Arguments.of(getHeaders("ALLOWALL", null), true)
                , Arguments.of(getHeaders("DENY", null), false)
                , Arguments.of(getHeaders("SAMEORIGIN", null), false)
                , Arguments.of(getHeaders("SAMEORIGIN; ALLOWALL", null), false)
                , Arguments.of(getHeaders("GNU Terry Pratchett", null), false)
                , Arguments.of(getHeaders(null, "default-src 'self'; img-src 'self'"), true)
                , Arguments.of(getHeaders(null, "frame-ancestors glacier.example.com; default-src 'self'; img-src 'self';"), true)
                , Arguments.of(getHeaders(null, "default-src 'self'; frame-ancestors glacier.example.com; img-src 'self';"), true)
                , Arguments.of(getHeaders(null, "default-src 'self';  img-src 'self'; frame-ancestors glacier.example.com;"), true)
                , Arguments.of(getHeaders(null, "frame-ancestors 'none'"), false)
                , Arguments.of(getHeaders(null, "frame-ancestors othersite.example.com;"), false)
                , Arguments.of(getHeaders(null, "frame-ancestors glacier.example.com;"), true)
                , Arguments.of(getHeaders(null, "frame-ancestors othersite.example.com glacier.example.com;"), true)
                , Arguments.of(getHeaders(null, "frame-ancestors glacier.example.com:80;"), true)
                , Arguments.of(getHeaders(null, "frame-ancestors glacier.example.com:443;"), true)
                , Arguments.of(getHeaders(null, "frame-ancestors http://glacier.example.com;"), true)
                , Arguments.of(getHeaders(null, "frame-ancestors https://glacier.example.com;"), true)
                , Arguments.of(getHeaders(null, "frame-ancestors http://glacier.example.com:80;"), true)
                , Arguments.of(getHeaders(null, "frame-ancestors https://glacier.example.com:443;"), true)
                , Arguments.of(getHeaders(null, "frame-ancestors http:;"), true)
                , Arguments.of(getHeaders(null, "frame-ancestors https:;"), true)
                , Arguments.of(getHeaders(null, ""), true)
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

        StompCallback callback = new StompCallback(subscriptionManager, messageCache, null, restTemplate, UUID.randomUUID().toString(), "hashtag", "glacier@example.com", "example.com");
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

        StompCallback callback = new StompCallback(subscriptionManager, messageCache, null, restTemplate, UUID.randomUUID().toString(), "hashtag", "glacier@example.com", "example.com");
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

        StompCallback callback = new StompCallback(subscriptionManager, messageCache, null, restTemplate, UUID.randomUUID().toString(), "hashtag", "glacier@example.com", "example.com");
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

        StompCallback callback = new StompCallback(subscriptionManager, messageCache, null, restTemplate, UUID.randomUUID().toString(), "hashtag", "glacier@example.com", "example.com");
        TechnicalEvent mockEvent = mock(TechnicalEvent.class);

        // Execute
        callback.onEvent(mockEvent);

        // Verify
        assertThat(logAppender.getLoggedMessages())
                .anySatisfy(msg -> assertThat(msg).contains("got an unknown WebSocketEvent:"));
    }

    /**
     * Tests if the event handler processes a GenericMessage status.update event with unloadable toot and optin correctly.
     * Updated per Phase 1 D-03: verifies zero recordThenPublish.
     */
    @Test
    public void onEvent_EventGenericMessage_UpdateWithUnloadableTootAndOptInIsHandled() throws JsonProcessingException {
        // Setup
        TestLogAppender logAppender = getTestLogAppender();

        HttpHeaders allowHeader = getHeaders("DENY", null);
        when(restTemplate.headForHeaders("https://example.com/4567" + "/embed")).thenReturn(allowHeader);

        StompCallback callback = new StompCallback(subscriptionManager, messageCache, null, restTemplate, UUID.randomUUID().toString(), "hashtag", "glacier@example.com", "example.com");

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

        // Verify — updated per Phase 1 D-03
        verify(messageCache, times(0)).recordThenPublish(any(), any(), any());
        assertThat(logAppender.getLoggedMessages())
                .anySatisfy(msg -> assertThat(msg).contains("Toot not loadable by this glacier instance. Ignoring"));
    }

    /**
     * Tests if the event handler processes a GenericMessage status.update event with loadable toot but without optin correctly.
     * Updated per Phase 1 D-03: verifies zero recordThenPublish.
     */
    @Test
    public void onEvent_EventGenericMessage_UpdateWithLoadableTootButMissingOptInIsHandled() throws JsonProcessingException {
        // Setup
        TestLogAppender logAppender = getTestLogAppender();

        HttpHeaders allowHeader = getHeaders("ALLOWALL", null);
        when(restTemplate.headForHeaders("https://example.com/4567" + "/embed")).thenReturn(allowHeader);

        StompCallback callback = new StompCallback(subscriptionManager, messageCache, null, restTemplate, UUID.randomUUID().toString(), "hashtag", "glacier@example.com", "example.com");

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

        // Verify — updated per Phase 1 D-03
        verify(messageCache, times(0)).recordThenPublish(any(), any(), any());
        assertThat(logAppender.getLoggedMessages())
                .anySatisfy(msg -> assertThat(msg).contains("No opt in. Ignoring"));
    }

    /**
     * Tests if the event handler processes a GenericMessage status.update event with loadable toot and optin correctly.
     * Updated per Phase 1 D-03: verifies recordThenPublish with UPDATED CacheEntry.
     */
    @Test
    public void onEvent_EventGenericMessage_StatusUpdateWithLoadableTootAndOptInIsHandled() throws JsonProcessingException {
        // Setup
        HttpHeaders allowHeader = getHeaders("ALLOWALL", null);
        when(restTemplate.headForHeaders("https://example.com/4567" + "/embed")).thenReturn(allowHeader);

        StompCallback callback = new StompCallback(subscriptionManager, messageCache, null, restTemplate, UUID.randomUUID().toString(), "hashtag", "glacier@example.com", "example.com");

        MastodonApiEvent.GenericMessage mockEvent = mock(MastodonApiEvent.GenericMessage.class);
        ObjectMapper mapper = new ObjectMapper();
        Mention mention = Mention.builder().id("4567").username("@peter.kropotkin").acct("glacier").build();
        // Use a valid ISO-8601 UTC timestamp for the editedAt field
        GenericMessageContentPayload payload = GenericMessageContentPayload.builder().mentions(List.of(mention)).url("https://example.com/4567").id("4567").editedAt("2026-04-21T10:00:00Z").build();
        String payloadAsText = mapper.writeValueAsString(payload);
        JsonNode jsonNode = TextNode.valueOf(payloadAsText);
        GenericMessageContent content = GenericMessageContent.builder().event("status.update").stream(List.of("hashtag")).payload(jsonNode).build();
        String serializedContent = mapper.writeValueAsString(content);
        when(mockEvent.getText()).thenReturn(serializedContent);

        // Execute
        callback.onEvent(mockEvent);

        // Verify — updated per Phase 1 D-03
        ArgumentCaptor<CacheEntry> captor = ArgumentCaptor.forClass(CacheEntry.class);
        verify(messageCache, times(1)).recordThenPublish(any(), eq("hashtag"), captor.capture());
        assertThat(captor.getValue().type()).isEqualTo(EventType.UPDATED);
        assertThat(captor.getValue().statusId()).isEqualTo("4567");
    }

    /**
     * Tests if the event handler processes a GenericMessage update event with loadable toot and optin correctly.
     * Updated per Phase 1 D-03: verifies recordThenPublish with CREATED CacheEntry.
     */
    @Test
    public void onEvent_EventGenericMessage_UpdateWithLoadableTootAndOptInIsHandled() throws JsonProcessingException {
        // Setup
        HttpHeaders allowHeader = getHeaders("ALLOWALL", null);
        when(restTemplate.headForHeaders("https://example.com/4567" + "/embed")).thenReturn(allowHeader);

        StompCallback callback = new StompCallback(subscriptionManager, messageCache, null, restTemplate, UUID.randomUUID().toString(), "hashtag", "glacier@example.com", "example.com");

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

        // Verify — updated per Phase 1 D-03
        ArgumentCaptor<CacheEntry> captor = ArgumentCaptor.forClass(CacheEntry.class);
        verify(messageCache, times(1)).recordThenPublish(any(), eq("hashtag"), captor.capture());
        assertThat(captor.getValue().type()).isEqualTo(EventType.CREATED);
        assertThat(captor.getValue().statusId()).isEqualTo("4567");
    }

    /**
     * Tests if the event handler processes a GenericMessage delete event correctly.
     * Updated per Phase 1 D-03.
     */
    @Test
    public void onEvent_EventGenericMessage_DeleteIsHandled() throws JsonProcessingException {
        // Setup
        StompCallback callback = new StompCallback(subscriptionManager, messageCache, null, restTemplate, UUID.randomUUID().toString(), "hashtag", "glacier@example.com", "example.com");
        MastodonApiEvent.GenericMessage mockEvent = mock(MastodonApiEvent.GenericMessage.class);

        ObjectMapper mapper = new ObjectMapper();
        String payloadAsText = mapper.writeValueAsString(4567);
        JsonNode jsonNode = TextNode.valueOf(payloadAsText);
        GenericMessageContent content = GenericMessageContent.builder().event("delete").stream(List.of("hashtag")).payload(jsonNode).build();
        String serializedContent = mapper.writeValueAsString(content);
        when(mockEvent.getText()).thenReturn(serializedContent);

        // Execute
        callback.onEvent(mockEvent);

        // Verify — updated per Phase 1 D-03
        ArgumentCaptor<CacheEntry> captor = ArgumentCaptor.forClass(CacheEntry.class);
        verify(messageCache, times(1)).recordThenPublish(any(), eq("hashtag"), captor.capture());
        assertThat(captor.getValue().type()).isEqualTo(EventType.DELETED);
    }

    /**
     * Tests if the event handler processes a GenericMessage status.delete event correctly.
     * Updated per Phase 1 D-03.
     */
    @Test
    public void onEvent_EventGenericMessage_StatusDeleteIsHandled() throws JsonProcessingException {
        // Setup
        StompCallback callback = new StompCallback(subscriptionManager, messageCache, null, restTemplate, UUID.randomUUID().toString(), "hashtag", "glacier@example.com", "example.com");
        MastodonApiEvent.GenericMessage mockEvent = mock(MastodonApiEvent.GenericMessage.class);

        ObjectMapper mapper = new ObjectMapper();
        String payloadAsText = mapper.writeValueAsString(4567);
        JsonNode jsonNode = TextNode.valueOf(payloadAsText);
        GenericMessageContent content = GenericMessageContent.builder().event("status.delete").stream(List.of("hashtag")).payload(jsonNode).build();
        String serializedContent = mapper.writeValueAsString(content);
        when(mockEvent.getText()).thenReturn(serializedContent);

        // Execute
        callback.onEvent(mockEvent);

        // Verify — updated per Phase 1 D-03
        verify(messageCache, times(1)).recordThenPublish(any(), eq("hashtag"), any(CacheEntry.class));
    }

    /**
     * Tests if the event handler processes a GenericMessage event, that's not a update or delete message, correctly
     */
    @Test
    public void onEvent_UnrelatedGenericMessageEvent_isIgnored() throws JsonProcessingException {
        // Setup
        TestLogAppender logAppender = getTestLogAppender();
        StompCallback callback = new StompCallback(subscriptionManager, messageCache, null, restTemplate, UUID.randomUUID().toString(), "hashtag", "glacier@example.com", "example.com");
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
        StompCallback callback = new StompCallback(subscriptionManager, messageCache, null, restTemplate, UUID.randomUUID().toString(), "hashtag", "glacier@example.com", "example.com");
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

        StompCallback callback = new StompCallback(subscriptionManager, messageCache, null, restTemplate, UUID.randomUUID().toString(), "hashtag", "glacier@example.com", "example.com");
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
        StompCallback callback = new StompCallback(subscriptionManager, messageCache, null, restTemplate, UUID.randomUUID().toString(), "hashtag", "glacier@example.com", "example.com");
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

    // -----------------------------------------------------------------
    // New tests for Phase 1 features (T-12, D-07)
    // -----------------------------------------------------------------

    /**
     * T-12: non-loadable toot must produce zero recordThenPublish calls.
     */
    @Test
    public void onEvent_statusCreated_nonLoadableToot_zeroRecordThenPublish() {
        // Arrange
        String principal = UUID.randomUUID().toString();
        String hashtag = "hashtag";
        when(mockStatus.getId()).thenReturn("12345");
        when(mockStatus.getUrl()).thenReturn("https://mastodon.example.com/12345");

        HttpHeaders denyHeader = getHeaders("DENY", null);
        when(restTemplate.headForHeaders("https://mastodon.example.com/12345/embed")).thenReturn(denyHeader);

        StompCallback callback = new StompCallback(subscriptionManager, messageCache, null, restTemplate, principal, hashtag, "glacier@example.com", "glacier.example.com");
        ParsedStreamEvent.StatusCreated event = new ParsedStreamEvent.StatusCreated(mockStatus);
        MastodonApiEvent.StreamEvent streamEvent = new MastodonApiEvent.StreamEvent(event, List.of());

        // Act
        callback.onEvent(streamEvent);

        // Assert — T-12
        verify(messageCache, never()).recordThenPublish(any(), any(), any());
    }

    /**
     * editedAt UTC normalisation: offset form +02:00 must be converted to Z form (D-07).
     */
    @Test
    public void normaliseEditedAt_offsetForm_convertedToUtcZ() {
        String utc = StompCallback.normaliseEditedAt("2026-04-21T10:00:00+02:00");

        assertThat(utc).endsWith("Z");
        assertThat(utc).isEqualTo("2026-04-21T08:00:00Z");
    }

    /**
     * editedAt UTC normalisation: already-UTC form must be preserved (D-07).
     */
    @Test
    public void normaliseEditedAt_utcForm_returnedUnchanged() {
        String utc = StompCallback.normaliseEditedAt("2026-04-21T10:00:00Z");
        assertThat(utc).isEqualTo("2026-04-21T10:00:00Z");
    }

    /**
     * D-07: malformed editedAt must log WARN and return null — no recordThenPublish.
     */
    @Test
    public void normaliseEditedAt_malformed_returnsNull() {
        TestLogAppender logAppender = getTestLogAppender();

        String result = StompCallback.normaliseEditedAt("not-a-date");

        assertThat(result).isNull();
        assertThat(logAppender.getLoggedMessages())
                .anySatisfy(msg -> assertThat(msg).contains("Could not parse editedAt"));
    }

    /**
     * D-07: GenericMessage with malformed editedAt must drop the event — zero recordThenPublish.
     */
    @Test
    public void onEvent_EventGenericMessage_MalformedEditedAt_dropsEvent() throws JsonProcessingException {
        // Arrange
        TestLogAppender logAppender = getTestLogAppender();
        HttpHeaders allowHeader = getHeaders("ALLOWALL", null);
        when(restTemplate.headForHeaders(anyString())).thenReturn(allowHeader);

        StompCallback callback = new StompCallback(subscriptionManager, messageCache, null, restTemplate, UUID.randomUUID().toString(), "hashtag", "glacier@example.com", "example.com");

        ObjectMapper mapper = new ObjectMapper();
        Mention mention = Mention.builder().id("4567").username("@peter.kropotkin").acct("glacier").build();
        GenericMessageContentPayload payload = GenericMessageContentPayload.builder()
                .mentions(List.of(mention))
                .url("https://example.com/4567")
                .id("4567")
                .editedAt("NOT_A_DATE")
                .build();
        String payloadAsText = mapper.writeValueAsString(payload);
        JsonNode jsonNode = TextNode.valueOf(payloadAsText);
        GenericMessageContent content = GenericMessageContent.builder()
                .event("status.update")
                .stream(List.of("hashtag"))
                .payload(jsonNode)
                .build();
        when(mock(MastodonApiEvent.GenericMessage.class).getText()).thenReturn(mapper.writeValueAsString(content));

        MastodonApiEvent.GenericMessage mockEvent = mock(MastodonApiEvent.GenericMessage.class);
        when(mockEvent.getText()).thenReturn(mapper.writeValueAsString(content));

        // Act
        callback.onEvent(mockEvent);

        // Assert
        verify(messageCache, never()).recordThenPublish(any(), any(), any());
        assertThat(logAppender.getLoggedMessages())
                .anySatisfy(msg -> assertThat(msg).contains("Could not parse editedAt"));
    }

    // -----------------------------------------------------------------
    // C-03: RestTemplate timeout / RestClientException handling
    // -----------------------------------------------------------------

    /**
     * C-03 — StreamEvent path: when headForHeaders throws ResourceAccessException
     * (connect or read timeout), the event is dropped silently, recordThenPublish is
     * never called, and no exception propagates out of onEvent.
     */
    @Test
    public void onEvent_statusCreated_headRequestThrowsResourceAccessException_dropsEventWithoutException() {
        // Arrange
        when(mockStatus.getId()).thenReturn("99999");
        when(mockStatus.getUrl()).thenReturn("https://slow.example.com/99999");
        when(restTemplate.headForHeaders(anyString()))
                .thenThrow(new ResourceAccessException("Read timed out"));

        StompCallback callback = new StompCallback(
                subscriptionManager, messageCache, null, restTemplate,
                UUID.randomUUID().toString(), "hashtag", "glacier@example.com", "example.com");
        ParsedStreamEvent.StatusCreated event = new ParsedStreamEvent.StatusCreated(mockStatus);
        MastodonApiEvent.StreamEvent streamEvent = new MastodonApiEvent.StreamEvent(event, List.of());

        // Act + Assert — must not throw
        assertDoesNotThrow(() -> callback.onEvent(streamEvent));

        // Assert — recordThenPublish must never be called (C-03)
        verify(messageCache, never()).recordThenPublish(any(), any(), any());
    }

    /**
     * C-03 — GenericMessage path: when headForHeaders throws ResourceAccessException
     * (connect or read timeout), the event is dropped silently, recordThenPublish is
     * never called, and no exception propagates out of onEvent.
     */
    @Test
    public void onEvent_genericMessage_headRequestThrowsResourceAccessException_dropsEventWithoutException() throws JsonProcessingException {
        // Arrange
        when(restTemplate.headForHeaders(anyString()))
                .thenThrow(new ResourceAccessException("Read timed out"));

        StompCallback callback = new StompCallback(
                subscriptionManager, messageCache, null, restTemplate,
                UUID.randomUUID().toString(), "hashtag", "glacier@example.com", "example.com");

        ObjectMapper mapper = new ObjectMapper();
        Mention mention = Mention.builder().id("99999").username("@user").acct("glacier").build();
        GenericMessageContentPayload payload = GenericMessageContentPayload.builder()
                .mentions(List.of(mention))
                .url("https://slow.example.com/99999")
                .id("99999")
                .build();
        String payloadAsText = mapper.writeValueAsString(payload);
        JsonNode jsonNode = TextNode.valueOf(payloadAsText);
        GenericMessageContent content = GenericMessageContent.builder()
                .event("update")
                .stream(List.of("hashtag"))
                .payload(jsonNode)
                .build();
        MastodonApiEvent.GenericMessage mockEvent = mock(MastodonApiEvent.GenericMessage.class);
        when(mockEvent.getText()).thenReturn(mapper.writeValueAsString(content));

        // Act + Assert — must not throw
        assertDoesNotThrow(() -> callback.onEvent(mockEvent));

        // Assert — recordThenPublish must never be called (C-03)
        verify(messageCache, never()).recordThenPublish(any(), any(), any());
    }

    @NotNull
    private static TestLogAppender getTestLogAppender() {
        TestLogAppender logAppender = new TestLogAppender();
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
}
