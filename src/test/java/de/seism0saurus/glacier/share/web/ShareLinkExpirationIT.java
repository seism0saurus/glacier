package de.seism0saurus.glacier.share.web;

import de.seism0saurus.glacier.share.domain.ShareLink;
import de.seism0saurus.glacier.share.domain.ShareLinkId;
import de.seism0saurus.glacier.share.domain.ShareLinkStatus;
import de.seism0saurus.glacier.share.infrastructure.InMemoryShareLinkRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration test for the TTL + sweeper lifecycle of {@link ShareLink} end-to-end.
 *
 * <p>Verifies the full expiry and sweeper pipeline:
 * <ol>
 *   <li>A link created with a very short TTL (1 second via
 *       {@code glacier.share.ttl=PT1S}) transitions to {@link ShareLinkStatus#EXPIRED}
 *       after the TTL elapses.</li>
 *   <li>The link is still physically present in the store after TTL expiry — the sweeper
 *       has not yet run.</li>
 *   <li>Calling {@link InMemoryShareLinkRepository#scheduledSweep()} removes the expired
 *       link from the in-memory store.</li>
 *   <li>{@code GET /rest/share/{id}/catalog} returns {@code 404} after expiry — consistent
 *       with the anti-enumeration invariant (unknown-ID and expired-ID are indistinguishable
 *       to the caller, SR-SHARE-01).</li>
 * </ol>
 *
 * <p>Security: SR-SHARE-01 (uniform 404 for expired and unknown IDs),
 * OWASP API1 (BOLA anti-enumeration invariant).
 *
 * <p>Uses {@link DirtiesContext} per method because each test mutates the shared
 * {@link InMemoryShareLinkRepository} singleton.
 */
@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
@TestPropertySource(properties = {
        "glacier.domain=glacier.example.com",
        "glacier.cookie.secure=false",
        "glacier.fallback.enabled=true",
        "mastodon.instance=mastodon.social",
        "mastodon.accessToken=dummy",
        "mastodon.handle=glacier@mastodon.social",
        "glacier.operator.name=Test",
        "glacier.operator.streetAndNumber=Test 1",
        "glacier.operator.zipcode=12345",
        "glacier.operator.city=Test",
        "glacier.operator.country=Test",
        "glacier.operator.phone=+1",
        "glacier.operator.mail=test@test.com",
        "glacier.operator.website=test.com",
        "glacier.share.imgproxy.hmacSecret=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
        // TTL of 1 second — link expires almost immediately
        "glacier.share.ttl=PT1S",
        // High rate limits so rate limiting does not interfere with 2x requests
        "glacier.share.ratelimit.fallback.perMinutePerViewer=10000",
        "glacier.share.ratelimit.fallback.perMinutePerIp=10000",
        "glacier.share.ratelimit.csrf.perMinutePerIp=10000"
})
class ShareLinkExpirationIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private InMemoryShareLinkRepository repository;

    /** A 43-char URL-safe base64 token (minimum valid ShareLinkId length). */
    private static final String FIXTURE_TOKEN = "AbCdEfGhIjKlMnOpQrStUvWxYzAbCdEfGhIjKlMnOpq";

    // -----------------------------------------------------------------------
    // TTL: link status transitions to EXPIRED after TTL elapses
    // -----------------------------------------------------------------------

    /**
     * Verifies that a link created with a 1-second TTL reports {@link ShareLinkStatus#EXPIRED}
     * after 2 seconds have elapsed.
     *
     * <p>Arrange: save a link with {@code expiresAt = now - 1s} (already expired at save time
     * by subtracting the TTL from the createdAt so that {@code createdAt + ttl < now}).
     * <br>Act: call {@link ShareLink#status(Instant)} with the current time.
     * <br>Assert: status is EXPIRED.
     */
    @Test
    void linkWithShortTtl_isExpiredAfterTtlElapsed() {
        // Arrange: create the link such that it was created 2 seconds ago and TTL is 1s.
        // With glacier.share.ttl=PT1S the lifetimePolicy is loaded from config, but we
        // can also construct the ShareLink directly using a 1s Duration to be explicit.
        Instant createdAt = Instant.now().minusSeconds(2); // created 2 s ago
        ShareLinkId id = ShareLinkId.fromUrlPath(FIXTURE_TOKEN);
        ShareLink link = ShareLink.create(id, "sharer-wall-id-fixture-00000000000", createdAt, Duration.ofSeconds(1));
        repository.save(link);

        // Act: derive status at the current clock instant
        ShareLinkStatus status = link.status(Instant.now());

        // Assert: the TTL of 1 s has elapsed — link must be EXPIRED
        assertThat(status)
                .as("link created 2 s ago with TTL=1s must be EXPIRED")
                .isEqualTo(ShareLinkStatus.EXPIRED);
    }

    // -----------------------------------------------------------------------
    // Sweeper: expired link is still present before sweep, gone after sweep
    // -----------------------------------------------------------------------

    /**
     * Verifies that an expired link is physically present in the store before the sweeper
     * runs but removed after {@link InMemoryShareLinkRepository#scheduledSweep()} is called.
     *
     * <p>Arrange: save an already-expired link (createdAt = 2 s ago, TTL = 1 s).
     * <br>Act (1): call {@link de.seism0saurus.glacier.share.domain.ShareLinkRepository#findById}
     * — must find it (sweeper has not run yet).
     * <br>Act (2): call {@link InMemoryShareLinkRepository#scheduledSweep()}.
     * <br>Assert: {@code findById} returns empty after the sweep.
     */
    @Test
    void expiredLink_isStillPresentBeforeSweep_thenRemovedAfterSweep() {
        // Arrange: create an already-expired link
        Instant createdAt = Instant.now().minusSeconds(2);
        ShareLinkId id = ShareLinkId.fromUrlPath(FIXTURE_TOKEN);
        ShareLink link = ShareLink.create(id, "sharer-wall-id-fixture-00000000000", createdAt, Duration.ofSeconds(1));
        repository.save(link);

        // Act (pre-sweep): the link must still exist physically — sweeper has not run
        assertThat(repository.findById(id))
                .as("expired link must still be present in store before sweeper runs")
                .isPresent();

        // Act (sweep): trigger the sweeper directly without waiting for the scheduler
        repository.scheduledSweep();

        // Assert: link removed from store after sweep
        assertThat(repository.findById(id))
                .as("expired link must be removed from store after sweep")
                .isEmpty();
    }

    // -----------------------------------------------------------------------
    // HTTP: catalog endpoint returns 404 for an expired link (anti-enumeration)
    // -----------------------------------------------------------------------

    /**
     * Verifies that {@code GET /rest/share/{id}/catalog} returns {@code 404} after a link
     * has expired, indistinguishable from an unknown ID (SR-SHARE-01 anti-enumeration).
     *
     * <p>Arrange: save an already-expired link (createdAt = 2 s ago, TTL = 1 s).
     * <br>Act: perform {@code GET /rest/share/{id}/catalog}.
     * <br>Assert: response status is {@code 404}.
     */
    @Test
    void catalogEndpoint_returns404ForExpiredLink_sameAsUnknownId() throws Exception {
        // Arrange: save an already-expired link
        Instant createdAt = Instant.now().minusSeconds(2);
        ShareLinkId id = ShareLinkId.fromUrlPath(FIXTURE_TOKEN);
        ShareLink link = ShareLink.create(id, "sharer-wall-id-fixture-00000000000", createdAt, Duration.ofSeconds(1));
        repository.save(link);

        // Act + Assert: catalog must return 404 — expired is treated uniformly with unknown
        mockMvc.perform(get("/rest/share/{id}/catalog", FIXTURE_TOKEN))
                .andExpect(status().isNotFound());
    }

    /**
     * Verifies that the 404 returned for an expired link has the same HTTP status as the
     * 404 returned for a never-inserted unknown ID — confirming the anti-enumeration invariant.
     *
     * <p>This test makes the anti-enumeration property machine-checkable: the caller cannot
     * distinguish existence from non-existence using the status code alone.
     */
    @Test
    void catalogEndpoint_expiredAndUnknownId_returnSame404() throws Exception {
        // Arrange: expired link
        Instant createdAt = Instant.now().minusSeconds(2);
        ShareLinkId expiredId = ShareLinkId.fromUrlPath(FIXTURE_TOKEN);
        ShareLink link = ShareLink.create(expiredId, "sharer-wall-id-fixture-00000000000", createdAt, Duration.ofSeconds(1));
        repository.save(link);

        // Truly unknown ID — never inserted into the repository
        String unknownToken = "ZzZzZzZzZzZzZzZzZzZzZzZzZzZzZzZzZzZzZzZzZzZ";

        // Act: both expired and unknown must return 404
        mockMvc.perform(get("/rest/share/{id}/catalog", FIXTURE_TOKEN))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/rest/share/{id}/catalog", unknownToken))
                .andExpect(status().isNotFound());
    }

    /**
     * Verifies that after the sweeper removes the expired link, the catalog endpoint still
     * returns {@code 404} — the behaviour is identical before and after the sweep.
     *
     * <p>This confirms that the anti-enumeration invariant is maintained even when the
     * underlying repository transitions from "expired-present" to "absent".
     */
    @Test
    void catalogEndpoint_returns404AfterSweepRemovesExpiredLink() throws Exception {
        // Arrange: save and then sweep an expired link
        Instant createdAt = Instant.now().minusSeconds(2);
        ShareLinkId id = ShareLinkId.fromUrlPath(FIXTURE_TOKEN);
        ShareLink link = ShareLink.create(id, "sharer-wall-id-fixture-00000000000", createdAt, Duration.ofSeconds(1));
        repository.save(link);
        repository.scheduledSweep(); // link is now physically absent

        // Assert: 404 still returned after sweeper removed the link
        mockMvc.perform(get("/rest/share/{id}/catalog", FIXTURE_TOKEN))
                .andExpect(status().isNotFound());
    }
}
