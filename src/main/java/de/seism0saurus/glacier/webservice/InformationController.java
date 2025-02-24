package de.seism0saurus.glacier.webservice;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletResponse;
import lombok.Data;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * InformationController is a REST controller that provides various endpoints
 * for retrieving wall ID cookies, mastodon handle information, and operator details.
 * It also initializes and manages configuration data through injected values.
 */
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
    private final String domain;
    private final String operatorName;
    private final String operatorStreetAndNumber;
    private final String operatorZipcode;
    private final String operatorCity;
    private final String operatorCountry;
    private final String operatorPhone;
    private final String operatorMail;
    private final String operatorWebsite;

    /**
     * The sole constructor for this class.
     * The needed variables are injected as {@link Value Value} by Spring.
     */
    public InformationController(
            @Value("${mastodon.handle}") final String mastodonHandle,
            @Value("${glacier.domain}") final String domain,
            @Value("${glacier.operatorName}") final String operatorName,
            @Value("${glacier.operatorStreetAndNumber}") final String operatorStreetAndNumber,
            @Value("${glacier.operatorZipcode}") final String operatorZipcode,
            @Value("${glacier.operatorCity}") final String operatorCity,
            @Value("${glacier.operatorCountry}") final String operatorCountry,
            @Value("${glacier.operatorPhone}") final String operatorPhone,
            @Value("${glacier.operatorMail}") final String operatorMail,
            @Value("${glacier.operatorWebsite}") final String operatorWebsite
    ) {
        this.mastodonHandle = mastodonHandle;
        this.domain = domain;
        this.operatorName = operatorName;
        this.operatorStreetAndNumber = operatorStreetAndNumber;
        this.operatorZipcode = operatorZipcode;
        this.operatorCity = operatorCity;
        this.operatorCountry = operatorCountry;
        this.operatorPhone = operatorPhone;
        this.operatorMail = operatorMail;
        this.operatorWebsite = operatorWebsite;
    }

    @GetMapping(value="/rest/wall-id", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public WallId readCookie(@CookieValue(value = "wallId", required = false) String wallId, HttpServletResponse response) {
        LOGGER.info("Fetching cookie");
        if (wallId == null) {
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

    @GetMapping(value="/rest/mastodon-handle", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public Handle getMastodonHandle() {
        LOGGER.debug("Mastodon Handle requested. Sending " + this.mastodonHandle);
        Handle handle = new Handle();
        handle.setName(this.mastodonHandle);
        return handle;
    }

    @GetMapping(value="/rest/operator", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public InstanceOperator getInstanceOperator() {
        LOGGER.debug("Instance operator requested. Sending " + this.mastodonHandle);
        InstanceOperator instanceOperator = new InstanceOperator();
        instanceOperator.setDomain(this.domain);
        instanceOperator.setOperatorName(this.operatorName);
        instanceOperator.setOperatorStreetAndNumber(this.operatorStreetAndNumber);
        instanceOperator.setOperatorZipcode(this.operatorZipcode);
        instanceOperator.setOperatorCity(this.operatorCity);
        instanceOperator.setOperatorCountry(this.operatorCountry);
        instanceOperator.setOperatorPhone(this.operatorPhone);
        instanceOperator.setOperatorMail(this.operatorMail);
        instanceOperator.setOperatorWebsite(this.operatorWebsite);
        return instanceOperator;
    }

    @Data
    static class Handle {
        private String name;
    }

    @Data
    static class InstanceOperator {
        private String domain;
        private String operatorName;
        private String operatorStreetAndNumber;
        private String operatorZipcode;
        private String operatorCity;
        private String operatorCountry;
        private String operatorPhone;
        private String operatorMail;
        private String operatorWebsite;
    }

    @Data
    static class WallId {
        private String id;
    }

    private String generateRandomWallId() {
        return UUID.randomUUID().toString();
    }
}

