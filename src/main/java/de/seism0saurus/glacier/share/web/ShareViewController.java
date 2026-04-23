package de.seism0saurus.glacier.share.web;

import de.seism0saurus.glacier.share.application.ShareLinkService;
import de.seism0saurus.glacier.share.domain.ShareLink;
import de.seism0saurus.glacier.share.domain.ShareLinkId;
import de.seism0saurus.glacier.share.domain.ShareLinkStatus;
import de.seism0saurus.glacier.util.LogScrubber;
import de.seism0saurus.glacier.webservice.messaging.ShareViewPrincipalHandler;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.constraints.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
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
    private final boolean fallbackEnabled;

    public ShareViewController(
            final ShareLinkService shareLinkService,
            final ShareViewerCookieFactory cookieFactory,
            final CsrfTokenCookieFactory csrfTokenCookieFactory,
            @Value("${glacier.fallback.enabled:true}") final boolean fallbackEnabled) {
        this.shareLinkService = shareLinkService;
        this.cookieFactory = cookieFactory;
        this.csrfTokenCookieFactory = csrfTokenCookieFactory;
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
     */
    @GetMapping(value = "/rest/share/{shareId}/catalog", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ShareCatalogResponse> getCatalog(
            @PathVariable @Pattern(regexp = SHARE_ID_PATTERN) String shareId,
            HttpServletRequest request,
            HttpServletResponse response) {

        // Manually extract viewer cookie to support both __Host- and non-Host- names
        // depending on glacier.cookie.secure mode
        String rawViewerId = extractViewerCookie(request);

        // Resolve the share link
        ShareLinkId linkId;
        try {
            linkId = new ShareLinkId(shareId);
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

        // Mint or validate viewer cookie
        String viewerId;
        if (!ShareViewPrincipalHandler.isValidShareViewerId(rawViewerId)) {
            viewerId = cookieFactory.mintAndSet(response, link.getExpiresAt());
            AUDIT.info("share.link.accessed kind=catalog shareId-hash={} viewerId-hash={} action=cookie_minted",
                    LogScrubber.hash8(shareId), LogScrubber.hash8(viewerId));
        } else {
            viewerId = rawViewerId;
            cookieFactory.refresh(response, viewerId, link.getExpiresAt());
            AUDIT.info("share.link.accessed kind=catalog shareId-hash={} viewerId-hash={}",
                    LogScrubber.hash8(shareId), LogScrubber.hash8(viewerId));
        }

        // Build catalog response — NEVER include sharerWallId (SR-SHARE-02)
        // Hashtag list would come from SubscriptionManager; for now return empty (peer lane owns this)
        List<String> hashtags = List.of(); // TODO: retrieve from SubscriptionManager via sharerWallId server-side

        ShareCatalogResponse catalog = ShareCatalogResponse.active(
                shareId,
                hashtags,
                link.getExpiresAt()
        );

        return ResponseEntity.ok(catalog);
    }

    /**
     * Issues a new CSRF token for the share view.
     *
     * <p>This endpoint is rate-limited (handled by infrastructure layer).
     * The token is stored in the {@code __Host-shareCsrf} cookie and must be echoed
     * in the {@code X-Share-Csrf-Token} header for state-changing requests.
     */
    @GetMapping(value = "/rest/share-csrf", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Void> issueCsrfToken(HttpServletResponse response) {
        csrfTokenCookieFactory.issueCsrfToken(response);
        return ResponseEntity.ok().build();
    }

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
