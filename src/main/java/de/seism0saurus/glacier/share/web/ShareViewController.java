package de.seism0saurus.glacier.share.web;

import de.seism0saurus.glacier.mastodon.SubscriptionManager;
import de.seism0saurus.glacier.share.application.ShareLinkService;
import de.seism0saurus.glacier.share.application.ShareViewStompRelay;
import de.seism0saurus.glacier.share.application.ReadonlyTootView;
import de.seism0saurus.glacier.share.domain.ShareLink;
import de.seism0saurus.glacier.share.domain.ShareLinkId;
import de.seism0saurus.glacier.util.LogScrubber;
import de.seism0saurus.glacier.webservice.messaging.ShareViewPrincipalHandler;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * REST controller for viewer-side share-link endpoints.
 *
 * <p>Endpoints:
 * <ul>
 *   <li>{@code GET /rest/share/{shareId}/catalog} — bootstrap endpoint; mints viewer cookie on miss</li>
 *   <li>{@code GET /rest/share-csrf} — issues a new CSRF token cookie</li>
 * </ul>
 *
 * <p>Anti-enumeration invariant (SR-SHARE-01):
 * The catalog endpoint returns {@code 404} for unknown {@code shareId} values and
 * {@code 200 {state:"expired"}} for known-but-inactive IDs.
 * This ensures a viewer cannot determine whether a share ID ever existed.
 * In this implementation, for simplicity and security, unknown IDs also return 404
 * (constant-time path).
 *
 * <p>Security: SR-SHARE-01 (anti-enumeration), SR-SHARE-02 (wallId non-disclosure),
 * SR-SHARE-06 (viewer cookie), SR-SHARE-07 (cookie flags), SR-SHARE-11 (CSP headers set by filter).
 */
@RestController
@Validated
public class ShareViewController {

    private static final Logger log = LoggerFactory.getLogger(ShareViewController.class);
    private static final Logger AUDIT = LoggerFactory.getLogger("AUDIT");

    /** Validation pattern for shareId path variable — URL-safe base64. */
    private static final String SHARE_ID_PATTERN = "^[A-Za-z0-9_-]{43,256}$";

    private final ShareLinkService shareLinkService;
    private final ShareViewerCookieFactory cookieFactory;
    private final CsrfTokenCookieFactory csrfTokenCookieFactory;
    private final ShareRateLimiter shareRateLimiter;
    private final ShareViewStompRelay shareViewStompRelay;
    private final SubscriptionManager subscriptionManager;
    private final boolean fallbackEnabled;

    public ShareViewController(
            final ShareLinkService shareLinkService,
            final ShareViewerCookieFactory cookieFactory,
            final CsrfTokenCookieFactory csrfTokenCookieFactory,
            final ShareRateLimiter shareRateLimiter,
            final ShareViewStompRelay shareViewStompRelay,
            final SubscriptionManager subscriptionManager,
            @Value("${glacier.fallback.enabled:true}") final boolean fallbackEnabled) {
        this.shareLinkService = shareLinkService;
        this.cookieFactory = cookieFactory;
        this.csrfTokenCookieFactory = csrfTokenCookieFactory;
        this.shareRateLimiter = shareRateLimiter;
        this.shareViewStompRelay = shareViewStompRelay;
        this.subscriptionManager = subscriptionManager;
        this.fallbackEnabled = fallbackEnabled;
    }

