package de.seism0saurus.glacier.webservice.messaging.messages;

import lombok.Data;

/**
 * The TerminationMessage class represents a message containing the subscriptionId to unsubscribe from.
 */
@Data
public class TerminationMessage {

    private String hashtag;
}
