package de.seism0saurus.glacier.share.web;

import de.seism0saurus.glacier.share.application.CapacityExceededException;
import de.seism0saurus.glacier.share.application.ShareLinkNotFoundOrNotAuthorisedException;
import de.seism0saurus.glacier.share.application.ShareLinkService;
import de.seism0saurus.glacier.share.domain.ShareLink;
import de.seism0saurus.glacier.share.domain.ShareLinkId;
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
    private final String domain;
    private final Clock clock;

    /**
     * Constructs the controller with all required collaborators.
     *
     * @param shareLinkService service for share-link lifecycle management
     * @param authGuard        cookie-based authentication guard (cookie-based in production)
     * @param rateLimiter      two-axis rate limiter
     * @param domain           the glacier domain for constructing readonly URLs
     *                         ({@code glacier.domain})
     * @param clock            injected clock for test determinism
     */
    public ShareLinkController(
            final ShareLinkService shareLinkService,
            final FallbackAuthGuard authGuard,
            final FallbackRateLimiter rateLimiter,
            @Value("${glacier.domain}") final String domain,
            final Clock clock) {
        this.shareLinkService = shareLinkService;
        this.authGuard = authGuard;
        this.rateLimiter = rateLimiter;
        this.domain = domain;
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

        FallbackRateLimiter.RateLimitResult rl = rateLimiter.check(principal, remoteIp);
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
     * @param rawWallId the {@code wallId} cookie value; null when absent
     * @param idStr     the share-link ID from the URL path
     * @param request   the raw servlet request
     * @return 204 No Content on success; 401 on missing auth; 404 on not-found or
     *         not-authorised; 400 on malformed ID
     */
    @DeleteMapping(value = "/{id}")
    public ResponseEntity<?> revokeShareLink(
            @CookieValue(value = "wallId", required = false) final String rawWallId,
            @PathVariable("id") final String idStr,
            final HttpServletRequest request) {

        FallbackAuthGuard.AuthResult auth = authGuard.authenticate(request, rawWallId);
        if (!auth.authenticated()) {
            LOGGER.debug("DELETE /rest/share-links auth failed");
            return ResponseEntity.status(401).body(Map.of("error", "missing_wallid"));
        }

        String principal = auth.principal();

        ShareLinkId id;
        try {
            id = ShareLinkId.fromUrlPath(idStr);
        } catch (IllegalArgumentException e) {
            // Malformed ID — treat the same as not-found (anti-enumeration)
            LOGGER.debug("DELETE /rest/share-links malformed id (hash8={})", LogScrubber.hash8(idStr));
            return ResponseEntity.notFound().build();
        }

        try {
            shareLinkService.revoke(id, principal, clock.instant());
            return ResponseEntity.noContent().build();
        } catch (ShareLinkNotFoundOrNotAuthorisedException e) {
            // Anti-enumeration: 404 for both not-found and not-authorised
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
        List<ShareLinkListEntry> entries = shareLinkService.listBySharer(principal, now)
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
                link.expiresAt(),
                buildReadonlyUrl(link.id().value()));
    }

    private ShareLinkListEntry toListEntry(final ShareLink link) {
        return new ShareLinkListEntry(
                link.id().value(),
                link.expiresAt(),
                buildReadonlyUrl(link.id().value()));
    }

    private String buildReadonlyUrl(final String shareLinkId) {
        return "https://" + domain + "/share/" + shareLinkId;
    }
}
