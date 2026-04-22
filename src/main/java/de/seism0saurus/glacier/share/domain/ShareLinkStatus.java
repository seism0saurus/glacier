package de.seism0saurus.glacier.share.domain;

/**
 * Lifecycle states for a {@link ShareLink}.
 *
 * <p>Terminal states ({@link #EXPIRED}, {@link #REVOKED}) are irreversible.
 * Once in a terminal state, the link can no longer be used.
 *
 * <p>References: ADR-SHARE-01, ShareLink aggregate invariants.
 */
public enum ShareLinkStatus {
    /** Within TTL and not explicitly revoked. */
    ACTIVE,
    /** TTL has elapsed. */
    EXPIRED,
    /** Explicitly revoked by the sharer. */
    REVOKED
}
