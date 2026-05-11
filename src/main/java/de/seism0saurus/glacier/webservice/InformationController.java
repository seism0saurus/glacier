package de.seism0saurus.glacier.webservice;

import de.seism0saurus.glacier.GlacierOperatorProperties;
import de.seism0saurus.glacier.mastodon.MastodonShortHandle;
import de.seism0saurus.glacier.webservice.cache.FallbackRateLimiter;
import de.seism0saurus.glacier.webservice.dto.Handle;
import de.seism0saurus.glacier.webservice.dto.InstanceOperator;
import de.seism0saurus.glacier.webservice.dto.WallId;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * InformationController is a REST controller that provides various endpoints
 * for retrieving wall ID cookies, mastodon handle information, and operator details.
 * It also initializes and manages configuration data through injected values.
 *
 * <p>Security hardening (D-09, SR-3, SR-4):
 * <ul>
 *   <li>{@code wallId} cookie emitted with {@code HttpOnly; Secure; SameSite=Lax; Path=/;
 *       Max-Age=2592000} (T-02 XSS theft, T-03 plaintext leakage, T-04 CSRF-on-read)</li>
 *   <li>Flag-less legacy cookies still accepted on read — no forced user logout</li>
 *   <li>Per-IP rate limiting on the UUID-issuance path (120/min, non-blocking Phase 2 fix #1)</li>
 *   <li>{@code Secure} flag controlled by {@code glacier.cookie.secure} (default {@code true});
 *       set to {@code false} only in dev/loopback environments</li>
 * </ul>
 */
@RestController
public class InformationController {

    /**
     * The {@link Logger Logger} for this class.
     * The logger is used for logging as configured for the application.
     *
     * @see "src/main/resources/logback.xml"
     */
    private final static Logger LOGGER = LoggerFactory.getLogger(InformationController.class);

    /** Cookie name used throughout the application for principal identity. */
    static final String WALL_ID_COOKIE_NAME = "wallId";

    /** Max-Age 30 days in seconds (D-09). */
    static final int COOKIE_MAX_AGE_SECONDS = 2_592_000;

    /**
     * The validated Mastodon handle of the bot account.
     *
     * <p>Injected as a validated value object rather than a raw string to ensure
     * the handle is structurally correct before it is served to clients (ADR-P3A-2).
     * {@link #getMastodonHandle()} returns {@link MastodonShortHandle#full()} — the canonical
     * form without a leading {@code @}.
     */
    private final MastodonShortHandle mastodonShortHandle;
    private final String domain;

    /**
     * Validated operator properties (ADR-P3A-3 key migration, ADR-P3A-1 bean ownership).
     * Replaces 8 individual {@code @Value} parameters for the {@code glacier.operator.*} keys.
     */
    private final GlacierOperatorProperties operatorProps;

    /**
     * Whether to set the {@code Secure} flag on the wallId cookie.
     * Defaults to {@code true}; set to {@code false} via {@code COOKIE_SECURE=false} in dev.
     *
     * <p>Remains on {@code @Value} — migration to a typed properties bean is
     * deferred to Bundle B (ADR-P3A-1; SR-P3A-05: partial wiring is forbidden).
     */
    private final boolean cookieSecure;

    /** Rate limiter for the per-IP throttle on UUID issuance (non-blocking Phase 2 fix #1). */
    private final FallbackRateLimiter rateLimiter;

    /**
     * The sole constructor for this class.
     *
     * <p>The {@link MastodonShortHandle} is injected as a Spring bean produced by
     * {@link de.seism0saurus.glacier.mastodon.MastodonHandleFactory} (ADR-P3A-2).
     * Operator properties are injected via {@link GlacierOperatorProperties} (ADR-P3A-3).
     * Cookie-secure remains on {@code @Value} until Bundle B (ADR-P3A-1).
     */
    public InformationController(
            final MastodonShortHandle mastodonShortHandle,
            @Value("${glacier.domain}") final String domain,
            final GlacierOperatorProperties operatorProps,
            @Value("${glacier.cookie.secure:true}") final boolean cookieSecure,
            final FallbackRateLimiter rateLimiter
    ) {
        this.mastodonShortHandle = mastodonShortHandle;
        this.domain = domain;
        this.operatorProps = operatorProps;
        this.cookieSecure = cookieSecure;
        this.rateLimiter = rateLimiter;
    }

    /**
     * Returns or creates the {@code wallId} cookie for this browser session.
     *
     * <p>If a valid cookie exists it is returned without re-issuing (no forced logout).
     * If absent, a new UUID is generated and emitted as a hardened {@link ResponseCookie}
     * with all five required attributes (D-09, SR-3).
     *
     * <p>The response cookie is set via {@code response.addHeader("Set-Cookie", …)} so that
     * the {@link ResponseCookie} attributes ({@code SameSite=Lax}) are preserved — the legacy
     * {@link jakarta.servlet.http.Cookie} API does not support {@code SameSite}.
     *
     * <p>Per-IP rate limiting (120/min) is applied on the UUID-issuance branch only.
     * Existing-cookie read requests are not rate-limited here.
     */
    @GetMapping(value = "/rest/wall-id", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public Object readCookie(
            @CookieValue(value = "wallId", required = false) final String wallId,
            final HttpServletRequest request,
            final HttpServletResponse response) {

        LOGGER.info("Fetching cookie");

        if (wallId != null && !wallId.isBlank()) {
            // Existing valid cookie — return without re-issuing (D-09, no forced logout)
            WallId answer = new WallId();
            answer.setId(wallId);
            return answer;
        }

        // Per-IP throttle on UUID issuance (non-blocking Phase 2 fix #1, SR-4)
        String remoteIp = request.getRemoteAddr();
        FallbackRateLimiter.RateLimitResult rl = rateLimiter.checkIpOnly(remoteIp);
        if (!rl.permitted()) {
            LOGGER.debug("Rate limit exceeded for /rest/wall-id ip-suffix={}", anonymiseIp(remoteIp));
            response.setStatus(429);
            response.setHeader("Retry-After", String.valueOf(rl.retryAfterSeconds()));
            return null; // body is empty for 429
        }

        // Issue new wallId
        String newWallId = generateRandomWallId();

        // D-09 / SR-3: emit cookie with all five security attributes via ResponseCookie
        // (jakarta.servlet.http.Cookie does not support SameSite — use header directly)
        ResponseCookie cookie = ResponseCookie.from(WALL_ID_COOKIE_NAME, newWallId)
                .httpOnly(true)             // T-02: prevents XSS theft
                .secure(cookieSecure)       // T-03: prevents plaintext leakage (configurable for dev)
                .sameSite("Lax")            // T-04: CSRF-on-read mitigation (Strict breaks top-nav)
                .path("/")                  // accessible to all paths
                .maxAge(COOKIE_MAX_AGE_SECONDS) // 30-day lifetime
                .build();

        // Must use addHeader — response.addCookie() does not support SameSite (D-09)
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());

        WallId answer = new WallId();
        answer.setId(newWallId);
        return answer;
    }

    @GetMapping(value = "/rest/mastodon-handle", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public Handle getMastodonHandle() {
        // Returns full() — canonical form without leading @ (ADR-P3A-2)
        LOGGER.debug("Mastodon Handle requested");
        Handle handle = new Handle();
        handle.setName(this.mastodonShortHandle.full());
        return handle;
    }

    @GetMapping(value = "/rest/operator", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public InstanceOperator getInstanceOperator() {
        LOGGER.debug("Instance operator requested");
        InstanceOperator instanceOperator = new InstanceOperator();
        instanceOperator.setDomain(this.domain);
        instanceOperator.setOperatorName(this.operatorProps.getName());
        instanceOperator.setOperatorStreetAndNumber(this.operatorProps.getStreetAndNumber());
        instanceOperator.setOperatorZipcode(this.operatorProps.getZipcode());
        instanceOperator.setOperatorCity(this.operatorProps.getCity());
        instanceOperator.setOperatorCountry(this.operatorProps.getCountry());
        instanceOperator.setOperatorPhone(this.operatorProps.getPhone());
        instanceOperator.setOperatorMail(this.operatorProps.getMail());
        instanceOperator.setOperatorWebsite(this.operatorProps.getWebsite());
        return instanceOperator;
    }

    private String generateRandomWallId() {
        return UUID.randomUUID().toString();
    }

    /** Masks last octet/group for log output (not a security control — full IP used for rate-limit key). */
    private static String anonymiseIp(final String ip) {
        if (ip == null) return "null";
        int lastDot = ip.lastIndexOf('.');
        int lastColon = ip.lastIndexOf(':');
        if (lastDot > 0) return ip.substring(0, lastDot) + ".xxx";
        if (lastColon > 0) return ip.substring(0, lastColon) + ":xxxx";
        return "redacted";
    }
}
