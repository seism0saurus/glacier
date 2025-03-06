package de.seism0saurus.glacier.webservice.messaging.messages;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * The GenericMessageContent class represents the core structure of a generic message.
 * It is used to encapsulate the data included in a message event, such as the event type,
 * the associated stream(s), and the event-specific payload.
 * <p>
 * This class is commonly used for processing messages within a messaging or event-driven system,
 * where different event types and their associated data are handled.
 * <p>
 * Fields:
 * - `stream`: A list of strings representing the streams associated with the event.
 *   Streams can be used to categorize or contextualize the event.
 * - `event`: A string representing the type or name of the event. It identifies the
 *   nature of the operation or action (e.g., "update", "delete").
 * - `payload`: A JSON-encoded structure containing the details or data specific to the
 *   event. The payload format can vary based on the type of event.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GenericMessageContent {

    private List<String> stream;
    private String event;
    private JsonNode payload;
}
