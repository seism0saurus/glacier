package de.seism0saurus.glacier.webservice.messages;

import lombok.*;

/**
 * StatusDeletedMessage is a subclass of StatusMessage that represents a status deleted event.
 * It contains the ID of the deleted status.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = false)
public class StatusDeletedMessage extends StatusMessage {

    private String id;
}
