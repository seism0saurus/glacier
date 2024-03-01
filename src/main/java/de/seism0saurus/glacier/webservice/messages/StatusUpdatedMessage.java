package de.seism0saurus.glacier.webservice.messages;

import lombok.*;

/**
 * The StatusUpdatedMessage class represents a status updated event message.
 * It extends the abstract class StatusMessage, which serves as a base class for other types of status messages.
 * <p>
 * This class contains the ID and text of the updated status.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = false)
public class StatusUpdatedMessage extends StatusMessage {

    private String id;
    private String text;
}
