package de.seism0saurus.glacier.webservice;

import de.seism0saurus.glacier.mastodon.SubscriptionManager;
import de.seism0saurus.glacier.webservice.messages.SubscriptionAckMessage;
import de.seism0saurus.glacier.webservice.messages.SubscriptionMessage;
import de.seism0saurus.glacier.webservice.messages.TerminationAckMessage;
import de.seism0saurus.glacier.webservice.messages.TerminationMessage;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;

/**
 * The SubscriptionControllerTest class is responsible for testing the SubscriptionController class.
 * It includes test methods for subscribing to a hashtag, unsubscribing with a valid subscriptionId,
 * unsubscribing with an invalid subscriptionId, and unsubscribing without providing a subscriptionId.
 */
@SpringBootTest
@ActiveProfiles("test")
public class SubscriptionControllerTest {

    @Autowired
    private SubscriptionController subscriptionController;

    @MockBean
    private SubscriptionManager subscriptionManager;

    @Test
    public void testSubscribe() {
        // Setup
        SubscriptionMessage subscriptionMessage = new SubscriptionMessage();
        subscriptionMessage.setHashtag("#TestHashtag");
        UUID uuid = UUID.randomUUID();
        when(subscriptionManager.subscribeToHashtag(any(String.class))).thenReturn(uuid);

        // Execute
        SubscriptionAckMessage result = subscriptionController.subscribe(subscriptionMessage);

        // Verify
        assertThat(result).isNotNull();
        assertThat(result.isSubscribed()).isTrue();
        assertThat(result.getSubscriptionId()).isEqualTo(uuid.toString());
        assertThat(result.getHashtag()).isEqualTo(subscriptionMessage.getHashtag());
    }

    /**
     * This method tests the unsubscribe functionality when a valid subscriptionId is provided.
     * It verifies that the TerminationAckMessage returned from the "unsubscribe" method has the correct values.
     * The valid subscriptionId is set in the TerminationMessage object and passed as a parameter to the "unsubscribe" method.
     * The result is then verified using assertions to ensure that the TerminationAckMessage is not null,
     * the "isTerminated" value is true, and the subscriptionId matches the valid UUID.
     */
    @Test
    public void testUnsubscribe_ValidSubscriptionId() {
        // Setup
        UUID uuid = UUID.randomUUID();
        TerminationMessage terminationMessage = new TerminationMessage();
        terminationMessage.setSubscriptionId(uuid.toString());
        doNothing().when(subscriptionManager).terminateSubscription(uuid);

        // Execute
        TerminationAckMessage result = subscriptionController.unsubscribe(terminationMessage);

        // Verify
        assertThat(result).isNotNull();
        assertThat(result.isTerminated()).isTrue();
        assertThat(result.getSubscriptionId()).isEqualTo(uuid.toString());
    }

    /**
     * This method tests the unsubscribe functionality when an invalid subscriptionId is provided.
     * It verifies that the TerminationAckMessage returned from the "unsubscribe" method has the correct values.
     * The invalid subscriptionId is set in the TerminationMessage object and passed as a parameter to the "unsubscribe" method.
     * The result is then verified using assertions to ensure that the TerminationAckMessage is not null,
     * the "isTerminated" value is false, and the subscriptionId matches the invalidId.
     */
    @Test
    public void testUnsubscribe_InvalidSubscriptionId(){
        // Setup
        TerminationMessage terminationMessage = new TerminationMessage();
        String invalidId = "Ph’nglui mglw’nafh Cthulhu R’lyeh wgah’nagl fhtagn.";
        terminationMessage.setSubscriptionId(invalidId);

        // Execute
        TerminationAckMessage result = subscriptionController.unsubscribe(terminationMessage);

        // Verify
        assertThat(result).isNotNull();
        assertThat(result.isTerminated()).isFalse();
        assertThat(result.getSubscriptionId()).isEqualTo(invalidId);
    }

    /**
     * This method tests the unsubscribe functionality when no subscriptionId is provided.
     * It verifies that the TerminationAckMessage returned from the "unsubscribe" method has the correct values.
     * The result is then verified using assertions to ensure that the TerminationAckMessage is not null,
     * the "isTerminated" value is false, and the subscriptionId is null.
     */
    @Test
    public void testUnsubscribe_NoSubscriptionId(){
        // Setup
        TerminationMessage terminationMessage = new TerminationMessage();

        // Execute
        TerminationAckMessage result = subscriptionController.unsubscribe(terminationMessage);

        // Verify
        assertThat(result).isNotNull();
        assertThat(result.isTerminated()).isFalse();
        assertThat(result.getSubscriptionId()).isEqualTo(null);
    }
}