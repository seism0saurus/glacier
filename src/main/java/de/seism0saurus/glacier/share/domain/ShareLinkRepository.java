package de.seism0saurus.glacier.share.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

// ShareLinkSummary is in the same package (share.domain) — no cross-layer import needed

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
     * Marks the link whose {@code idHash8} matches (first 8 hex chars of SHA-256(token))
     * AND that belongs to {@code sharerWallId} as revoked at {@code when}, scoped to the
     * authenticated sharer.
     *
     * <p>This is the revoke path for the sharer's self-management list, which only ever sees
     * the non-secret {@code idHash8} — the raw token is shown exactly once at creation and is
     * never re-served (ADR-SQLITE-05). The {@code sharerWallId} scope is the authorization
     * boundary: a sharer can only revoke their own links. Anti-enumeration (T-07) is preserved
     * by returning {@code false} (rather than throwing) for not-found, not-owned, and
     * already-revoked alike, so the caller maps all three to the same outcome.
     *
     * @param idHash8      the first 8 lowercase hex chars of SHA-256(token); the caller is
     *                     responsible for format validation
     * @param sharerWallId the authenticated sharer's wallId; the authorization scope
     * @param when         the revocation instant
     * @return {@code true} if exactly one active link was transitioned to revoked;
     *         {@code false} for not-found, not-owned, or already-revoked
     */
    boolean markRevokedByHash8(String idHash8, String sharerWallId, Instant when);

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
     * Returns a summary projection for all links belonging to the given sharer.
     *
     * <p>Used by the sharer's self-management UI. Does NOT return the raw token — the
     * share URL is shown exactly once at creation (ADR-SQLITE-05). The SQLite adapter
     * stores only SHA-256(token) and cannot reconstruct the original token; the summary
     * exposes {@code idHash8} (first 8 hex chars of the hash) as a visual identifier.
     *
     * @param sharerWallId the sharer's wallId; must not be null
     * @param now          reference instant for {@link ShareLinkStatus} computation
     * @return list of summaries ordered by creation time descending; never null; may be empty
     */
    List<ShareLinkSummary> listSummaryBySharer(String sharerWallId, Instant now);

    /**
     * Returns all links for the given sharer, regardless of status.
     *
     * <p>The application layer filters for ACTIVE status using
     * {@link ShareLink#status(Instant)}.
     *
     * @param sharerWallId the sharer's wallId; must not be null
     * @return all stored links for this sharer; never null; may be empty
     * @deprecated Use {@link #listSummaryBySharer(String, Instant)} instead.
     *             This method returns full aggregates including the raw share-link token,
     *             which is incompatible with at-rest token hashing (ADR-SQLITE-04).
     */
    @Deprecated
    List<ShareLink> findAllBySharer(String sharerWallId);
}
