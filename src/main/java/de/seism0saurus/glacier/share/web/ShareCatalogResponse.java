package de.seism0saurus.glacier.share.web;

import de.seism0saurus.glacier.share.application.ReadonlyTootView;

import java.time.Instant;
import java.util.List;

/**
 * Response DTO for {@code GET /rest/share/{id}/catalog}.
 *
 * <p>Invariant: {@code sharerWallId} is NEVER included here — SR-SHARE-02.
 * The {@code shareId} field is the opaque token (safe to transmit; it's already in the URL).
 *
 * <p>The {@code initialToots} field carries a pre-rendered list of recent toots for
 * the share wall. In the MVP (ADR-RENDER-03) this list is always empty — the share wall
 * fills via live STOMP toots after initial render. The field is present so the Angular
 * client can spread it without a null-guard ({@code [...catalog.initialToots]}); once
 * history caching is in scope it will be non-empty.
 *
 * <p>References: SR-SHARE-01, SR-SHARE-02, ADR-SHARE-03, ADR-RENDER-03.
 */
public record ShareCatalogResponse(
        String shareId,
        List<String> hashtags,
        Instant expiresAt,
        String state,
        List<ReadonlyTootView> initialToots
) {
    /** The link is active and available. */
    public static final String STATE_ACTIVE = "active";

    /**
     * Creates a response for an ACTIVE link.
     *
     * <p>Anti-enumeration invariant (SR-SHARE-01): unknown and inactive share IDs both return
     * 404 (not this method). This factory is only called for confirmed-active links.
     *
     * @param shareId      the opaque share-link token (safe to transmit; already in the URL)
     * @param hashtags     the list of hashtags the sharer is subscribed to (derived server-side)
     * @param expiresAt    when this share link expires
     * @param initialToots pre-rendered toots for initial render; empty in the MVP (ADR-RENDER-03)
     */
    public static ShareCatalogResponse active(
            String shareId,
            List<String> hashtags,
            Instant expiresAt,
            List<ReadonlyTootView> initialToots) {
        return new ShareCatalogResponse(shareId, hashtags, expiresAt, STATE_ACTIVE, initialToots);
    }
}
