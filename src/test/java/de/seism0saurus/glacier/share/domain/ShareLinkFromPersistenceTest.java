package de.seism0saurus.glacier.share.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link ShareLink#fromPersistence} static factory method.
 *
 * <p>Verifies three scenarios:
 * <ol>
 *   <li>A non-revoked link is reconstituted with all fields intact and ACTIVE status.</li>
 *   <li>A revoked link is reconstituted with the revocation instant and REVOKED status.</li>
 *   <li>A {@code null} creator IP is accepted (IPs stored as HMAC, not raw, in SQLite).</li>
 * </ol>
 */
class ShareLinkFromPersistenceTest {

    private static final String SHARER_WALL_ID = "sharer-wall-id-fixture-value-0000000000";
    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant EXPIRES = T0.plusSeconds(7 * 86400L);

    private ShareLinkId makeId() {
        return ShareLinkId.fromUrlPath("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA");
    }

    /**
     * A non-revoked link is reconstituted with the correct field values and derives
     * {@link ShareLinkStatus#ACTIVE} when queried before expiry.
     *
     * <p>Arrange: valid id, sharerWallId, creatorIp, createdAt, expiresAt; revokedAt=null.
     * <p>Act:     call {@code ShareLink.fromPersistence(...)}.
     * <p>Assert:  all fields match; status at T0 is ACTIVE; revokedAt is empty.
     */
    @Test
    void fromPersistence_nonRevokedLink_isActive() {
        ShareLinkId id = makeId();

        ShareLink link = ShareLink.fromPersistence(id, SHARER_WALL_ID, "10.0.0.1", T0, EXPIRES, null);

        assertThat(link.id()).isEqualTo(id);
        assertThat(link.sharerWallId()).isEqualTo(SHARER_WALL_ID);
        assertThat(link.creatorIp()).isPresent().contains("10.0.0.1");
        assertThat(link.createdAt()).isEqualTo(T0);
        assertThat(link.expiresAt()).isEqualTo(EXPIRES);
        assertThat(link.revokedAt()).isEmpty();
        assertThat(link.status(T0)).isEqualTo(ShareLinkStatus.ACTIVE);
    }

    /**
     * A revoked link is reconstituted with the revocation instant and derives
     * {@link ShareLinkStatus#REVOKED} at any query time.
     *
     * <p>Arrange: valid fields; revokedAt set one minute after creation.
     * <p>Act:     call {@code ShareLink.fromPersistence(...)}.
     * <p>Assert:  revokedAt is present; status is REVOKED regardless of time.
     */
    @Test
    void fromPersistence_revokedLink_isRevoked() {
        ShareLinkId id = makeId();
        Instant revokedAt = T0.plusSeconds(60);

        ShareLink link = ShareLink.fromPersistence(id, SHARER_WALL_ID, null, T0, EXPIRES, revokedAt);

        assertThat(link.revokedAt()).isPresent().contains(revokedAt);
        // REVOKED takes precedence over ACTIVE and EXPIRED
        assertThat(link.status(T0)).isEqualTo(ShareLinkStatus.REVOKED);
        assertThat(link.status(EXPIRES.plusSeconds(1))).isEqualTo(ShareLinkStatus.REVOKED);
    }

    /**
     * A {@code null} creator IP is accepted — the SQLite adapter stores only the HMAC
     * and cannot reconstitute the raw IP.
     *
     * <p>Arrange: creatorIp=null; all other fields valid.
     * <p>Act:     call {@code ShareLink.fromPersistence(...)}.
     * <p>Assert:  link is created without exception; {@code creatorIp()} is empty.
     */
    @Test
    void fromPersistence_nullCreatorIp_isAccepted() {
        ShareLinkId id = makeId();

        ShareLink link = ShareLink.fromPersistence(id, SHARER_WALL_ID, null, T0, EXPIRES, null);

        assertThat(link.creatorIp()).isEmpty();
        assertThat(link.status(T0)).isEqualTo(ShareLinkStatus.ACTIVE);
    }
}
