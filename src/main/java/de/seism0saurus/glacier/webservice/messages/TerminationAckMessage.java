package de.seism0saurus.glacier.webservice.messages;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * The TerminationAckMessage class represents a message indicating the termination status of a subscription.
 * It contains the subscription ID and whether the subscription is terminated.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TerminationAckMessage {

    private String subscriptionId;
    private boolean isTerminated;
}
