package de.seism0saurus.glacier.share.domain;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for the {@link ShareLink} aggregate root.
 *
 * <p>Verifies domain invariants as documented on the aggregate:
 * <ol>
 *   <li>{@code expiresAt == createdAt + 7d}.</li>
 *   <li>{@code status(now)} returns {@code ACTIVE}, {@code EXPIRED}, or {@code REVOKED}
 *       according to the clock position.</li>
 *   <li>Revocation requires the calling {@code wallId} to match {@code sharerWallId}.</li>
 *   <li>Double-revocation is idempotent.</li>
 *   <li>Once revoked, {@code status(anyTime)} returns {@code REVOKED} regardless of TTL.</li>
 * </ol>
 *
 * <p>All tests use {@link Instant} values derived from a fixed reference point
 * ({@code T0 = 2025-01-01T00:00:00Z}) so TTL boundary crossings are deterministic.
 */
class ShareLinkTest {

    private static final Instant T0 = Instant.parse("2025-01-01T00:00:00Z");
    private static final Duration TTL = Duration.ofDays(7);
    private static final Instant T_EXPIRES = T0.plus(TTL);
    private static final Instant T_AFTER_EXPIRY = T_EXPIRES.plusSeconds(1);

    private static final String SHARER_WALL_ID = "sharer-wall-id-fixture-value-000";

    private ShareLinkId anyShareLinkId() {
        return new ShareLinkId("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA");
    }

    private ShareLink createActiveLink() {
        return ShareLink.create(anyShareLinkId(), SHARER_WALL_ID, T0, TTL);
    }

    // ---------------------------------------------------------------------------
    // Invariant 1 — expiresAt == createdAt + 7d
    // ---------------------------------------------------------------------------

    @Test
    void expiresAtIsExactlyCreatedAtPlusTtl() {
        // Arrange + Act
        ShareLink link = createActiveLink();
        // Assert
        assertThat(link.expiresAt()).isEqualTo(T0.plus(TTL));
    }

    // ---------------------------------------------------------------------------
    // Invariant 2 — status(now) derivation
    // ---------------------------------------------------------------------------

    @Test
    void statusIsActiveWhenNowIsBeforeExpiry() {
        ShareLink link = createActiveLink();
        assertThat(link.status(T0)).isEqualTo(ShareLinkStatus.ACTIVE);
    }

    @Test
    void statusIsActiveAtExactCreationTime() {
        ShareLink link = createActiveLink();
        assertThat(link.status(T0)).isEqualTo(ShareLinkStatus.ACTIVE);
    }

    @Test
    void statusIsActiveOneInstantBeforeExpiry() {
        ShareLink link = createActiveLink();
        Instant justBefore = T_EXPIRES.minusNanos(1);
        assertThat(link.status(justBefore)).isEqualTo(ShareLinkStatus.ACTIVE);
    }

    @Test
    void statusIsExpiredAtExactExpiryInstant() {
        // The invariant is: EXPIRED when now.isAfter(expiresAt) OR now.equals(expiresAt)
        ShareLink link = createActiveLink();
        assertThat(link.status(T_EXPIRES)).isEqualTo(ShareLinkStatus.EXPIRED);
    }

    @Test
    void statusIsExpiredAfterExpiry() {
        ShareLink link = createActiveLink();
        assertThat(link.status(T_AFTER_EXPIRY)).isEqualTo(ShareLinkStatus.EXPIRED);
    }

    // ---------------------------------------------------------------------------
    // Invariant 3 — revocation requires matching sharerWallId
    // ---------------------------------------------------------------------------

    @Test
    void revokeWithMatchingWallIdSucceeds() {
        ShareLink link = createActiveLink();
        // Act
        link.revoke(SHARER_WALL_ID, T0.plusSeconds(60));
        // Assert
        assertThat(link.status(T0.plusSeconds(60))).isEqualTo(ShareLinkStatus.REVOKED);
    }

    @Test
    void revokeWithWrongWallIdThrowsException() {
        ShareLink link = createActiveLink();
        assertThatThrownBy(() -> link.revoke("wrong-wall-id-value-that-does-not-match", T0.plusSeconds(60)))
                .isInstanceOf(ShareLinkRevocationException.class);
    }

    @Test
    void revokedAtIsSetToTheInstantSupplied() {
        ShareLink link = createActiveLink();
        Instant revokeTime = T0.plusSeconds(3600);
        link.revoke(SHARER_WALL_ID, revokeTime);
        assertThat(link.revokedAt()).hasValue(revokeTime);
    }

    // ---------------------------------------------------------------------------
    // Invariant 4 — double-revocation is idempotent
    // ---------------------------------------------------------------------------

    @Test
    void doubleRevokeWithSameWallIdIsIdempotent() {
        ShareLink link = createActiveLink();
        Instant first = T0.plusSeconds(60);
        Instant second = T0.plusSeconds(120);
        link.revoke(SHARER_WALL_ID, first);
        // Second revoke should not throw
        link.revoke(SHARER_WALL_ID, second);
        // revokedAt stays at the first revocation time
        assertThat(link.revokedAt()).hasValue(first);
    }

    // ---------------------------------------------------------------------------
    // Invariant 5 — REVOKED is terminal; overrides TTL
    // ---------------------------------------------------------------------------

    @Test
    void statusIsRevokedEvenAfterExpiryWhenRevoked() {
        ShareLink link = createActiveLink();
        link.revoke(SHARER_WALL_ID, T0.plusSeconds(60));
        // Even well past the expiry time, REVOKED wins
        assertThat(link.status(T_AFTER_EXPIRY)).isEqualTo(ShareLinkStatus.REVOKED);
    }

    // ---------------------------------------------------------------------------
    // Immutability — sharerWallId is set at creation and never changes
    // ---------------------------------------------------------------------------

    @Test
    void sharerWallIdIsPreservedFromCreation() {
        ShareLink link = createActiveLink();
        assertThat(link.sharerWallId()).isEqualTo(SHARER_WALL_ID);
    }

    @Test
    void idIsPreservedFromCreation() {
        ShareLinkId id = anyShareLinkId();
        ShareLink link = ShareLink.create(id, SHARER_WALL_ID, T0, TTL);
        assertThat(link.id()).isEqualTo(id);
    }
}
