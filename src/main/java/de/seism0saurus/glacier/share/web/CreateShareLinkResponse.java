package de.seism0saurus.glacier.share.web;

import java.time.Instant;

/**
 * Response DTO for {@code POST /rest/share-links}.
 *
 * <p>Security contract: {@code sharerWallId} is intentionally absent — it is an internal
 * domain value that must never cross the wire to any client (viewers or the sharer's own
 * browser via DevTools).
 *
 * @param shareLinkId the opaque URL-safe token identifying this link (safe for URL use)
 * @param expiresAt   the UTC instant at which the link expires
 * @param readonlyUrl the absolute readonly wall URL that viewers should follow;
 *                    formed as {@code https://<domain>/share/<shareLinkId>}
 */
public record CreateShareLinkResponse(
        String shareLinkId,
        Instant expiresAt,
        String readonlyUrl) {
}
