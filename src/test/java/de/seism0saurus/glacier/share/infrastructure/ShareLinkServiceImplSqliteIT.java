package de.seism0saurus.glacier.share.infrastructure;

import de.seism0saurus.glacier.share.application.ShareLinkService;
import de.seism0saurus.glacier.share.domain.ShareLink;
import de.seism0saurus.glacier.share.domain.ShareLinkSummary;
import de.seism0saurus.glacier.share.domain.ShareLinkStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Full Spring context integration test for {@link ShareLinkService} backed by the
 * in-memory SQLite adapter ({@code glacier.share.db.path=:memory:}).
 *
 * <p>This test starts a full Spring application context with the SQLite adapter active,
 * then exercises the key share-link lifecycle operations through the service layer to
 * confirm correct end-to-end wiring.
 *
 * <p>Scenarios: create → listSummaryBySharer → revoke → listSummaryBySharer (empty).
 *
 * <p>References: ADR-SQLITE-01; test plan §ShareLinkServiceImplSqliteIT.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "glacier.share.db.path=:memory:",
        "glacier.share.db.ip-hmac-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "mastodon.instance=mastodon.social",
        "mastodon.accessToken=dummy",
        "mastodon.handle=glacier@mastodon.social",
        "glacier.domain=glacier.example.com",
        "glacier.operator.name=Test",
        "glacier.operator.streetAndNumber=Test 1",
        "glacier.operator.zipcode=12345",
        "glacier.operator.city=Test",
        "glacier.operator.country=Test",
        "glacier.operator.phone=+1",
        "glacier.operator.mail=test@test.com",
        "glacier.operator.website=test.com"
})
class ShareLinkServiceImplSqliteIT {

    private static final String SHARER_WALL_ID = "sharer-wall-id-sqlite-it-0000000000000";
    private static final String SHARER_IP = "10.10.10.10";

    @Autowired
    private ShareLinkService shareLinkService;

    /**
     * Create a link → list summary → verify summary fields.
     *
     * <p>Arrange: fresh SQLite in-memory context.
     * <p>Act:     create a link, then call listSummaryBySharer.
     * <p>Assert:  one summary returned; status ACTIVE; sharerWallId matches; no raw token.
     */
    @Test
    void create_thenListSummary_returnsSummaryWithoutRawToken() {
        Instant now = Instant.parse("2025-06-01T00:00:00Z");

        ShareLink created = shareLinkService.create(SHARER_WALL_ID, SHARER_IP, now);
        assertThat(created).isNotNull();
        assertThat(created.status(now)).isEqualTo(ShareLinkStatus.ACTIVE);

        var summaries = shareLinkService.listSummaryBySharer(SHARER_WALL_ID, now);

        assertThat(summaries).hasSize(1);
        ShareLinkSummary summary = summaries.get(0);
        assertThat(summary.status()).isEqualTo(ShareLinkStatus.ACTIVE);
        assertThat(summary.sharerWallId()).isEqualTo(SHARER_WALL_ID);
        assertThat(summary.idHash8())
                .as("idHash8 must be an 8-char hex string, not the raw token")
                .matches("[0-9a-f]{8}");
        assertThat(summary.idHash8())
                .as("idHash8 must not equal the raw token value")
                .isNotEqualTo(created.id().value());
    }

    /**
     * Create → revoke → list summary must return empty.
     *
     * <p>Arrange: create a link; revoke it.
     * <p>Act:     listSummaryBySharer after revocation.
     * <p>Assert:  empty list (revoked links are excluded from ACTIVE summary).
     */
    @Test
    void create_revoke_thenListSummary_returnsEmpty() {
        Instant now = Instant.parse("2025-06-02T00:00:00Z");

        ShareLink created = shareLinkService.create(SHARER_WALL_ID + "-revoke-test", SHARER_IP, now);
        shareLinkService.revoke(created.id(), SHARER_WALL_ID + "-revoke-test", now.plusSeconds(60));

        var summaries = shareLinkService.listSummaryBySharer(SHARER_WALL_ID + "-revoke-test",
                now.plusSeconds(120));

        assertThat(summaries)
                .as("listSummaryBySharer must return empty after all links are revoked")
                .isEmpty();
    }
}