    /**
     * Bootstrap endpoint for the readonly share wall.
     *
     * <p>If the viewer presents no valid {@code shareViewerId} cookie, one is minted
     * and set in the response.
     *
     * <p>Returns {@code 404} for unknown share IDs (anti-enumeration).
     * Returns {@code 200 {state}} for known-but-inactive IDs.
     *
     * <p>Owner-scoped authorization (Sec-16/P1-04): if a {@code wallId} cookie is
     * present in the request, it must match the share link's owner ({@code sharerWallId}).
     * A mismatch returns {@code 401} to avoid link enumeration (unified error code).
     * Pure viewer requests (no {@code wallId} cookie) are not affected.
     */
    @GetMapping(value = "/rest/share/{shareId}/catalog", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ShareCatalogResponse> getCatalog(
            @PathVariable @Pattern(regexp = SHARE_ID_PATTERN) String shareId,
            @CookieValue(value = "wallId", required = false) String wallId,
            HttpServletRequest request,
            HttpServletResponse response) {

        // Manually extract viewer cookie to support both __Host- and non-Host- names
        // depending on glacier.cookie.secure mode
        String rawViewerId = extractViewerCookie(request);

        // Rate limiting (SR-SHARE-12): per-viewer + per-IP to prevent fallback polling abuse
        // OWASP API4: Lack of Resources & Rate Limiting
        String remoteIp = request.getRemoteAddr();
        String viewerIdForRateLimit = rawViewerId != null ? rawViewerId : remoteIp;
        ShareRateLimiter.RateLimitResult rl = shareRateLimiter.checkShareFallback(viewerIdForRateLimit, remoteIp);
        if (!rl.permitted()) {
            AUDIT.info("share.catalog.ratelimit shareId-hash={} viewerId-hash={}",
                    LogScrubber.hash8(shareId), LogScrubber.hash8(viewerIdForRateLimit));
            return ResponseEntity.status(429)
                    .header("Retry-After", String.valueOf(rl.retryAfterSeconds()))
                    .build();
        }

        // Resolve the share link
        ShareLinkId linkId;
        try {
            linkId = ShareLinkId.fromUrlPath(shareId);
        } catch (IllegalArgumentException e) {
            // Invalid format — treat as not found (anti-enumeration)
            return ResponseEntity.notFound().build();
        }

        Instant now = Instant.now();
        Optional<ShareLink> linkOpt = shareLinkService.resolve(linkId, now);

        if (linkOpt.isEmpty()) {
            // Unknown or expired/revoked — uniform 404 (anti-enumeration: SR-SHARE-01)
            return ResponseEntity.notFound().build();
        }

        ShareLink link = linkOpt.get();

        // Sec-16/P1-04: owner-scoped authorization.
        // If the request presents a wallId cookie (the sharer's identity), verify it matches
        // the share link's owner. A mismatch returns 401 (not 403, not 404) to avoid
        // link enumeration. Pure viewer requests (no wallId cookie) are unaffected.
        // OWASP A01:2021 Broken Access Control — C1 — server-side authorization at every entry.
        if (wallId != null && !wallId.isBlank()) {
            if (!wallId.equals(link.sharerWallId())) {
                AUDIT.info("share.catalog.owner_mismatch shareId-hash={} wallId-hash={}",
                        LogScrubber.hash8(shareId), LogScrubber.hash8(wallId));
                // 401 — unified error, does not reveal whether the link exists (Sec-16)
                return ResponseEntity.status(401).build();
            }
        }

        // Mint or validate viewer cookie
        String viewerId;
        if (!ShareViewPrincipalHandler.isValidShareViewerId(rawViewerId)) {
            viewerId = cookieFactory.mintAndSet(response, link.expiresAt());
            AUDIT.info("share.link.accessed kind=catalog shareId-hash={} viewerId-hash={} action=cookie_minted",
                    LogScrubber.hash8(shareId), LogScrubber.hash8(viewerId));
        } else {
            viewerId = rawViewerId;
            cookieFactory.refresh(response, viewerId, link.expiresAt());
            AUDIT.info("share.link.accessed kind=catalog shareId-hash={} viewerId-hash={}",
                    LogScrubber.hash8(shareId), LogScrubber.hash8(viewerId));
        }

        // Derive hashtags from the live subscription map, resolved server-side
        // from the confirmed-active link's sharerWallId (ADR-RENDER-02).
        // The sharerWallId is used ONLY here, never serialized into the response (SR-SHARE-02).
        // SR-CAT-02: hashtags are derived only after resolve() confirms an active link,
        // and only from link.sharerWallId() — never from any caller-supplied value.
        // D-13/SR-8: do not log raw sharerWallId — hash it
        List<String> hashtags = new ArrayList<>(
                subscriptionManager.getSubscribedHashtags(link.sharerWallId()));
        log.debug("share.catalog.hashtags shareId-hash={} sharer-hash={} count={}",
                LogScrubber.hash8(shareId),
                LogScrubber.hash8(link.sharerWallId()),
                hashtags.size());

        // FLAW-3: hydrate initialToots from the share Status cache, rendered per-link. This shows
        // recent history on first load AND is the only content source for HTTP-fallback viewers
        // (who never receive live STOMP frames). Rendering is per-link (image proxy URLs are signed
        // per share link); sharerWallId stays server-side (SR-SHARE-02).
        List<ReadonlyTootView> initialToots = new ArrayList<>();
        for (String tag : hashtags) {
            initialToots.addAll(shareViewStompRelay.getRecentMessages(linkId, tag, null, now));
        }
        ShareCatalogResponse catalog = ShareCatalogResponse.active(
                shareId,
                hashtags,
                link.expiresAt(),
                List.copyOf(initialToots)
        );

        return ResponseEntity.ok(catalog);
    }

    /**
     * Issues a new CSRF token for the share view.
     *
     * <p>This endpoint is rate-limited (handled by infrastructure layer).
     * The token is stored in the {@code __Host-shareCsrf} cookie and must be echoed
     * in the {@code X-Share-CSRF} header for state-changing requests.
     *
     * <p>The token is also returned in the JSON body as {@code {"token": "<value>"}} so
     * that non-cookie-capable clients or tests can extract it directly.
     *
     * @return 200 OK with body {@code {"token": "<value>"}}
     */
    @GetMapping(value = "/rest/share-csrf", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, String>> issueCsrfToken(
            HttpServletRequest request,
            HttpServletResponse response) {
        // Rate limiting (SR-SHARE-12): per-IP to prevent CSRF token farming
        // OWASP API4: Lack of Resources & Rate Limiting
        String remoteIp = request.getRemoteAddr();
        ShareRateLimiter.RateLimitResult rl = shareRateLimiter.checkCsrfIssuance(remoteIp);
        if (!rl.permitted()) {
            return ResponseEntity.status(429)
                    .header("Retry-After", String.valueOf(rl.retryAfterSeconds()))
                    .build();
        }

        String token = csrfTokenCookieFactory.issueCsrfToken(response);
        return ResponseEntity.ok(Map.of("token", token));
    }

    /**
     * Fallback polling endpoint for viewers whose network blocks WebSocket/STOMP.
     *
     * <p>Returns cached toot events for the given share link and hashtag, filtered
     * by the {@code since} sequence cursor. This mirrors the main fallback endpoint
     * {@code GET /rest/messages} but is scoped to an active share link instead of a wallId.
     *
     * <p>Processing order (fail-fast):
     * <ol>
     *   <li>Kill-switch check ({@code glacier.fallback.enabled=false}) → 404</li>
     *   <li>Rate limiting per viewer + per IP → 429 with {@code Retry-After}</li>
     *   <li>Share link validation (unknown / expired / revoked) → 404 (anti-enumeration SR-SHARE-01)</li>
     *   <li>Cache lookup via {@link ShareViewStompRelay#getRecentMessages} → 200 list</li>
     * </ol>
     *
     * <p>The sharer's wallId is used server-side only and is NEVER returned in the response (SR-SHARE-02).
     *
     * <p>Security: SR-SHARE-01 (anti-enumeration), SR-SHARE-02 (wallId non-disclosure),
     * SR-SHARE-12 (rate limiting), SR-SHARE-13 (killswitch), OWASP API4.
     *
     * @param shareId  the share link ID (URL path variable, validated against SHARE_ID_PATTERN)
     * @param hashtag  the hashtag to fetch events for (validated against HASHTAG_PATTERN)
     * @param since    sequence cursor — events with sequence &gt; since are returned; null returns all
     * @param request  the raw servlet request (for IP and viewer cookie extraction)
     * @param response the HTTP response (for viewer cookie refresh)
     * @return 200 with a JSON array of {@link CacheEntry} objects, or an error response
     */
    @GetMapping(value = "/rest/share/{shareId}/messages", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<List<ReadonlyTootView>> getMessages(
            @PathVariable @Pattern(regexp = SHARE_ID_PATTERN) String shareId,
            @RequestParam("hashtag") @Pattern(regexp = HASHTAG_PATTERN) String hashtag,
            @RequestParam(value = "since", required = false)
            @Min(value = 0, message = "invalid_cursor")
            @Max(value = Long.MAX_VALUE / 2, message = "invalid_cursor")
            Long since,
            HttpServletRequest request,
            HttpServletResponse response) {

        // glacier-fallback-mode-discipline: killswitch disables the entire polling path
        // (SR-SHARE-13). Return 404 — same as the main FallbackController killswitch behavior.
        if (!fallbackEnabled) {
            return ResponseEntity.notFound().build();
        }

        // Rate limiting (SR-SHARE-12): per-viewer + per-IP — same check as catalog endpoint
        String rawViewerId = extractViewerCookie(request);
        String remoteIp = request.getRemoteAddr();
        String viewerIdForRateLimit = rawViewerId != null ? rawViewerId : remoteIp;
        ShareRateLimiter.RateLimitResult rl = shareRateLimiter.checkShareFallback(viewerIdForRateLimit, remoteIp);
        if (!rl.permitted()) {
            AUDIT.info("share.messages.ratelimit shareId-hash={} viewerId-hash={}",
                    LogScrubber.hash8(shareId), LogScrubber.hash8(viewerIdForRateLimit));
            return ResponseEntity.status(429)
                    .header("Retry-After", String.valueOf(rl.retryAfterSeconds()))
                    .build();
        }

        // Resolve the share link — 404 for unknown / expired (anti-enumeration SR-SHARE-01)
        ShareLinkId linkId;
        try {
            linkId = ShareLinkId.fromUrlPath(shareId);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }

        Instant now = Instant.now();
        Optional<ShareLink> linkOpt = shareLinkService.resolve(linkId, now);
        if (linkOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        // Refresh viewer cookie lifetime on successful resolution
        ShareLink link = linkOpt.get();
        if (rawViewerId != null && ShareViewPrincipalHandler.isValidShareViewerId(rawViewerId)) {
            cookieFactory.refresh(response, rawViewerId, link.expiresAt());
        }

        // Fetch from ring buffer via relay — sharerWallId is resolved server-side (SR-SHARE-02)
        List<ReadonlyTootView> events = shareViewStompRelay.getRecentMessages(linkId, hashtag, since, now);
        log.debug("share.messages.served shareId-hash={} hashtag={} count={}",
                LogScrubber.hash8(shareId), hashtag, events.size());

        return ResponseEntity.ok(events);
    }

    /** Validation pattern for the {@code hashtag} parameter — same as {@link de.seism0saurus.glacier.webservice.FallbackController#HASHTAG_PATTERN}. */
    private static final String HASHTAG_PATTERN = "^[\\p{L}\\p{N}_]{1,50}$";

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    private String extractViewerCookie(HttpServletRequest request) {
        jakarta.servlet.http.Cookie[] cookies = request.getCookies();
        if (cookies == null) return null;
        // Try __Host- first (secure mode), then fall back to plain name
        for (String name : new String[]{
                ShareViewPrincipalHandler.COOKIE_NAME_SECURE,
                ShareViewPrincipalHandler.COOKIE_NAME_INSECURE}) {
            Optional<String> found = Arrays.stream(cookies)
                    .filter(c -> name.equals(c.getName()))
                    .map(jakarta.servlet.http.Cookie::getValue)
                    .findFirst();
            if (found.isPresent()) return found.get();
        }
        return null;
    }
}
