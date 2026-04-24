package de.seism0saurus.glacier.share.application;

import de.seism0saurus.glacier.share.domain.ShareLink;
import de.seism0saurus.glacier.share.domain.ShareLinkCapPolicy;
import de.seism0saurus.glacier.share.domain.ShareLinkId;
import de.seism0saurus.glacier.share.domain.ShareLinkLifetimePolicy;
import de.seism0saurus.glacier.share.domain.ShareLinkRepository;
import de.seism0saurus.glacier.share.domain.ShareLinkStatus;
import de.seism0saurus.glacier.share.domain.SecureRandomTokenGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ShareLinkServiceImpl}.
 *
 * <p>Uses Mockito for the {@link ShareLinkRepository} collaborator so the service logic
 * can be tested in isolation without starting a Spring context.  A {@link Clock#fixed}
 * instance is injected to make TTL boundary crossings deterministic.
 *
 * <p>Key scenarios covered:
 * <ul>
 *   <li>Create: happy path, per-sharer cap exceeded, per-IP cap exceeded.</li>
 *   <li>Resolve: ACTIVE → present, EXPIRED → empty, REVOKED → empty; both "not found"
 *       and "not active" cases return {@link Optional#empty()} via structurally equivalent
 *       code paths (no extra work on either branch). Timing invariance is enforced
 *       structurally — measurement is in {@code ShareCatalogEndpointTimingIT}, not here,
 *       because MockMvc mock-dispatch overhead makes nanosecond-precision in-process timing
 *       assertions unreliable at the unit-test level.</li>
 *   <li>Revoke: matching wallId succeeds; non-matching wallId and unknown ID both throw
 *       {@link ShareLinkNotFoundOrNotAuthorisedException} with the same uniform shape
 *       (anti-enumeration).</li>
 *   <li>ListBySharer: returns only ACTIVE links for the calling sharer.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class ShareLinkServiceTest {

    private static final Instant T0 = Instant.parse("2025-01-01T00:00:00Z");
    private static final Duration TTL = Duration.ofDays(7);
    private static final String SHARER_WALL_ID = "sharer-wall-id-fixture-000000000000";
    private static final String SHARER_IP = "10.0.0.1";

    @Mock
    private ShareLinkRepository repository;

    private ShareLinkLifetimePolicy lifetimePolicy;
    private ShareLinkCapPolicy capPolicy;
    private Clock fixedClock;
    private SecureRandomTokenGenerator tokenGenerator;
    private ShareLinkServiceImpl service;

    @BeforeEach
    void setUp() {
        fixedClock = Clock.fixed(T0, ZoneOffset.UTC);
        lifetimePolicy = new ShareLinkLifetimePolicy();
        lifetimePolicy.setTtl(TTL);
        capPolicy = new ShareLinkCapPolicy();
        tokenGenerator = new SecureRandomTokenGenerator();
        // Pass null shareViewStompRelay — unit tests that do not test revocation notification
        // use null to keep the dependency minimal (null-safe call site in revoke())
        service = new ShareLinkServiceImpl(repository, tokenGenerator, lifetimePolicy, capPolicy, null, fixedClock);
    }

    // ---------------------------------------------------------------------------
    // create — happy path
    // ---------------------------------------------------------------------------

    @Test
    void createReturnsSavedShareLink() {
        when(repository.countActiveForSharer(SHARER_WALL_ID, T0)).thenReturn(0);
        when(repository.countActiveForIp(SHARER_IP, T0)).thenReturn(0);
        when(repository.save(any(ShareLink.class))).thenAnswer(inv -> inv.getArgument(0));

        ShareLink result = service.create(SHARER_WALL_ID, SHARER_IP, T0);

        assertThat(result).isNotNull();
        assertThat(result.sharerWallId()).isEqualTo(SHARER_WALL_ID);
        assertThat(result.status(T0)).isEqualTo(ShareLinkStatus.ACTIVE);
        verify(repository).save(any(ShareLink.class));
    }

    // ---------------------------------------------------------------------------
    // create — per-sharer cap enforcement
    // ---------------------------------------------------------------------------

    @Test
    void createThrowsCapacityExceededWhenSharerCapIsReached() {
        capPolicy.setMaxActivePerSharer(3);
        when(repository.countActiveForSharer(SHARER_WALL_ID, T0)).thenReturn(3);

        assertThatThrownBy(() -> service.create(SHARER_WALL_ID, SHARER_IP, T0))
                .isInstanceOf(CapacityExceededException.class)
                .hasMessageContaining("sharer");
    }

    // ---------------------------------------------------------------------------
    // create — per-IP cap enforcement
    // ---------------------------------------------------------------------------

    @Test
    void createThrowsCapacityExceededWhenIpCapIsReached() {
        capPolicy.setMaxActivePerIp(10);
        when(repository.countActiveForSharer(SHARER_WALL_ID, T0)).thenReturn(0);
        when(repository.countActiveForIp(SHARER_IP, T0)).thenReturn(10);

        assertThatThrownBy(() -> service.create(SHARER_WALL_ID, SHARER_IP, T0))
                .isInstanceOf(CapacityExceededException.class)
                .hasMessageContaining("ip");
    }

    // ---------------------------------------------------------------------------
    // resolve — present for ACTIVE
    // ---------------------------------------------------------------------------

    @Test
    void resolvePresentForActiveLink() {
        ShareLinkId id = tokenGenerator.generateShareLinkId();
        ShareLink link = ShareLink.create(id, SHARER_WALL_ID, T0, TTL);
        when(repository.findById(id)).thenReturn(Optional.of(link));

        Optional<ShareLink> result = service.resolve(id, T0);

        assertThat(result).isPresent();
        assertThat(result.get().id()).isEqualTo(id);
    }

    // ---------------------------------------------------------------------------
    // resolve — empty for EXPIRED
    // ---------------------------------------------------------------------------

    @Test
    void resolveEmptyForExpiredLink() {
        ShareLinkId id = tokenGenerator.generateShareLinkId();
        ShareLink link = ShareLink.create(id, SHARER_WALL_ID, T0.minus(TTL).minusSeconds(1), TTL);
        when(repository.findById(id)).thenReturn(Optional.of(link));

        // T0 is after expiresAt
        Optional<ShareLink> result = service.resolve(id, T0);

        assertThat(result).isEmpty();
    }

    // ---------------------------------------------------------------------------
    // resolve — empty for REVOKED
    // ---------------------------------------------------------------------------

    @Test
    void resolveEmptyForRevokedLink() {
        ShareLinkId id = tokenGenerator.generateShareLinkId();
        ShareLink link = ShareLink.create(id, SHARER_WALL_ID, T0, TTL);
        link.revoke(SHARER_WALL_ID, T0.plusSeconds(30));
        when(repository.findById(id)).thenReturn(Optional.of(link));

        Optional<ShareLink> result = service.resolve(id, T0.plusSeconds(60));

        assertThat(result).isEmpty();
    }

    // ---------------------------------------------------------------------------
    // resolve — empty (not found)
    // ---------------------------------------------------------------------------

    @Test
    void resolveEmptyForUnknownId() {
        ShareLinkId id = tokenGenerator.generateShareLinkId();
        when(repository.findById(id)).thenReturn(Optional.empty());

        Optional<ShareLink> result = service.resolve(id, T0);

        assertThat(result).isEmpty();
    }

    // ---------------------------------------------------------------------------
    // revoke — happy path
    // ---------------------------------------------------------------------------

    @Test
    void revokeWithMatchingWallIdCallsMarkRevoked() {
        ShareLinkId id = tokenGenerator.generateShareLinkId();
        ShareLink link = ShareLink.create(id, SHARER_WALL_ID, T0, TTL);
        when(repository.findById(id)).thenReturn(Optional.of(link));

        service.revoke(id, SHARER_WALL_ID, T0.plusSeconds(10));

        verify(repository).markRevoked(eq(id), any(Instant.class));
    }

    // ---------------------------------------------------------------------------
    // revoke — anti-enumeration: unknown ID and wrong wallId throw same exception type
    // ---------------------------------------------------------------------------

    @Test
    void revokeWithUnknownIdThrowsShareLinkNotFoundOrNotAuthorised() {
        ShareLinkId unknownId = tokenGenerator.generateShareLinkId();
        when(repository.findById(unknownId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.revoke(unknownId, SHARER_WALL_ID, T0))
                .isInstanceOf(ShareLinkNotFoundOrNotAuthorisedException.class);
    }

    @Test
    void revokeWithWrongWallIdThrowsShareLinkNotFoundOrNotAuthorised() {
        ShareLinkId id = tokenGenerator.generateShareLinkId();
        ShareLink link = ShareLink.create(id, SHARER_WALL_ID, T0, TTL);
        when(repository.findById(id)).thenReturn(Optional.of(link));

        assertThatThrownBy(() -> service.revoke(id, "wrong-wall-id-0000000000000000000000", T0))
                .isInstanceOf(ShareLinkNotFoundOrNotAuthorisedException.class);
    }

    @Test
    void revokeUnknownIdAndWrongWallIdThrowExceptionWithSameType() {
        // Both branches must produce the same exception type — anti-enumeration invariant
        ShareLinkId unknownId = tokenGenerator.generateShareLinkId();
        when(repository.findById(unknownId)).thenReturn(Optional.empty());

        ShareLinkId knownId = tokenGenerator.generateShareLinkId();
        ShareLink link = ShareLink.create(knownId, SHARER_WALL_ID, T0, TTL);
        when(repository.findById(knownId)).thenReturn(Optional.of(link));

        Class<?> unknownException = null;
        Class<?> wrongWallException = null;

        try {
            service.revoke(unknownId, SHARER_WALL_ID, T0);
        } catch (ShareLinkNotFoundOrNotAuthorisedException e) {
            unknownException = e.getClass();
        }

        try {
            service.revoke(knownId, "wrong-wall-id-00000000000000000000000", T0);
        } catch (ShareLinkNotFoundOrNotAuthorisedException e) {
            wrongWallException = e.getClass();
        }

        assertThat(unknownException).isNotNull().isEqualTo(wrongWallException);
    }

    // ---------------------------------------------------------------------------
    // listBySharer
    // ---------------------------------------------------------------------------

    @Test
    void listBySharerReturnsOnlyActiveLinksForCallingSharer() {
        ShareLinkId id1 = tokenGenerator.generateShareLinkId();
        ShareLinkId id2 = tokenGenerator.generateShareLinkId();
        ShareLink active = ShareLink.create(id1, SHARER_WALL_ID, T0, TTL);
        ShareLink expired = ShareLink.create(id2, SHARER_WALL_ID, T0.minus(TTL).minusSeconds(1), TTL);
        when(repository.findAllBySharer(SHARER_WALL_ID)).thenReturn(List.of(active, expired));

        List<ShareLink> result = service.listBySharer(SHARER_WALL_ID, T0);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).id()).isEqualTo(id1);
    }

    @Test
    void listBySharerReturnsEmptyListWhenNoActiveLinks() {
        when(repository.findAllBySharer(SHARER_WALL_ID)).thenReturn(List.of());
        assertThat(service.listBySharer(SHARER_WALL_ID, T0)).isEmpty();
    }
}
