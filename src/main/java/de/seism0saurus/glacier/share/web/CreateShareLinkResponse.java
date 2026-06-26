package de.seism0saurus.glacier.share.web;

import java.time.Instant;

/**
 * Response DTO for {@code POST /rest/share-links}.
 *
 * <p>Security contract: {@code sharerWallId} is intentionally absent — it is an internal
 * domain value that must never cross the wire to any client (viewers or the sharer's own
 * browser via DevTools).
 *
 * @param shareLinkId the opaque URL-safe token identifying this link (safe for URL use);
 *                    shown exactly once here and never re-served
 * @param idHash8     the first 8 hex chars of SHA-256(token) — the non-secret, stable handle
 *                    the client uses to revoke this link ({@code DELETE /rest/share-links/{idHash8}}),
 *                    matching what {@link ShareLinkListEntry} carries; does not reconstruct the token
 * @param expiresAt   the UTC instant at which the link expires
 * @param readonlyUrl the absolute readonly wall URL that viewers should follow;
 *                    formed as {@code https://<domain>/share/<shareLinkId>}
 */
public record CreateShareLinkResponse(
        String shareLinkId,
        String idHash8,
        Instant expiresAt,
        String readonlyUrl) {
}
