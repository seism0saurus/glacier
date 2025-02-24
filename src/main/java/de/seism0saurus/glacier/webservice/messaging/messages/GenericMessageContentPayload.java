package de.seism0saurus.glacier.webservice.messaging.messages;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * GenericMessageContentPayload represents the payload structure for a generic message content.
 * This class encapsulates the core attributes of the payload, including an identifier, a URL,
 * and a list of mentions.
 *
 * Fields:
 * - `id`: A string representing the unique identifier of the content payload.
 * - `url`: A string containing the URL associated with the payload.
 * - `mentions`: A list of {@link Mention} objects representing the mentions included in the payload.
 *
 * This class is typically used to parse and process the payload section of a generic message event.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class GenericMessageContentPayload {

    private String id;
    private String url;
    private List<Mention> mentions;
}
