package de.seism0saurus.glacier.webservice.messaging.messages;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * SubscriptionAckMessage represents a message indicating the subscription status for a hashtag.
 *
 * <p>When {@code isSubscribed} is {@code true}, the {@code rejection} field is {@code null}.
 * When {@code isSubscribed} is {@code false}, the optional {@code rejection} field carries
 * a machine-readable {@link SubscriptionRejection} explaining why the request was declined.
 * The absence of a {@code rejection} value on a negative ack indicates an unclassified error
 * (e.g. missing principal).</p>
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
     * Structured rejection details, present only when {@code isSubscribed} is {@code false}
     * and the failure reason is known.
     *
     * <p>May be {@code null} for legacy negative ack paths (e.g. missing principal)
     * that predate structured rejection support.</p>
     */
    private SubscriptionRejection rejection;
}
