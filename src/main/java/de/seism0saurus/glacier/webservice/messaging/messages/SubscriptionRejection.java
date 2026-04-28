package de.seism0saurus.glacier.webservice.messaging.messages;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * SubscriptionRejection carries the structured reason why a subscription request
 * was declined by the server.
 *
 * <p>An instance of this class is embedded in the {@code rejection} field of a
 * negative {@link SubscriptionAckMessage} (i.e. one where {@code isSubscribed} is
 * {@code false}). Clients may inspect the {@link RejectionCode} to distinguish
 * between different error conditions and display appropriate feedback.</p>
 *
 * <p>When a subscription succeeds, the {@code rejection} field in the ack message
 * is {@code null}.</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SubscriptionRejection {

    /**
     * The machine-readable reason for the rejection.
     *
     * <p>Never {@code null} when this object is present in a negative ack.</p>
     */
    private RejectionCode code;
}
