package de.seism0saurus.glacier.share.web;

import de.seism0saurus.glacier.share.domain.ShareLinkStatus;

import java.time.Instant;

/**
 * One entry in the {@code GET /rest/share-links} listing for a sharer's self-management UI.
 *
 * <p>Security contract:
 * <ul>
 *   <li>{@code sharerWallId} is absent — only the sharer's own browser calls this endpoint
 *       (authenticated via {@code wallId} cookie), so the sharer's own wallId does not need
 *       to appear in the response.</li>
 *   <li>{@code readonlyUrl} is absent — the share URL is shown exactly once at creation time
 *       and is never retrievable from the management list (ADR-SQLITE-05; Stripe / GitHub /
 *       AWS bearer-token UX pattern). Operators receive a "shown once" warning at creation.</li>
 *   <li>{@code idHash8} is a safe 8-character fingerprint of the link ID for display purposes
 *       only; it is NOT a lookup key or the raw token.</li>
 * </ul>
 *
 * @param idHash8   first 8 hex chars of SHA-256(token) for display; never the raw token
 * @param createdAt the UTC instant at which the link was created
 * @param expiresAt the UTC instant at which the link expires
 * @param status    the link status at the time the list was fetched
 */
public record ShareLinkListEntry(
        String idHash8,
        Instant createdAt,
        Instant expiresAt,
        ShareLinkStatus status) {
}
