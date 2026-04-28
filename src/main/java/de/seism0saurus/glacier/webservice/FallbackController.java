package de.seism0saurus.glacier.webservice;

import de.seism0saurus.glacier.webservice.cache.FallbackRateLimiter;
import de.seism0saurus.glacier.webservice.cache.FallbackResponse;
import de.seism0saurus.glacier.webservice.cache.MessageCache;
import de.seism0saurus.glacier.webservice.cache.UnknownSubscriptionException;
import de.seism0saurus.glacier.webservice.messaging.HashtagFormat;
import de.seism0saurus.glacier.webservice.messaging.PrincipalKey;
import de.seism0saurus.glacier.webservice.messaging.PrincipalKind;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import de.seism0saurus.glacier.util.LogScrubber;
import java.util.List;
import java.util.Map;

/**
 * HTTP fallback endpoint for clients whose network blocks STOMP/WebSocket.
 *
 * <p>Clients poll {@code GET /rest/messages?hashtag={tag}&since={seq}} at a 5-second
 * interval while in {@code FALLBACK} transport mode.  The {@code since} cursor is the
 * highest {@code sequence} value the client has seen for this hashtag; omitting it (or
 * passing {@code 0}) requests the full ring buffer.
 *
 * <p>Responses:
 * <ul>
 *   <li>{@code 200 OK}  — new events available; body is a {@link FallbackResponse}</li>
 *   <li>{@code 204 No Content} — cursor is already at the ring head; no body</li>
 *   <li>{@code 400 Bad Request} — the {@code (wallId, hashtag)} tuple is not provisioned,
 *       or input validation failed; body is {@code {"error":"code"}}</li>
 *   <li>{@code 401 Unauthorized} — wallId cookie absent, blank, or too short;
 *       body is {@code {"error":"missing_wallid"}}</li>
 *   <li>{@code 404 Not Found} — the kill-switch
 *       {@code glacier.fallback.enabled=false} is active</li>
 *   <li>{@code 429 Too Many Requests} — rate limit exceeded; body is empty;
 *       {@code Retry-After} header present</li>
 * </ul>
 *
 * <p>Security hardening (D-08, D-09, D-10, SR-1, SR-2, SR-4, SR-7):
 * <ul>
 *   <li>{@code @Validated} enables Bean Validation on method parameters (SR-1)</li>
 *   <li>{@code hashtag} validated against {@link HashtagFormat#PATTERN} — never echoed in errors</li>
 *   <li>{@code since} clamped to {@code [0, Long.MAX_VALUE/2]}</li>
 *   <li>Authentication delegated to {@link CookieBasedFallbackAuthGuard} (SR-2)</li>
 *   <li>Rate limiting applied before cache access (SR-4)</li>
 *   <li>Validation failures logged at DEBUG with hashed wallId-8 prefix, never raw cookie</li>
 *   <li>Kill switch: {@code glacier.fallback.enabled=false} → 404 (SR-7, D-11)</li>
 * </ul>
 */
@RestController
@Validated
public class FallbackController {

    /**
     * The {@link Logger} for this class.
     *
     * @see "src/main/ressources/logback.xml"
     */
    private static final Logger LOGGER = LoggerFactory.getLogger(FallbackController.class);

    /**
     * Upper bound for the {@code since} cursor to prevent integer-overflow tricks (SR-1).
     * Half of {@code Long.MAX_VALUE} is well beyond any realistic sequence number.
     */
    public static final long SINCE_MAX = Long.MAX_VALUE / 2;

    private final MessageCache messageCache;
    private final FallbackAuthGuard authGuard;
    private final FallbackRateLimiter rateLimiter;
    private final boolean fallbackEnabled;

    /**
     * Constructs the controller with all required collaborators.
     *
     * @param messageCache    the ring-buffer cache service
     * @param authGuard       the authentication guard (cookie-based in production)
     * @param rateLimiter     the two-axis rate limiter
     * @param fallbackEnabled {@code glacier.fallback.enabled} kill-switch (default {@code true})
     */
    public FallbackController(
            final MessageCache messageCache,
            final FallbackAuthGuard authGuard,
            final FallbackRateLimiter rateLimiter,
            @Value("${glacier.fallback.enabled:true}") final boolean fallbackEnabled) {
        this.messageCache = messageCache;
        this.authGuard = authGuard;
        this.rateLimiter = rateLimiter;
        this.fallbackEnabled = fallbackEnabled;
    }

