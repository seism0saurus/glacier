package de.seism0saurus.glacier.mastodon;

import de.seism0saurus.glacier.webservice.messaging.messages.StatusCreatedMessage;
import de.seism0saurus.glacier.webservice.messaging.messages.StatusDeletedMessage;
import de.seism0saurus.glacier.webservice.messaging.messages.StatusUpdatedMessage;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestTemplate;
import social.bigbone.MastodonClient;
import social.bigbone.api.entity.Account;
import social.bigbone.api.entity.Status;
import social.bigbone.api.entity.streaming.MastodonApiEvent;
import social.bigbone.api.entity.streaming.ParsedStreamEvent;
import social.bigbone.api.entity.streaming.TechnicalEvent;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * The StompCallbackTest class is used to test the functionality of the StompCallback class.
 */
@SpringBootTest
@ActiveProfiles("test")
public class StompCallbackTest {

    @MockBean
    SubscriptionManager subscriptionManager;

    @MockBean
    social.bigbone.MastodonClient client;

    @MockBean
    SimpMessagingTemplate mockTemplate;

    @MockBean
    RestTemplate restTemplate;

    @Mock
    Status mockStatus;

    /**
     * Tests if the event handler processes a Status Created event correctly
     */
    @Test
    public void testOnEventStatusCreated() {
        // Setup
        UUID uuid = UUID.randomUUID();
        String expectedDestination = "/topic/hashtags/" + uuid + "/creation";
        StatusCreatedMessage expectedMessage = StatusCreatedMessage.builder().id("12345").author("peter.kropotkin@example.com").url("https://mastodon.example.com/1234").build();

        doNothing().when(mockTemplate).convertAndSend(eq(expectedDestination), any(StatusCreatedMessage.class));
        Account account = mock(Account.class);
        when(account.getDisplayName()).thenReturn("peter.kropotkin@example.com");
        when(mockStatus.getId()).thenReturn("12345");
        when(mockStatus.getUrl()).thenReturn("https://mastodon.example.com/1234");
        when(mockStatus.getAccount()).thenReturn(account);

        StompCallback callback = new StompCallback(subscriptionManager, mockTemplate, restTemplate, uuid.toString(), "hashtag", "example.com");
        ParsedStreamEvent.StatusCreated mockEvent = new ParsedStreamEvent.StatusCreated(mockStatus);
        MastodonApiEvent.StreamEvent streamEvent = new MastodonApiEvent.StreamEvent(mockEvent, List.of());

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
    public void testOnEventStatusEdited() {
        // Setup
        UUID uuid = UUID.randomUUID();
        String expectedDestination = "/topic/hashtags/" + uuid + "/modification";
        StatusUpdatedMessage expectedMessage = StatusUpdatedMessage.builder().id("12345").url("Fixed a typo: FHTAGN").build();

        doNothing().when(mockTemplate).convertAndSend(eq(expectedDestination), any(StatusUpdatedMessage.class));
        when(mockStatus.getId()).thenReturn("12345");
        when(mockStatus.getContent()).thenReturn("Fixed a typo: FHTAGN");

        StompCallback callback = new StompCallback(subscriptionManager, mockTemplate, restTemplate, uuid.toString(), "hashtag", "example.com");
        ParsedStreamEvent.StatusEdited mockEvent = new ParsedStreamEvent.StatusEdited(mockStatus);
        MastodonApiEvent.StreamEvent streamEvent = new MastodonApiEvent.StreamEvent(mockEvent, List.of());

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
    public void testOnEventStatusDeleted() {
        // Setup
        String statusId = "statusId";
        UUID uuid = UUID.randomUUID();
        String expectedDestination = "/topic/hashtags/" + uuid + "/deletion";
        StatusDeletedMessage expectedMessage = StatusDeletedMessage.builder().id(statusId).build();

        doNothing().when(mockTemplate).convertAndSend(eq(expectedDestination), any(StatusDeletedMessage.class));

        StompCallback callback = new StompCallback(subscriptionManager, mockTemplate, restTemplate, uuid.toString(), "hashtag", "example.com");
        ParsedStreamEvent.StatusDeleted mockEvent = new ParsedStreamEvent.StatusDeleted(statusId);
        MastodonApiEvent.StreamEvent streamEvent = new MastodonApiEvent.StreamEvent(mockEvent, List.of());

        // Execute
        callback.onEvent(streamEvent);

        // Verify
        Mockito.verify(mockTemplate).convertAndSend(
                eq(expectedDestination),
                eq(expectedMessage)
        );
    }

    /**
     * Tests if the event handler processes a Technical Failure event correctly
     */
    @Test
    public void testOnEventTechnicalFailure() {
        // Setup
        String errorMessage = "Error Message";
        StompCallback callback = new StompCallback(subscriptionManager, mockTemplate, restTemplate, UUID.randomUUID().toString(), "hashtag", "example.com");
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