package de.seism0saurus.glacier.share.web;

import java.time.Instant;
import java.util.List;

/**
 * Response DTO for {@code GET /rest/share/{id}/catalog}.
 *
 * <p>Invariant: {@code sharerWallId} is NEVER included here — SR-SHARE-02.
 * The {@code shareId} field is the opaque token (safe to transmit; it's already in the URL).
 *
 * <p>References: SR-SHARE-01, SR-SHARE-02, ADR-SHARE-03.
 */
public record ShareCatalogResponse(
        String shareId,
        List<String> hashtags,
        Instant expiresAt,
        String state
) {
    /** The link is active and available. */
    public static final String STATE_ACTIVE = "active";

    /**
     * Creates a response for an ACTIVE link.
     *
     * <p>Anti-enumeration invariant (SR-SHARE-01): unknown and inactive share IDs both return
     * 404 (not this method). This factory is only called for confirmed-active links.
     */
    public static ShareCatalogResponse active(String shareId, List<String> hashtags, Instant expiresAt) {
        return new ShareCatalogResponse(shareId, hashtags, expiresAt, STATE_ACTIVE);
    }
}
