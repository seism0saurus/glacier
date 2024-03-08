package de.seism0saurus.glacier.webservice;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletResponse;
import lombok.Data;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
public class InformationController {

    /**
     * The {@link Logger Logger} for this class.
     * The logger is used for logging as configured for the application.
     *
     * @see "src/main/ressources/logback.xml"
     */
    private final static Logger LOGGER = LoggerFactory.getLogger(InformationController.class);

    /**
     * The mastodon handle of the account we use to connect to the fediverse.
     * It can be used to opt-out of glacier through a block.
     */
    private final String mastodonHandle;

    /**
     * The sole constructor for this class.
     * The needed variables are injected as {@link Value Value} by Spring.
     */
    public InformationController(@Value("${mastodon.handle}") final String mastodonHandle) {
        this.mastodonHandle = mastodonHandle;
        LOGGER.debug("Information Controller instantiated " + this.mastodonHandle);
    }

    @GetMapping("/rest/wall-id")
    public WallId readCookie(@CookieValue(value = "wallId", required = false) String wallId, HttpServletResponse response) {
        LOGGER.info("Fetching cookie");
        if (wallId == null){
            String newWallId = generateRandomWallId();
            Cookie cookie = new Cookie("wallId", newWallId);
            cookie.setPath("/");
            cookie.setMaxAge(2592000); // 30 days
            response.addCookie(cookie);
            WallId answer = new WallId();
            answer.setId(newWallId);
            return answer;
        }
        WallId answer = new WallId();
        answer.setId(wallId);
        return answer;
    }

    @GetMapping("/rest/mastodon-handle")
    public Handle getMastodonHandle() {
        LOGGER.debug("Mastodon Handle requested. Sending " + this.mastodonHandle);
        Handle handle = new Handle();
        handle.setName(this.mastodonHandle);
        return handle;
    }

    @Data
    static class Handle{
        private String name;
    }

    @Data
    static class WallId{
        private String id;
    }

    private String generateRandomWallId() {
        return UUID.randomUUID().toString();
    }
}

