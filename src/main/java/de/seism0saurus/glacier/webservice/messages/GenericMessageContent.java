package de.seism0saurus.glacier.webservice.messages;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GenericMessageContent {

    private List<String> stream;
    private String event;
    private JsonNode payload;
}
