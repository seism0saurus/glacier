package de.seism0saurus.glacier.share.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link ShareLinkSummary} record construction and accessor coverage.
 *
 * <p>The record is a thin data carrier; these tests verify all fields are accessible via
 * canonical record accessors and that null {@code revokedAt} is handled correctly.
 */
class ShareLinkSummaryTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant T1 = T0.plusSeconds(3600);
    private static final Instant T7 = T0.plusSeconds(7 * 86400L);

    /**
     * All fields are set; all accessors return the expected values.
     *
     * <p>Arrange: full summary with all fields non-null.
     * <p>Act:     access each field via canonical accessor.
     * <p>Assert:  each accessor returns the construction argument.
     */
    @Test
    void allFieldsAccessible_whenFullySuppliedd() {
        ShareLinkSummary summary = new ShareLinkSummary(
                "abcd1234",
                T0,
                T7,
                T1,
                ShareLinkStatus.REVOKED,
                "sharer-wall-id-fixture-value");

        assertThat(summary.idHash8()).isEqualTo("abcd1234");
        assertThat(summary.createdAt()).isEqualTo(T0);
        assertThat(summary.expiresAt()).isEqualTo(T7);
        assertThat(summary.revokedAt()).isEqualTo(T1);
        assertThat(summary.status()).isEqualTo(ShareLinkStatus.REVOKED);
        assertThat(summary.sharerWallId()).isEqualTo("sharer-wall-id-fixture-value");
    }

    /**
     * A non-revoked summary has {@code null} for {@code revokedAt}.
     *
     * <p>Arrange: summary with {@code revokedAt = null}.
     * <p>Act:     access {@code revokedAt}.
     * <p>Assert:  returns null; status is ACTIVE.
     */
    @Test
    void revokedAt_isNull_forNonRevokedSummary() {
        ShareLinkSummary summary = new ShareLinkSummary(
                "ef012345",
                T0,
                T7,
                null,
                ShareLinkStatus.ACTIVE,
                "sharer-wall-id-fixture-value");

        assertThat(summary.revokedAt()).isNull();
        assertThat(summary.status()).isEqualTo(ShareLinkStatus.ACTIVE);
    }

    /**
     * An expired summary has {@code revokedAt = null} and status EXPIRED.
     *
     * <p>Arrange: summary with past {@code expiresAt} and status EXPIRED.
     * <p>Act:     access {@code status} and {@code revokedAt}.
     * <p>Assert:  status is EXPIRED; revokedAt is null.
     */
    @Test
    void status_isExpired_forExpiredSummary() {
        Instant pastExpiry = T0.minusSeconds(1);
        ShareLinkSummary summary = new ShareLinkSummary(
                "dead0000",
                T0.minusSeconds(7 * 86400L + 1),
                pastExpiry,
                null,
                ShareLinkStatus.EXPIRED,
                "sharer-wall-id-fixture-value");

        assertThat(summary.status()).isEqualTo(ShareLinkStatus.EXPIRED);
        assertThat(summary.revokedAt()).isNull();
    }

    /**
     * Record equality: two summaries with the same values are equal.
     *
     * <p>Arrange: two identically-constructed summaries.
     * <p>Act:     compare with equals.
     * <p>Assert:  equal and same hashCode.
     */
    @Test
    void equalSummaries_areEqual() {
        ShareLinkSummary s1 = new ShareLinkSummary(
                "abcd1234", T0, T7, null, ShareLinkStatus.ACTIVE, "wall-id");
        ShareLinkSummary s2 = new ShareLinkSummary(
                "abcd1234", T0, T7, null, ShareLinkStatus.ACTIVE, "wall-id");

        assertThat(s1).isEqualTo(s2);
        assertThat(s1.hashCode()).isEqualTo(s2.hashCode());
    }

    /**
     * Record inequality: summaries with different {@code idHash8} are not equal.
     *
     * <p>Arrange: two summaries differing only in {@code idHash8}.
     * <p>Act:     compare with equals.
     * <p>Assert:  not equal.
     */
    @Test
    void summariesWithDifferentIdHash8_areNotEqual() {
        ShareLinkSummary s1 = new ShareLinkSummary(
                "aaaa0000", T0, T7, null, ShareLinkStatus.ACTIVE, "wall-id");
        ShareLinkSummary s2 = new ShareLinkSummary(
                "bbbb1111", T0, T7, null, ShareLinkStatus.ACTIVE, "wall-id");

        assertThat(s1).isNotEqualTo(s2);
    }
}
