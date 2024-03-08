package de.seism0saurus.glacier.webservice.storage;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document
public class Subscription {

    @Id
    private String destination;
    private String wallId;
    private String hashtag;
    private String sessionId;
    private String subId;

}
