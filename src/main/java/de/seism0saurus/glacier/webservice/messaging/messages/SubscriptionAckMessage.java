package de.seism0saurus.glacier.webservice.messaging.messages;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * SubscriptionAckMessage represents a message indicating the subscription status for a hashtag.
 * It contains the hashtag, subscription ID, and whether the subscription is successful.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SubscriptionAckMessage {

    private String hashtag;
    private String principal;
    private boolean isSubscribed;
}
