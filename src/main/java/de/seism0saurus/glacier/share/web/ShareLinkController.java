package de.seism0saurus.glacier.share.web;

import de.seism0saurus.glacier.share.application.CapacityExceededException;
import de.seism0saurus.glacier.share.application.ShareLinkNotFoundOrNotAuthorisedException;
import de.seism0saurus.glacier.share.application.ShareLinkService;
import de.seism0saurus.glacier.share.domain.ShareLink;
import de.seism0saurus.glacier.share.domain.ShareLinkSummary;
import de.seism0saurus.glacier.util.LogScrubber;
import de.seism0saurus.glacier.webservice.FallbackAuthGuard;
import de.seism0saurus.glacier.webservice.cache.FallbackRateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * REST controller for share-link CRUD operations (sharer-facing).
 *
 * <p>Endpoints:
 * <ul>
 *   <li>{@code POST /rest/share-links} — creates a new share link; returns 201 with body
 *       {@link CreateShareLinkResponse}.  Rate-limited per wallId + per IP.
 *       Per-sharer and per-IP caps enforced by {@link ShareLinkService#create}.</li>
 *   <li>{@code DELETE /rest/share-links/{id}} — revokes a share link; returns 204 on success
 *       or 404 for both not-found and not-authorised (anti-enumeration, T-07).</li>
 *   <li>{@code GET /rest/share-links} — lists all ACTIVE links for the authenticated
 *       sharer; returns 200 with a JSON array of {@link ShareLinkListEntry}.</li>
 * </ul>
 *
 * <p>All endpoints require a valid {@code wallId} cookie (authenticated by the injected
 * {@link FallbackAuthGuard}).  The {@code sharerWallId} is NEVER included in any response
 * body — only the opaque {@code shareLinkId} is returned.
 *
 * <p>Security controls (ADR-SHARE-01, D-08, D-10, SR-2, SR-4):
 * <ul>
 *   <li>Authentication: {@link FallbackAuthGuard} (cookie-based, same as fallback endpoint).</li>
 *   <li>Rate limiting: two axes on POST — per-wallId ({@code share.create.perMinutePerWallId=5})
 *       and per-IP ({@code share.create.perMinutePerIp=20}) via {@link FallbackRateLimiter}.</li>
 *   <li>Anti-enumeration: DELETE returns 404 for both "not found" and "not authorised".</li>
 *   <li>No raw IDs or wallIds in log messages — only hash8 fingerprints (D-13, SR-8).</li>
 * </ul>
 */
@RestController
@RequestMapping("/rest/share-links")
public class ShareLinkController {

    private static final Logger LOGGER = LoggerFactory.getLogger(ShareLinkController.class);
    private static final Logger AUDIT = LoggerFactory.getLogger("AUDIT");

    /** Minimum length for a wallId to be treated as valid. */
    private static final int MIN_WALL_ID_LENGTH = 32;

    private final ShareLinkService shareLinkService;
    private final FallbackAuthGuard authGuard;
    private final FallbackRateLimiter rateLimiter;
    private final ShareCsrfGuard csrfGuard;
    private final ShareRateLimiter shareRateLimiter;
    private final String shareHost;
    private final Clock clock;

    /**
     * Constructs the controller with all required collaborators.
     *
     * @param shareLinkService service for share-link lifecycle management
     * @param authGuard        cookie-based authentication guard (cookie-based in production)
     * @param rateLimiter      general two-axis rate limiter (kept for backward compat)
     * @param csrfGuard        CSRF guard for state-changing operations (SR-SHARE-05)
     * @param shareRateLimiter share-specific six-axis rate limiter (SR-SHARE-12)
     * @param shareHost        the dedicated share host for constructing readonly URLs
     *                         ({@code glacier.share.host}). The readonly link MUST live on
     *                         the share host — ShareHostRouter serves {@code /share/*} only
     *                         there and 404s it on the main wall host (anti-enumeration).
     * @param clock            injected clock for test determinism
     */
    public ShareLinkController(
            final ShareLinkService shareLinkService,
            final FallbackAuthGuard authGuard,
            final FallbackRateLimiter rateLimiter,
            final ShareCsrfGuard csrfGuard,
            final ShareRateLimiter shareRateLimiter,
            @Value("${glacier.share.host}") final String shareHost,
            final Clock clock) {
        this.shareLinkService = shareLinkService;
        this.authGuard = authGuard;
        this.rateLimiter = rateLimiter;
        this.csrfGuard = csrfGuard;
        this.shareRateLimiter = shareRateLimiter;
        this.shareHost = shareHost;
        this.clock = clock;
    }

    // -------------------------------------------------------------------------
    // POST /rest/share-links — create
    // -------------------------------------------------------------------------

    /**
     * Creates a new share link for the authenticated sharer.
     *
     * <p>Processing order:
     * <ol>
     *   <li>Authentication (wallId cookie) → 401 on failure.</li>
     *   <li>Rate limiting (per-wallId + per-IP) → 429 on exhaustion.</li>
     *   <li>Capacity cap (per-sharer + per-IP active-link count) → 429 on excess.</li>
     *   <li>Creation → 201 with {@link CreateShareLinkResponse}.</li>
     * </ol>
     *
     * @param rawWallId the {@code wallId} cookie value; null when absent
     * @param request   the raw servlet request (for IP extraction)
     * @return 201 Created with body, 401, or 429
     */
    @PostMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> createShareLink(
            @CookieValue(value = "wallId", required = false) final String rawWallId,
            final HttpServletRequest request) {

        // Authentication first (SR-SHARE-01: fail before CSRF to avoid leaking info about CSRF state)
        FallbackAuthGuard.AuthResult auth = authGuard.authenticate(request, rawWallId);
        if (!auth.authenticated()) {
            LOGGER.debug("POST /rest/share-links auth failed wallId-hash8={}",
                    LogScrubber.hash8(rawWallId));
            AUDIT.info("share.auth.fail endpoint=create wallId-hash8={}",
                    LogScrubber.hash8(rawWallId));
            return ResponseEntity.status(401).body(Map.of("error", "missing_wallid"));
        }

        String principal = auth.principal();
        String remoteIp = request.getRemoteAddr();

        // CSRF verification (SR-SHARE-05: double-submit cookie pattern)
        // OWASP A01: CSRF tokens must be validated for all state-changing operations
        if (!csrfGuard.verify(request)) {
            AUDIT.info("share.csrf.fail endpoint=create wallId-hash8={}", LogScrubber.hash8(principal));
            return ResponseEntity.status(403).body(Map.of("error", "csrf_validation_failed"));
        }

        // Use share-specific rate limiter (SR-SHARE-12) for share creation
        // This applies the share.create.perMinutePerWallId and share.create.perMinutePerIp axes
        ShareRateLimiter.RateLimitResult rl = shareRateLimiter.checkShareCreate(principal, remoteIp);
        if (!rl.permitted()) {
            LOGGER.debug("POST /rest/share-links rate limited wallId-hash8={} ip={}",
                    LogScrubber.hash8(principal), LogScrubber.maskIp(remoteIp));
            AUDIT.info("share.ratelimit.exceeded axis=create-perWallId principal-hash8={} ip={}",
                    LogScrubber.hash8(principal), LogScrubber.maskIp(remoteIp));
            return ResponseEntity.status(429)
                    .header("Retry-After", String.valueOf(rl.retryAfterSeconds()))
                    .build();
        }

        try {
            Instant now = clock.instant();
            ShareLink link = shareLinkService.create(principal, remoteIp, now);
            CreateShareLinkResponse response = toCreateResponse(link);
            return ResponseEntity.status(201).body(response);
        } catch (CapacityExceededException e) {
            LOGGER.debug("POST /rest/share-links cap exceeded wallId-hash8={}",
                    LogScrubber.hash8(principal));
            return ResponseEntity.status(429).body(Map.of("error", "capacity_exceeded"));
        }
    }

    // -------------------------------------------------------------------------
    // DELETE /rest/share-links/{id} — revoke
    // -------------------------------------------------------------------------

    /**
     * Revokes the share link identified by {@code id}.
     *
     * <p>Anti-enumeration: both "not found" and "not authorised" return
     * {@code 404 Not Found} with the same empty body — preventing callers from
     * determining whether a given ID exists (T-07).
     *
     * <p>The path variable is the non-secret {@code idHash8} (first 8 hex chars of
     * SHA-256(token)), NOT the raw token. The token is shown exactly once at creation and is
     * never re-served (ADR-SQLITE-05); revoking by {@code idHash8} keeps the secret out of
     * request URLs and reverse-proxy access logs, and lets the sharer revoke any of their links
     * from the self-management list (which only ever carries {@code idHash8}).
     *
     * @param rawWallId the {@code wallId} cookie value; null when absent
     * @param idHash8   the link's {@code idHash8} from the URL path
     * @param request   the raw servlet request
     * @return 204 No Content on success; 401 on missing auth; 404 on not-found, not-authorised,
     *         or malformed id (anti-enumeration)
     */
    @DeleteMapping(value = "/{idHash8}")
    public ResponseEntity<?> revokeShareLink(
            @CookieValue(value = "wallId", required = false) final String rawWallId,
            @PathVariable("idHash8") final String idHash8,
            final HttpServletRequest request) {

        FallbackAuthGuard.AuthResult auth = authGuard.authenticate(request, rawWallId);
        if (!auth.authenticated()) {
            LOGGER.debug("DELETE /rest/share-links auth failed");
            return ResponseEntity.status(401).body(Map.of("error", "missing_wallid"));
        }

        String principal = auth.principal();

        // CSRF verification (SR-SHARE-05: double-submit cookie pattern)
        // OWASP A01: CSRF tokens must be validated for all state-changing operations
        if (!csrfGuard.verify(request)) {
            AUDIT.info("share.csrf.fail endpoint=revoke wallId-hash8={}", LogScrubber.hash8(principal));
            return ResponseEntity.status(403).body(Map.of("error", "csrf_validation_failed"));
        }

        try {
            // The service validates the idHash8 format and maps malformed/not-found/not-owned
            // alike to ShareLinkNotFoundOrNotAuthorisedException → a uniform 404 (anti-enumeration).
            shareLinkService.revokeByHash8(idHash8, principal, clock.instant());
            return ResponseEntity.noContent().build();
        } catch (ShareLinkNotFoundOrNotAuthorisedException e) {
            // Anti-enumeration: 404 for malformed, not-found, and not-authorised
            return ResponseEntity.notFound().build();
        }
    }

    // -------------------------------------------------------------------------
    // GET /rest/share-links — list for sharer
    // -------------------------------------------------------------------------

    /**
     * Returns all ACTIVE share links for the authenticated sharer.
     *
     * <p>Used by the sharer's management UI to show which links are currently active,
     * along with their expiry times and QR-code URLs.
     *
     * @param rawWallId the {@code wallId} cookie value; null when absent
     * @param request   the raw servlet request
     * @return 200 OK with a JSON array; 401 when the cookie is absent or invalid
     */
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> listShareLinks(
            @CookieValue(value = "wallId", required = false) final String rawWallId,
            final HttpServletRequest request) {

        FallbackAuthGuard.AuthResult auth = authGuard.authenticate(request, rawWallId);
        if (!auth.authenticated()) {
            LOGGER.debug("GET /rest/share-links auth failed");
            return ResponseEntity.status(401).body(Map.of("error", "missing_wallid"));
        }

        String principal = auth.principal();
        Instant now = clock.instant();
        List<ShareLinkListEntry> entries = shareLinkService.listSummaryBySharer(principal, now)
                .stream()
                .map(this::toListEntry)
                .toList();

        return ResponseEntity.ok(entries);
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private CreateShareLinkResponse toCreateResponse(final ShareLink link) {
        return new CreateShareLinkResponse(
                link.id().value(),
                link.id().hash8(),
                link.expiresAt(),
                buildReadonlyUrl(link.id().value()));
    }

    private ShareLinkListEntry toListEntry(final ShareLinkSummary summary) {
        return new ShareLinkListEntry(
                summary.idHash8(),
                summary.createdAt(),
                summary.expiresAt(),
                summary.status());
    }

    private String buildReadonlyUrl(final String shareLinkId) {
        // Readonly links live on the share host (glacier.share.host), NOT the main wall
        // host: ShareHostRouter serves /share/* only on the share host and returns 404 on
        // the main host. Building this on glacier.domain would yield a dead (404) link.
        return "https://" + shareHost + "/share/" + shareLinkId;
    }
}
