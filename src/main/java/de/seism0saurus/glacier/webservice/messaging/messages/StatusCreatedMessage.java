package de.seism0saurus.glacier.webservice.messaging.messages;

import lombok.*;

/**
 * The StatusCreatedMessage class represents a status created event message.
 * It extends the abstract class StatusMessage, which serves as a base class for other types of status messages.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = false)
public class StatusCreatedMessage extends StatusMessage {

    private String id;
    private String author;
    private String url;
}
