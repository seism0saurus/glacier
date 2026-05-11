package de.seism0saurus.glacier.share.domain;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link ShareLink#fromPersistence} factory (ADR-SQLITE-08).
 *
 * <p>This factory is the only domain-layer reconstitution path from persistent storage.
 * All invariants of the created aggregate must hold as if the link were created via
 * {@link ShareLink#create}.
 *
 * <p>Three scenario groups are tested:
 * <ol>
 *   <li>Revoked reconstitution — {@code revokedAt != null} must yield {@link ShareLinkStatus#REVOKED}.</li>
 *   <li>Null {@code revokedAt} — status depends purely on {@code expiresAt}.</li>
 *   <li>Null-argument rejection — required fields must not accept null.</li>
 * </ol>
 */
class ShareLinkFromPersistenceTest {

    private static final Instant T0 = Instant.parse("2025-06-01T00:00:00Z");
    private static final Duration TTL = Duration.ofDays(7);
    private static final String SHARER_WALL_ID = "sharer-wall-id-fixture-persisted-000";

    private ShareLinkId anyId() {
        return ShareLinkId.fromUrlPath("PERSISTED1PERSISTED1PERSISTED1PERSISTED1PER");
    }

    // -------------------------------------------------------------------------
    // 3a-1: revokedAt set → status is always REVOKED
    // -------------------------------------------------------------------------

    /**
     * Reconstituting a link with a non-null {@code revokedAt} must yield
     * {@link ShareLinkStatus#REVOKED} regardless of the current clock instant.
     *
     * <p>Arrange: {@code revokedAt = T0}, {@code expiresAt} far in the future.
     * <p>Act:     call {@code fromPersistence(..., revokedAt = T0)}.
     * <p>Assert:  {@code status(T0)} and {@code status(T0 + 1 year)} both return REVOKED.
     */
    @Test
    void fromPersistence_carriesRevokedAtIntoStatusDerivation() {
        Instant createdAt = T0.minus(Duration.ofDays(1));
        Instant expiresAt = T0.plus(TTL);
        Instant revokedAt = T0;

        ShareLink link = ShareLink.fromPersistence(anyId(), SHARER_WALL_ID, null, createdAt, expiresAt, revokedAt);

        assertThat(link.status(T0))
                .as("Status should be REVOKED immediately after revokedAt (invariant 5)")
                .isEqualTo(ShareLinkStatus.REVOKED);
        assertThat(link.status(T0.plus(Duration.ofDays(365))))
                .as("Status should remain REVOKED indefinitely once revoked (terminal state)")
                .isEqualTo(ShareLinkStatus.REVOKED);
        assertThat(link.revokedAt())
                .as("revokedAt() should return the supplied revocation instant")
                .hasValue(revokedAt);
    }

    // -------------------------------------------------------------------------
    // 3a-2: null revokedAt → status depends on expiresAt
    // -------------------------------------------------------------------------

    /**
     * When {@code revokedAt} is null and {@code expiresAt} is in the future,
     * the reconstituted link must be ACTIVE.
     *
     * <p>When {@code expiresAt} is in the past (relative to {@code now}),
     * the reconstituted link must be EXPIRED.
     *
     * <p>Arrange: future expiry for ACTIVE; past expiry for EXPIRED.
     * <p>Act:     call {@code fromPersistence(..., revokedAt = null)}.
     * <p>Assert:  ACTIVE when future, EXPIRED when past.
     */
    @Test
    void fromPersistence_nullRevokedAt_yieldsActiveOrExpired() {
        Instant futureExpiry = T0.plus(TTL);
        ShareLink activeLink = ShareLink.fromPersistence(anyId(), SHARER_WALL_ID, null, T0, futureExpiry, null);

        assertThat(activeLink.status(T0))
                .as("Link with future expiresAt and no revocation must be ACTIVE")
                .isEqualTo(ShareLinkStatus.ACTIVE);
        assertThat(activeLink.revokedAt())
                .as("revokedAt() must be empty when null was supplied")
                .isEmpty();

        Instant pastExpiry = T0.minus(Duration.ofSeconds(1));
        ShareLinkId otherId = ShareLinkId.fromUrlPath("PERSISTED2PERSISTED2PERSISTED2PERSISTED2PER");
        ShareLink expiredLink = ShareLink.fromPersistence(otherId, SHARER_WALL_ID, null,
                T0.minus(TTL).minus(Duration.ofDays(1)), pastExpiry, null);

        assertThat(expiredLink.status(T0))
                .as("Link with past expiresAt and no revocation must be EXPIRED")
                .isEqualTo(ShareLinkStatus.EXPIRED);
    }

    // -------------------------------------------------------------------------
    // 3a-3: null arguments — required fields reject null
    // -------------------------------------------------------------------------

    /**
     * {@code id}, {@code sharerWallId}, {@code createdAt}, and {@code expiresAt} are
     * required; passing null for any of them must throw {@link NullPointerException}.
     *
     * <p>Null {@code creatorIp} and null {@code revokedAt} are explicitly allowed.
     *
     * <p>Arrange: valid values for all other fields; one field set to null at a time.
     * <p>Act:     call {@code fromPersistence} with the null argument.
     * <p>Assert:  {@link NullPointerException} for each required field.
     */
    @Test
    void fromPersistence_nullArgumentsRejected() {
        Instant createdAt = T0;
        Instant expiresAt = T0.plus(TTL);

        // null id → NullPointerException
        assertThatThrownBy(() -> ShareLink.fromPersistence(null, SHARER_WALL_ID, null, createdAt, expiresAt, null))
                .as("null id must throw NullPointerException")
                .isInstanceOf(NullPointerException.class);

        // null sharerWallId → NullPointerException
        assertThatThrownBy(() -> ShareLink.fromPersistence(anyId(), null, null, createdAt, expiresAt, null))
                .as("null sharerWallId must throw NullPointerException")
                .isInstanceOf(NullPointerException.class);

        // null createdAt → NullPointerException
        assertThatThrownBy(() -> ShareLink.fromPersistence(anyId(), SHARER_WALL_ID, null, null, expiresAt, null))
                .as("null createdAt must throw NullPointerException")
                .isInstanceOf(NullPointerException.class);

        // null expiresAt → NullPointerException
        assertThatThrownBy(() -> ShareLink.fromPersistence(anyId(), SHARER_WALL_ID, null, createdAt, null, null))
                .as("null expiresAt must throw NullPointerException")
                .isInstanceOf(NullPointerException.class);
    }

    /**
     * Null {@code creatorIp} is allowed — older records and reconstituted aggregates
     * carry no raw IP (HMAC stored, not the raw IP, per ADR-SQLITE-06).
     *
     * <p>Null {@code revokedAt} is allowed — it indicates the link has never been revoked.
     *
     * <p>Arrange: valid required fields; null for both optional fields.
     * <p>Act:     call {@code fromPersistence}.
     * <p>Assert:  no exception; {@code creatorIp()} returns empty; {@code revokedAt()} empty.
     */
    @Test
    void fromPersistence_nullCreatorIpAndNullRevokedAt_areAccepted() {
        ShareLink link = ShareLink.fromPersistence(anyId(), SHARER_WALL_ID, null,
                T0, T0.plus(TTL), null);

        assertThat(link.creatorIp())
                .as("null creatorIp must be accepted and return empty Optional")
                .isEmpty();
        assertThat(link.revokedAt())
                .as("null revokedAt must be accepted and return empty Optional")
                .isEmpty();
    }

    /**
     * Fields supplied to {@code fromPersistence} must be preserved verbatim.
     *
     * <p>Arrange: distinctive values for all fields.
     * <p>Act:     call {@code fromPersistence}.
     * <p>Assert:  each accessor returns the corresponding supplied value.
     */
    @Test
    void fromPersistence_preservesAllSuppliedFields() {
        ShareLinkId id = anyId();
        String creatorIp = "10.20.30.40";
        Instant createdAt = T0;
        Instant expiresAt = T0.plus(TTL);

        ShareLink link = ShareLink.fromPersistence(id, SHARER_WALL_ID, creatorIp, createdAt, expiresAt, null);

        assertThat(link.id()).isEqualTo(id);
        assertThat(link.sharerWallId()).isEqualTo(SHARER_WALL_ID);
        assertThat(link.creatorIp()).hasValue(creatorIp);
        assertThat(link.createdAt()).isEqualTo(createdAt);
        assertThat(link.expiresAt()).isEqualTo(expiresAt);
    }
}
