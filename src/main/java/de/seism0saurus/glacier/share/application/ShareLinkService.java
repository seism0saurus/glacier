package de.seism0saurus.glacier.share.application;

import de.seism0saurus.glacier.share.domain.ShareLink;
import de.seism0saurus.glacier.share.domain.ShareLinkId;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Application service for share-link lifecycle management.
 *
 * <p>Orchestrates domain operations on {@link ShareLink} aggregates without exposing
 * infrastructure details to the web layer.  All methods accept an explicit {@code now}
 * instant so callers can inject a {@link java.time.Clock} for test determinism.
 *
 * <p>Security contracts:
 * <ul>
 *   <li>{@link #resolve} returns {@link Optional#empty()} for expired, revoked, or unknown
 *       links — callers cannot distinguish these cases.  Internally the lookup cost is
 *       constant-time for both "not found" and "not active" branches (no timing oracle).</li>
 *   <li>{@link #revoke} throws {@link ShareLinkNotFoundOrNotAuthorisedException} for both
 *       "not found" and "wrong wallId" cases — anti-enumeration (T-07).</li>
 *   <li>{@link #create} throws {@link CapacityExceededException} when per-sharer or per-IP
 *       caps are exceeded (DoS hardening, ADR-SHARE-01).</li>
 * </ul>
 */
public interface ShareLinkService {

    /**
     * Creates a new share link for the given sharer.
     *
     * <p>Pre-conditions enforced:
     * <ul>
     *   <li>Active link count for {@code sharerWallId} < {@code maxActivePerSharer}.</li>
     *   <li>Active link count for {@code sharerIp} < {@code maxActivePerIp}.</li>
     * </ul>
     *
     * @param sharerWallId the wallId of the requesting sharer; must be non-blank
     * @param sharerIp     the remote IP of the creating request; used for per-IP cap only
     * @param now          the current clock instant; drives TTL computation
     * @return the newly created and saved {@link ShareLink}
     * @throws CapacityExceededException if any cap is exceeded
     */
    ShareLink create(String sharerWallId, String sharerIp, Instant now);

    /**
     * Resolves a share link by ID, returning it only if currently ACTIVE.
     *
     * <p>Returns {@link Optional#empty()} when:
     * <ul>
     *   <li>The ID is not found in the repository.</li>
     *   <li>The link is expired or revoked.</li>
     * </ul>
     * These cases are intentionally indistinguishable to callers.
     *
     * @param id  the share-link ID to look up; must not be null
     * @param now the current clock instant; drives status derivation
     * @return the ACTIVE link if found; empty otherwise
     */
    Optional<ShareLink> resolve(ShareLinkId id, Instant now);

    /**
     * Revokes the share link identified by {@code id} on behalf of {@code callerWallId}.
     *
     * <p>Anti-enumeration: both "link not found" and "wallId does not match" throw
     * {@link ShareLinkNotFoundOrNotAuthorisedException} with the same type and a generic message.
     *
     * @param id           the share-link ID to revoke; must not be null
     * @param callerWallId the wallId authorising the revocation; must match the link's
     *                     {@code sharerWallId}
     * @param now          the current clock instant
     * @throws ShareLinkNotFoundOrNotAuthorisedException if the link is not found or the caller
     *                                                   is not the sharer
     */
    void revoke(ShareLinkId id, String callerWallId, Instant now);

    /**
     * Returns all ACTIVE share links created by the given sharer.
     *
     * <p>Expired and revoked links are filtered out so only currently usable links appear in
     * the sharer's management UI.
     *
     * @param sharerWallId the sharer's wallId; must not be null
     * @param now          the current clock instant; drives status filtering
     * @return a (possibly empty) list of ACTIVE {@link ShareLink}s; never null
     */
    List<ShareLink> listBySharer(String sharerWallId, Instant now);
}
