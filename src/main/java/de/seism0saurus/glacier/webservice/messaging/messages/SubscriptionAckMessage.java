package de.seism0saurus.glacier.webservice.messaging.messages;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * SubscriptionAckMessage represents a message indicating the subscription status for a hashtag.
 * It contains the hashtag, subscription ID, and whether the subscription is successful.
 *
 * <p>When {@code isSubscribed} is {@code false} due to a server-side constraint, the
 * {@code rejection} field carries a structured {@link SubscriptionRejection} with a
 * {@link RejectionCode} and optional details (D-12).  On success {@code rejection} is
 * {@code null}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SubscriptionAckMessage {

    private String hashtag;
    private String principal;
    private boolean isSubscribed;

    /**
     * Structured rejection information; {@code null} when {@code isSubscribed} is {@code true}.
     */
    private SubscriptionRejection rejection;
}
