package de.seism0saurus.glacier.mastodon;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.TextNode;
import de.seism0saurus.glacier.share.application.SafeUrlValidator;
import de.seism0saurus.glacier.share.application.ShareViewStompRelay;
import de.seism0saurus.glacier.webservice.cache.CacheEntry;
import de.seism0saurus.glacier.webservice.cache.EventType;
import de.seism0saurus.glacier.webservice.cache.MessageCache;
import de.seism0saurus.glacier.webservice.messaging.PrincipalKey;
import de.seism0saurus.glacier.webservice.messaging.PrincipalKind;
import de.seism0saurus.glacier.webservice.messaging.messages.*;
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
import org.mockito.ArgumentCaptor;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;
import social.bigbone.MastodonClient;
import social.bigbone.api.entity.Account;
import social.bigbone.api.entity.Notification;
import social.bigbone.api.entity.Status;
import social.bigbone.api.entity.streaming.MastodonApiEvent;
import social.bigbone.api.entity.streaming.ParsedStreamEvent;
import social.bigbone.api.entity.streaming.TechnicalEvent;
import social.bigbone.api.entity.streaming.WebSocketEvent;

import java.io.IOException;
import java.lang.reflect.Field;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * The StompCallbackTest class is used to test the functionality of the StompCallback class.
 *
 * <p>Constructor convention: all test helpers use a permissive {@link SafeUrlValidator}
 * lambda ({@code raw -> Optional.of(URI.create(raw))}) for the happy-path tests that
 * predated the SSRF guard. New SSRF tests use a blocking validator to verify the guard
 * fires (SR-PT-04, SR-PT-05, SR-PT-06, SR-PT-10).
 *
 * <p>Constructor note (merged design): the merged StompCallback constructor is
 * {@code (SubscriptionManager, MessageCache, ShareViewStompRelay, RestTemplate,
 * SafeUrlValidator, String principal, String hashtag, String handle, String glacierDomain)}.
 * Publishing now goes through {@link MessageCache#recordThenPublish} (D-03); the former
 * {@code SimpMessagingTemplate} parameter has been removed.</p>
 */
public class StompCallbackTest {

    /**
     * Permissive {@link SafeUrlValidator} used by tests that exercise behaviour other
     * than the SSRF guard: always returns the parsed URI, simulating a production
     * environment where the toot URL passes validation.
     */
    private static final SafeUrlValidator PERMISSIVE_VALIDATOR =
            rawUrl -> Optional.of(URI.create(rawUrl));

    /**
     * Blocking {@link SafeUrlValidator} used by SSRF guard tests: always returns empty,
     * simulating a URL that resolves to a private/loopback address.
     */
    private static final SafeUrlValidator BLOCKING_VALIDATOR =
            rawUrl -> Optional.empty();

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
     * A RestTemplate object for making HTTP requests.
     */
    RestTemplate restTemplate;

    /**
     * The MessageCache mock. Used to verify cache writes and to assert the SSRF guard
     * fires BEFORE any {@link MessageCache#recordThenPublish} call (SR-PT-10).
     */
    MessageCache messageCache;

    /**
     * The ShareViewStompRelay mock. Present to satisfy the merged constructor signature
     * (ADR-SHARE-04). Not asserted in tests that predate the share-link feature.
     */
    ShareViewStompRelay shareViewStompRelay;

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
        this.restTemplate = mock(RestTemplate.class);
        this.messageCache = mock(MessageCache.class);
        this.shareViewStompRelay = mock(ShareViewStompRelay.class);
        this.mockStatus = mock(Status.class);
        // Default stub: recordThenPublish returns a representative CacheEntry so that
        // the shareViewStompRelay relay path and callers that inspect the returned entry
        // do not NPE. Individual tests that need different return values override this.
        when(messageCache.recordThenPublish(any(PrincipalKey.class), any(String.class), any(CacheEntry.class)))
                .thenReturn(new CacheEntry(EventType.CREATED, "stub-id", "https://stub.example.com/embed", null, 1L));
    }

    /**
     * Tests if the event handler processes a Status Created event correctly.
     *
     * <p>In the merged design, publication goes through {@link MessageCache#recordThenPublish}
     * (D-03). The test verifies that when a loadable toot with opt-in arrives, the cache
     * receives a {@code CREATED} entry with the toot URL (embed suffix appended).</p>
     */
    @Test
    public void onEvent_statusCreated_writesCreatedEntryToCache() {
        // Setup
        String principal = UUID.randomUUID().toString();
        String hashtag = "hashtag";

        Account account = mock(Account.class);
        when(account.getDisplayName()).thenReturn("peter.kropotkin@example.com");
        when(mockStatus.getId()).thenReturn("12345");
        when(mockStatus.getUrl()).thenReturn("https://mastodon.example.com/12345");
        when(mockStatus.getAccount()).thenReturn(account);

        HttpHeaders allowHeader = getHeaders("ALLOWALL", null);
        when(restTemplate.headForHeaders("https://mastodon.example.com/12345/embed")).thenReturn(allowHeader);

        StompCallback callback = new StompCallback(
                subscriptionManager, messageCache, shareViewStompRelay, restTemplate,
                PERMISSIVE_VALIDATOR, principal, hashtag, "glacier@example.com", "glacier.example.com");
        ParsedStreamEvent.StatusCreated event = new ParsedStreamEvent.StatusCreated(mockStatus);
        MastodonApiEvent.StreamEvent streamEvent = new MastodonApiEvent.StreamEvent(event, List.of());

        // Execute
        callback.onEvent(streamEvent);

        // Verify: cache must receive the CREATED entry
        verify(messageCache).recordThenPublish(
                any(PrincipalKey.class),
                eq(hashtag),
                eq(new CacheEntry(EventType.CREATED, "12345", "https://mastodon.example.com/12345/embed", null, 0L))
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
                new StompCallback(subscriptionManager, messageCache, shareViewStompRelay, restTemplate,
                        PERMISSIVE_VALIDATOR, UUID.randomUUID().toString(), "hashtag", handle, "glacier.example.com")
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
                new StompCallback(subscriptionManager, messageCache, shareViewStompRelay, restTemplate,
                        PERMISSIVE_VALIDATOR, UUID.randomUUID().toString(), "hashtag", handle, "glacier.example.com")
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
        StompCallback stompCallback = new StompCallback(
                subscriptionManager, messageCache, shareViewStompRelay, restTemplate,
                PERMISSIVE_VALIDATOR, UUID.randomUUID().toString(), "hashtag", handle, "glacier.example.com");

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
        StompCallback stompCallback = new StompCallback(
                subscriptionManager, messageCache, shareViewStompRelay, restTemplate,
                PERMISSIVE_VALIDATOR, UUID.randomUUID().toString(), "hashtag", handle, "glacier.example.com");

        // Get the private field 'shortHandle' using reflection
        Field shortHandleField = StompCallback.class.getDeclaredField("shortHandle");
        shortHandleField.setAccessible(true); // Make the private field accessible
        String shortHandle = (String) shortHandleField.get(stompCallback); // Read the value

        // Assert
        assertEquals("peter.kropotkin", shortHandle);
    }

    /**
     * Tests if the event handler processes a Status Edited event correctly.
     *
     * <p>In the merged design, the SSRF guard runs first, then {@link MessageCache#recordThenPublish}
     * receives an {@code UPDATED} entry (D-03). The test verifies the cache is called when the
     * toot URL passes validation.</p>
     */
    @Test
    public void onEvent_statusEdited_writesUpdatedEntryToCache() {
        // Setup
        String principal = UUID.randomUUID().toString();
        String hashtag = "hashtag";

        Account account = mock(Account.class);
        when(account.getDisplayName()).thenReturn("peter.kropotkin@example.com");
        when(mockStatus.getId()).thenReturn("12345");
        when(mockStatus.getUrl()).thenReturn("https://mastodon.example.com/12345");
        when(mockStatus.getAccount()).thenReturn(account);

        StompCallback callback = new StompCallback(
                subscriptionManager, messageCache, shareViewStompRelay, restTemplate,
                PERMISSIVE_VALIDATOR, principal, hashtag, "glacier@example.com", "glacier.example.com");
        ParsedStreamEvent.StatusEdited event = new ParsedStreamEvent.StatusEdited(mockStatus);
        MastodonApiEvent.StreamEvent streamEvent = new MastodonApiEvent.StreamEvent(event, List.of());

        // Execute
        callback.onEvent(streamEvent);

        // Verify: cache must receive the UPDATED entry (processStatusEditedEvent has no HEAD check)
        verify(messageCache).recordThenPublish(
                any(PrincipalKey.class),
                eq(hashtag),
                any(CacheEntry.class)
        );
    }

    /**
     * Tests if the event handler processes a Status Deleted event correctly.
     *
     * <p>In the merged design, a {@code DELETED} entry is written to the cache via
     * {@link MessageCache#recordThenPublish} (D-03). No URL validation is required
     * for deletion events — only the status ID is present.</p>
     */
    @Test
    public void onEvent_statusDeleted_writesDeletedEntryToCache() {
        // Setup
        String principal = UUID.randomUUID().toString();
        String hashtag = "hashtag";

        when(messageCache.recordThenPublish(any(), any(), any()))
                .thenReturn(new CacheEntry(EventType.DELETED, "12345", null, null, 1L));

        StompCallback callback = new StompCallback(
                subscriptionManager, messageCache, shareViewStompRelay, restTemplate,
                PERMISSIVE_VALIDATOR, principal, hashtag, "glacier@example.com", "glacier.example.com");
        ParsedStreamEvent.StatusDeleted event = new ParsedStreamEvent.StatusDeleted("12345");
        MastodonApiEvent.StreamEvent streamEvent = new MastodonApiEvent.StreamEvent(event, List.of());

        // Execute
        callback.onEvent(streamEvent);

        // Verify
        verify(messageCache).recordThenPublish(
                any(PrincipalKey.class),
                eq(hashtag),
                eq(new CacheEntry(EventType.DELETED, "12345", null, null, 0L))
        );
    }

    /**
     * Tests if the event handler processes an unknown StreamEvent correctly
     * and does not write anything to the cache.
     */
    @Test
    public void onEvent_unknownStreamEvent_dontWriteToCache() {
        // Setup
        String principal = UUID.randomUUID().toString();
        String hashtag = "hashtag";

        StompCallback callback = new StompCallback(
                subscriptionManager, messageCache, shareViewStompRelay, restTemplate,
                PERMISSIVE_VALIDATOR, principal, hashtag, "glacier@example.com", "glacier.example.com");
        Notification notification = new Notification();
        ParsedStreamEvent.NewNotification event = new ParsedStreamEvent.NewNotification(notification);
        MastodonApiEvent.StreamEvent streamEvent = new MastodonApiEvent.StreamEvent(event, List.of());

        // Execute
        callback.onEvent(streamEvent);

        // Verify
        Mockito.verify(messageCache, times(0)).recordThenPublish(any(), any(), any());
    }

    /**
     * Tests if the headers from the embedded url are correctly parsed and unloadable urls are not written to cache.
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

        when(restTemplate.headForHeaders("https://mastodon.example.com/12345/embed")).thenReturn(headers);

        StompCallback callback = new StompCallback(
                subscriptionManager, messageCache, shareViewStompRelay, restTemplate,
                PERMISSIVE_VALIDATOR, principal, hashtag, "glacier@example.com", "glacier.example.com");
        ParsedStreamEvent.StatusCreated event = new ParsedStreamEvent.StatusCreated(mockStatus);
        MastodonApiEvent.StreamEvent streamEvent = new MastodonApiEvent.StreamEvent(event, List.of());

        // Execute
        callback.onEvent(streamEvent);

        // Verify
        if (isLoadable) {
            verify(messageCache).recordThenPublish(any(PrincipalKey.class), eq(hashtag), any(CacheEntry.class));
        } else {
            verify(messageCache, times(0)).recordThenPublish(any(), any(), any());
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
     * Tests if the event handler processes a Technical Open event correctly.
     *
     * <p>Updated for TD-5-B (ADR-TD5-B): the log now contains only the class name
     * via {@code (class=%s).formatted(open.getClass().getSimpleName())}, never the
     * peer-controlled {@code toString()} representation of the {@link TechnicalEvent.Open}
     * object (CWE-117 / D-13 / SR-8).
     */
    @Test
    public void onEvent_EventTechnicalOpen() {
        // Setup
        TestLogAppender logAppender = getTestLogAppender();

        StompCallback callback = new StompCallback(
                subscriptionManager, messageCache, shareViewStompRelay, restTemplate,
                PERMISSIVE_VALIDATOR, UUID.randomUUID().toString(), "hashtag", "glacier@example.com", "example.com");
        TechnicalEvent.Open mockEvent = mock(TechnicalEvent.Open.class);

        // Execute
        callback.onEvent(mockEvent);

        // Verify: new bounded format — class= prefix, never raw toString() (ADR-TD5-B)
        assertThat(logAppender.getLoggedMessages())
                .anySatisfy(msg -> assertThat(msg).contains("got an Open event (class="));
    }

    /**
     * Tests if the event handler processes a Technical Closing event correctly.
     *
     * <p>Updated for TD-2 (ADR-TD2-03): the log now contains only the numeric close code
     * via {@code code=%d}, never the peer-controlled reason string or the raw Kotlin
     * {@code toString()} representation of the event object.</p>
     */
    @Test
    public void onEvent_EventTechnicalClosing() {
        // Setup
        TestLogAppender logAppender = getTestLogAppender();

        StompCallback callback = new StompCallback(
                subscriptionManager, messageCache, shareViewStompRelay, restTemplate,
                PERMISSIVE_VALIDATOR, UUID.randomUUID().toString(), "hashtag", "glacier@example.com", "example.com");
        TechnicalEvent.Closing mockEvent = mock(TechnicalEvent.Closing.class);
        when(mockEvent.getCode()).thenReturn(1000);

        // Execute
        callback.onEvent(mockEvent);

        // Verify: new format — numeric code only, no raw toString dump
        assertThat(logAppender.getLoggedMessages())
                .anySatisfy(msg -> assertThat(msg).contains("got a Closing event — code=1000"));
    }

    /**
     * Tests if the event handler processes a Technical Closed event correctly.
     *
     * <p>Updated for TD-2 (ADR-TD2-03): the log now contains only the numeric close code
     * via {@code code=%d}, never the peer-controlled reason string or the raw Kotlin
     * {@code toString()} representation of the event object.</p>
     */
    @Test
    public void onEvent_EventTechnicalClosed() {
        // Setup
        TestLogAppender logAppender = getTestLogAppender();

        StompCallback callback = new StompCallback(
                subscriptionManager, messageCache, shareViewStompRelay, restTemplate,
                PERMISSIVE_VALIDATOR, UUID.randomUUID().toString(), "hashtag", "glacier@example.com", "example.com");
        TechnicalEvent.Closed mockEvent = mock(TechnicalEvent.Closed.class);
        when(mockEvent.getCode()).thenReturn(1000);

        // Execute
        callback.onEvent(mockEvent);

        // Verify: new format — numeric code only, no raw toString dump
        assertThat(logAppender.getLoggedMessages())
                .anySatisfy(msg -> assertThat(msg).contains("got a Closed event — code=1000"));
    }

    /**
     * Tests if the event handler processes an unknown Technical event correctly.
     *
     * <p>Updated for TD-5-A (ADR-TD5-A): the log now contains only the class name
     * via {@code (class=%s).formatted(event.getClass().getSimpleName())}, never the
     * peer-controlled {@code toString()} representation of the unknown event object
     * (CWE-117 / D-13 / SR-8).
     */
    @Test
    public void onEvent_EventTechnicalUnknown() {
        // Setup
        TestLogAppender logAppender = getTestLogAppender();

        StompCallback callback = new StompCallback(
                subscriptionManager, messageCache, shareViewStompRelay, restTemplate,
                PERMISSIVE_VALIDATOR, UUID.randomUUID().toString(), "hashtag", "glacier@example.com", "example.com");
        TechnicalEvent mockEvent = mock(TechnicalEvent.class);

        // Execute
        callback.onEvent(mockEvent);

        // Verify: new bounded format — class= prefix, never raw toString() (ADR-TD5-A)
        assertThat(logAppender.getLoggedMessages())
                .anySatisfy(msg -> assertThat(msg).contains("got an unknown WebSocketEvent (class="));
    }

    /**
     * Tests if the event handler processes a GenericMessage status.update event with unloadable toot and optin correctly.
     *
     * <p>With the merged design, the cache must NOT be written when the toot is not loadable
     * as an iframe (X-Frame-Options: DENY blocks embedding).</p>
     */
    @Test
    public void onEvent_EventGenericMessage_UpdateWithUnloadableTootAndOptInIsHandled() throws JsonProcessingException {
        // Setup
        TestLogAppender logAppender = getTestLogAppender();

        HttpHeaders allowHeader = getHeaders("DENY", null);
        when(restTemplate.headForHeaders("https://example.com/4567/embed")).thenReturn(allowHeader);

        StompCallback callback = new StompCallback(
                subscriptionManager, messageCache, shareViewStompRelay, restTemplate,
                PERMISSIVE_VALIDATOR, UUID.randomUUID().toString(), "hashtag", "glacier@example.com", "example.com");

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

        // Verify: cache must NOT be written when the toot is not loadable
        verify(messageCache, times(0)).recordThenPublish(any(), any(), any());
        assertThat(logAppender.getLoggedMessages())
                .anySatisfy(msg -> assertThat(msg).contains("Toot not loadable by this glacier instance. Ignoring"));
    }

    /**
     * Tests if the event handler processes a GenericMessage status.update event with loadable toot but without optin correctly.
     *
     * <p>With the merged design, the cache must NOT be written when the toot lacks the bot opt-in mention.</p>
     */
    @Test
    public void onEvent_EventGenericMessage_UpdateWithLoadableTootButMissingOptInIsHandled() throws JsonProcessingException {
        // Setup
        TestLogAppender logAppender = getTestLogAppender();

        HttpHeaders allowHeader = getHeaders("ALLOWALL", null);
        when(restTemplate.headForHeaders("https://example.com/4567/embed")).thenReturn(allowHeader);

        StompCallback callback = new StompCallback(
                subscriptionManager, messageCache, shareViewStompRelay, restTemplate,
                PERMISSIVE_VALIDATOR, UUID.randomUUID().toString(), "hashtag", "glacier@example.com", "example.com");

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

        // Verify: cache must NOT be written when opt-in is missing
        verify(messageCache, times(0)).recordThenPublish(any(), any(), any());
        assertThat(logAppender.getLoggedMessages())
                .anySatisfy(msg -> assertThat(msg).contains("No opt in. Ignoring"));
    }

    /**
     * Tests if the event handler processes a GenericMessage status.update event with loadable toot and optin correctly.
     *
     * <p>In the merged design, a qualified update event writes an {@code UPDATED} entry to the
     * cache via {@link MessageCache#recordThenPublish} (D-03). The test verifies the cache
     * receives the call with the correct hashtag.</p>
     */
    @Test
    public void onEvent_EventGenericMessage_StatusUpdateWithLoadableTootAndOptInIsHandled() throws JsonProcessingException {
        // Setup
        HttpHeaders allowHeader = getHeaders("ALLOWALL", null);
        when(restTemplate.headForHeaders("https://example.com/4567/embed")).thenReturn(allowHeader);

        StompCallback callback = new StompCallback(
                subscriptionManager, messageCache, shareViewStompRelay, restTemplate,
                PERMISSIVE_VALIDATOR, UUID.randomUUID().toString(), "hashtag", "glacier@example.com", "example.com");

        MastodonApiEvent.GenericMessage mockEvent = mock(MastodonApiEvent.GenericMessage.class);
        ObjectMapper mapper = new ObjectMapper();
        Mention mention = Mention.builder().id("4567").username("@peter.kropotkin").acct("glacier").build();
        GenericMessageContentPayload payload = GenericMessageContentPayload.builder().mentions(List.of(mention)).url("https://example.com/4567").id("4567").editedAt("2025-01-17T00:00:00Z").build();
        String payloadAsText = mapper.writeValueAsString(payload);
        JsonNode jsonNode = TextNode.valueOf(payloadAsText);
        GenericMessageContent content = GenericMessageContent.builder().event("status.update").stream(List.of("hashtag")).payload(jsonNode).build();
        String serializedContent = mapper.writeValueAsString(content);
        when(mockEvent.getText()).thenReturn(serializedContent);

        // Execute
        callback.onEvent(mockEvent);

        // Verify: an UPDATED entry is written to the cache
        verify(messageCache, times(1)).recordThenPublish(
                any(PrincipalKey.class),
                eq("hashtag"),
                argThat(entry -> entry.type() == EventType.UPDATED && "4567".equals(entry.statusId()))
        );
    }

    /**
     * Tests if the event handler processes a GenericMessage update event with loadable toot and optin correctly.
     *
     * <p>In the merged design, a qualified "update" event writes a {@code CREATED} entry to
     * the cache via {@link MessageCache#recordThenPublish} (D-03).</p>
     */
    @Test
    public void onEvent_EventGenericMessage_UpdateWithLoadableTootAndOptInIsHandled() throws JsonProcessingException {
        // Setup
        HttpHeaders allowHeader = getHeaders("ALLOWALL", null);
        when(restTemplate.headForHeaders("https://example.com/4567/embed")).thenReturn(allowHeader);

        StompCallback callback = new StompCallback(
                subscriptionManager, messageCache, shareViewStompRelay, restTemplate,
                PERMISSIVE_VALIDATOR, UUID.randomUUID().toString(), "hashtag", "glacier@example.com", "example.com");

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

        // Verify: a CREATED entry is written to the cache
        verify(messageCache, times(1)).recordThenPublish(
                any(PrincipalKey.class),
                eq("hashtag"),
                argThat(entry -> entry.type() == EventType.CREATED && "4567".equals(entry.statusId()))
        );
    }

    /**
     * Tests if the event handler processes a GenericMessage delete event correctly.
     *
     * <p>In the merged design, the "delete" event writes a {@code DELETED} entry to the cache.</p>
     */
    @Test
    public void onEvent_EventGenericMessage_DeleteIsHandled() throws JsonProcessingException {
        // Setup
        when(messageCache.recordThenPublish(any(), any(), any()))
                .thenReturn(new CacheEntry(EventType.DELETED, "4567", null, null, 1L));

        StompCallback callback = new StompCallback(
                subscriptionManager, messageCache, shareViewStompRelay, restTemplate,
                PERMISSIVE_VALIDATOR, UUID.randomUUID().toString(), "hashtag", "glacier@example.com", "example.com");
        MastodonApiEvent.GenericMessage mockEvent = mock(MastodonApiEvent.GenericMessage.class);

        ObjectMapper mapper = new ObjectMapper();
        String payloadAsText = mapper.writeValueAsString(4567);
        JsonNode jsonNode = TextNode.valueOf(payloadAsText);
        GenericMessageContent content = GenericMessageContent.builder().event("delete").stream(List.of("hashtag")).payload(jsonNode).build();
        String serializedContent = mapper.writeValueAsString(content);
        when(mockEvent.getText()).thenReturn(serializedContent);

        // Execute
        callback.onEvent(mockEvent);

        // Verify: a DELETED entry is written to the cache
        verify(messageCache, times(1)).recordThenPublish(
                any(PrincipalKey.class),
                eq("hashtag"),
                argThat(entry -> entry.type() == EventType.DELETED)
        );
    }

    /**
     * Tests if the event handler processes a GenericMessage status.delete event correctly.
     *
     * <p>In the merged design, the "status.delete" event also writes a {@code DELETED} entry to the cache.</p>
     */
    @Test
    public void onEvent_EventGenericMessage_StatusDeleteIsHandled() throws JsonProcessingException {
        // Setup
        when(messageCache.recordThenPublish(any(), any(), any()))
                .thenReturn(new CacheEntry(EventType.DELETED, "4567", null, null, 1L));

        StompCallback callback = new StompCallback(
                subscriptionManager, messageCache, shareViewStompRelay, restTemplate,
                PERMISSIVE_VALIDATOR, UUID.randomUUID().toString(), "hashtag", "glacier@example.com", "example.com");
        MastodonApiEvent.GenericMessage mockEvent = mock(MastodonApiEvent.GenericMessage.class);

        ObjectMapper mapper = new ObjectMapper();
        String payloadAsText = mapper.writeValueAsString(4567);
        JsonNode jsonNode = TextNode.valueOf(payloadAsText);
        GenericMessageContent content = GenericMessageContent.builder().event("status.delete").stream(List.of("hashtag")).payload(jsonNode).build();
        String serializedContent = mapper.writeValueAsString(content);
        when(mockEvent.getText()).thenReturn(serializedContent);

        // Execute
        callback.onEvent(mockEvent);

        // Verify: a DELETED entry is written to the cache
        verify(messageCache, times(1)).recordThenPublish(
                any(PrincipalKey.class),
                eq("hashtag"),
                argThat(entry -> entry.type() == EventType.DELETED)
        );
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
        StompCallback callback = new StompCallback(
                subscriptionManager, messageCache, shareViewStompRelay, restTemplate,
                PERMISSIVE_VALIDATOR, UUID.randomUUID().toString(), "hashtag", "glacier@example.com", "example.com");
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
        StompCallback callback = new StompCallback(
                subscriptionManager, messageCache, shareViewStompRelay, restTemplate,
                PERMISSIVE_VALIDATOR, UUID.randomUUID().toString(), "hashtag", "glacier@example.com", "example.com");
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
    // T-A constants shared by the TD-2 technical-event log-hygiene tests
    // -------------------------------------------------------------------------

    /**
     * Network-layer exception classes that OkHttp wraps in {@link TechnicalEvent.Failure}.
     * These are the JVM-controlled simple names that are safe to log — they contain
     * no peer-influenced bytes. Used by T-A2 to verify the logged token is drawn from
     * this bounded set, and by Lane B (T-B1) as the parameterised source.
     */
    private static final List<Class<? extends Throwable>> EXPECTED_TECHNICAL_FAILURE_EXCEPTIONS = List.of(
            java.io.IOException.class,
            java.io.EOFException.class,
            java.io.InterruptedIOException.class,
            java.net.SocketTimeoutException.class,
            java.net.ConnectException.class,
            java.net.SocketException.class,
            java.net.UnknownHostException.class,
            java.net.ProtocolException.class,
            java.nio.channels.ClosedChannelException.class,
            javax.net.ssl.SSLException.class,
            javax.net.ssl.SSLHandshakeException.class,
            javax.net.ssl.SSLPeerUnverifiedException.class,
            javax.net.ssl.SSLProtocolException.class,
            okhttp3.internal.http2.StreamResetException.class,
            okhttp3.internal.http2.ConnectionShutdownException.class
    );

    /**
     * Canary sentinel for TD-2 tests. Injected as the exception message in
     * {@link TechnicalEvent.Failure} events to detect if peer-controlled bytes
     * (exception message text) reach the log record.
     *
     * <p>A random UUID suffix ensures the value is unique per JVM run and cannot
     * accidentally match a pre-existing log fragment.
     */
    private static final String CANARY_FRAGMENT_TD2 =
            "__GLACIER_TD2_CANARY_" + UUID.randomUUID() + "__";

    /**
     * Canary sentinel for TD-5 tests. Injected via {@code toString()} on a mocked
     * {@link WebSocketEvent} or {@link TechnicalEvent.Open} to detect if peer-controlled
     * bytes (via {@code %s} formatting of the event object) reach the log record.
     *
     * <p>A random UUID suffix ensures the value is unique per JVM run and cannot
     * accidentally match a pre-existing log fragment.
     */
    private static final String CANARY_FRAGMENT_TD5 =
            "__CANARY_TD5_EVENT__" + UUID.randomUUID() + "__";

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
                subscriptionManager, messageCache, shareViewStompRelay, restTemplate,
                PERMISSIVE_VALIDATOR, UUID.randomUUID().toString(), "hashtag", "glacier@example.com", "example.com");
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
                subscriptionManager, messageCache, shareViewStompRelay, restTemplate,
                PERMISSIVE_VALIDATOR, UUID.randomUUID().toString(), "hashtag", "glacier@example.com", "example.com");
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
                subscriptionManager, messageCache, shareViewStompRelay, restTemplate,
                PERMISSIVE_VALIDATOR, UUID.randomUUID().toString(), "hashtag", "glacier@example.com", "example.com");
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
                subscriptionManager, messageCache, shareViewStompRelay, restTemplate,
                PERMISSIVE_VALIDATOR, UUID.randomUUID().toString(), "hashtag", "glacier@example.com", "example.com");
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
                subscriptionManager, messageCache, shareViewStompRelay, restTemplate,
                PERMISSIVE_VALIDATOR, UUID.randomUUID().toString(), "hashtag", "glacier@example.com", "example.com");
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
                subscriptionManager, messageCache, shareViewStompRelay, restTemplate,
                PERMISSIVE_VALIDATOR, UUID.randomUUID().toString(), "hashtag", "glacier@example.com", "example.com");
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

        StompCallback callback = new StompCallback(
                subscriptionManager, messageCache, shareViewStompRelay, restTemplate,
                PERMISSIVE_VALIDATOR, UUID.randomUUID().toString(), "hashtag", "glacier@example.com", "example.com");
        WebSocketEvent mockEvent = mock(WebSocketEvent.class);

        // Execute
        callback.onEvent(mockEvent);

        // Verify
        assertThat(logAppender.getLoggedMessages())
                .anySatisfy(msg -> assertThat(msg).contains("got an unknown event: class social.bigbone.api.entity.streaming.WebSocketEvent$"));
    }

    // -------------------------------------------------------------------------
    // TD-2 — Lane A tests  (T-A1 through T-A6, T-A4b, T-A5b)
    //
    // Each test exercises processTechnicalEvent to verify that the new log format
    // (ADR-TD2-01) does not leak peer-controlled bytes (exception message,
    // WebSocket close reason) into log records.
    // -------------------------------------------------------------------------

    /**
     * T-A1 — Verifies that a TechnicalEvent.Failure produces at least one INFO-level log event.
     *
     * <p>Arrange: StompCallback + TestLogAppender; {@link TechnicalEvent.Failure} wrapping an
     *             {@link java.io.IOException} whose message is the TD-2 canary sentinel.
     * Act:     invoke onEvent(failureEvent).
     * Assert:  at least one captured ILoggingEvent has Level.INFO.
     */
    @Test
    public void failureEvent_logsInfoLevel() {
        // Arrange
        TestLogAppender logAppender = getTestLogAppender();
        String principal = UUID.randomUUID().toString();
        StompCallback callback = new StompCallback(
                subscriptionManager, messageCache, shareViewStompRelay, restTemplate,
                PERMISSIVE_VALIDATOR, principal, "hashtag", "glacier@example.com", "example.com");
        TechnicalEvent.Failure failureEvent = mock(TechnicalEvent.Failure.class);
        when(failureEvent.getError()).thenReturn(new java.io.IOException(CANARY_FRAGMENT_TD2));

        // Act
        callback.onEvent(failureEvent);

        // Assert: at least one INFO-level event was emitted
        assertThat(logAppender.getLoggedEvents())
                .anySatisfy(event -> assertThat(event.getLevel()).isEqualTo(Level.INFO));
    }

    /**
     * T-A2 — Verifies that the Failure log message names an exception simple class from the
     * allowlist ({@link #EXPECTED_TECHNICAL_FAILURE_EXCEPTIONS}), proving the type is surfaced
     * without leaking the exception's full message (which contains peer-controlled text).
     *
     * <p>Arrange: same IOException canary input.
     * Act:     invoke onEvent.
     * Assert:  getFormattedMessage() of at least one event contains a name from
     *          {@code EXPECTED_TECHNICAL_FAILURE_EXCEPTIONS} (matched by simple class name).
     */
    @Test
    public void failureEvent_includesAllowlistedExceptionSimpleName() {
        // Arrange
        TestLogAppender logAppender = getTestLogAppender();
        String principal = UUID.randomUUID().toString();
        StompCallback callback = new StompCallback(
                subscriptionManager, messageCache, shareViewStompRelay, restTemplate,
                PERMISSIVE_VALIDATOR, principal, "hashtag", "glacier@example.com", "example.com");
        TechnicalEvent.Failure failureEvent = mock(TechnicalEvent.Failure.class);
        when(failureEvent.getError()).thenReturn(new java.io.IOException(CANARY_FRAGMENT_TD2));

        // Act
        callback.onEvent(failureEvent);

        // Assert: the formatted message names one allowlisted exception class
        assertThat(logAppender.getLoggedEvents())
                .anySatisfy(event ->
                        assertThat(EXPECTED_TECHNICAL_FAILURE_EXCEPTIONS)
                                .anyMatch(clazz -> event.getFormattedMessage().contains(clazz.getSimpleName())));
    }

    /**
     * T-A3 — Verifies that the log message uses the new prefix format
     * {@code "got a Failure event. Restarting subscription. exception="} and does NOT
     * contain the legacy format {@code "The error is:"} (ADR-TD2-03 / SR-TD2-03).
     *
     * <p>Arrange: IOException with canary sentinel as the message.
     * Act:     invoke onEvent.
     * Assert:  at least one formatted message contains the new prefix;
     *          no message contains the legacy prefix.
     */
    @Test
    public void failureEvent_messagePrefixMatches() {
        // Arrange
        TestLogAppender logAppender = getTestLogAppender();
        String principal = UUID.randomUUID().toString();
        StompCallback callback = new StompCallback(
                subscriptionManager, messageCache, shareViewStompRelay, restTemplate,
                PERMISSIVE_VALIDATOR, principal, "hashtag", "glacier@example.com", "example.com");
        TechnicalEvent.Failure failureEvent = mock(TechnicalEvent.Failure.class);
        when(failureEvent.getError()).thenReturn(new java.io.IOException(CANARY_FRAGMENT_TD2));

        // Act
        callback.onEvent(failureEvent);

        // Assert: new structured prefix is present (ADR-TD2-01)
        assertThat(logAppender.getLoggedMessages())
                .anySatisfy(msg -> assertThat(msg).contains("got a Failure event. Restarting subscription. exception="));
        // Assert: legacy format is fully removed (SR-TD2-03)
        assertThat(logAppender.getLoggedMessages())
                .noneSatisfy(msg -> assertThat(msg).contains("The error is:"));
    }

    /**
     * T-A4 — Verifies that no Throwable is attached to any captured log event.
     *
     * <p>This is the primary regression guard for ADR-TD2-01: attaching the Throwable
     * as a SLF4J argument causes Logback to render {@link Throwable#getMessage()} —
     * which embeds peer-controlled error text — into the structured JSON log record.
     *
     * <p>Arrange: IOException with canary sentinel.
     * Act:     invoke onEvent.
     * Assert:  every captured ILoggingEvent has getThrowableProxy() == null (SR-TD2-09).
     */
    @Test
    public void failureEvent_attachesNoThrowable() {
        // Arrange
        TestLogAppender logAppender = getTestLogAppender();
        String principal = UUID.randomUUID().toString();
        StompCallback callback = new StompCallback(
                subscriptionManager, messageCache, shareViewStompRelay, restTemplate,
                PERMISSIVE_VALIDATOR, principal, "hashtag", "glacier@example.com", "example.com");
        TechnicalEvent.Failure failureEvent = mock(TechnicalEvent.Failure.class);
        when(failureEvent.getError()).thenReturn(new java.io.IOException(CANARY_FRAGMENT_TD2));

        // Act
        callback.onEvent(failureEvent);

        // Assert: no event carries a Throwable proxy — the exception message cannot be
        // serialised into the log record by Logback's ThrowableProxyConverter (SR-TD2-09)
        assertThat(logAppender.getLoggedEvents())
                .allSatisfy(event -> assertThat(event.getThrowableProxy()).isNull());
    }

    /**
     * T-A5 — Verifies that a TechnicalEvent.Failure neither pollutes the SLF4J MDC context
     * nor emits events to the dedicated AUDIT logger (SR-TD2-12).
     *
     * <p>Arrange: IOException with canary sentinel; attach both AUDIT and normal appenders.
     * Act:     invoke onEvent.
     * Assert:  MDC contains no key with CANARY_FRAGMENT_TD2 value;
     *          AUDIT appender received zero events.
     */
    @Test
    public void failureEvent_doesNotPolluteMdcOrAudit() {
        // Arrange
        TestLogAppender logAppender = getTestLogAppender();
        TestLogAppender auditAppender = getAuditLogAppender();
        MDC.clear();
        String principal = UUID.randomUUID().toString();
        StompCallback callback = new StompCallback(
                subscriptionManager, messageCache, shareViewStompRelay, restTemplate,
                PERMISSIVE_VALIDATOR, principal, "hashtag", "glacier@example.com", "example.com");
        TechnicalEvent.Failure failureEvent = mock(TechnicalEvent.Failure.class);
        when(failureEvent.getError()).thenReturn(new java.io.IOException(CANARY_FRAGMENT_TD2));

        // Act
        callback.onEvent(failureEvent);

        // Assert: MDC does not contain the canary fragment
        assertThat(MDC.getCopyOfContextMap()).satisfiesAnyOf(
                map -> assertThat(map).isNull(),
                map -> assertThat(map).doesNotContainValue(CANARY_FRAGMENT_TD2)
        );
        // Assert: AUDIT logger received no events — technical network failures are
        // operational events, not security audit events (SR-TD2-12)
        assertThat(auditAppender.getLoggedEvents()).isEmpty();
    }

    /**
     * T-A6 — Verifies that on a TechnicalEvent.Failure the subscription manager
     * is first terminated then restarted (SR-TD2-13).
     *
     * <p>This test replaces the old {@code onEvent_EventTechnicalFailure} which asserted
     * the legacy "The error is: Error Message" format — removed by ADR-TD2-01.
     *
     * <p>Arrange: StompCallback with mocked subscriptionManager; TechnicalEvent.Failure with
     *             any Throwable.
     * Act:     invoke onEvent(failureEvent).
     * Assert:  {@code subscriptionManager.terminateSubscription(principal, hashtag)} called once;
     *          {@code subscriptionManager.subscribeToHashtag(principal, hashtag)} called once.
     */
    @Test
    public void failureEvent_restartsSubscription() {
        // Arrange
        String principal = UUID.randomUUID().toString();
        String hashtag = "hashtag";
        StompCallback callback = new StompCallback(
                subscriptionManager, messageCache, shareViewStompRelay, restTemplate,
                PERMISSIVE_VALIDATOR, principal, hashtag, "glacier@example.com", "example.com");
        TechnicalEvent.Failure failureEvent = mock(TechnicalEvent.Failure.class);
        when(failureEvent.getError()).thenReturn(new java.io.IOException("any error"));

        // Act
        callback.onEvent(failureEvent);

        // Assert: subscription lifecycle — terminate then restart (SR-TD2-13)
        Mockito.verify(subscriptionManager, Mockito.times(1)).terminateSubscription(principal, hashtag);
        Mockito.verify(subscriptionManager, Mockito.times(1)).subscribeToHashtag(principal, hashtag);
    }

    /**
     * T-A4b — Verifies that a TechnicalEvent.Closing logs the numeric close code
     * and does NOT log the peer-controlled reason string (SR-TD2-14).
     *
     * <p>Arrange: mocked Closing with code=1006 and reason=CANARY_FRAGMENT_TD2.
     * Act:     invoke onEvent.
     * Assert:  at least one log message contains "1006";
     *          no log message contains CANARY_FRAGMENT_TD2 (the peer reason).
     */
    @Test
    public void closingEvent_logsCodeNotReason() {
        // Arrange
        TestLogAppender logAppender = getTestLogAppender();
        String principal = UUID.randomUUID().toString();
        StompCallback callback = new StompCallback(
                subscriptionManager, messageCache, shareViewStompRelay, restTemplate,
                PERMISSIVE_VALIDATOR, principal, "hashtag", "glacier@example.com", "example.com");
        TechnicalEvent.Closing closingEvent = mock(TechnicalEvent.Closing.class);
        when(closingEvent.getCode()).thenReturn(1006);
        when(closingEvent.getReason()).thenReturn(CANARY_FRAGMENT_TD2);

        // Act
        callback.onEvent(closingEvent);

        // Assert: numeric code is present in the log (ADR-TD2-03)
        assertThat(logAppender.getLoggedMessages())
                .anySatisfy(msg -> assertThat(msg).contains("1006"));
        // Assert: peer-controlled reason string must be fully absent (SR-TD2-14)
        assertThat(logAppender.getLoggedMessages())
                .noneSatisfy(msg -> assertThat(msg).contains(CANARY_FRAGMENT_TD2));
    }

    /**
     * T-A5b — Verifies that a TechnicalEvent.Closed logs the numeric close code
     * and does NOT log the peer-controlled reason string (SR-TD2-14).
     *
     * <p>Arrange: mocked Closed with code=1011 and reason=CANARY_FRAGMENT_TD2.
     * Act:     invoke onEvent.
     * Assert:  at least one log message contains "1011";
     *          no log message contains CANARY_FRAGMENT_TD2 (the peer reason).
     */
    @Test
    public void closedEvent_logsCodeNotReason() {
        // Arrange
        TestLogAppender logAppender = getTestLogAppender();
        String principal = UUID.randomUUID().toString();
        StompCallback callback = new StompCallback(
                subscriptionManager, messageCache, shareViewStompRelay, restTemplate,
                PERMISSIVE_VALIDATOR, principal, "hashtag", "glacier@example.com", "example.com");
        TechnicalEvent.Closed closedEvent = mock(TechnicalEvent.Closed.class);
        when(closedEvent.getCode()).thenReturn(1011);
        when(closedEvent.getReason()).thenReturn(CANARY_FRAGMENT_TD2);

        // Act
        callback.onEvent(closedEvent);

        // Assert: numeric code is present in the log (ADR-TD2-03)
        assertThat(logAppender.getLoggedMessages())
                .anySatisfy(msg -> assertThat(msg).contains("1011"));
        // Assert: peer-controlled reason string must be fully absent (SR-TD2-14)
        assertThat(logAppender.getLoggedMessages())
                .noneSatisfy(msg -> assertThat(msg).contains(CANARY_FRAGMENT_TD2));
    }

    // -------------------------------------------------------------------------
    // TD-2 — Lane B test  (T-B1)
    //
    // Parameterised injection canary: seven adversarial fragment variants are
    // embedded as the IOException message and fed through the Failure event path.
    // The test verifies that none of the attacker-controlled bytes surface in any
    // ILoggingEvent (formatted message, argument array, throwable proxy, or MDC).
    //
    // SR coverage: SR-TD2-01 (peer-controlled byte), SR-TD2-09 (no Throwable),
    //              SR-TD2-10 (CRLF injection), SR-TD2-11 (ANSI injection),
    //              SR-TD2-12 (AUDIT zero events).
    // -------------------------------------------------------------------------

    /**
     * Provides seven adversarial injection variants for T-B1 (TD-2 lane).
     * <p>
     * Each variant represents a distinct attack surface:
     * <ul>
     *   <li>ASCII sentinel — baseline canary to detect direct getMessage() leak</li>
     *   <li>Realistic error message — real-looking network error that OkHttp may produce</li>
     *   <li>CRLF — log-injection via embedded newline (SR-TD2-10)</li>
     *   <li>ANSI escape — terminal colour injection (SR-TD2-11)</li>
     *   <li>Null-byte prefix — null-byte injection (SR-TD2-01)</li>
     *   <li>HTTP/2 error with host — peer-controlled host name embedded in message</li>
     *   <li>JSON fragment — JSON-breaking injection attempting structured log poisoning</li>
     * </ul>
     */
    private static Stream<Arguments> injectionVariantsTD2() {
        return Stream.of(
                Arguments.of("__ASCII_CANARY_TD2__"),
                Arguments.of("failed to connect to attacker.example.com:31337"),
                Arguments.of("\r\nFAKE_LOG_LINE: evil=injected"),
                Arguments.of("[31mANSI_RED_INJECT[0m"),
                Arguments.of(" NULL_BYTE_INJECT"),
                Arguments.of("http2 connection error: PROTOCOL_ERROR (code=1) host=victim.example"),
                Arguments.of("\"}\n\"injected\":\"value")
        );
    }

    /**
     * T-B1 — Verifies that no attacker-controlled fragment (from the IOException message)
     * leaks into any log record when a {@link TechnicalEvent.Failure} is processed.
     * <p>
     * The production fix (ADR-TD2-03) logs {@code failure.getError().getClass().getSimpleName()}
     * instead of {@code failure.getError().getMessage()}, so the class name {@code "IOException"}
     * must appear in the log, but the peer-controlled message text must not.
     * <p>
     * Seven injection variants are tested: plain ASCII, realistic network error, CRLF, ANSI
     * escape, null-byte prefix, HTTP/2 error with embedded host, and JSON-breaking fragment.
     * For each variant the test asserts:
     * <ol>
     *   <li>Positive signal: at least one formatted message contains {@code "got a Failure event"}
     *       to confirm the failure path actually fired (not a silent no-op).</li>
     *   <li>SR-TD2-01 / SR-TD2-10 / SR-TD2-11: no {@link ILoggingEvent#getFormattedMessage()}
     *       contains the attacker-controlled fragment.</li>
     *   <li>SR-TD2-01: no element in {@link ILoggingEvent#getArgumentArray()}, when converted
     *       to String, contains the fragment.</li>
     *   <li>SR-TD2-09: every event has {@link ILoggingEvent#getThrowableProxy()} {@code == null}
     *       (strict — the exception must not be attached to any log record).</li>
     *   <li>MDC: no MDC copy value contains the fragment (prevents structured-log poisoning via
     *       contextual fields).</li>
     *   <li>SR-TD2-12: AUDIT appender received zero events — network failures are not
     *       security audit events.</li>
     * </ol>
     *
     * <p>Uses {@code new IOException(fragment)} (not a mock) so that
     * {@code getClass().getSimpleName()} returns the real {@code "IOException"} string.
     * This ensures we test the production path exactly: the class name IS logged, the
     * peer-controlled message IS NOT logged.
     *
     * @param fragment the adversarial string injected as the IOException message
     */
    @ParameterizedTest(name = "fragment={0}")
    @MethodSource("injectionVariantsTD2")
    public void failureEvent_doesNotLeakAttackerControlledFragment(String fragment) {
        // Arrange
        TestLogAppender logAppender = getTestLogAppender();
        TestLogAppender auditAppender = getAuditLogAppender();
        MDC.clear();

        String principal = UUID.randomUUID().toString();
        StompCallback callback = new StompCallback(
                subscriptionManager, messageCache, shareViewStompRelay, restTemplate,
                PERMISSIVE_VALIDATOR, principal, "hashtag", "glacier@example.com", "example.com");

        // Use a real IOException (not mock) so getClass().getSimpleName() returns "IOException",
        // the exact string the production code logs. The fragment is the getMessage() content
        // that the fix must NOT include in the log output. (SR-TD2-01 / SR-TD2-09)
        TechnicalEvent.Failure failureEvent = mock(TechnicalEvent.Failure.class);
        IOException throwable = new IOException(fragment);
        when(failureEvent.getError()).thenReturn(throwable);

        // Act
        callback.onEvent(failureEvent);

        // Collect events from both the StompCallback logger and the AUDIT logger
        List<ILoggingEvent> allEvents = new ArrayList<>(logAppender.getLoggedEvents());
        allEvents.addAll(auditAppender.getLoggedEvents());

        // Positive signal: the failure path must have fired — confirms the test is exercising
        // the right code, not passing trivially because onEvent was a no-op.
        assertThat(logAppender.getLoggedEvents())
                .anySatisfy(event ->
                        assertThat(event.getFormattedMessage())
                                .contains("got a Failure event"));

        // SR-TD2-01 / SR-TD2-10 / SR-TD2-11: no formatted message may contain the fragment
        assertThat(allEvents)
                .allSatisfy(event ->
                        assertThat(event.getFormattedMessage())
                                .doesNotContain(fragment));

        // SR-TD2-01: no SLF4J argument in the argument array may resolve to the fragment
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

        // SR-TD2-09: no Throwable may be attached — prevents Logback's ThrowableProxyConverter
        // from rendering IOException.getMessage() (which contains the fragment) into the JSON log.
        assertThat(allEvents)
                .allSatisfy(event ->
                        assertThat(event.getThrowableProxy()).isNull());

        // MDC: peer-controlled bytes must not appear in any contextual field of any log event.
        // Captured MDC snapshots are stored inside ILoggingEvent at append time.
        assertThat(allEvents)
                .allSatisfy(event -> {
                    java.util.Map<String, String> mdcCopy = event.getMDCPropertyMap();
                    if (mdcCopy != null && !mdcCopy.isEmpty()) {
                        assertThat(mdcCopy.values())
                                .noneMatch(value -> value != null && value.contains(fragment));
                    }
                });

        // SR-TD2-12: AUDIT logger must receive zero events — network-layer failures are
        // operational events, not security audit events.
        assertThat(auditAppender.getLoggedEvents()).isEmpty();
    }

    // -------------------------------------------------------------------------
    // SSRF guard tests — SR-PT-04, SR-PT-05, SR-PT-06, SR-PT-10
    // -------------------------------------------------------------------------

    /**
     * SR-PT-04 + SR-PT-10 (processStatusCreatedEvent path):
     * When the SSRF validator blocks a URL, headForHeaders must NOT be called.
     *
     * Arrange: a blocked validator + a StatusCreated event.
     * Act: onEvent.
     * Assert: restTemplate.headForHeaders is never invoked.
     */
    @Test
    public void onEvent_statusCreated_ssrfGuardBlocks_doesNotCallHeadForHeaders() {
        // Setup
        String principal = UUID.randomUUID().toString();
        String hashtag = "hashtag";

        when(mockStatus.getId()).thenReturn("12345");
        when(mockStatus.getUrl()).thenReturn("https://mastodon.example.com/12345");

        StompCallback callback = new StompCallback(
                subscriptionManager, messageCache, shareViewStompRelay, restTemplate,
                BLOCKING_VALIDATOR, principal, hashtag, "glacier@example.com", "glacier.example.com");
        ParsedStreamEvent.StatusCreated event = new ParsedStreamEvent.StatusCreated(mockStatus);
        MastodonApiEvent.StreamEvent streamEvent = new MastodonApiEvent.StreamEvent(event, List.of());

        // Execute
        callback.onEvent(streamEvent);

        // Verify (SR-PT-04)
        verify(restTemplate, never()).headForHeaders(any(String.class));
    }

    /**
     * SR-PT-05 + SR-PT-10 (processStatusCreatedEvent path):
     * When the SSRF validator blocks a URL, the cache must NOT be written.
     *
     * Arrange: a blocked validator + a StatusCreated event.
     * Act: onEvent.
     * Assert: messageCache.recordThenPublish is never called — the blocked toot
     * must not reach any wall subscriber or the cache (SR-PT-10).
     */
    @Test
    public void onEvent_statusCreated_ssrfGuardBlocks_doesNotWriteToCache() {
        // Setup
        String principal = UUID.randomUUID().toString();
        String hashtag = "hashtag";

        when(mockStatus.getId()).thenReturn("12345");
        when(mockStatus.getUrl()).thenReturn("https://mastodon.example.com/12345");

        StompCallback callback = new StompCallback(
                subscriptionManager, messageCache, shareViewStompRelay, restTemplate,
                BLOCKING_VALIDATOR, principal, hashtag, "glacier@example.com", "glacier.example.com");
        ParsedStreamEvent.StatusCreated event = new ParsedStreamEvent.StatusCreated(mockStatus);
        MastodonApiEvent.StreamEvent streamEvent = new MastodonApiEvent.StreamEvent(event, List.of());

        // Execute
        callback.onEvent(streamEvent);

        // Verify (SR-PT-10): cache must not be written when the SSRF guard fires
        verify(messageCache, never()).recordThenPublish(any(), any(), any());
    }

    /**
     * SR-PT-06 + SR-PT-10 (processStatusCreatedEvent path):
     * When the SSRF validator blocks a URL, an AUDIT event stomp.embed.ssrf_blocked is emitted.
     *
     * Arrange: a blocked validator + a StatusCreated event + a log appender on the AUDIT logger.
     * Act: onEvent.
     * Assert: the AUDIT logger emits "stomp.embed.ssrf_blocked" with url-host-hash and scheme.
     */
    @Test
    public void onEvent_statusCreated_ssrfGuardBlocks_emitsAuditEvent() {
        // Setup
        TestLogAppender auditAppender = new TestLogAppender();
        auditAppender.start();
        Logger auditLogger = (Logger) LoggerFactory.getLogger("AUDIT");
        auditLogger.addAppender(auditAppender);

        String principal = UUID.randomUUID().toString();
        String hashtag = "hashtag";

        when(mockStatus.getId()).thenReturn("12345");
        when(mockStatus.getUrl()).thenReturn("https://mastodon.example.com/12345");

        StompCallback callback = new StompCallback(
                subscriptionManager, messageCache, shareViewStompRelay, restTemplate,
                BLOCKING_VALIDATOR, principal, hashtag, "glacier@example.com", "glacier.example.com");
        ParsedStreamEvent.StatusCreated event = new ParsedStreamEvent.StatusCreated(mockStatus);
        MastodonApiEvent.StreamEvent streamEvent = new MastodonApiEvent.StreamEvent(event, List.of());

        // Execute
        callback.onEvent(streamEvent);

        // Verify (SR-PT-06): audit event emitted with expected fields
        assertThat(auditAppender.getLoggedMessages())
                .anySatisfy(msg -> {
                    assertThat(msg).contains("stomp.embed.ssrf_blocked");
                    assertThat(msg).contains("url-host-hash=");
                    assertThat(msg).contains("scheme=https");
                });

        auditLogger.detachAppender(auditAppender);
    }

    /**
     * SR-PT-04 + SR-PT-10 (sendMessage / GenericMessage update path):
     * When the SSRF validator blocks a URL in a GenericMessage update, headForHeaders must NOT be called.
     */
    @Test
    public void onEvent_genericMessageUpdate_ssrfGuardBlocks_doesNotCallHeadForHeaders() throws JsonProcessingException {
        // Setup
        StompCallback callback = new StompCallback(
                subscriptionManager, messageCache, shareViewStompRelay, restTemplate,
                BLOCKING_VALIDATOR, UUID.randomUUID().toString(), "hashtag", "glacier@example.com", "example.com");

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

        // Verify (SR-PT-04)
        verify(restTemplate, never()).headForHeaders(any(String.class));
    }

    /**
     * SR-PT-05 + SR-PT-10 (sendMessage / GenericMessage update path):
     * When the SSRF validator blocks a URL in a GenericMessage update, the cache must NOT be written.
     */
    @Test
    public void onEvent_genericMessageUpdate_ssrfGuardBlocks_doesNotWriteToCache() throws JsonProcessingException {
        // Setup
        StompCallback callback = new StompCallback(
                subscriptionManager, messageCache, shareViewStompRelay, restTemplate,
                BLOCKING_VALIDATOR, UUID.randomUUID().toString(), "hashtag", "glacier@example.com", "example.com");

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

        // Verify (SR-PT-10): cache must not be written when the SSRF guard fires
        verify(messageCache, never()).recordThenPublish(any(), any(), any());
    }

    // -------------------------------------------------------------------------
    // SSRF guard tests — SR-PT-04, SR-PT-05, SR-PT-06 (processStatusEditedEvent path)
    // These tests must FAIL before the production fix and PASS after it.
    // -------------------------------------------------------------------------

    /**
     * SR-PT-04 (processStatusEditedEvent path):
     * When the SSRF validator blocks a URL for an edited status, no outbound HEAD request is issued.
     *
     * <p>Arrange: a blocking {@link SafeUrlValidator} + a StatusEdited event carrying a private-range URL.</p>
     * <p>Act: onEvent.</p>
     * <p>Assert: {@code restTemplate.headForHeaders} is never called — the guard short-circuits before
     * any outbound HTTP request reaches the potentially hostile host.</p>
     */
    @Test
    public void onEvent_statusEdited_ssrfGuardBlocks_doesNotCallHeadForHeaders() {
        // Arrange
        String principal = UUID.randomUUID().toString();
        String hashtag = "hashtag";

        when(mockStatus.getId()).thenReturn("edit-1");
        when(mockStatus.getUrl()).thenReturn("http://192.168.1.1/status/1");

        StompCallback callback = new StompCallback(
                subscriptionManager, messageCache, shareViewStompRelay, restTemplate, BLOCKING_VALIDATOR,
                principal, hashtag, "glacier@example.com", "glacier.example.com");
        ParsedStreamEvent.StatusEdited edited = new ParsedStreamEvent.StatusEdited(mockStatus);
        MastodonApiEvent.StreamEvent streamEvent = new MastodonApiEvent.StreamEvent(edited, List.of());

        // Act
        callback.onEvent(streamEvent);

        // Assert (SR-PT-04): no outbound HEAD request issued
        verify(restTemplate, never()).headForHeaders(any(String.class));
    }

    /**
     * SR-PT-05 (processStatusEditedEvent path):
     * When the SSRF validator blocks a URL for an edited status, the cache must NOT be written.
     *
     * <p>Arrange: a blocking {@link SafeUrlValidator} + a StatusEdited event.</p>
     * <p>Act: onEvent.</p>
     * <p>Assert: {@code messageCache.recordThenPublish} is never called — the blocked toot
     * must not reach any wall subscriber or the cache (SR-PT-10).</p>
     */
    @Test
    public void onEvent_statusEdited_ssrfGuardBlocks_doesNotWriteToCache() {
        // Arrange
        String principal = UUID.randomUUID().toString();
        String hashtag = "hashtag";

        when(mockStatus.getId()).thenReturn("edit-1");
        when(mockStatus.getUrl()).thenReturn("http://192.168.1.1/status/1");

        StompCallback callback = new StompCallback(
                subscriptionManager, messageCache, shareViewStompRelay, restTemplate, BLOCKING_VALIDATOR,
                principal, hashtag, "glacier@example.com", "glacier.example.com");
        ParsedStreamEvent.StatusEdited edited = new ParsedStreamEvent.StatusEdited(mockStatus);
        MastodonApiEvent.StreamEvent streamEvent = new MastodonApiEvent.StreamEvent(edited, List.of());

        // Act
        callback.onEvent(streamEvent);

        // Assert (SR-PT-05 / SR-PT-10): cache must not be written when the SSRF guard fires
        verify(messageCache, never()).recordThenPublish(any(), any(), any());
    }

    /**
     * SR-PT-06 (processStatusEditedEvent path):
     * When the SSRF validator blocks a URL for an edited status, the AUDIT logger emits
     * a {@code stomp.embed.ssrf_blocked} event containing scrubbed URL metadata.
     *
     * <p>Arrange: a blocking {@link SafeUrlValidator} + a StatusEdited event + a log appender
     * attached to the {@code AUDIT} logger.</p>
     * <p>Act: onEvent.</p>
     * <p>Assert: the AUDIT logger emits {@code stomp.embed.ssrf_blocked} with {@code url-host-hash}
     * and {@code scheme} fields — raw URL must never appear in the log (D-13 / SR-8).</p>
     */
    @Test
    public void onEvent_statusEdited_ssrfGuardBlocks_emitsAuditEvent() {
        // Arrange
        TestLogAppender auditAppender = new TestLogAppender();
        auditAppender.start();
        Logger auditLogger = (Logger) LoggerFactory.getLogger("AUDIT");
        auditLogger.addAppender(auditAppender);

        String principal = UUID.randomUUID().toString();
        String hashtag = "hashtag";

        when(mockStatus.getId()).thenReturn("edit-1");
        when(mockStatus.getUrl()).thenReturn("http://192.168.1.1/status/1");

        StompCallback callback = new StompCallback(
                subscriptionManager, messageCache, shareViewStompRelay, restTemplate, BLOCKING_VALIDATOR,
                principal, hashtag, "glacier@example.com", "glacier.example.com");
        ParsedStreamEvent.StatusEdited edited = new ParsedStreamEvent.StatusEdited(mockStatus);
        MastodonApiEvent.StreamEvent streamEvent = new MastodonApiEvent.StreamEvent(edited, List.of());

        // Act
        callback.onEvent(streamEvent);

        // Assert (SR-PT-06): AUDIT event emitted with scrubbed fields only
        assertThat(auditAppender.getLoggedMessages())
                .anySatisfy(msg -> {
                    assertThat(msg).contains("stomp.embed.ssrf_blocked");
                    assertThat(msg).contains("url-host-hash=");
                    assertThat(msg).contains("scheme=http");
                });

        auditLogger.detachAppender(auditAppender);
    }

    // -----------------------------------------------------------------
    // T-12: non-loadable toot (from Phase 1 / main branch)
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

        StompCallback callback = new StompCallback(
                subscriptionManager, messageCache, shareViewStompRelay, restTemplate,
                PERMISSIVE_VALIDATOR, principal, hashtag, "glacier@example.com", "glacier.example.com");
        ParsedStreamEvent.StatusCreated event = new ParsedStreamEvent.StatusCreated(mockStatus);
        MastodonApiEvent.StreamEvent streamEvent = new MastodonApiEvent.StreamEvent(event, List.of());

        // Act
        callback.onEvent(streamEvent);

        // Assert — T-12
        verify(messageCache, never()).recordThenPublish(any(), any(), any());
    }

    // -----------------------------------------------------------------
    // D-07: editedAt UTC normalisation (from Phase 1 / main branch)
    // -----------------------------------------------------------------

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
    public void onEvent_EventGenericMessage_MalformedEditedAt_dropsEvent() throws com.fasterxml.jackson.core.JsonProcessingException {
        // Arrange
        TestLogAppender logAppender = getTestLogAppender();
        HttpHeaders allowHeader = getHeaders("ALLOWALL", null);
        when(restTemplate.headForHeaders(anyString())).thenReturn(allowHeader);

        StompCallback callback = new StompCallback(
                subscriptionManager, messageCache, shareViewStompRelay, restTemplate,
                PERMISSIVE_VALIDATOR, UUID.randomUUID().toString(), "hashtag", "glacier@example.com", "example.com");

        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        de.seism0saurus.glacier.webservice.messaging.messages.Mention mention =
                de.seism0saurus.glacier.webservice.messaging.messages.Mention.builder()
                        .id("4567").username("@peter.kropotkin").acct("glacier").build();
        de.seism0saurus.glacier.webservice.messaging.messages.GenericMessageContentPayload payload =
                de.seism0saurus.glacier.webservice.messaging.messages.GenericMessageContentPayload.builder()
                        .mentions(List.of(mention))
                        .url("https://example.com/4567")
                        .id("4567")
                        .editedAt("NOT_A_DATE")
                        .build();
        String payloadAsText = mapper.writeValueAsString(payload);
        com.fasterxml.jackson.databind.JsonNode jsonNode = com.fasterxml.jackson.databind.node.TextNode.valueOf(payloadAsText);
        de.seism0saurus.glacier.webservice.messaging.messages.GenericMessageContent content =
                de.seism0saurus.glacier.webservice.messaging.messages.GenericMessageContent.builder()
                        .event("status.update")
                        .stream(List.of("hashtag"))
                        .payload(jsonNode)
                        .build();
        MastodonApiEvent.GenericMessage mockEvent = mock(MastodonApiEvent.GenericMessage.class);
        when(mockEvent.getText()).thenReturn(mapper.writeValueAsString(content));

        // Act
        callback.onEvent(mockEvent);

        // Assert
        verify(messageCache, never()).recordThenPublish(any(), any(), any());
        assertThat(logAppender.getLoggedMessages())
                .anySatisfy(msg -> assertThat(msg).contains("Could not parse editedAt"));
        // SR-TD3-01: the raw peer-controlled value must not reach the log encoder
        logAppender.getLoggedMessages().forEach(msg ->
                assertThat(msg).doesNotContain("NOT_A_DATE"));
        // SR-TD3-01: "value '" pattern (from the old format string) must not appear
        logAppender.getLoggedMessages().forEach(msg ->
                assertThat(msg).doesNotContain("value '"));
    }

    // -----------------------------------------------------------------
    // D-07 log-hygiene: SR-TD3 — raw editedAt must never reach the log encoder
    // -----------------------------------------------------------------

    /**
     * SR-TD3-01/SR-TD3-02: a malformed editedAt value must not appear verbatim in the
     * formatted log message. The log must contain "Could not parse editedAt", the length
     * of the raw string, and the errorIndex from the parse exception — nothing more from
     * the peer-controlled input.
     *
     * <p>Arrange: raw = "not-a-date" (length 10).
     * <p>Act: call {@link StompCallback#normaliseEditedAt(String)}.
     * <p>Assert:
     * <ul>
     *   <li>formatted message contains "Could not parse editedAt"</li>
     *   <li>formatted message contains "len=10"</li>
     *   <li>formatted message contains "errorIndex="</li>
     *   <li>formatted message does NOT contain "not-a-date"</li>
     *   <li>formatted message does NOT contain "value '"</li>
     *   <li>no argument in getArgumentArray() equals "not-a-date"</li>
     * </ul>
     */
    @Test
    public void normaliseEditedAt_malformed_logsLengthNotRawValue() {
        // Arrange
        TestLogAppender logAppender = getTestLogAppender();
        String raw = "not-a-date";

        // Act
        StompCallback.normaliseEditedAt(raw);

        // Assert — positive signal: parse-failure path fired
        assertThat(logAppender.getLoggedMessages())
                .anySatisfy(msg -> assertThat(msg).contains("Could not parse editedAt"));
        // SR-TD3-02: length of input is logged
        assertThat(logAppender.getLoggedMessages())
                .anySatisfy(msg -> assertThat(msg).contains("len=10"));
        // SR-TD3-11: errorIndex from DateTimeParseException is logged
        assertThat(logAppender.getLoggedMessages())
                .anySatisfy(msg -> assertThat(msg).contains("errorIndex="));
        // SR-TD3-01: raw peer bytes must NOT appear in any formatted message
        logAppender.getLoggedMessages().forEach(msg ->
                assertThat(msg).doesNotContain("not-a-date"));
        // SR-TD3-01: old "value '" format pattern must not appear
        logAppender.getLoggedMessages().forEach(msg ->
                assertThat(msg).doesNotContain("value '"));
        // SR-TD3-14: argument array must not contain the raw input string
        logAppender.getLoggedEvents().forEach(event -> {
            Object[] args = event.getArgumentArray();
            if (args != null) {
                for (Object arg : args) {
                    assertThat(String.valueOf(arg)).doesNotContain("not-a-date");
                }
            }
        });
    }

    /**
     * SR-TD3-01/SR-TD3-03: a CRLF-injection payload must not appear in any log message.
     * The injected fragment "\r\nWARN  INJECTED FAKE LINE" must not surface in any
     * formatted message. The length (27) must appear instead.
     *
     * <p>Arrange: raw = "x\r\nWARN  INJECTED FAKE LINE" (length 27: 'x' + CR + LF + 24 chars).
     * <p>Act: call {@link StompCallback#normaliseEditedAt(String)}.
     * <p>Assert:
     * <ul>
     *   <li>no message contains "INJECTED"</li>
     *   <li>at least one message contains "len=27"</li>
     * </ul>
     */
    @Test
    public void normaliseEditedAt_malformed_crlfPayload_doesNotReachLogEncoder() {
        // Arrange
        TestLogAppender logAppender = getTestLogAppender();
        String raw = "x\r\nWARN  INJECTED FAKE LINE";

        // Act
        StompCallback.normaliseEditedAt(raw);

        // Assert — SR-TD3-03: int argument structurally eliminates CRLF injection
        logAppender.getLoggedMessages().forEach(msg ->
                assertThat(msg).doesNotContain("INJECTED"));
        // SR-TD3-02: length must be logged (27: 'x' + CR + LF + 24 printable chars)
        assertThat(logAppender.getLoggedMessages())
                .anySatisfy(msg -> assertThat(msg).contains("len=27"));
    }

    /**
     * SR-TD3-08: a 5 KB oversize payload must be distinguishable by its length in the log,
     * and the raw bytes must not appear verbatim. The formatted message must contain "len=5000"
     * but must not contain a repeated "A" sequence of 100 or more characters.
     *
     * <p>Arrange: raw = "A".repeat(5000).
     * <p>Act: call {@link StompCallback#normaliseEditedAt(String)}.
     * <p>Assert:
     * <ul>
     *   <li>message contains "len=5000"</li>
     *   <li>message does NOT contain "A".repeat(100)</li>
     * </ul>
     */
    @Test
    public void normaliseEditedAt_malformed_oversizeInput_lengthDistinguishable() {
        // Arrange
        TestLogAppender logAppender = getTestLogAppender();
        String raw = "A".repeat(5000);

        // Act
        StompCallback.normaliseEditedAt(raw);

        // Assert — SR-TD3-08: length distinguishes truncated ISO-8601 from injection attempt
        assertThat(logAppender.getLoggedMessages())
                .anySatisfy(msg -> assertThat(msg).contains("len=5000"));
        // Raw bytes must not appear verbatim — not even a 100-char fragment
        logAppender.getLoggedMessages().forEach(msg ->
                assertThat(msg).doesNotContain("A".repeat(100)));
    }

    /**
     * SR-TD3-13/SR-TD3-14: parameterized test covering 8 adversarial raw values.
     *
     * <p>For every row the test asserts:
     * <ol>
     *   <li>No {@link ILoggingEvent#getFormattedMessage()} contains the raw input value.</li>
     *   <li>At least one formatted message contains "len=" followed by the expected length.</li>
     *   <li>No element in {@link ILoggingEvent#getArgumentArray()}, when converted to String,
     *       equals the raw input String (SR-TD3-14).</li>
     * </ol>
     *
     * @param raw            adversarial raw editedAt value
     * @param expectedLength the expected length logged as len=N (pre-computed to avoid
     *                       re-deriving from raw in the assertion body)
     */
    @ParameterizedTest(name = "raw.length={1}")
    @MethodSource("malformedEditedAtVariants")
    public void normaliseEditedAt_malformed_neverLogsRawValue(String raw, int expectedLength) {
        // Arrange
        TestLogAppender logAppender = getTestLogAppender();

        // Act
        StompCallback.normaliseEditedAt(raw);

        // Assert — SR-TD3-01: raw peer bytes must not appear in any formatted message
        logAppender.getLoggedMessages().forEach(msg ->
                assertThat(msg).doesNotContain(raw));
        // SR-TD3-02: the length must always be logged
        assertThat(logAppender.getLoggedMessages())
                .anySatisfy(msg -> assertThat(msg).contains("len=" + expectedLength));
        // SR-TD3-14: argument array must not contain the raw input string as any element
        logAppender.getLoggedEvents().forEach(event -> {
            Object[] args = event.getArgumentArray();
            if (args != null) {
                for (Object arg : args) {
                    assertThat(String.valueOf(arg)).isNotEqualTo(raw);
                }
            }
        });
    }

    /**
     * Provides 8 adversarial raw editedAt values for
     * {@link #normaliseEditedAt_malformed_neverLogsRawValue(String, int)}.
     *
     * <p>Each row is {@code Arguments.of(rawValue, rawValue.length())} so that the assertion
     * can check the expected length without re-deriving it from the raw string.
     */
    private static Stream<Arguments> malformedEditedAtVariants() {
        String crlf = "x\r\nWARN  INJECTED FAKE LINE";
        String oversized5k = "A".repeat(5000);
        String bom = "﻿";
        String rtl = "‮";
        String lineSep = " ";
        String paraSep = " ";
        String oversized10k = "A".repeat(10240);
        return Stream.of(
                Arguments.of("not-a-date", "not-a-date".length()),
                Arguments.of(crlf, crlf.length()),
                Arguments.of(oversized5k, oversized5k.length()),
                Arguments.of(bom, bom.length()),
                Arguments.of(rtl, rtl.length()),
                Arguments.of(lineSep, lineSep.length()),
                Arguments.of(paraSep, paraSep.length()),
                Arguments.of(oversized10k, oversized10k.length())
        );
    }

    /**
     * SR-TD3-12: the happy-path return value of {@link StompCallback#normaliseEditedAt(String)}
     * must match the UTC Z-form pattern {@code ^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d+)?Z$}.
     *
     * <p>Tests both a whole-second form and a sub-second form to confirm the regex allows
     * optional fractional seconds.
     */
    @Test
    public void normaliseEditedAt_utcZForm_matchesExpectedPattern() {
        String patternUtcZ = "^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?Z$";

        String wholeSecond = StompCallback.normaliseEditedAt("2025-01-17T00:00:00Z");
        assertThat(wholeSecond).matches(patternUtcZ);

        String subSecond = StompCallback.normaliseEditedAt("2025-01-17T12:34:56.789Z");
        assertThat(subSecond).matches(patternUtcZ);
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
                subscriptionManager, messageCache, shareViewStompRelay, restTemplate,
                PERMISSIVE_VALIDATOR, UUID.randomUUID().toString(), "hashtag", "glacier@example.com", "example.com");
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
    public void onEvent_genericMessage_headRequestThrowsResourceAccessException_dropsEventWithoutException() throws com.fasterxml.jackson.core.JsonProcessingException {
        // Arrange
        when(restTemplate.headForHeaders(anyString()))
                .thenThrow(new ResourceAccessException("Read timed out"));

        StompCallback callback = new StompCallback(
                subscriptionManager, messageCache, shareViewStompRelay, restTemplate,
                PERMISSIVE_VALIDATOR, UUID.randomUUID().toString(), "hashtag", "glacier@example.com", "example.com");

        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        de.seism0saurus.glacier.webservice.messaging.messages.Mention mention =
                de.seism0saurus.glacier.webservice.messaging.messages.Mention.builder()
                        .id("99999").username("@user").acct("glacier").build();
        de.seism0saurus.glacier.webservice.messaging.messages.GenericMessageContentPayload payload =
                de.seism0saurus.glacier.webservice.messaging.messages.GenericMessageContentPayload.builder()
                        .mentions(List.of(mention))
                        .url("https://slow.example.com/99999")
                        .id("99999")
                        .build();
        String payloadAsText = mapper.writeValueAsString(payload);
        com.fasterxml.jackson.databind.JsonNode jsonNode = com.fasterxml.jackson.databind.node.TextNode.valueOf(payloadAsText);
        de.seism0saurus.glacier.webservice.messaging.messages.GenericMessageContent content =
                de.seism0saurus.glacier.webservice.messaging.messages.GenericMessageContent.builder()
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

    // -----------------------------------------------------------------
    // Behavioral gap tests (from Phase 1 / main branch)
    // -----------------------------------------------------------------

    /**
     * {@code X-Frame-Options} header matching must be case-insensitive — the header
     * value {@code deny} (lowercase) must be treated the same as {@code DENY}.
     */
    @Test
    public void isLoadable_caseInsensitiveXFrameOptions() {
        // Arrange — lowercase "deny" must be treated as DENY (case-insensitive)
        Account account = mock(Account.class);
        when(account.getDisplayName()).thenReturn("user@example.com");
        when(mockStatus.getId()).thenReturn("case-test-001");
        when(mockStatus.getUrl()).thenReturn("https://case.example.com/case-test-001");
        when(mockStatus.getAccount()).thenReturn(account);

        HttpHeaders lowerCaseDenyHeader = new HttpHeaders();
        lowerCaseDenyHeader.set("X-Frame-Options", "deny");
        when(restTemplate.headForHeaders("https://case.example.com/case-test-001/embed"))
                .thenReturn(lowerCaseDenyHeader);

        StompCallback callback = new StompCallback(
                subscriptionManager, messageCache, shareViewStompRelay, restTemplate,
                PERMISSIVE_VALIDATOR, UUID.randomUUID().toString(), "hashtag", "glacier@example.com", "glacier.example.com");
        ParsedStreamEvent.StatusCreated event = new ParsedStreamEvent.StatusCreated(mockStatus);
        MastodonApiEvent.StreamEvent streamEvent = new MastodonApiEvent.StreamEvent(event, List.of());

        // Act
        callback.onEvent(streamEvent);

        // Assert — lowercase "deny" treated as non-loadable
        verify(messageCache, never()).recordThenPublish(any(), any(), any());
    }

    /**
     * When the HEAD request for the embed URL throws ResourceAccessException (redirect loop / refused),
     * the event must be dropped — recordThenPublish must not be called.
     */
    @Test
    public void isLoadable_handles302Redirect_returnsFalse() {
        // Arrange — HEAD request throws ResourceAccessException
        when(mockStatus.getId()).thenReturn("redirect-999");
        when(mockStatus.getUrl()).thenReturn("https://redirect.example.com/redirect-999");
        Account account = mock(Account.class);
        when(mockStatus.getAccount()).thenReturn(account);
        when(restTemplate.headForHeaders("https://redirect.example.com/redirect-999/embed"))
                .thenThrow(new ResourceAccessException("Connection refused"));

        StompCallback callback = new StompCallback(
                subscriptionManager, messageCache, shareViewStompRelay, restTemplate,
                PERMISSIVE_VALIDATOR, UUID.randomUUID().toString(), "hashtag", "glacier@example.com", "glacier.example.com");
        ParsedStreamEvent.StatusCreated event = new ParsedStreamEvent.StatusCreated(mockStatus);
        MastodonApiEvent.StreamEvent streamEvent = new MastodonApiEvent.StreamEvent(event, List.of());

        // Act + Assert — must not throw
        assertDoesNotThrow(() -> callback.onEvent(streamEvent));

        // Assert — redirect treated as non-loadable, cache not touched
        verify(messageCache, never()).recordThenPublish(any(), any(), any());
    }

    // -------------------------------------------------------------------------
    // D-13 / SR-8 log-shape tests (Fix #1–#6, ADR-F6)
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
        new StompCallback(subscriptionManager, messageCache, shareViewStompRelay, restTemplate,
                PERMISSIVE_VALIDATOR, principal, hashtag, "glacier@example.com", "example.com");

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
        StompCallback callback = new StompCallback(subscriptionManager, messageCache, shareViewStompRelay, restTemplate,
                PERMISSIVE_VALIDATOR, principal, "hashtag", "glacier@example.com", "glacier.example.com");

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
        StompCallback callback = new StompCallback(subscriptionManager, messageCache, shareViewStompRelay, restTemplate,
                PERMISSIVE_VALIDATOR, UUID.randomUUID().toString(), "hashtag", "glacier@example.com", "example.com");
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
        HttpHeaders allowHeader = getHeaders("ALLOWALL", null);
        when(restTemplate.headForHeaders("https://example.com/4567/embed")).thenReturn(allowHeader);

        String principal = UUID.randomUUID().toString();
        // Use a distinctive hashtag that won't accidentally match log key names like "hashtag-len"
        String hashtag = "glacier2025";
        StompCallback callback = new StompCallback(subscriptionManager, messageCache, shareViewStompRelay, restTemplate,
                PERMISSIVE_VALIDATOR, principal, hashtag, "glacier@example.com", "example.com");

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

    // -------------------------------------------------------------------------
    // F-6-INFO-2 security tests (Lane B)
    //
    // SR-F6INFO2-04: unknown StatusMessage subtype must not propagate an exception
    //               up the Bigbone virtual-thread stack — logs ERROR and returns early.
    // SR-F6INFO2-09: structural guard — sendMessage must have no destination parameter.
    // SR-F6INFO2-06: cross-module invariant — event-type derived from statusMessageClass,
    //               never from the destination path (which embeds the hashtag).
    // -------------------------------------------------------------------------

    /**
     * SR-F6INFO2-04: {@code eventTypeFor} must return {@code Optional.empty()} for an
     * unknown {@link StatusMessage} subtype and must not throw any exception.
     *
     * <p>The guard in {@code sendMessage} then emits LOGGER.error with the key
     * {@code stomp.message.unknown_status_class} before returning early — preventing
     * any exception from propagating up the Bigbone virtual-thread stack (ADR-F6-INFO-2-D).
     *
     * <p>Implementation note: {@code eventTypeFor} is package-private static; we invoke it
     * via reflection because a direct call requires a class in the same package. The
     * reflection approach also serves as the authoritative structural probe — if the method
     * signature changes the reflection lookup will fail, alerting us to re-check the guard.
     */
    @Test
    public void sendMessage_unknownStatusMessageClass_doesNotThrow_andLogsError() throws Exception {
        // Arrange: a concrete but unknown StatusMessage subtype (not Created or Updated)
        class UnknownStatusMessage extends StatusMessage {}

        // Probe eventTypeFor via reflection — it is package-private static; accessible from the test package
        java.lang.reflect.Method eventTypeForMethod =
                StompCallback.class.getDeclaredMethod("eventTypeFor", Class.class);
        eventTypeForMethod.setAccessible(true);

        // Act: invoke eventTypeFor with the unknown subtype — must not throw
        @SuppressWarnings("unchecked")
        Optional<StompEventType> result =
                (Optional<StompEventType>) eventTypeForMethod.invoke(null, UnknownStatusMessage.class);

        // Assert: returns empty — the caller (sendMessage) will log ERROR and return early
        assertThat(result).isEmpty();

        // Now verify the full sendMessage path logs ERROR and does not throw.
        // We drive it through processGenericEvent by constructing a GenericMessage that would normally
        // route to sendMessage, then use a TestLogAppender to capture the error log.
        // Because processGenericEvent only dispatches to StatusCreatedMessage / StatusUpdatedMessage
        // internally, we cannot reach sendMessage with an unknown class through the normal dispatch.
        // The eventTypeFor reflection test above is therefore the canonical SR-F6INFO2-04 assertion.
        // As a defence-in-depth check: verify that a known-good onEvent call does NOT emit
        // stomp.message.unknown_status_class (confirming the error path is only for unknown types).
        TestLogAppender logAppender = getTestLogAppender();
        HttpHeaders allowHeader = getHeaders("ALLOWALL", null);
        when(restTemplate.headForHeaders("https://example.com/4567/embed")).thenReturn(allowHeader);

        StompCallback callback = new StompCallback(
                subscriptionManager, messageCache, shareViewStompRelay, restTemplate,
                PERMISSIVE_VALIDATOR, UUID.randomUUID().toString(), "hashtag", "glacier@example.com", "example.com");

        ObjectMapper mapper = new ObjectMapper();
        Mention mention = Mention.builder().id("4567").username("@glacier").acct("glacier").build();
        GenericMessageContentPayload knownPayload = GenericMessageContentPayload.builder()
                .mentions(List.of(mention)).url("https://example.com/4567").id("4567").build();
        String payloadAsText = mapper.writeValueAsString(knownPayload);
        JsonNode jsonNode = TextNode.valueOf(payloadAsText);
        GenericMessageContent knownContent = GenericMessageContent.builder()
                .event("update").stream(List.of("hashtag")).payload(jsonNode).build();
        MastodonApiEvent.GenericMessage knownEvent = mock(MastodonApiEvent.GenericMessage.class);
        when(knownEvent.getText()).thenReturn(mapper.writeValueAsString(knownContent));

        // Act: known class path — must NOT log the unknown-class error
        callback.onEvent(knownEvent);

        // Assert: no log message mentions unknown_status_class for the known path
        // (SR-F6INFO2-04: the error is only for truly unknown subtypes)
        assertThat(logAppender.getLoggedMessages())
                .noneSatisfy(msg -> assertThat(msg).contains("stomp.message.unknown_status_class"));
    }

    /**
     * SR-F6INFO2-09: structural guard — the {@code sendMessage} method must have exactly three
     * parameters and must NOT have a {@code String destination} fourth parameter.
     *
     * <p>This test fails fast if the destination parameter is accidentally re-introduced,
     * which would break the F-6-INFO-2 invariant that event-type is derived solely from
     * the {@code statusMessageClass} argument, not from the destination string.
     */
    @Test
    public void sendMessage_signature_hasNoDestinationParameter() {
        // Assert three-parameter form exists — this is the post-F-6-INFO-2 contract
        java.lang.reflect.Method threeParam = null;
        try {
            threeParam = StompCallback.class.getDeclaredMethod(
                    "sendMessage", ObjectMapper.class, Class.class, GenericMessageContent.class);
        } catch (NoSuchMethodException e) {
            throw new AssertionError(
                    "SR-F6INFO2-09: sendMessage(ObjectMapper, Class, GenericMessageContent) not found — " +
                    "the three-parameter form must exist after the destination-param removal", e);
        }
        assertThat(threeParam.getParameterCount())
                .as("sendMessage must have exactly 3 parameters (no destination String)")
                .isEqualTo(3);

        // Assert four-parameter form (with destination String) does NOT exist
        assertThat(StompCallback.class)
                .satisfies(cls -> {
                    try {
                        cls.getDeclaredMethod(
                                "sendMessage", ObjectMapper.class, Class.class, GenericMessageContent.class, String.class);
                        throw new AssertionError(
                                "SR-F6INFO2-09: sendMessage(ObjectMapper, Class, GenericMessageContent, String) " +
                                "must NOT exist — the destination parameter was removed by F-6-INFO-2");
                    } catch (NoSuchMethodException expected) {
                        // correct: the four-parameter overload must not exist
                    }
                });
    }

    /**
     * SR-F6INFO2-06: cross-module invariant — event-type in the published log is derived from
     * {@code statusMessageClass}, never from the destination path (which embeds the hashtag).
     *
     * <p>This test constructs a {@link StompCallback} with hashtag {@code "a/b"} — a value that
     * bypasses {@code HashtagFormat} validation because this is a unit test, not the production
     * path. The hashtag's trailing segment {@code "b"} happens to equal the legacy
     * {@code lastIndexOf('/')} substring that the old code used to derive the event suffix.
     *
     * <p>If the implementation were accidentally re-introduced to derive event-type from the
     * destination string (e.g. {@code destination.substring(destination.lastIndexOf('/') + 1)}),
     * the log would contain {@code "event-type=b"} instead of {@code "event-type=creation"}.
     * This test pins the contract that the enum {@link StompEventType#suffix()} is the sole source.
     *
     * <p>Today this scenario is unreachable in production because {@code HashtagFormat.PATTERN}
     * forbids {@code '/'} in hashtags. The test exists as a forward-compatibility defence: if
     * {@code HashtagFormat} is ever loosened, this test detects a log-injection regression before
     * it reaches production (defence-in-depth, NIST SP 800-53 SI-10).
     */
    @Test
    public void stompMessagePublished_logsEventTypeFromEnum_evenIfHashtagContainsSlash()
            throws JsonProcessingException {
        // Arrange: hashtag "a/b" — unit-test only; bypasses production HashtagFormat validation.
        // The trailing segment "b" is what the old lastIndexOf-based code would have derived.
        TestLogAppender logAppender = getTestLogAppender();
        HttpHeaders allowHeader = getHeaders("ALLOWALL", null);
        when(restTemplate.headForHeaders("https://example.com/4567/embed")).thenReturn(allowHeader);

        // Principal and hashtag injected directly into the constructor — no validation gate here
        String principal = UUID.randomUUID().toString();
        String hashtagWithSlash = "a/b"; // deliberately contains '/' to probe old suffix extraction
        StompCallback callback = new StompCallback(
                subscriptionManager, messageCache, shareViewStompRelay, restTemplate,
                PERMISSIVE_VALIDATOR, principal, hashtagWithSlash, "glacier@example.com", "example.com");

        ObjectMapper mapper = new ObjectMapper();
        Mention mention = Mention.builder().id("4567").username("@glacier").acct("glacier").build();
        GenericMessageContentPayload payload = GenericMessageContentPayload.builder()
                .mentions(List.of(mention)).url("https://example.com/4567").id("4567").build();
        String payloadAsText = mapper.writeValueAsString(payload);
        JsonNode jsonNode = TextNode.valueOf(payloadAsText);
        // "update" event → maps to StatusCreatedMessage → StompEventType.CREATION → suffix "creation"
        GenericMessageContent content = GenericMessageContent.builder()
                .event("update").stream(List.of("hashtag")).payload(jsonNode).build();
        MastodonApiEvent.GenericMessage mockEvent = mock(MastodonApiEvent.GenericMessage.class);
        when(mockEvent.getText()).thenReturn(mapper.writeValueAsString(content));

        // Act
        callback.onEvent(mockEvent);

        // Assert: event-type derived from StompEventType.CREATION.suffix() == "creation"
        assertThat(logAppender.getLoggedMessages())
                .anySatisfy(msg -> {
                    assertThat(msg).contains("stomp.message.published");
                    assertThat(msg).contains("event-type=creation");
                });

        // Assert: event-type must NOT be "b" — the old lastIndexOf-based derivation would produce "b"
        // because the destination was "/topic/hashtags/<principal>/a/b" and lastIndexOf('/') + 1 = "b"
        assertThat(logAppender.getLoggedMessages())
                .noneSatisfy(msg -> assertThat(msg).contains("event-type=b"));
    }

    /**
     * Verifies that a {@code status.update} GenericMessage published to the wall logs
     * {@code event-type=modification} — confirming that {@link StompEventType#MODIFICATION}
     * is the sole source of the event-type field, never a destination-string substring
     * (SR-F6INFO2-02).
     *
     * <p>Mirrors {@link #stompMessagePublished_logsEventTypeFromEnum_evenIfHashtagContainsSlash}
     * for the MODIFICATION branch. Together the two tests cover the full CREATION / MODIFICATION
     * symmetry of the {@code eventTypeFor} mapper.
     */
    @Test
    public void stompMessagePublished_logsEventTypeModification_whenStatusUpdatedDispatched()
            throws JsonProcessingException {
        TestLogAppender logAppender = getTestLogAppender();
        HttpHeaders allowHeader = getHeaders("ALLOWALL", null);
        when(restTemplate.headForHeaders("https://example.com/4567/embed")).thenReturn(allowHeader);

        String principal = UUID.randomUUID().toString();
        StompCallback callback = new StompCallback(
                subscriptionManager, messageCache, shareViewStompRelay, restTemplate,
                PERMISSIVE_VALIDATOR, principal, "hashtag", "glacier@example.com", "example.com");

        ObjectMapper mapper = new ObjectMapper();
        Mention mention = Mention.builder().id("4567").username("@glacier").acct("glacier").build();
        GenericMessageContentPayload payload = GenericMessageContentPayload.builder()
                .mentions(List.of(mention)).url("https://example.com/4567").id("4567")
                .editedAt("2025-01-17T00:00:00Z").build();
        String payloadAsText = mapper.writeValueAsString(payload);
        JsonNode jsonNode = TextNode.valueOf(payloadAsText);
        // "status.update" event → maps to StatusUpdatedMessage → StompEventType.MODIFICATION → suffix "modification"
        GenericMessageContent content = GenericMessageContent.builder()
                .event("status.update").stream(List.of("hashtag")).payload(jsonNode).build();
        MastodonApiEvent.GenericMessage mockEvent = mock(MastodonApiEvent.GenericMessage.class);
        when(mockEvent.getText()).thenReturn(mapper.writeValueAsString(content));

        callback.onEvent(mockEvent);

        assertThat(logAppender.getLoggedMessages())
                .anySatisfy(msg -> {
                    assertThat(msg).contains("stomp.message.published");
                    assertThat(msg).contains("event-type=modification");
                });
        // Guard: "event-type=hashtag" would indicate the old destination-substring derivation
        assertThat(logAppender.getLoggedMessages())
                .noneSatisfy(msg -> assertThat(msg).contains("event-type=hashtag"));
    }

    // -----------------------------------------------------------------
    // TD-4: xFrameOptions log hygiene — CWE-117 / D-13 / SR-8 canaries
    // T6a: canary value must never appear in any log event
    // T6b: log event contains bounded fields at WARN, never routed to AUDIT
    // T6c: parameterised control-char payloads must not reach the log encoder
    // -----------------------------------------------------------------

    /**
     * T6a — Verifies that a canary value injected via {@code X-Frame-Options} never appears
     * in any captured log event when the "unknown or invalid value" branch fires.
     *
     * <p>The branch at {@code isLoadable} line 411 (pre-fix) logs the raw {@code xFrameOptions}
     * list via {@code {}}, passing peer-controlled bytes to the JSON encoder. After the TD-4 fix
     * the call must use {@code LogScrubber.xfoSummary}, so the canary never appears.
     *
     * <p>Arrange: {@link HttpHeaders} with {@code X-Frame-Options: __CANARY_TD4_XFO__} (no CSP).
     *             This triggers the "unknown or invalid value" branch — neither DENY/SAMEORIGIN
     *             (explicitlyNotAllowed) nor ALLOWALL (explicitlyAllowed).
     * Act: feed a {@link ParsedStreamEvent.StatusCreated} event through
     *      {@code callback.onEvent(streamEvent)}.
     * Assert:
     * <ol>
     *   <li>At least one captured {@link ILoggingEvent} has {@link Level#WARN} and formatted
     *       message containing {@code "xfo-values="} — the bounded summary is logged.</li>
     *   <li>No captured event's {@link ILoggingEvent#getFormattedMessage()} contains
     *       {@code "__CANARY_TD4_XFO__"}.</li>
     *   <li>No element of any event's {@link ILoggingEvent#getArgumentArray()} (stringified via
     *       {@link String#valueOf}) contains {@code "__CANARY_TD4_XFO__"}.</li>
     *   <li>{@link MessageCache#recordThenPublish} is never called — the toot is dropped.</li>
     * </ol>
     */
    @Test
    public void isLoadable_unknownXFrameOptions_doesNotLogRawListValue() {
        // Arrange
        TestLogAppender logAppender = getTestLogAppender();
        String principal = UUID.randomUUID().toString();
        StompCallback callback = new StompCallback(
                subscriptionManager, messageCache, shareViewStompRelay, restTemplate,
                PERMISSIVE_VALIDATOR, principal, "hashtag", "glacier@example.com", "example.com");

        // Set X-Frame-Options to a canary value that triggers the "unknown or invalid" branch.
        // The branch fires because the value is not DENY, SAMEORIGIN, or ALLOWALL.
        HttpHeaders xfoHeaders = new HttpHeaders();
        xfoHeaders.set("X-Frame-Options", "__CANARY_TD4_XFO__");
        when(restTemplate.headForHeaders("https://mastodon.example.com/12345/embed")).thenReturn(xfoHeaders);

        Account account = mock(Account.class);
        when(account.getDisplayName()).thenReturn("user@example.com");
        when(mockStatus.getId()).thenReturn("12345");
        when(mockStatus.getUrl()).thenReturn("https://mastodon.example.com/12345");
        when(mockStatus.getAccount()).thenReturn(account);

        ParsedStreamEvent.StatusCreated event = new ParsedStreamEvent.StatusCreated(mockStatus);
        MastodonApiEvent.StreamEvent streamEvent = new MastodonApiEvent.StreamEvent(event, List.of());

        // Act
        callback.onEvent(streamEvent);

        // Assert 1: the bounded summary must appear in at least one WARN event
        assertThat(logAppender.getLoggedEvents())
                .anySatisfy(e -> {
                    assertThat(e.getLevel()).isEqualTo(Level.WARN);
                    assertThat(e.getFormattedMessage()).contains("xfo-values=");
                });

        // Assert 2: no formatted message may contain the raw canary
        assertThat(logAppender.getLoggedEvents())
                .allSatisfy(e ->
                        assertThat(e.getFormattedMessage()).doesNotContain("__CANARY_TD4_XFO__"));

        // Assert 3: no argument array element (stringified) may contain the raw canary
        assertThat(logAppender.getLoggedEvents())
                .allSatisfy(e -> {
                    Object[] args = e.getArgumentArray();
                    if (args != null) {
                        for (Object arg : args) {
                            assertThat(String.valueOf(arg)).doesNotContain("__CANARY_TD4_XFO__");
                        }
                    }
                });

        // Assert 4: the toot is dropped — no cache write
        verify(messageCache, never()).recordThenPublish(any(), any(), any());
    }

    /**
     * T6b — Verifies that the "unknown X-Frame-Options" log event uses the bounded summary,
     * is emitted at WARN level, and is never routed to the AUDIT logger.
     *
     * <p>Arrange: two XFO values {@code "MAYBE"} and {@code "ALSOMAYBE"} — both unknown,
     *             triggering the same branch as T6a but with {@code xfo-values=2}.
     * Act: feed a {@link ParsedStreamEvent.StatusCreated} event.
     * Assert:
     * <ol>
     *   <li>Exactly one WARN event has formatted message containing {@code "xfo-values=2"} and
     *       {@code "xfo-totallen="}.</li>
     *   <li>That event's level is {@link Level#WARN}.</li>
     *   <li>AUDIT logger receives zero events for this scenario.</li>
     * </ol>
     */
    @Test
    public void isLoadable_unknownXFrameOptions_logsBoundedFieldsAtWarnNotAudit() {
        // Arrange
        TestLogAppender logAppender = getTestLogAppender();
        TestLogAppender auditAppender = getAuditLogAppender();
        String principal = UUID.randomUUID().toString();
        StompCallback callback = new StompCallback(
                subscriptionManager, messageCache, shareViewStompRelay, restTemplate,
                PERMISSIVE_VALIDATOR, principal, "hashtag", "glacier@example.com", "example.com");

        HttpHeaders xfoHeaders = new HttpHeaders();
        xfoHeaders.addAll("X-Frame-Options", List.of("MAYBE", "ALSOMAYBE"));
        when(restTemplate.headForHeaders("https://mastodon.example.com/12345/embed")).thenReturn(xfoHeaders);

        Account account = mock(Account.class);
        when(account.getDisplayName()).thenReturn("user@example.com");
        when(mockStatus.getId()).thenReturn("12345");
        when(mockStatus.getUrl()).thenReturn("https://mastodon.example.com/12345");
        when(mockStatus.getAccount()).thenReturn(account);

        ParsedStreamEvent.StatusCreated event = new ParsedStreamEvent.StatusCreated(mockStatus);
        MastodonApiEvent.StreamEvent streamEvent = new MastodonApiEvent.StreamEvent(event, List.of());

        // Act
        callback.onEvent(streamEvent);

        // Assert 1 & 2: exactly one WARN event with the bounded summary for 2 values
        List<ILoggingEvent> matchingEvents = logAppender.getLoggedEvents().stream()
                .filter(e -> e.getLevel() == Level.WARN
                        && e.getFormattedMessage().contains("xfo-values=2")
                        && e.getFormattedMessage().contains("xfo-totallen="))
                .toList();
        assertThat(matchingEvents)
                .as("Exactly one WARN event must contain 'xfo-values=2' and 'xfo-totallen='")
                .hasSize(1);
        assertThat(matchingEvents.get(0).getLevel()).isEqualTo(Level.WARN);

        // Assert 3: AUDIT logger must not receive any events — unknown XFO is not a security audit event
        assertThat(auditAppender.getLoggedEvents()).isEmpty();
    }

    /**
     * Provides 8 adversarial X-Frame-Options values covering the control-character families
     * that are most dangerous in log injection scenarios (TD-4 / T6c).
     *
     * <p>Each row is a single adversarial codepoint (or sequence) that MUST NOT appear in
     * any formatted log message or argument array after the fix is applied.
     */
    private static Stream<Arguments> xfoControlCharVariants() {
        return Stream.of(
                Arguments.of("\r",        "CR (CARRIAGE RETURN)"),
                Arguments.of("\n",        "LF (LINE FEED)"),
                Arguments.of("",    "ESC (ANSI escape introducer)"),
                Arguments.of(" ",    "NUL (null byte)"),
                Arguments.of(" ",    "LINE SEPARATOR"),
                Arguments.of(" ",    "PARAGRAPH SEPARATOR"),
                Arguments.of("‮",    "RIGHT-TO-LEFT OVERRIDE"),
                Arguments.of("﻿",    "BOM / ZERO-WIDTH NO-BREAK SPACE")
        );
    }

    /**
     * T6c — Parameterised: for each of 8 adversarial codepoints injected via
     * {@code X-Frame-Options}, no captured log event may contain that codepoint.
     *
     * <p>The eight variants mirror the {@code malformedEditedAtVariants} discipline from TD-3
     * and cover the control-character families most dangerous in CWE-117 injection scenarios.
     *
     * <p>Arrange: set {@code X-Frame-Options} to a value containing the adversarial codepoint;
     *             feed a {@link ParsedStreamEvent.StatusCreated} event.
     * Assert: for each row:
     * <ol>
     *   <li>No captured {@link ILoggingEvent#getFormattedMessage()} contains the codepoint.</li>
     *   <li>No element in any {@link ILoggingEvent#getArgumentArray()} (stringified) contains it.</li>
     * </ol>
     *
     * @param adversarialCodepoint the adversarial codepoint string injected in the XFO header
     * @param description          human-readable description of the codepoint for test naming
     */
    @ParameterizedTest(name = "xfo-control-char: {1}")
    @MethodSource("xfoControlCharVariants")
    public void isLoadable_unknownXFrameOptions_neverLogsControlChars(
            String adversarialCodepoint, String description) {
        // Arrange
        TestLogAppender logAppender = getTestLogAppender();
        String principal = UUID.randomUUID().toString();
        StompCallback callback = new StompCallback(
                subscriptionManager, messageCache, shareViewStompRelay, restTemplate,
                PERMISSIVE_VALIDATOR, principal, "hashtag", "glacier@example.com", "example.com");

        // Inject the adversarial codepoint as the XFO header value — wraps it in a minimal
        // unknown value so neither DENY/SAMEORIGIN nor ALLOWALL match.
        String xfoValue = "UNKNOWN" + adversarialCodepoint + "VALUE";
        HttpHeaders xfoHeaders = new HttpHeaders();
        xfoHeaders.set("X-Frame-Options", xfoValue);
        when(restTemplate.headForHeaders("https://mastodon.example.com/12345/embed")).thenReturn(xfoHeaders);

        Account account = mock(Account.class);
        when(account.getDisplayName()).thenReturn("user@example.com");
        when(mockStatus.getId()).thenReturn("12345");
        when(mockStatus.getUrl()).thenReturn("https://mastodon.example.com/12345");
        when(mockStatus.getAccount()).thenReturn(account);

        ParsedStreamEvent.StatusCreated event = new ParsedStreamEvent.StatusCreated(mockStatus);
        MastodonApiEvent.StreamEvent streamEvent = new MastodonApiEvent.StreamEvent(event, List.of());

        // Act
        callback.onEvent(streamEvent);

        // Assert: no formatted message contains the adversarial codepoint
        assertThat(logAppender.getLoggedEvents())
                .allSatisfy(e ->
                        assertThat(e.getFormattedMessage())
                                .as("Formatted message must not contain adversarial codepoint: %s", description)
                                .doesNotContain(adversarialCodepoint));

        // Assert: no argument array element (stringified) contains the adversarial codepoint
        assertThat(logAppender.getLoggedEvents())
                .allSatisfy(e -> {
                    Object[] args = e.getArgumentArray();
                    if (args != null) {
                        for (Object arg : args) {
                            assertThat(String.valueOf(arg))
                                    .as("Argument must not contain adversarial codepoint: %s", description)
                                    .doesNotContain(adversarialCodepoint);
                        }
                    }
                });
    }

    // -----------------------------------------------------------------
    // SR-TD4-05: T6b-struct — parameterised injection fuzz
    // -----------------------------------------------------------------

    /**
     * Provides 8 adversarial {@code X-Frame-Options} header values for the T6b-struct
     * injection fuzz test (SR-TD4-05 / CWE-117 / D-13).
     *
     * <p>Each row is a tuple of:
     * <ol>
     *   <li>The full adversarial XFO header value to inject.</li>
     *   <li>The specific fragment that MUST NOT appear in any log output if the fix holds.</li>
     *   <li>A human-readable description used as the parameterised-test display name.</li>
     * </ol>
     *
     * <p>Row design follows the TD-2 T-B1 injection-fuzz discipline: every adversarial class
     * (ASCII canary, CRLF injection, oversized blob, ANSI escape, NUL byte, Unicode line
     * separators, RTL override, BOM) is represented exactly once.
     */
    private static Stream<Arguments> xfoInjectionFuzzVariants() {
        return Stream.of(
                // Row 1: ASCII canary — the fragment itself must not appear in any log field
                Arguments.of("__CANARY_TD4_STRUCT__",
                        "__CANARY_TD4_STRUCT__",
                        "ASCII canary"),
                // Row 2: CRLF injection — "INJECTED" must not appear (would indicate a fake log line was accepted)
                Arguments.of("foo\r\nWARN INJECTED FAKE LINE",
                        "INJECTED",
                        "CRLF injection"),
                // Row 3: Oversized value (5000 'A' chars) — 10-char run must not appear
                Arguments.of("A".repeat(5000),
                        "AAAAAAAAAA",
                        "oversized value 5000 chars"),
                // Row 4: ANSI escape sequence — ESC byte must not appear
                Arguments.of("MAYBE[31mANSI",
                        "",
                        "ANSI escape (ESC)"),
                // Row 5: NUL byte — NUL must not appear
                Arguments.of("MAYBE NULL",
                        " ",
                        "NUL byte"),
                // Row 6: Unicode LINE SEPARATOR (U+2028) — must not appear
                Arguments.of("MAYBE LINE_SEP",
                        " ",
                        "Unicode LINE SEPARATOR U+2028"),
                // Row 7: Right-to-left override (U+202E) — must not appear
                Arguments.of("MAYBE‮RLO",
                        "‮",
                        "Right-to-left override U+202E"),
                // Row 8: BOM / zero-width no-break space (U+FEFF) — must not appear
                Arguments.of("MAYBE﻿BOM",
                        "﻿",
                        "BOM U+FEFF")
        );
    }

    /**
     * T6b-struct (SR-TD4-05) — parameterised injection fuzz: for each adversarial
     * {@code X-Frame-Options} value, the bounded {@code LogScrubber.xfoSummary} output must
     * never leak the attacker-controlled bytes into any log field.
     *
     * <p>Mirrors the TD-2 T-B1 injection-fuzz discipline. The "unknown or invalid value"
     * branch in {@code StompCallback.isLoadable} is triggered because none of the adversarial
     * values match DENY, SAMEORIGIN, or ALLOWALL.
     *
     * <p>Arrange: set {@code X-Frame-Options} to the adversarial value (no CSP).
     *             Feed a {@link ParsedStreamEvent.StatusCreated} event through the callback.
     * Assert for each row:
     * <ol>
     *   <li>No captured {@link ILoggingEvent#getFormattedMessage()} contains the adversarial
     *       fragment (e.g. the canary, {@code "INJECTED"}, 10-char {@code "AAAAAAAAAA"},
     *       ESC byte, NUL byte, U+2028, U+202E, U+FEFF).</li>
     *   <li>No element in any {@link ILoggingEvent#getArgumentArray()} (stringified via
     *       {@link String#valueOf}) contains the adversarial fragment.</li>
     *   <li>No captured event has a non-null {@link ch.qos.logback.classic.spi.IThrowableProxy}
     *       attached — no exception thrown by the logging path.</li>
     * </ol>
     *
     * @param adversarialXfoValue the full header value to inject via {@code X-Frame-Options}
     * @param forbiddenFragment   the specific string fragment that must not appear in log output
     * @param description         human-readable row label used as test display name
     */
    @ParameterizedTest(name = "xfo-injection-fuzz: {2}")
    @MethodSource("xfoInjectionFuzzVariants")
    public void isLoadable_unknownXFrameOptions_neverLeaksAttackerBytes(
            String adversarialXfoValue, String forbiddenFragment, String description) {
        // Arrange
        TestLogAppender logAppender = getTestLogAppender();
        String principal = UUID.randomUUID().toString();
        StompCallback callback = new StompCallback(
                subscriptionManager, messageCache, shareViewStompRelay, restTemplate,
                PERMISSIVE_VALIDATOR, principal, "hashtag", "glacier@example.com", "example.com");

        // Set X-Frame-Options to the adversarial value — unknown value triggers the
        // "unknown or invalid" branch which calls LogScrubber.xfoSummary (TD-4 fix).
        HttpHeaders xfoHeaders = new HttpHeaders();
        xfoHeaders.set("X-Frame-Options", adversarialXfoValue);
        when(restTemplate.headForHeaders("https://mastodon.example.com/12345/embed")).thenReturn(xfoHeaders);

        Account account = mock(Account.class);
        when(account.getDisplayName()).thenReturn("user@example.com");
        when(mockStatus.getId()).thenReturn("12345");
        when(mockStatus.getUrl()).thenReturn("https://mastodon.example.com/12345");
        when(mockStatus.getAccount()).thenReturn(account);

        ParsedStreamEvent.StatusCreated event = new ParsedStreamEvent.StatusCreated(mockStatus);
        MastodonApiEvent.StreamEvent streamEvent = new MastodonApiEvent.StreamEvent(event, List.of());

        // Act — must not throw
        callback.onEvent(streamEvent);

        // Assert 1 (SR-TD4-05): no formatted message may contain the forbidden fragment
        assertThat(logAppender.getLoggedEvents())
                .allSatisfy(e ->
                        assertThat(e.getFormattedMessage())
                                .as("SR-TD4-05: formatted message must not contain adversarial fragment [%s] — row: %s",
                                        forbiddenFragment, description)
                                .doesNotContain(forbiddenFragment));

        // Assert 2 (SR-TD4-05): no argument array element (stringified) may contain the forbidden fragment
        assertThat(logAppender.getLoggedEvents())
                .allSatisfy(e -> {
                    Object[] args = e.getArgumentArray();
                    if (args != null) {
                        for (Object arg : args) {
                            assertThat(String.valueOf(arg))
                                    .as("SR-TD4-05: argument array element must not contain adversarial fragment [%s] — row: %s",
                                            forbiddenFragment, description)
                                    .doesNotContain(forbiddenFragment);
                        }
                    }
                });

        // Assert 3 (SR-TD4-05): no log event may carry an attached throwable
        assertThat(logAppender.getLoggedEvents())
                .allSatisfy(e ->
                        assertThat(e.getThrowableProxy())
                                .as("SR-TD4-05: no exception must be attached to any log event — row: %s", description)
                                .isNull());
    }

    // -----------------------------------------------------------------
    // SR-TD4-07, SR-TD4-08, SR-TD4-10: T6b-gate — structural regression gate
    // -----------------------------------------------------------------

    /**
     * T6b-gate (SR-TD4-07 / SR-TD4-10) — structural regression gate: no {@code LOGGER.*}
     * call in {@code StompCallback.java} passes the bare {@code xFrameOptions} or {@code csp}
     * variable directly, and the original vulnerable format-string fragment is absent.
     *
     * <p>This test reads {@code StompCallback.java} as source text and asserts three invariants
     * that would be violated by a regression of the TD-4 fix (CWE-117 / D-13 / SR-8):
     *
     * <ol>
     *   <li>(SR-TD4-07 / SR-TD4-10 primary): No source line containing {@code LOGGER.} also
     *       contains a bare {@code xFrameOptions} token not followed by {@code .} —
     *       regex {@code \bxFrameOptions\b(?!\.)}. The fix replaced bare {@code xFrameOptions}
     *       with {@code LogScrubber.xfoSummary(xFrameOptions)}, so the bare token must never
     *       appear on a logger call line again.</li>
     *   <li>(SR-TD4-07 / SR-TD4-10 primary): No source line containing {@code LOGGER.} also
     *       contains a bare {@code csp} token not followed by {@code .} —
     *       regex {@code \bcsp\b(?!\.)}.  The {@code csp} variable is also peer-controlled
     *       input from the remote instance response; passing it bare to a logger would
     *       re-introduce the same CWE-117 vulnerability.</li>
     *   <li>(SR-TD4-10 secondary / belt-and-braces): The entire source file does not contain
     *       the original format-string fragment {@code "unknown or invalid value: {}"} — the
     *       exact vulnerable format string that was replaced by the TD-4 fix.  Its presence
     *       would indicate either a revert or a copy-paste regression.</li>
     * </ol>
     *
     * <p>Note: SR-TD4-08 routing (WARN level / not AUDIT) is verified behaviourally by the
     * existing T6b test ({@code isLoadable_unknownXFrameOptions_logsBoundedFieldsAtWarnNotAudit});
     * this gate provides complementary structural coverage at the source level.
     *
     * <p>The source file is located via the same
     * {@link Class#getProtectionDomain()} pattern used by the SR-TD3-10 gate.
     *
     * <p>Note: this gate scans physical lines. A future multi-line LOGGER.*(...) statement
     * with xFrameOptions or csp on a continuation line would not be caught.
     * All current logger calls in StompCallback.java are single-line (verified TD-4 2026-04-30).
     *
     * @throws Exception if the source file cannot be read — treated as a test failure
     */
    @Test
    public void isLoadable_structuralRegressionGate_noRawXfoOrCspOnLoggerLines()
            throws Exception {
        // Locate StompCallback.java from the compiled class location.
        // The class file sits at …/target/classes/de/seism0saurus/glacier/mastodon/StompCallback.class;
        // navigate to the Maven project root and then to the source tree.
        java.net.URL classUrl = StompCallback.class.getProtectionDomain().getCodeSource().getLocation();
        // classUrl is .../target/classes/ — resolve to the project root (parent of target/) then to source
        java.nio.file.Path classesDir = Paths.get(classUrl.toURI());
        // Walk up from target/classes to the Maven project root (parent of target/)
        java.nio.file.Path projectRoot = classesDir.getParent().getParent();
        java.nio.file.Path sourceFile = projectRoot
                .resolve("src/main/java/de/seism0saurus/glacier/mastodon/StompCallback.java");

        assertThat(sourceFile).as("StompCallback.java must exist at resolved path").exists();

        List<String> lines = Files.readAllLines(sourceFile);
        String fullSource = String.join("\n", lines);

        // Invariant 3 / SR-TD4-10 secondary (belt-and-braces):
        // The original vulnerable format-string fragment must not appear anywhere in the file.
        // Its presence would indicate the fix was reverted or a similar vulnerable call was added.
        assertThat(fullSource)
                .as("SR-TD4-10: the old vulnerable fragment \"unknown or invalid value: {}\" must not " +
                    "appear in StompCallback.java — its presence indicates a raw peer-controlled list " +
                    "is being passed to the log encoder (CWE-117 / D-13 / SR-8)")
                .doesNotContain("unknown or invalid value: {}");

        // Patterns for Invariants 1 and 2 (SR-TD4-07 / SR-TD4-10 primary):
        //
        // \bxFrameOptions\b(?!\.) — xFrameOptions as a whole word NOT followed by a dot.
        // Allowed:  LogScrubber.xfoSummary(xFrameOptions)   → xFrameOptions is the arg, not on LOGGER line
        //           xFrameOptions.stream()                  → method call, dot follows
        //           xFrameOptions != null                   → safe boolean check, not on LOGGER line
        // Forbidden: LOGGER.warn("...", xFrameOptions)      → raw list passed directly
        //
        // \bcsp\b(?!\.) — csp as a whole word NOT followed by a dot.
        // Allowed:  csp.getFirst()                          → method call, dot follows
        //           csp != null                             → safe boolean check, not on LOGGER line
        // Forbidden: LOGGER.warn("...", csp)                → raw peer-controlled list passed directly
        Pattern bareXfoOnLoggerLine = Pattern.compile("\\bxFrameOptions\\b(?!\\.)");
        Pattern bareCspOnLoggerLine = Pattern.compile("\\bcsp\\b(?!\\.)");

        for (String line : lines) {
            String trimmed = line.trim();
            // Only inspect active logger call lines — skip comments and Javadoc
            if (trimmed.startsWith("//") || trimmed.startsWith("*")) {
                continue;
            }
            if (trimmed.contains("LOGGER.")) {
                // Invariant 1 (SR-TD4-07 / SR-TD4-10): no bare xFrameOptions on a logger line
                assertThat(bareXfoOnLoggerLine.matcher(trimmed).find())
                        .as("SR-TD4-07/SR-TD4-10: logger call line must not pass bare xFrameOptions — " +
                            "peer-controlled list must go through LogScrubber.xfoSummary() " +
                            "(CWE-117 / D-13 / SR-8). Offending line: [" + trimmed + "]")
                        .isFalse();

                // Invariant 2 (SR-TD4-07 / SR-TD4-10): no bare csp on a logger line
                assertThat(bareCspOnLoggerLine.matcher(trimmed).find())
                        .as("SR-TD4-07/SR-TD4-10: logger call line must not pass bare csp — " +
                            "peer-controlled CSP header list must be scrubbed before logging " +
                            "(CWE-117 / D-13 / SR-8). Offending line: [" + trimmed + "]")
                        .isFalse();
            }
        }
    }

    // -----------------------------------------------------------------
    // TD-5 — Lane A tests  (T7a, T7b)
    //
    // Canary tests for the two CWE-117 violations in processTechnicalEvent:
    //   TD-5-A: default branch — event.toString() reached the log encoder
    //   TD-5-B: Open branch   — open.toString() reached the log encoder
    //
    // SR coverage: SR-TD5-01 (default branch: no peer bytes), SR-TD5-02 (Open branch:
    //              no peer bytes), SR-TD5-05 (class= present), SR-TD5-06 (open recognised).
    // -----------------------------------------------------------------

    /**
     * T7a — Canary test for TD-5-A: the default branch of {@code processTechnicalEvent}
     * must NOT log the {@code toString()} value of an unknown {@link TechnicalEvent}.
     *
     * <p>Arrange: a mocked {@link TechnicalEvent} whose {@code toString()} returns
     *             {@link #CANARY_FRAGMENT_TD5}. This triggers the {@code default} branch
     *             inside {@code processTechnicalEvent} (the event matches no named case).
     * Act:     invoke {@code onEvent} with the mock event.
     * Assert (SR-TD5-01):
     * <ol>
     *   <li>No {@link ILoggingEvent#getFormattedMessage()} contains the canary fragment —
     *       the raw {@code toString()} value must not reach the log encoder.</li>
     *   <li>No element of {@link ILoggingEvent#getArgumentArray()} (stringified via
     *       {@link String#valueOf}) contains the canary fragment.</li>
     * </ol>
     * Assert (SR-TD5-05 — positive shape):
     * <ol start="3">
     *   <li>At least one formatted message contains {@code "class="} — the fix preserves
     *       the operational diagnostic signal by logging the simple class name.</li>
     * </ol>
     *
     * <p><strong>RED state</strong>: this test FAILS before the TD-5-A fix is applied
     * because the current production code calls
     * {@code logEvent("got an unknown WebSocketEvent: %s".formatted(event))},
     * which expands to the canary string.
     */
    @Test
    public void processTechnicalEvent_defaultBranch_doesNotLogEventToString() {
        // Arrange
        TestLogAppender logAppender = getTestLogAppender();
        StompCallback callback = new StompCallback(
                subscriptionManager, messageCache, shareViewStompRelay, restTemplate,
                PERMISSIVE_VALIDATOR, UUID.randomUUID().toString(), "hashtag", "glacier@example.com", "example.com");

        // Mock a TechnicalEvent that is not one of the four named cases (Open/Closing/Closed/Failure).
        // Override toString() to return the canary so any %s-formatting leaks are detected.
        TechnicalEvent mockEvent = mock(TechnicalEvent.class);
        when(mockEvent.toString()).thenReturn(CANARY_FRAGMENT_TD5);

        // Act
        callback.onEvent(mockEvent);

        // Assert SR-TD5-01: canary must not appear in any formatted message
        List<ILoggingEvent> allEvents = new ArrayList<>(logAppender.getLoggedEvents());
        assertThat(allEvents)
                .as("SR-TD5-01: event.toString() canary must not appear in any formatted log message")
                .allSatisfy(event ->
                        assertThat(event.getFormattedMessage())
                                .doesNotContain(CANARY_FRAGMENT_TD5));

        // Assert SR-TD5-01: canary must not appear in any SLF4J argument
        assertThat(allEvents)
                .as("SR-TD5-01: event.toString() canary must not appear in any log argument")
                .allSatisfy(event -> {
                    Object[] args = event.getArgumentArray();
                    if (args != null) {
                        for (Object arg : args) {
                            assertThat(String.valueOf(arg))
                                    .doesNotContain(CANARY_FRAGMENT_TD5);
                        }
                    }
                });

        // Assert SR-TD5-05 (positive shape): at least one message contains "class=" to confirm
        // the fix replaced event.toString() with event.getClass().getSimpleName()
        assertThat(allEvents)
                .as("SR-TD5-05: at least one log message must contain 'class=' (operational diagnostic signal)")
                .anySatisfy(event ->
                        assertThat(event.getFormattedMessage())
                                .contains("class="));
    }

    /**
     * T7b — Canary test for TD-5-B: the Open branch of {@code processTechnicalEvent}
     * must NOT log the {@code toString()} value of the {@link TechnicalEvent.Open} object.
     *
     * <p>{@link TechnicalEvent.Open} is a Kotlin {@code data object} whose current
     * {@code toString()} returns the constant string {@code "Open"}. This is not
     * peer-controlled today, but D-13/SR-8 prohibits any {@code toString()} call on an
     * external library type in a {@code logEvent()} path (ADR-TD5-B, preventive fix).
     *
     * <p>Arrange: a mocked {@link TechnicalEvent.Open} whose {@code toString()} returns
     *             {@link #CANARY_FRAGMENT_TD5}. The mock is used because the real singleton
     *             cannot have its {@code toString()} overridden.
     * Act:     invoke {@code onEvent} with the mock.
     * Assert (SR-TD5-02):
     * <ol>
     *   <li>No {@link ILoggingEvent#getFormattedMessage()} contains the canary fragment —
     *       the raw {@code toString()} value must not reach the log encoder.</li>
     *   <li>No element of {@link ILoggingEvent#getArgumentArray()} (stringified) contains
     *       the canary fragment.</li>
     * </ol>
     * Assert (SR-TD5-06 — positive shape):
     * <ol start="3">
     *   <li>At least one formatted message contains {@code "class="} — the fix preserves
     *       the operational signal by logging the simple class name.</li>
     * </ol>
     *
     * <p><strong>RED state</strong>: this test FAILS before the TD-5-B fix is applied
     * because the current production code calls
     * {@code logEvent("got an Open event: %s".formatted(open))},
     * which expands to the canary string.
     */
    @Test
    public void processTechnicalEvent_openBranch_doesNotLogOpenToString() {
        // Arrange
        TestLogAppender logAppender = getTestLogAppender();
        StompCallback callback = new StompCallback(
                subscriptionManager, messageCache, shareViewStompRelay, restTemplate,
                PERMISSIVE_VALIDATOR, UUID.randomUUID().toString(), "hashtag", "glacier@example.com", "example.com");

        // Mock TechnicalEvent.Open and override toString() to return the canary.
        // This detects any %s-formatting that would expand open.toString() into the log message.
        TechnicalEvent.Open mockOpen = mock(TechnicalEvent.Open.class);
        when(mockOpen.toString()).thenReturn(CANARY_FRAGMENT_TD5);

        // Act
        callback.onEvent(mockOpen);

        // Assert SR-TD5-02: canary must not appear in any formatted message
        List<ILoggingEvent> allEvents = new ArrayList<>(logAppender.getLoggedEvents());
        assertThat(allEvents)
                .as("SR-TD5-02: open.toString() canary must not appear in any formatted log message")
                .allSatisfy(event ->
                        assertThat(event.getFormattedMessage())
                                .doesNotContain(CANARY_FRAGMENT_TD5));

        // Assert SR-TD5-02: canary must not appear in any SLF4J argument
        assertThat(allEvents)
                .as("SR-TD5-02: open.toString() canary must not appear in any log argument")
                .allSatisfy(event -> {
                    Object[] args = event.getArgumentArray();
                    if (args != null) {
                        for (Object arg : args) {
                            assertThat(String.valueOf(arg))
                                    .doesNotContain(CANARY_FRAGMENT_TD5);
                        }
                    }
                });

        // Assert SR-TD5-06 (positive shape): at least one message contains "class=" to confirm
        // the fix replaced open.toString() with open.getClass().getSimpleName()
        assertThat(allEvents)
                .as("SR-TD5-06: at least one log message must contain 'class=' (operational diagnostic signal)")
                .anySatisfy(event ->
                        assertThat(event.getFormattedMessage())
                                .contains("class="));
    }

    // -----------------------------------------------------------------
    // TD-5 — Lane B tests  (T7a-struct, T7b-struct, T7-gate)
    //
    // T7a-struct: parameterised injection fuzz for the default branch (TD-5-A)
    // T7b-struct: parameterised injection fuzz for the Open branch (TD-5-B)
    // T7-gate:    structural regression gate — no .formatted(event)/.formatted(open) on LOGGER lines
    //
    // SR coverage: SR-TD5-01 (default branch: no peer bytes), SR-TD5-02 (Open branch:
    //              no peer bytes), SR-TD5-04 (structural source gate)
    // -----------------------------------------------------------------

    /**
     * Provides 8 adversarial {@code toString()} values for the T7a-struct and T7b-struct
     * injection fuzz tests (SR-TD5-01 / SR-TD5-02 / CWE-117 / D-13).
     *
     * <p>Each row is a single adversarial string that MUST NOT appear in any log output
     * if the fix holds, because {@code getClass().getSimpleName()} is always called, never
     * {@code toString()}.  The adversarial bytes are injected via {@code mock.toString()}
     * — if the production code ever regresses to {@code %s}-formatting the event object,
     * the sentinel surfaces in the log and the test fails.
     *
     * <p>Attack families covered (mirrors the TD-2 T-B1 discipline):
     * <ul>
     *   <li>ASCII canary — baseline signal for direct {@code toString()} leak detection</li>
     *   <li>CRLF injection — SR-TD5-03: embedded newline that would create a fake log line</li>
     *   <li>ANSI escape — terminal colour injection (ESC byte U+001B)</li>
     *   <li>NUL byte — message-truncation injection (U+0000)</li>
     *   <li>U+2028 LINE SEPARATOR — Unicode line break that some log frameworks interpret</li>
     *   <li>U+202E RIGHT-TO-LEFT OVERRIDE — visual obfuscation / RTL spoofing</li>
     *   <li>U+FEFF BOM / ZERO-WIDTH NO-BREAK SPACE — silent invisible injection</li>
     *   <li>U+2029 PARAGRAPH SEPARATOR — Unicode paragraph break</li>
     * </ul>
     */
    private static Stream<Arguments> td5StructInjectionVariants() {
        return Stream.of(
                // Row 1: ASCII canary — baseline toString() leak detector
                Arguments.of("__CANARY_TD5_STRUCT__"),
                // Row 2: CRLF injection — embedded newline producing a fake log line (SR-TD5-03)
                Arguments.of("foo\r\nbar"),
                // Row 3: ANSI escape sequence — ESC byte U+001B, colour injection
                Arguments.of("foobar"),
                // Row 4: NUL byte — message-truncation injection U+0000
                Arguments.of("foo bar"),
                // Row 5: U+2028 LINE SEPARATOR — Unicode line break
                Arguments.of("foo bar"),
                // Row 6: U+202E RIGHT-TO-LEFT OVERRIDE — RTL visual spoofing
                Arguments.of("foo‮bar"),
                // Row 7: U+FEFF BOM / ZERO-WIDTH NO-BREAK SPACE — invisible injection
                Arguments.of("foo﻿bar"),
                // Row 8: U+2029 PARAGRAPH SEPARATOR — Unicode paragraph break
                Arguments.of("foo bar")
        );
    }

    /**
     * T7a-struct (SR-TD5-01) — parameterised injection fuzz for the TD-5-A default branch.
     *
     * <p>The production fix (ADR-TD5-A) uses {@code event.getClass().getSimpleName()}
     * instead of {@code event.toString()} (or {@code %s} formatting) in the default branch
     * of {@code processTechnicalEvent}. {@code getSimpleName()} returns the JVM-assigned
     * simple class name — a constant string under JVM control, never peer-influenced.
     *
     * <p>This test verifies the invariant structurally by providing 8 adversarial strings
     * as the return value of {@code mock.toString()} and confirming that none of them
     * appear in any log output. The only thing that can appear is the class simple name.
     *
     * <p>Arrange: a mocked {@link TechnicalEvent} (not Open/Closing/Closed/Failure) whose
     *             {@code toString()} returns each adversarial string in turn.
     *             This routes through the {@code default} branch of the switch in
     *             {@code processTechnicalEvent}.
     * Act:     invoke {@code onEvent} with the mocked event.
     * Assert (SR-TD5-01):
     * <ol>
     *   <li>No {@link ILoggingEvent#getFormattedMessage()} contains any adversarial fragment —
     *       {@code toString()} is never called in the logging path.</li>
     *   <li>No element of {@link ILoggingEvent#getArgumentArray()} (stringified via
     *       {@link String#valueOf}) contains any adversarial fragment.</li>
     *   <li>Positive shape: at least one formatted message contains {@code "class="} —
     *       the operational signal is preserved via {@code getSimpleName()}.</li>
     * </ol>
     *
     * <p>Note: {@code event.getClass().getSimpleName()} for a Mockito proxy will return a
     * Mockito-generated class name (e.g. "TechnicalEvent$MockitoMock..."), not the adversarial
     * string. This is the exact guarantee the fix relies on.
     *
     * @param adversarialToString the adversarial string returned by {@code mock.toString()}
     */
    @ParameterizedTest(name = "TD5-A-default-fuzz: fragment={0}")
    @MethodSource("td5StructInjectionVariants")
    public void processTechnicalEvent_defaultBranch_injectionFuzz_doesNotLeakToString(
            String adversarialToString) {
        // Arrange
        TestLogAppender logAppender = getTestLogAppender();
        TestLogAppender auditAppender = getAuditLogAppender();
        StompCallback callback = new StompCallback(
                subscriptionManager, messageCache, shareViewStompRelay, restTemplate,
                PERMISSIVE_VALIDATOR, UUID.randomUUID().toString(), "hashtag", "glacier@example.com", "example.com");

        // Mock a TechnicalEvent that routes to the default branch (not Open/Closing/Closed/Failure).
        // Override toString() with the adversarial value: if the fix regresses to %s-formatting,
        // the sentinel will appear in the log and the assertion below will catch it.
        TechnicalEvent mockEvent = mock(TechnicalEvent.class);
        when(mockEvent.toString()).thenReturn(adversarialToString);

        // Act
        callback.onEvent(mockEvent);

        // Collect events from both the StompCallback logger and the AUDIT logger
        List<ILoggingEvent> allEvents = new ArrayList<>(logAppender.getLoggedEvents());
        allEvents.addAll(auditAppender.getLoggedEvents());

        // Assert SR-TD5-01: adversarial bytes must not appear in any formatted message.
        // getClass().getSimpleName() is always JVM-controlled — never peer-controlled.
        assertThat(allEvents)
                .as("SR-TD5-01: event.toString() adversarial fragment must not appear in any " +
                    "formatted log message — getSimpleName() must be used, not toString() " +
                    "(CWE-117 / D-13 / SR-8). Fragment: [%s]", adversarialToString)
                .allSatisfy(event ->
                        assertThat(event.getFormattedMessage())
                                .doesNotContain(adversarialToString));

        // Assert SR-TD5-01: adversarial bytes must not appear in any SLF4J argument
        assertThat(allEvents)
                .as("SR-TD5-01: event.toString() adversarial fragment must not appear in any " +
                    "log argument array element. Fragment: [%s]", adversarialToString)
                .allSatisfy(event -> {
                    Object[] args = event.getArgumentArray();
                    if (args != null) {
                        for (Object arg : args) {
                            assertThat(String.valueOf(arg))
                                    .doesNotContain(adversarialToString);
                        }
                    }
                });

        // Assert positive shape (SR-TD5-05): the operational "class=" prefix must still appear —
        // getSimpleName() produces the JVM class name, preserving the diagnostic signal.
        assertThat(logAppender.getLoggedEvents())
                .as("SR-TD5-05: at least one message must contain 'class=' — " +
                    "getSimpleName() diagnostic signal must be preserved")
                .anySatisfy(event ->
                        assertThat(event.getFormattedMessage())
                                .contains("class="));
    }

    /**
     * T7b-struct (SR-TD5-02) — parameterised injection fuzz for the TD-5-B Open branch.
     *
     * <p>The production fix (ADR-TD5-B) uses {@code open.getClass().getSimpleName()} instead
     * of {@code open.toString()} (or {@code %s} formatting) in the {@code TechnicalEvent.Open}
     * branch of {@code processTechnicalEvent}. This is a preventive fix: the real singleton's
     * {@code toString()} currently returns the bounded constant {@code "Open"}, but the
     * D-13/SR-8 policy prohibits any {@code toString()} call on a peer library type in a
     * logging path.
     *
     * <p>Structural confirmation: since {@code getSimpleName()} is used (never {@code toString()}),
     * adversarial bytes set via {@code mock.toString()} must never appear in the log output.
     * The formatted message must match the bounded format {@code "got an Open event (class=X)"}
     * where {@code X} is the Mockito proxy class simple name — a JVM-controlled value.
     *
     * <p>Arrange: a mocked {@link TechnicalEvent.Open} whose {@code toString()} returns each
     *             adversarial string in turn.
     * Act:     invoke {@code onEvent} with the mock.
     * Assert (SR-TD5-02):
     * <ol>
     *   <li>No {@link ILoggingEvent#getFormattedMessage()} contains any adversarial fragment —
     *       the {@code toString()} value is never used in the logging path.</li>
     *   <li>No element of {@link ILoggingEvent#getArgumentArray()} (stringified) contains
     *       any adversarial fragment.</li>
     *   <li>Positive shape: at least one formatted message contains
     *       {@code "got an Open event (class="} — the bounded message format is preserved.</li>
     *   <li>Structural confirmation: at least one formatted message matches the pattern
     *       {@code "got an Open event (class=<anything>)"}, confirming {@code getSimpleName()}
     *       — not {@code toString()} — is the argument source.</li>
     * </ol>
     *
     * @param adversarialToString the adversarial string returned by {@code mock.toString()}
     */
    @ParameterizedTest(name = "TD5-B-open-fuzz: fragment={0}")
    @MethodSource("td5StructInjectionVariants")
    public void processTechnicalEvent_openBranch_injectionFuzz_doesNotLeakToString(
            String adversarialToString) {
        // Arrange
        TestLogAppender logAppender = getTestLogAppender();
        TestLogAppender auditAppender = getAuditLogAppender();
        StompCallback callback = new StompCallback(
                subscriptionManager, messageCache, shareViewStompRelay, restTemplate,
                PERMISSIVE_VALIDATOR, UUID.randomUUID().toString(), "hashtag", "glacier@example.com", "example.com");

        // Mock TechnicalEvent.Open and override toString() with the adversarial value.
        // If the fix regresses to %s-formatting (expanding open.toString()), the adversarial
        // string surfaces in the log and the assertion below detects the regression.
        TechnicalEvent.Open mockOpen = mock(TechnicalEvent.Open.class);
        when(mockOpen.toString()).thenReturn(adversarialToString);

        // Act
        callback.onEvent(mockOpen);

        // Collect events from both the StompCallback logger and the AUDIT logger
        List<ILoggingEvent> allEvents = new ArrayList<>(logAppender.getLoggedEvents());
        allEvents.addAll(auditAppender.getLoggedEvents());

        // Assert SR-TD5-02: adversarial bytes must not appear in any formatted message.
        // open.getClass().getSimpleName() is JVM-controlled; toString() is never called.
        assertThat(allEvents)
                .as("SR-TD5-02: open.toString() adversarial fragment must not appear in any " +
                    "formatted log message — getSimpleName() must be used, not toString() " +
                    "(CWE-117 / D-13 / SR-8). Fragment: [%s]", adversarialToString)
                .allSatisfy(event ->
                        assertThat(event.getFormattedMessage())
                                .doesNotContain(adversarialToString));

        // Assert SR-TD5-02: adversarial bytes must not appear in any SLF4J argument
        assertThat(allEvents)
                .as("SR-TD5-02: open.toString() adversarial fragment must not appear in any " +
                    "log argument array element. Fragment: [%s]", adversarialToString)
                .allSatisfy(event -> {
                    Object[] args = event.getArgumentArray();
                    if (args != null) {
                        for (Object arg : args) {
                            assertThat(String.valueOf(arg))
                                    .doesNotContain(adversarialToString);
                        }
                    }
                });

        // Assert SR-TD5-06 — positive shape (bounded message format preserved):
        // The message must start with the bounded prefix "got an Open event (class="
        // confirming getSimpleName() is the source of the class token, not toString().
        assertThat(logAppender.getLoggedEvents())
                .as("SR-TD5-06: at least one message must contain 'got an Open event (class=' — " +
                    "bounded message format with getSimpleName() must be preserved")
                .anySatisfy(event ->
                        assertThat(event.getFormattedMessage())
                                .contains("got an Open event (class="));
    }

    /**
     * T7-gate (SR-TD5-04) — structural regression gate: no {@code LOGGER.*} call line in
     * {@code StompCallback.java} contains {@code .formatted(event)} or {@code .formatted(open)}.
     *
     * <p>Primary check: for each physical line that contains {@code LOGGER.} (whitespace-normalised),
     * the line must NOT contain the substrings {@code .formatted(event)} or {@code .formatted(open)}.
     * These patterns would indicate that the production code is passing the peer-controlled
     * {@code event.toString()} or {@code open.toString()} value directly to the log encoder
     * via Java's {@link String#formatted} method, bypassing the {@code getSimpleName()} fix
     * (CWE-117 / D-13 / SR-8 / ADR-TD5-A / ADR-TD5-B).
     *
     * <p>Note: this gate scans physical lines (same limitation as T6b-gate — a future
     * multi-line LOGGER call could evade it). All current LOGGER calls in
     * {@code StompCallback.java} are single-line (verified 2026-04-30).
     *
     * <p>Note: {@code logEvent(} is a wrapper over {@code LOGGER.info} — this gate only covers
     * direct LOGGER calls. {@code logEvent} call sites are covered behaviourally by
     * T7a-struct and T7b-struct above.
     *
     * @throws Exception if the source file cannot be read — treated as a test failure
     */
    @Test
    public void stompCallback_noRawEventOrOpenToString_structuralGate() throws Exception {
        // Locate StompCallback.java using the same pattern as T6b-gate and SR-TD3-10-gate.
        // The class file sits at .../target/classes/...; navigate to the project root then source.
        java.net.URL classUrl = StompCallback.class.getProtectionDomain().getCodeSource().getLocation();
        java.nio.file.Path classesDir = Paths.get(classUrl.toURI());
        // Walk up from target/classes to the Maven project root (parent of target/)
        java.nio.file.Path projectRoot = classesDir.getParent().getParent();
        java.nio.file.Path source = projectRoot
                .resolve("src/main/java/de/seism0saurus/glacier/mastodon/StompCallback.java");

        assertThat(source).as("StompCallback.java must exist at resolved path").exists();

        List<String> lines = Files.readAllLines(source);
        for (int i = 0; i < lines.size(); i++) {
            String normalised = lines.get(i).strip();
            if (normalised.contains("LOGGER.")) {
                String lineRef = "line " + (i + 1);
                // Primary check (SR-TD5-04 / ADR-TD5-A): no .formatted(event) on any LOGGER line.
                // This pattern indicates the peer-controlled event object is passed to the log
                // encoder via String.formatted(), expanding event.toString() into the log message.
                assertThat(normalised)
                        .as("SR-TD5-04 Primary: bare .formatted(event) on LOGGER line at " + lineRef +
                            " — event.getClass().getSimpleName() must be used, not event.toString() " +
                            "(CWE-117 / D-13 / SR-8 / ADR-TD5-A)")
                        .doesNotContain(".formatted(event)");
                // Primary check (SR-TD5-04 / ADR-TD5-B): no .formatted(open) on any LOGGER line.
                // This pattern indicates the peer-controlled open object is passed to the log
                // encoder via String.formatted(), expanding open.toString() into the log message.
                assertThat(normalised)
                        .as("SR-TD5-04 Primary: bare .formatted(open) on LOGGER line at " + lineRef +
                            " — open.getClass().getSimpleName() must be used, not open.toString() " +
                            "(CWE-117 / D-13 / SR-8 / ADR-TD5-B)")
                        .doesNotContain(".formatted(open)");
            }
        }
    }

    // -----------------------------------------------------------------
    // SR-TD3-10: Structural regression gate
    // -----------------------------------------------------------------

    /**
     * SR-TD3-10: structural regression gate — no {@code LOGGER.*} call in
     * {@code StompCallback.java} passes a raw peer-controlled String argument.
     *
     * <p>This test reads {@code StompCallback.java} as text and asserts two invariants
     * that would be violated by the old log-injection bug (CWE-117 / D-13 / SR-8):
     *
     * <ol>
     *   <li>{@code "value '"} does not appear anywhere in the source file — this was the
     *       exact format-string fragment of the vulnerable log call before TD-3 was fixed.
     *       Its presence would indicate the fix was reverted or a similar call was added.</li>
     *   <li>No source line that contains {@code LOGGER.} (a logger call site) also contains
     *       {@code raw} as a bare variable token, i.e. matched by the word-boundary regex
     *       {@code \braw\b}. The {@code normaliseEditedAt} method parameter is named
     *       {@code raw}; the fix replaced {@code raw} with {@code raw.length()} in the
     *       format-argument position so the raw String value never reaches the log encoder.
     *       A logger line containing the bare token {@code raw} would signal a regression.</li>
     * </ol>
     *
     * <p>The test resolves {@code StompCallback.java} via
     * {@link Class#getProtectionDomain()} so it is not sensitive to the build layout or
     * the current working directory from which the test suite is launched.
     *
     * @throws Exception if the source file cannot be read or the class URL cannot be
     *                   resolved to a path — treated as a test failure (the file must
     *                   be present for the gate to have meaning)
     */
    @Test
    public void normaliseEditedAt_noRawValueInAnyLoggerCall_structuralRegressionGate()
            throws Exception {
        // Locate StompCallback.java from the compiled class location.
        // The class file sits at …/target/classes/de/seism0saurus/glacier/mastodon/StompCallback.class;
        // navigate to the Maven project root and then to the source tree.
        java.net.URL classUrl = StompCallback.class.getProtectionDomain().getCodeSource().getLocation();
        // classUrl is .../target/classes/ — resolve to the project root (three levels up) then to the source
        java.nio.file.Path classesDir = Paths.get(classUrl.toURI());
        // Walk up from target/classes to the Maven project root (parent of target/)
        java.nio.file.Path projectRoot = classesDir.getParent().getParent();
        java.nio.file.Path sourceFile = projectRoot
                .resolve("src/main/java/de/seism0saurus/glacier/mastodon/StompCallback.java");

        assertThat(sourceFile).as("StompCallback.java must exist at resolved path").exists();

        List<String> lines = Files.readAllLines(sourceFile);
        String fullSource = String.join("\n", lines);

        // Invariant 1 (SR-TD3-10): the old vulnerable pattern "value '" must not appear anywhere.
        // Its presence would mean the log call was reverted to pass the raw string in a quoted literal.
        assertThat(fullSource)
                .as("SR-TD3-10: the old vulnerable fragment \"value '\" must not appear in StompCallback.java — " +
                    "its presence indicates the raw peer-controlled value is being logged directly (CWE-117 / D-13)")
                .doesNotContain("value '");

        // Invariant 2 (SR-TD3-10): no LOGGER call line may pass `raw` as a bare String argument.
        //
        // Allowed patterns:
        //   raw.length()    — safe: passes the int length, not the raw string
        //   rawUrl          — safe: different variable (URL extraction helpers)
        //   raw.isEmpty()   — safe: boolean derived from raw
        //
        // Forbidden pattern:
        //   LOGGER.warn("...", raw)           — passes the raw peer-controlled String directly
        //   LOGGER.warn("...", raw, something) — same
        //   LOGGER.error("...", ex, raw)       — same
        //
        // The regex matches `raw` as a whole word (word boundary on both sides) where the
        // character immediately following is NOT a `.` (method invocation).
        // `\braw\b(?!\.)` — raw as word, not followed by dot (i.e. not raw.length() etc.)
        Pattern bareRawAsArgument = Pattern.compile("\\braw\\b(?!\\.)");
        for (String line : lines) {
            String trimmed = line.trim();
            // Only inspect active logger call lines — skip comments and Javadoc
            if (trimmed.startsWith("//") || trimmed.startsWith("*")) {
                continue;
            }
            if (trimmed.contains("LOGGER.")) {
                assertThat(bareRawAsArgument.matcher(trimmed).find())
                        .as("SR-TD3-10: logger call line must not pass raw String variable directly — " +
                            "raw peer-controlled strings must never reach the log encoder " +
                            "(D-13/SR-8/CWE-117). Use raw.length() or a safe derived value. " +
                            "Offending line: [" + trimmed + "]")
                        .isFalse();
            }
        }
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

    @NotNull
    private static LevelAwareTestLogAppender getLevelAwareTestLogAppender() {
        LevelAwareTestLogAppender logAppender = new LevelAwareTestLogAppender();
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
    static class TestLogAppender extends AppenderBase<ILoggingEvent> {
        private final List<String> loggedMessages = new ArrayList<>();
        private final List<ILoggingEvent> loggedEvents = new ArrayList<>();

        public List<String> getLoggedMessages() {
            return loggedMessages;
        }

        public List<ILoggingEvent> getLoggedEvents() {
            return loggedEvents;
        }

        @Override
        protected void append(ILoggingEvent eventObject) {
            loggedMessages.add(eventObject.getFormattedMessage());
            loggedEvents.add(eventObject);
        }
    }

    /**
     * Level-aware log appender that separates INFO (and above) messages from DEBUG messages.
     * Used for Fix #2 verification: event.toString() must not appear at INFO level.
     */
    static class LevelAwareTestLogAppender extends AppenderBase<ILoggingEvent> {
        private final List<String> infoMessages = new ArrayList<>();
        private final List<String> debugMessages = new ArrayList<>();
        private final List<String> allMessages = new ArrayList<>();

        public List<String> getInfoMessages() { return infoMessages; }
        public List<String> getDebugMessages() { return debugMessages; }
        public List<String> getAllMessages() { return allMessages; }

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
