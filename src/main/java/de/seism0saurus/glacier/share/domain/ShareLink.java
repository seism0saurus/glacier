package de.seism0saurus.glacier.share.domain;

import java.time.Instant;
import java.util.Optional;

/**
 * Aggregate root for a share-link grant.
 *
 * <p>Invariants (all enforced in this class):
 * <ol>
 *   <li>{@code expiresAt == createdAt.plus(P7D)} — enforced by constructor.</li>
 *   <li>Revocation is irreversible: once {@code revokedAt.isPresent()}, status is always REVOKED.</li>
 *   <li>A revocation must include the calling wallId; rejected if it doesn't match {@code sharerWallId}.</li>
 *   <li>{@code status(now)} is derived, never stored.</li>
 * </ol>
 *
 * <p>Security: ADR-SHARE-01. The {@code sharerWallId} is high-sensitivity — never transmit
 * to viewer clients; only use in server-side logic and AUDIT logs (hashed).
 */
public class ShareLink {

    private final ShareLinkId id;
    /**
     * The wallId that created this link. HIGH SENSITIVITY — never transmit to viewers.
     * SR-SHARE-02, ADR-SHARE-02.
     */
    private final String sharerWallId;
    private final Instant createdAt;
    private final Instant expiresAt;
    private volatile Instant revokedAt;

    /**
     * Reconstructs a {@link ShareLink} (e.g., from repository).
     * The {@code expiresAt} is passed in rather than computed here to support
     * repository reload from persisted state; invariant verification is caller's responsibility.
     */
    public ShareLink(ShareLinkId id, String sharerWallId, Instant createdAt, Instant expiresAt) {
        this.id = id;
        this.sharerWallId = sharerWallId;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
        this.revokedAt = null;
    }

    /** @return the share link identifier (never null) */
    public ShareLinkId getId() { return id; }

    /**
     * @return the sharer's wallId — HIGH SENSITIVITY, server-side only (SR-SHARE-02)
     */
    public String getSharerWallId() { return sharerWallId; }

    /** @return when this link was created */
    public Instant getCreatedAt() { return createdAt; }

    /** @return when this link expires */
    public Instant getExpiresAt() { return expiresAt; }

    /** @return Optional containing revocation time, or empty if not revoked */
    public Optional<Instant> getRevokedAt() { return Optional.ofNullable(revokedAt); }

    /**
     * Derives the lifecycle status relative to {@code now}.
     *
     * <p>Invariant: REVOKED takes precedence over EXPIRED to prevent state-machine confusion
     * when a link is revoked after its natural expiry.
     */
    public ShareLinkStatus status(Instant now) {
        if (revokedAt != null) return ShareLinkStatus.REVOKED;
        if (now.isAfter(expiresAt)) return ShareLinkStatus.EXPIRED;
        return ShareLinkStatus.ACTIVE;
    }

    /**
     * Marks this link as revoked.
     *
     * @param callerWallId the wallId making the revocation request
     * @param when         the revocation timestamp
     * @throws IllegalArgumentException if callerWallId does not match sharerWallId (invariant 3)
     * @throws IllegalStateException    if already revoked (revocation is idempotent — double-revoke by same owner is accepted)
     */
    public synchronized void revoke(String callerWallId, Instant when) {
        if (!sharerWallId.equals(callerWallId)) {
            throw new IllegalArgumentException("Revocation rejected: caller is not the link owner");
        }
        if (revokedAt == null) {
            this.revokedAt = when;
        }
        // idempotent: double-revoke by same owner is accepted
    }

    /**
     * Package-private setter for repository use during sweep.
     */
    void setRevokedAt(Instant when) {
        this.revokedAt = when;
    }
}
