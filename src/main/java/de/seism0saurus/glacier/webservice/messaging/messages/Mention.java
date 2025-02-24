package de.seism0saurus.glacier.webservice.messaging.messages;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * The Mention class represents a user mention in a communication or messaging context.
 * It encapsulates the essential details about the mentioned entity, including an identifier,
 * username, and account information.
 *
 * Fields:
 * - `id`: A unique identifier for the mentioned user or entity.
 * - `username`: The username of the mentioned user.
 * - `acct`: The account identifier or address associated with the mention.
 *
 * This class is typically included in payloads or messages to identify referenced users.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class Mention {

    private String id;
    private String username;
    private String acct;
}