    /**
     * Returns cached events newer than {@code since} for the given {@code hashtag}.
     *
     * <p>Processing order (fail-fast):
     * <ol>
     *   <li>Kill-switch check → 404</li>
     *   <li>Authentication (wallId cookie) → 401 {@code missing_wallid}</li>
     *   <li>Rate limiting (per-wallId, per-IP) → 429 with {@code Retry-After}</li>
     *   <li>Bean Validation of {@code hashtag} and {@code since} → 400 (via
     *       {@link FallbackControllerAdvice})</li>
     *   <li>Cache lookup → 200 / 204 / 400 {@code unknown_subscription}</li>
     * </ol>
     *
     * @param rawWallId the {@code wallId} cookie; {@code null} when absent
     * @param hashtag   the subscribed hashtag to query (validated against {@link HashtagFormat#PATTERN})
     * @param since     cursor from the client's last successful poll; {@code null} → full buffer
     * @param request   the raw servlet request (used for auth guard and remote IP extraction)
     * @return one of the documented response codes
     */
    @GetMapping(value = "/rest/messages", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> getMessages(
            @CookieValue(value = "wallId", required = false) final String rawWallId,
            @RequestParam("hashtag")
            @Pattern(regexp = HashtagFormat.PATTERN, message = "invalid_hashtag")
            final String hashtag,
            @RequestParam(value = "since", required = false)
            @Min(value = 0, message = "invalid_cursor")
            @Max(value = SINCE_MAX, message = "invalid_cursor")
            final Long since,
            final HttpServletRequest request) {

        // SR-7 / D-11: kill switch — checked before any auth/rate-limit processing
        if (!fallbackEnabled) {
            LOGGER.debug("Fallback kill-switch is active; returning 404");
            return ResponseEntity.notFound().build();
        }

        // SR-2 / D-08 / ADR-06: cookie-only authentication
        FallbackAuthGuard.AuthResult auth = authGuard.authenticate(request, rawWallId);
        if (!auth.authenticated()) {
            // Log with hashed wallId prefix only — never raw cookie (D-13, SR-8)
            LOGGER.debug("Auth failed for /rest/messages wallId-hash8={}",
                    LogScrubber.hash8(rawWallId));
            return ResponseEntity.status(401)
                    .body(Map.of("error", "missing_wallid"));
        }

        String principal = auth.principal();
        // ADR-SHARE-05 (revised): wrap raw wallId string in PrincipalKey so FallbackRateLimiter
        // and MessageCache use type-safe keys. The cookie-based auth guard always resolves to a
        // WALL principal (viewer polling goes through ShareViewController, not here).
        PrincipalKey principalKey = new PrincipalKey(PrincipalKind.WALL, principal);

        // SR-4 / D-10: two-axis rate limiting (per-wallId + per-IP)
        String remoteIp = request.getRemoteAddr();
        FallbackRateLimiter.RateLimitResult rl = rateLimiter.check(principalKey, remoteIp);
        if (!rl.permitted()) {
            LOGGER.debug("Rate limit exceeded wallId-hash8={} ip={}",
                    LogScrubber.hash8(principal), LogScrubber.maskIp(remoteIp));
            return ResponseEntity.status(429)
                    .header("Retry-After", String.valueOf(rl.retryAfterSeconds()))
                    .build();
        }

        // At this point hashtag and since have already been validated by Bean Validation
        // (ConstraintViolationException → FallbackControllerAdvice → 400).
        try {
            var snapshot = messageCache.snapshot(principalKey, hashtag, since);

            if (snapshot.events().isEmpty() && !snapshot.gap()) {
                return ResponseEntity.noContent().build();
            }

            List<FallbackResponse.CacheEntryView> views = snapshot.events().stream()
                    .map(FallbackResponse.CacheEntryView::from)
                    .toList();

            FallbackResponse body = new FallbackResponse(hashtag, snapshot.nextSince(), snapshot.gap(), views);
            return ResponseEntity.ok(body);

        } catch (UnknownSubscriptionException ex) {
            // Anti-enumeration (T-07, SR-2): unknown subscription and "not yours" produce the
            // same 400 body — the exception message is never forwarded to the client.
            LOGGER.debug("Unknown subscription wallId-hash8={} hashtag-len={}",
                    LogScrubber.hash8(principal), hashtag.length());
            return ResponseEntity.badRequest().body(Map.of("error", "unknown_subscription"));
        }
    }

}
