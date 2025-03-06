package de.seism0saurus.glacier.webservice.messaging.messages;

import lombok.*;

/**
 * StatusDeletedMessage represents a message indicating a status deletion event.
 * It is a subclass of the abstract class StatusMessage and provides specific details
 * related to the deleted status.
 * <p>
 * Fields:
 * - `id`: A unique identifier for the deleted status.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = false)
public class StatusDeletedMessage extends StatusMessage {

    private String id;
}
