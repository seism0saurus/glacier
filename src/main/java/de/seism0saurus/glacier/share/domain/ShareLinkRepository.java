package de.seism0saurus.glacier.share.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;


/**
 * Repository interface for {@link ShareLink} aggregate persistence.
 *
 * <p>The only production implementation is
 * {@link de.seism0saurus.glacier.share.infrastructure.InMemoryShareLinkRepository}.
 *
 * <p>Repository contract notes:
 * <ul>
 *   <li>{@link #save} has create-only semantics — calling it with an existing ID replaces
 *       the stored link (safe because IDs are cryptographically random).</li>
 *   <li>{@link #markRevoked} applies the revocation directly inside the stored aggregate —
 *       it does NOT go through {@link ShareLink#revoke}; caller is responsible for
 *       authorization checks in the application layer.</li>
 *   <li>All {@code count*} methods accept a {@code now} instant for status computation
 *       so they return correct results when time is fixed in tests.</li>
 * </ul>
 */
public interface ShareLinkRepository {

    /**
     * Stores (or replaces) the given {@link ShareLink}.
     *
     * @param link the aggregate to persist; must not be null
     * @return the same link (enables fluent usage)
     */
    ShareLink save(ShareLink link);

    /**
     * Finds a {@link ShareLink} by its opaque identifier.
     *
     * @param id the share-link ID; must not be null
     * @return the link if found; empty otherwise
     */
    Optional<ShareLink> findById(ShareLinkId id);

    /**
     * Marks the link identified by {@code id} as revoked at {@code when}.
     *
     * <p>If the ID does not exist, this method is a no-op (caller has already validated
     * the not-found / not-authorised paths at the application layer).
     *
     * @param id   the share-link ID; must not be null
     * @param when the revocation instant; must not be null
     */
    void markRevoked(ShareLinkId id, Instant when);

    /**
     * Removes all links whose {@code expiresAt} is not after {@code now} and that are not
     * already revoked (revoked links survive the sweep until they are explicitly fetched
     * and found to be both revoked and expired — the sweep only removes time-expired links).
     *
     * @param now the reference instant for expiry comparison
     * @return the number of links removed
     */
    int sweepExpired(Instant now);

    /**
     * Returns the total number of {@link ShareLinkStatus#ACTIVE} links at the given instant.
     *
     * @param now reference instant for status derivation
     * @return non-negative count
     */
    long countActive(Instant now);

    /**
     * Returns the number of {@link ShareLinkStatus#ACTIVE} links whose
     * {@code sharerWallId} equals the given value.
     *
     * @param sharerWallId the sharer's wallId; must not be null
     * @param now          reference instant for status derivation
     * @return non-negative count
     */
    int countActiveForSharer(String sharerWallId, Instant now);

    /**
     * Returns the number of {@link ShareLinkStatus#ACTIVE} links whose
     * {@code creatorIp} equals the given IP address.
     *
     * @param ip  the source IP at creation; must not be null
     * @param now reference instant for status derivation
     * @return non-negative count
     */
    int countActiveForIp(String ip, Instant now);

    /**
     * Returns all links for the given sharer, regardless of status.
     *
     * <p>The application layer filters for ACTIVE status using
     * {@link ShareLink#status(Instant)}.
     *
     * @param sharerWallId the sharer's wallId; must not be null
     * @return all stored links for this sharer; never null; may be empty
     * @deprecated Use {@link #listSummaryBySharer(String, Instant)} instead.
     *             This method is retained for the in-memory adapter; the SQLite adapter
     *             cannot reconstruct raw tokens from the hash stored as the primary key,
     *             so it returns an empty list with a deprecation warning (ADR-SQLITE-05).
     */
    @Deprecated(since = "P3-05", forRemoval = false)
    List<ShareLink> findAllBySharer(String sharerWallId);

    /**
     * Returns summary projections for all links belonging to the given sharer,
     * filtered to {@link ShareLinkStatus#ACTIVE} at the given instant.
     *
     * <p>The raw share-link token is NOT included in the summary — it is shown exactly
     * once at creation time. This method is used for the management list view.
     *
     * @param sharerWallId the sharer's wallId; must not be null
     * @param now          reference instant for status derivation
     * @return list of summaries; never null; may be empty
     */
    List<ShareLinkSummary> listSummaryBySharer(String sharerWallId, Instant now);
}
