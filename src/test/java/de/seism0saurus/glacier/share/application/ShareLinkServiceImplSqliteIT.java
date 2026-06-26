package de.seism0saurus.glacier.share.application;

import de.seism0saurus.glacier.share.domain.ShareLinkSummary;
import de.seism0saurus.glacier.share.domain.ShareLinkStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import social.bigbone.MastodonClient;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Full Spring context integration test for {@link ShareLinkServiceImpl} wired with the
 * SQLite adapter ({@code glacier.share.db.path=:memory:}).
 *
 * <p>Verifies the end-to-end flow through the SQLite adapter:
 * <ul>
 *   <li>{@link ShareLinkService#create} persists a link to SQLite.</li>
 *   <li>{@link ShareLinkService#listSummaryBySharer} returns the summary projection
 *       with the correct status.</li>
 *   <li>{@link ShareLinkService#revoke} marks the link revoked; subsequent
 *       {@code listSummaryBySharer} reflects the REVOKED status.</li>
 * </ul>
 *
 * <p>References: R-05; ADR-SQLITE-01; SR-SQLITE-01; SR-SQLITE-04.
 */
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
@TestPropertySource(properties = {
        "glacier.domain=glacier.example.com",
        "glacier.cookie.secure=false",
        "glacier.fallback.enabled=true",
        "mastodon.instance=mastodon.social",
        "mastodon.handle=@glacier@mastodon.social",
        "mastodon.accessToken=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
        "glacier.share.maxActivePerSharer=10",
        "glacier.share.maxActivePerIp=20",
        "glacier.share.globalMax=1000",
        "glacier.share.maxViewersPerLink=100",
        // SQLite in-memory adapter (activates SqliteShareLinkRepository)
        "glacier.share.db.path=:memory:",
        "glacier.share.db.ip-hmac-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
})
class ShareLinkServiceImplSqliteIT {

    private static final String SHARER_WALL_ID = "sharer-wall-id-fixture-0000000000000000";
    private static final String SHARER_IP = "10.0.0.1";

    @Autowired
    private ShareLinkService shareLinkService;

    @Autowired
    private Clock clock;

    @MockitoBean
    private MastodonClient mastodonClient;

    /**
     * Creating a link and calling listSummaryBySharer returns a summary with ACTIVE status.
     *
     * <p>Arrange: empty SQLite in-memory DB.
     * <p>Act:     create a link; call listSummaryBySharer.
     * <p>Assert:  one summary; status ACTIVE; idHash8 is 8 hex chars.
     */
    @Test
    void create_thenListSummaryBySharer_returnsActiveSummary() {
        Instant now = clock.instant();

        shareLinkService.create(SHARER_WALL_ID, SHARER_IP, now);

        List<ShareLinkSummary> summaries = shareLinkService.listSummaryBySharer(SHARER_WALL_ID, now);

        assertThat(summaries).hasSize(1);
        ShareLinkSummary summary = summaries.get(0);
        assertThat(summary.status()).isEqualTo(ShareLinkStatus.ACTIVE);
        assertThat(summary.idHash8())
                .as("idHash8 must be exactly 8 hex characters")
                .matches("[0-9a-f]{8}");
        assertThat(summary.sharerWallId()).isEqualTo(SHARER_WALL_ID);
        assertThat(summary.revokedAt()).isNull();
    }

    /**
     * Revoking a link changes its status to REVOKED in the summary.
     *
     * <p>Arrange: create a link; note its raw ID from the create response.
     * <p>Act:     revoke it; call listSummaryBySharer.
     * <p>Assert:  summary status is REVOKED; revokedAt is set.
     */
    @Test
    void revoke_thenListSummaryBySharer_returnsRevokedSummary() {
        Instant now = clock.instant();

        var link = shareLinkService.create(SHARER_WALL_ID, SHARER_IP, now);
        Instant revokedAt = now.plusSeconds(10);
        shareLinkService.revoke(link.id(), SHARER_WALL_ID, revokedAt);

        List<ShareLinkSummary> summaries = shareLinkService.listSummaryBySharer(SHARER_WALL_ID, revokedAt.plusSeconds(1));

        assertThat(summaries).hasSize(1);
        assertThat(summaries.get(0).status()).isEqualTo(ShareLinkStatus.REVOKED);
        assertThat(summaries.get(0).revokedAt()).isNotNull();
    }

    /**
     * Multiple links for the same sharer all appear in listSummaryBySharer.
     *
     * <p>Arrange: create 3 links for the same sharer.
     * <p>Act:     call listSummaryBySharer.
     * <p>Assert:  all 3 summaries returned; all ACTIVE.
     */
    @Test
    void multipleLinksSameSharer_allAppearsInSummary() {
        Instant now = clock.instant();

        shareLinkService.create(SHARER_WALL_ID, SHARER_IP, now);
        shareLinkService.create(SHARER_WALL_ID, SHARER_IP, now);
        shareLinkService.create(SHARER_WALL_ID, SHARER_IP, now);

        List<ShareLinkSummary> summaries = shareLinkService.listSummaryBySharer(SHARER_WALL_ID, now);

        assertThat(summaries).hasSize(3);
        assertThat(summaries).allSatisfy(s ->
                assertThat(s.status()).isEqualTo(ShareLinkStatus.ACTIVE));
    }

    /**
     * Revoking by the non-secret {@code idHash8} through the full wiring (real service + registry +
     * SQLite adapter) marks the link REVOKED. This is the integration boundary the unit tests can
     * only mock (registry + repository): the service either recovers the token from the in-memory
     * registry or falls back to {@code markRevokedByHash8} on SQLite — either way the persisted
     * link must end up revoked.
     */
    @Test
    void revokeByHash8_throughRealWiring_marksLinkRevoked() {
        Instant now = clock.instant();

        var link = shareLinkService.create(SHARER_WALL_ID, SHARER_IP, now);
        String idHash8 = link.id().hash8();
        Instant revokedAt = now.plusSeconds(10);

        shareLinkService.revokeByHash8(idHash8, SHARER_WALL_ID, revokedAt);

        List<ShareLinkSummary> summaries =
                shareLinkService.listSummaryBySharer(SHARER_WALL_ID, revokedAt.plusSeconds(1));
        assertThat(summaries).hasSize(1);
        assertThat(summaries.get(0).status()).isEqualTo(ShareLinkStatus.REVOKED);
        assertThat(summaries.get(0).revokedAt()).isNotNull();
    }

    /**
     * Anti-enumeration through the real stack: a foreign sharer presenting the correct {@code idHash8}
     * cannot revoke another sharer's link — the call throws and the link stays ACTIVE. This verifies
     * the {@code sharer_wall_id} scoping is enforced end-to-end (registry getActiveLinks is keyed by
     * the caller, and the SQLite fallback's WHERE clause includes sharer_wall_id).
     */
    @Test
    void revokeByHash8_foreignSharer_throwsAndLeavesLinkActive() {
        Instant now = clock.instant();

        var link = shareLinkService.create(SHARER_WALL_ID, SHARER_IP, now);
        String idHash8 = link.id().hash8();

        assertThatThrownBy(() -> shareLinkService.revokeByHash8(
                idHash8, "other-sharer-wall-id-99999999999999999", now.plusSeconds(5)))
                .isInstanceOf(ShareLinkNotFoundOrNotAuthorisedException.class);

        List<ShareLinkSummary> summaries = shareLinkService.listSummaryBySharer(SHARER_WALL_ID, now.plusSeconds(6));
        assertThat(summaries).hasSize(1);
        assertThat(summaries.get(0).status())
                .as("a foreign sharer must not be able to revoke the link")
                .isEqualTo(ShareLinkStatus.ACTIVE);
    }
}
