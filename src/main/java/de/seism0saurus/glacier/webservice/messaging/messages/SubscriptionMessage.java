package de.seism0saurus.glacier.webservice.messaging.messages;

import de.seism0saurus.glacier.webservice.messaging.HashtagFormat;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

/**
 * SubscriptionMessage represents a message containing a hashtag to be subscribed to.
 *
 * <p>The {@code hashtag} field is validated using Bean Validation constraints.
 * Invalid messages are rejected by {@code SubscriptionController.subscribe()} before
 * any Mastodon streaming subscription is started, preventing injection attacks.</p>
 */
@Data
public class SubscriptionMessage {

    /**
     * The hashtag the client wishes to subscribe to, without the leading {@code #} character.
     *
     * <p>Must be non-blank and conform to {@link HashtagFormat#PATTERN}: one to fifty
     * Unicode letters, digits, or underscores.</p>
     */
    @NotBlank
    @Pattern(regexp = HashtagFormat.PATTERN)
    private String hashtag;
}
