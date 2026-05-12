package de.seism0saurus.glacier.share.domain;

import java.time.Instant;

/**
 * Read-model projection for the share-link management list.
 *
 * <p>This projection intentionally omits the raw {@link ShareLinkId} — the SQLite adapter
 * stores only SHA-256(token) and cannot reconstruct the original URL. The sharer's
 * management UI shows the first 8 hex chars of the hash as a visual identifier.
 *
 * <p>The {@code sharerWallId} is included for repository-layer convenience (the SQLite adapter
 * needs it to reconstruct the projection from a {@code SELECT} that filters by it), but the
 * web layer MUST NOT include it in HTTP responses.
 *
 * <p>Security contract: no {@code id}, {@code token}, {@code secret}, or {@code url} field may
 * be added to this record (ArchUnit rule SR-SQLITE-20 enforces this once enabled in the
 * acceptance phase). The {@code idHash8} field is the first 8 hex characters of
 * SHA-256(token) — a visual identifier that does not reconstruct the token.
 *
 * @param idHash8      first 8 hex chars of SHA-256(token) for display identification
 * @param createdAt    when the link was created
 * @param expiresAt    when the link expires
 * @param revokedAt    when the link was revoked, or {@code null} if not revoked
 * @param status       current lifecycle status derived at listing time
 * @param sharerWallId the sharer's wallId — for repository use; the web layer must not
 *                     return this in HTTP responses
 */
public record ShareLinkSummary(
        String idHash8,
        Instant createdAt,
        Instant expiresAt,
        Instant revokedAt,
        ShareLinkStatus status,
        String sharerWallId) {
}
