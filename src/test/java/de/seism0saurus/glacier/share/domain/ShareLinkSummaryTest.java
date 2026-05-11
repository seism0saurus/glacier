package de.seism0saurus.glacier.share.domain;

import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Unit tests for {@link ShareLinkSummary} (SR-SQLITE-20; ADR-SQLITE-05).
 *
 * <p>Verifies:
 * <ol>
 *   <li>The record's component names do not include sensitive token-bearing names.</li>
 *   <li>Null {@code revokedAt} is accepted without exception (nullable by contract).</li>
 * </ol>
 */
class ShareLinkSummaryTest {

    private static final Instant T0 = Instant.parse("2025-06-01T00:00:00Z");
    private static final String SHARER_WALL_ID = "sharer-wall-id-fixture-summary-00";

    /**
     * SR-SQLITE-20 structural assertion: none of the record components may be named
     * {@code id}, {@code token}, {@code secret}, {@code url}, or {@code readonlyUrl}
     * (case-insensitive).
     *
     * <p>This mirrors the ArchUnit rule in {@code ShareLinkPersistenceArchitectureTest}
     * but provides a self-documenting in-package assertion that runs even before
     * ArchUnit byte-code scanning.
     *
     * <p>Arrange: load the record's component names via reflection.
     * <p>Act:     extract component names; check against the forbidden-names set.
     * <p>Assert:  no component name is in the forbidden set (case-insensitive).
     */
    @Test
    void summaryRecord_doesNotContainSensitiveTokenFieldNames() {
        Set<String> forbiddenNames = Set.of("id", "token", "secret", "url", "readonlyurl");

        RecordComponent[] components = ShareLinkSummary.class.getRecordComponents();
        Set<String> componentNamesLower = Arrays.stream(components)
                .map(c -> c.getName().toLowerCase())
                .collect(Collectors.toSet());

        // Find any intersection between component names and forbidden names
        Set<String> violations = componentNamesLower.stream()
                .filter(forbiddenNames::contains)
                .collect(Collectors.toSet());

        assertThat(violations)
                .as("ShareLinkSummary must not have field names id/token/secret/url/readonlyUrl "
                        + "(SR-SQLITE-20, ADR-SQLITE-05 — raw token must not appear in the list view)")
                .isEmpty();
    }

    /**
     * Null {@code revokedAt} must be accepted — links that have never been revoked
     * carry a null revocation instant by contract.
     *
     * <p>Arrange: valid values for all non-nullable fields; null for {@code revokedAt}.
     * <p>Act:     construct the record.
     * <p>Assert:  no exception; {@code revokedAt()} returns null.
     */
    @Test
    void summaryWithNullRevokedAt_isCreatedWithoutException() {
        assertThatCode(() -> {
            ShareLinkSummary summary = new ShareLinkSummary(
                    "abcd1234",
                    T0,
                    T0.plusSeconds(86400 * 7),
                    null, // revokedAt — nullable
                    ShareLinkStatus.ACTIVE,
                    SHARER_WALL_ID);
            assertThat(summary.revokedAt()).isNull();
        })
                .as("ShareLinkSummary must accept null revokedAt without throwing")
                .doesNotThrowAnyException();
    }

    /**
     * All supplied field values are accessible via the record's accessor methods.
     *
     * <p>Arrange: distinct non-null values for all fields including revokedAt.
     * <p>Act:     construct the record.
     * <p>Assert:  each accessor returns the corresponding supplied value.
     */
    @Test
    void summaryRecord_accessorsReturnSuppliedValues() {
        String idHash8 = "deadbeef";
        Instant createdAt = T0;
        Instant expiresAt = T0.plusSeconds(86400 * 7);
        Instant revokedAt = T0.plusSeconds(3600);

        ShareLinkSummary summary = new ShareLinkSummary(
                idHash8, createdAt, expiresAt, revokedAt, ShareLinkStatus.REVOKED, SHARER_WALL_ID);

        assertThat(summary.idHash8()).isEqualTo(idHash8);
        assertThat(summary.createdAt()).isEqualTo(createdAt);
        assertThat(summary.expiresAt()).isEqualTo(expiresAt);
        assertThat(summary.revokedAt()).isEqualTo(revokedAt);
        assertThat(summary.status()).isEqualTo(ShareLinkStatus.REVOKED);
        assertThat(summary.sharerWallId()).isEqualTo(SHARER_WALL_ID);
    }
}
