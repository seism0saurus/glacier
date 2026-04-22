package de.seism0saurus.glacier.share.web;

import java.time.Instant;

/**
 * One entry in the {@code GET /rest/share-links} listing for a sharer's self-management UI.
 *
 * <p>Security contract: {@code sharerWallId} is absent — only the sharer's own browser
 * calls this endpoint (authenticated via {@code wallId} cookie), so the sharer's own wallId
 * does not need to appear in the response.
 *
 * @param shareLinkId the opaque URL-safe token for this link
 * @param expiresAt   the UTC instant at which the link expires
 * @param readonlyUrl the absolute readonly wall URL for sharing
 */
public record ShareLinkListEntry(
        String shareLinkId,
        Instant expiresAt,
        String readonlyUrl) {
}
