package de.seism0saurus.glacier.webservice.messages;

import lombok.Data;

/**
 * SubscriptionMessage represents a message containing a hashtag to be subscribed to.
 */
@Data
public class SubscriptionMessage {

    private String hashtag;
}
