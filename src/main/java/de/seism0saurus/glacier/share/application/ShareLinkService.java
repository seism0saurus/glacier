package de.seism0saurus.glacier.share.application;

import de.seism0saurus.glacier.share.domain.ShareLink;
import de.seism0saurus.glacier.share.domain.ShareLinkId;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Application service interface for share-link lifecycle management.
 *
 * <p>This interface is the integration point between the security/rendering lane
 * ({@code secure-tdd-implementer}) and the domain lane ({@code tdd-ddd-implementer}).
 * The {@code secure-tdd-implementer} consumes this interface; the {@code tdd-ddd-implementer}
 * owns the implementation.
 *
 * <p>Authorization rules (tested in implementation):
 * <ul>
 *   <li>{@link #create}: caller must present a valid wallId.</li>
 *   <li>{@link #resolve}: no caller auth required; constant-time lookup (anti-enumeration).</li>
 *   <li>{@link #revoke}: caller's wallId must equal sharerWallId; not-found and not-authorised
 *       return the same error shape (anti-enumeration).</li>
 * </ul>
 *
 * <p>References: ADR-SHARE-01, SR-SHARE-01.
 */
public interface ShareLinkService {

    /**
     * Creates a new share link for the given sharer.
     *
     * @param sharerWallId the wallId of the sharer creating this link
     * @param now          current time (for TTL calculation)
     * @return the created {@link ShareLink}
     * @throws CapacityExceededException if the sharer has reached the per-sharer active-link cap
     */
    ShareLink create(String sharerWallId, Instant now);

    /**
     * Resolves a share link by ID.
     *
     * <p>Returns empty for unknown, expired, or revoked links — anti-enumeration invariant.
     * Callers must not distinguish between these cases.
     *
     * @param id  the share link identifier
     * @param now current time (for expiry check)
     * @return an Optional containing the ACTIVE link, or empty
     */
    Optional<ShareLink> resolve(ShareLinkId id, Instant now);

    /**
     * Revokes a share link.
     *
     * <p>Not-found and not-authorised produce the same outcome (anti-enumeration).
     *
     * @param id           the share link identifier
     * @param callerWallId the wallId of the caller attempting revocation
     * @param now          current time
     */
    void revoke(ShareLinkId id, String callerWallId, Instant now);

    /**
     * Lists active share links for the given sharer.
     *
     * @param sharerWallId the sharer's wallId
     * @param now          current time
     * @return active share links for this sharer
     */
    List<ShareLink> listBySharer(String sharerWallId, Instant now);

    /**
     * Thrown when the per-sharer active-link cap is exceeded.
     */
    class CapacityExceededException extends RuntimeException {
        public CapacityExceededException(String message) {
            super(message);
        }
    }
}
