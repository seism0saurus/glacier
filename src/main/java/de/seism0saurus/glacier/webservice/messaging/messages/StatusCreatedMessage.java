package de.seism0saurus.glacier.webservice.messaging.messages;

import lombok.*;

/**
 * StatusCreatedMessage represents a message indicating a new status creation event.
 * It is a subclass of the abstract class StatusMessage and provides specific details
 * for the newly created status.
 * <p>
 * Fields:
 * - `id`: A unique identifier for the created status.
 * - `author`: The author of the created status.
 * - `url`: A URL associated with the created status.
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
