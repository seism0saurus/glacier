package de.seism0saurus.glacier.share.web;

import de.seism0saurus.glacier.share.domain.ShareLinkStatus;

import java.time.Instant;

/**
 * One entry in the {@code GET /rest/share-links} listing for a sharer's self-management UI.
 *
 * <p>Security contract: the share-link URL is NOT returned here. It is shown exactly once
 * at creation time (copy-to-clipboard flow, SR-21). The {@code idHash8} is the first 8
 * hex digits of SHA-256(token) — a visual identifier that does not reconstruct the token.
 *
 * <p>The {@code sharerWallId} is intentionally absent — only the sharer's own browser
 * calls this endpoint (authenticated via {@code wallId} cookie), so the sharer's own
 * wallId does not need to appear in the response.
 *
 * @param idHash8   first 8 hex chars of SHA-256(token) for display identification
 * @param createdAt when the link was created
 * @param expiresAt when the link expires
 * @param status    current lifecycle status: {@link ShareLinkStatus#ACTIVE},
 *                  {@link ShareLinkStatus#EXPIRED}, or {@link ShareLinkStatus#REVOKED}
 */
public record ShareLinkListEntry(
        String idHash8,
        Instant createdAt,
        Instant expiresAt,
        ShareLinkStatus status) {
}
