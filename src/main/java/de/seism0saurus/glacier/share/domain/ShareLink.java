package de.seism0saurus.glacier.share.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Aggregate root for the Share-Link bounded context (ADR-SHARE-01).
 *
 * <p>A {@code ShareLink} grants readonly access to a sharer's wall for up to 7 days.
 * It is created by the sharer, consumed by viewers, and can be revoked by the sharer at
 * any time before expiry.
 *
 * <h2>Invariants</h2>
 * <ol>
 *   <li>{@code expiresAt == createdAt + ttl} — enforced at construction; immutable.</li>
 *   <li>{@code status(now)} is derived: {@code REVOKED} if revoked;
 *       {@code EXPIRED} if {@code !now.isBefore(expiresAt)}; else {@code ACTIVE}.</li>
 *   <li>Revocation requires the calling wallId to match {@code sharerWallId}; throws
 *       {@link ShareLinkRevocationException} otherwise.</li>
 *   <li>Double-revocation with the matching wallId is idempotent — only the first
 *       {@code revokedAt} is stored.</li>
 *   <li>Once {@code revokedAt} is set, {@code status(anyTime) == REVOKED}.</li>
 * </ol>
 *
 * <h2>Security notes</h2>
 * <ul>
 *   <li>{@code sharerWallId} is INTERNAL — it is never serialised to client responses
 *       (NEVER returned in HTTP bodies to viewers or logged raw).</li>
 *   <li>The optional {@code creatorIp} is stored for per-IP cap accounting; it is NEVER
 *       logged in free-form messages — only through {@link de.seism0saurus.glacier.util.LogScrubber#maskIp}.</li>
 * </ul>
 */
public final class ShareLink {

    private final ShareLinkId id;
    private final String sharerWallId;
    private final String creatorIp;   // used for per-IP cap accounting; never exposed
    private final Instant createdAt;
    private final Instant expiresAt;

    // Mutable only via revoke() — guarded by the aggregate's own write methods
    private volatile Instant revokedAt;

    /**
     * Private constructor — use {@link #create} factory method.
     */
    private ShareLink(
            final ShareLinkId id,
            final String sharerWallId,
            final String creatorIp,
            final Instant createdAt,
            final Instant expiresAt) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.sharerWallId = Objects.requireNonNull(sharerWallId, "sharerWallId must not be null");
        this.creatorIp = creatorIp; // nullable — older code paths may not supply it
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
        this.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt must not be null");
        this.revokedAt = null;
    }

    // -------------------------------------------------------------------------
    // Factory
    // -------------------------------------------------------------------------

    /**
     * Reconstitutes a {@code ShareLink} from persistent storage.
     *
     * <p><strong>For persistence reconstitution ONLY</strong> — this is not a domain-creation
     * path. The {@code expiresAt} value is the historically recorded expiry, not derived from
     * the TTL policy. The {@code revokedAt} parameter is null when the link has never been
     * revoked.
     *
     * <p>Callers are restricted to {@code de.seism0saurus.glacier.share.infrastructure}
     * by design — only the persistence adapter should call this method.
     *
     * <p>Null constraints:
     * <ul>
     *   <li>{@code id}, {@code sharerWallId}, {@code createdAt}, {@code expiresAt} must not be null.</li>
     *   <li>{@code creatorIp} may be null — older records and reconstituted aggregates carry
     *       no raw IP (HMAC stored, not the raw IP, per ADR-SQLITE-06).</li>
     *   <li>{@code revokedAt} may be null — null indicates the link has never been revoked.</li>
     * </ul>
     *
     * @param id           the share-link identifier (reconstructed by the caller, not derived from hash)
     * @param sharerWallId the wallId of the sharer; must not be null
     * @param creatorIp    the creator IP at creation time; may be null after DB reconstitution
     * @param createdAt    the historically recorded creation instant; must not be null
     * @param expiresAt    the historically recorded expiry instant; must not be null
     * @param revokedAt    the revocation instant if the link was revoked; null if never revoked
     * @return a reconstituted {@code ShareLink} whose status matches the persisted state
     */
    public static ShareLink fromPersistence(
            final ShareLinkId id,
            final String sharerWallId,
            final String creatorIp,
            final Instant createdAt,
            final Instant expiresAt,
            final Instant revokedAt) {
        ShareLink link = new ShareLink(id, sharerWallId, creatorIp, createdAt, expiresAt);
        if (revokedAt != null) {
            link.revokedAt = revokedAt;
        }
        return link;
    }

    /**
     * Creates a new {@code ShareLink} with the given TTL applied to {@code createdAt}.
     *
     * @param id           opaque share-link identifier generated by {@link SecureRandomTokenGenerator}
     * @param sharerWallId the wallId that owns this link (immutable after creation)
     * @param createdAt    the clock-time at creation (use an injected {@code Clock})
     * @param ttl          the link lifetime (typically 7 days; sourced from
     *                     {@link ShareLinkLifetimePolicy#getTtl()})
     * @return a new {@code ShareLink} in {@link ShareLinkStatus#ACTIVE} state
     */
    public static ShareLink create(
            final ShareLinkId id,
            final String sharerWallId,
            final Instant createdAt,
            final Duration ttl) {
        return new ShareLink(id, sharerWallId, null, createdAt, createdAt.plus(ttl));
    }

    /**
     * Creates a new {@code ShareLink} recording the creator's IP for per-IP cap accounting.
     *
     * @param id           opaque share-link identifier
     * @param sharerWallId the wallId that owns this link
     * @param creatorIp    the remote IP at creation time (stored for cap accounting only)
     * @param createdAt    the clock-time at creation
     * @param ttl          the link lifetime
     * @return a new {@code ShareLink} in {@link ShareLinkStatus#ACTIVE} state
     */
    public static ShareLink create(
            final ShareLinkId id,
            final String sharerWallId,
            final String creatorIp,
            final Instant createdAt,
            final Duration ttl) {
        return new ShareLink(id, sharerWallId, creatorIp, createdAt, createdAt.plus(ttl));
    }

    // -------------------------------------------------------------------------
    // Query methods
    // -------------------------------------------------------------------------

    /**
     * Returns the unique identifier for this link.
     *
     * @return the share-link ID; never null
     */
    public ShareLinkId id() {
        return id;
    }

    /**
     * Returns the wallId of the sharer who created this link.
     *
     * <p><strong>INTERNAL USE ONLY</strong> — never include in HTTP responses to viewers.
     *
     * @return the sharer's wallId; never null
     */
    public String sharerWallId() {
        return sharerWallId;
    }

    /**
     * Returns the IP address that created this link, or empty if not recorded.
     *
     * <p>Used only for per-IP cap accounting in
     * {@link de.seism0saurus.glacier.share.domain.ShareLinkRepository#countActiveForIp}.
     *
     * @return the creator IP, or empty
     */
    public Optional<String> creatorIp() {
        return Optional.ofNullable(creatorIp);
    }

    /**
     * Returns the instant at which this link was created.
     *
     * @return creation time; never null
     */
    public Instant createdAt() {
        return createdAt;
    }

    /**
     * Returns the instant at which this link expires (derived: {@code createdAt + ttl}).
     *
     * @return expiry time; never null
     */
    public Instant expiresAt() {
        return expiresAt;
    }

    /**
     * Returns the instant at which this link was revoked, or empty if not revoked.
     *
     * @return revocation time; empty when the link has not been revoked
     */
    public Optional<Instant> revokedAt() {
        return Optional.ofNullable(revokedAt);
    }

    /**
     * Derives the link status at the given clock instant.
     *
     * <p>Derivation rules (in priority order):
     * <ol>
     *   <li>If {@code revokedAt} is set → {@link ShareLinkStatus#REVOKED} (terminal; overrides expiry).</li>
     *   <li>If {@code !now.isBefore(expiresAt)} → {@link ShareLinkStatus#EXPIRED}.</li>
     *   <li>Otherwise → {@link ShareLinkStatus#ACTIVE}.</li>
     * </ol>
     *
     * @param now the current clock instant; must not be null
     * @return the derived status
     */
    public ShareLinkStatus status(final Instant now) {
        if (revokedAt != null) {
            return ShareLinkStatus.REVOKED;
        }
        if (!now.isBefore(expiresAt)) {
            return ShareLinkStatus.EXPIRED;
        }
        return ShareLinkStatus.ACTIVE;
    }

    // -------------------------------------------------------------------------
    // Command methods
    // -------------------------------------------------------------------------

    /**
     * Revokes this link on behalf of {@code callerWallId}.
     *
     * <p>Invariants enforced:
     * <ul>
     *   <li>The {@code callerWallId} must equal {@code sharerWallId} — otherwise
     *       {@link ShareLinkRevocationException} is thrown.</li>
     *   <li>Double-revocation with the correct wallId is idempotent — only the first
     *       {@code revokedAt} is stored.</li>
     * </ul>
     *
     * @param callerWallId the wallId of the revocation initiator
     * @param when         the clock instant of revocation
     * @throws ShareLinkRevocationException if {@code callerWallId} does not match
     *                                      {@code sharerWallId}
     */
    public synchronized void revoke(final String callerWallId, final Instant when) {
        if (!sharerWallId.equals(callerWallId)) {
            throw new ShareLinkRevocationException(
                    "revocation rejected: caller wallId does not match sharerWallId");
        }
        if (revokedAt == null) {
            revokedAt = Objects.requireNonNull(when, "revocation time must not be null");
        }
        // Idempotent: already revoked — no-op
    }
}
