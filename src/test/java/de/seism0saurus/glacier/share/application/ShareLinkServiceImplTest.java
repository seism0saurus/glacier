package de.seism0saurus.glacier.share.application;

import de.seism0saurus.glacier.share.domain.SecureRandomTokenGenerator;
import de.seism0saurus.glacier.share.domain.ShareLink;
import de.seism0saurus.glacier.share.domain.ShareLinkCapPolicy;
import de.seism0saurus.glacier.share.domain.ShareLinkId;
import de.seism0saurus.glacier.share.domain.ShareLinkLifetimePolicy;
import de.seism0saurus.glacier.share.domain.ShareLinkRepository;
import de.seism0saurus.glacier.share.domain.ShareLinkStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Focused unit-test suite for {@link ShareLinkServiceImpl} using a {@link Clock#fixed}
 * injected clock, so all TTL boundary crossings are deterministic.
 *
 * <p>These tests exercise the service contract beyond the minimal happy-path coverage
 * in {@link ShareLinkServiceTest}: clock injection, cap assertion ordering, STOMP
 * relay integration, and {@code listBySharer} filtering are all verified here.
 *
 * <p>Arrange / Act / Assert conventions:
 * <ul>
 *   <li><b>Arrange</b> — clock fixed at {@link #T0}, stubs configured on mocks.</li>
 *   <li><b>Act</b>    — single service method call.</li>
 *   <li><b>Assert</b> — state, interaction, and exception-type verification.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class ShareLinkServiceImplTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
    private static final Duration TTL = Duration.ofDays(7);
    private static final String SHARER_WALL_ID = "sharer-wall-id-00000000000000000000000";
    private static final String SHARER_IP = "10.0.0.42";

    @Mock
    private ShareLinkRepository repository;

    @Mock
    private ShareViewStompRelay shareViewStompRelay;

    private ShareLinkLifetimePolicy lifetimePolicy;
    private ShareLinkCapPolicy capPolicy;
    private Clock fixedClock;
    private SecureRandomTokenGenerator tokenGenerator;
    private ShareLinkServiceImpl service;

    @BeforeEach
    void setUp() {
        // Arrange — a fixed clock at T0 is injected so time is not a test variable.
        fixedClock = Clock.fixed(T0, ZoneOffset.UTC);
        lifetimePolicy = new ShareLinkLifetimePolicy();
        lifetimePolicy.setTtl(TTL);
        capPolicy = new ShareLinkCapPolicy();
        tokenGenerator = new SecureRandomTokenGenerator();
        // Real relay mock passed in; specific tests pass null to verify null-safety.
        service = new ShareLinkServiceImpl(
                repository, tokenGenerator, lifetimePolicy, capPolicy, shareViewStompRelay, fixedClock);
    }

    // -----------------------------------------------------------------------
    // Priority 1 — create
    // -----------------------------------------------------------------------

    /**
     * Verifies that {@code create} persists a link whose fields match the injected clock.
     *
     * <p>Arrange: caps below limit, repository echoes the argument.
     * <p>Act:     call {@code create}.
     * <p>Assert:  {@code save()} was called with a link whose {@code createdAt} equals T0
     *             and whose {@code expiresAt} equals T0 + TTL.
     */
    @Test
    void create_persistsLink_withInjectedClock() {
        when(repository.countActiveForSharer(SHARER_WALL_ID, T0)).thenReturn(0);
        when(repository.countActiveForIp(SHARER_IP, T0)).thenReturn(0);
        when(repository.save(any(ShareLink.class))).thenAnswer(inv -> inv.getArgument(0));

        ShareLink result = service.create(SHARER_WALL_ID, SHARER_IP, T0);

        ArgumentCaptor<ShareLink> captor = ArgumentCaptor.forClass(ShareLink.class);
        verify(repository).save(captor.capture());
        ShareLink saved = captor.getValue();

        assertThat(saved.createdAt()).isEqualTo(T0);
        assertThat(saved.expiresAt()).isEqualTo(T0.plus(TTL));
        assertThat(saved.sharerWallId()).isEqualTo(SHARER_WALL_ID);
        assertThat(saved.status(T0)).isEqualTo(ShareLinkStatus.ACTIVE);
        // Returned link is the saved link
        assertThat(result).isEqualTo(saved);
    }

    /**
     * Verifies that when the per-sharer cap is hit, {@code create} throws
     * {@link CapacityExceededException} and never calls {@code save()}.
     *
     * <p>Arrange: {@code countActiveForSharer} returns exactly the cap value (≥ cap).
     * <p>Act:     call {@code create}.
     * <p>Assert:  {@link CapacityExceededException} thrown; {@code save()} never called.
     */
    @Test
    void create_throwsCapacityExceeded_whenSharerCapHit_andNeverPersists() {
        capPolicy.setMaxActivePerSharer(3);
        when(repository.countActiveForSharer(SHARER_WALL_ID, T0)).thenReturn(3);

        assertThatThrownBy(() -> service.create(SHARER_WALL_ID, SHARER_IP, T0))
                .isInstanceOf(CapacityExceededException.class)
                .hasMessageContaining("sharer");

        verify(repository, never()).save(any());
    }

    /**
     * Verifies that when the per-IP cap is hit, {@code create} throws
     * {@link CapacityExceededException} and never calls {@code save()}.
     *
     * <p>Arrange: sharer count below cap; IP count at cap.
     * <p>Act:     call {@code create}.
     * <p>Assert:  {@link CapacityExceededException} thrown; {@code save()} never called.
     */
    @Test
    void create_throwsCapacityExceeded_whenIpCapHit_andNeverPersists() {
        capPolicy.setMaxActivePerIp(10);
        when(repository.countActiveForSharer(SHARER_WALL_ID, T0)).thenReturn(0);
        when(repository.countActiveForIp(SHARER_IP, T0)).thenReturn(10);

        assertThatThrownBy(() -> service.create(SHARER_WALL_ID, SHARER_IP, T0))
                .isInstanceOf(CapacityExceededException.class)
                .hasMessageContaining("ip");

        verify(repository, never()).save(any());
    }

    // -----------------------------------------------------------------------
    // Priority 1 — resolve
    // -----------------------------------------------------------------------

    /**
     * Resolving an unknown ID returns empty without throwing.
     */
    @Test
    void resolve_returnsEmpty_forUnknownId() {
        ShareLinkId unknownId = tokenGenerator.generateShareLinkId();
        when(repository.findById(unknownId)).thenReturn(Optional.empty());

        Optional<ShareLink> result = service.resolve(unknownId, T0);

        assertThat(result).isEmpty();
    }

    /**
     * Resolving an expired link returns empty — the caller cannot tell from the return
     * value whether the link was expired or never existed (constant-time branch, T-07).
     */
    @Test
    void resolve_returnsEmpty_forExpiredId() {
        ShareLinkId id = tokenGenerator.generateShareLinkId();
        // Link was created one TTL + 1s before T0, so it is already expired at T0
        ShareLink expired = ShareLink.create(id, SHARER_WALL_ID, T0.minus(TTL).minusSeconds(1), TTL);
        when(repository.findById(id)).thenReturn(Optional.of(expired));

        Optional<ShareLink> result = service.resolve(id, T0);

        assertThat(result).isEmpty();
    }

    /**
     * Resolving a revoked link returns empty — the caller cannot tell from the return
     * value whether the link was revoked or expired (anti-enumeration, T-07).
     */
    @Test
    void resolve_returnsEmpty_forRevokedId() {
        ShareLinkId id = tokenGenerator.generateShareLinkId();
        ShareLink link = ShareLink.create(id, SHARER_WALL_ID, T0, TTL);
        // Revoked 30 seconds after creation — still within TTL but REVOKED
        link.revoke(SHARER_WALL_ID, T0.plusSeconds(30));
        when(repository.findById(id)).thenReturn(Optional.of(link));

        Optional<ShareLink> result = service.resolve(id, T0.plusSeconds(60));

        assertThat(result).isEmpty();
    }

    /**
     * Resolving an ACTIVE link returns the link.
     */
    @Test
    void resolve_returnsLink_forActiveLink() {
        ShareLinkId id = tokenGenerator.generateShareLinkId();
        ShareLink link = ShareLink.create(id, SHARER_WALL_ID, T0, TTL);
        when(repository.findById(id)).thenReturn(Optional.of(link));

        Optional<ShareLink> result = service.resolve(id, T0);

        assertThat(result).isPresent();
        assertThat(result.get().id()).isEqualTo(id);
        assertThat(result.get().status(T0)).isEqualTo(ShareLinkStatus.ACTIVE);
    }

    // -----------------------------------------------------------------------
    // Priority 1 — revoke
    // -----------------------------------------------------------------------

    /**
     * Revoking a missing ID throws {@link ShareLinkNotFoundOrNotAuthorisedException}
     * (anti-enumeration: same exception type as a wallId mismatch).
     */
    @Test
    void revoke_throwsNotFoundOrNotAuthorised_whenIdMissing() {
        ShareLinkId missingId = tokenGenerator.generateShareLinkId();
        when(repository.findById(missingId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.revoke(missingId, SHARER_WALL_ID, T0))
                .isInstanceOf(ShareLinkNotFoundOrNotAuthorisedException.class);
    }

    /**
     * Revoking with a wallId that does not match the link's sharer throws
     * {@link ShareLinkNotFoundOrNotAuthorisedException} — same exception type as
     * "not found" to prevent enumeration (T-07).
     */
    @Test
    void revoke_throwsNotFoundOrNotAuthorised_whenWallIdMismatch() {
        ShareLinkId id = tokenGenerator.generateShareLinkId();
        ShareLink link = ShareLink.create(id, SHARER_WALL_ID, T0, TTL);
        when(repository.findById(id)).thenReturn(Optional.of(link));

        assertThatThrownBy(() -> service.revoke(id, "wrong-wall-id-00000000000000000000000", T0))
                .isInstanceOf(ShareLinkNotFoundOrNotAuthorisedException.class);
    }

    /**
     * Revoking with the correct wallId marks the link revoked in the repository
     * and pushes a STOMP revocation message to viewers.
     */
    @Test
    void revoke_marksLinkRevoked_andPushesStompRevocation() {
        ShareLinkId id = tokenGenerator.generateShareLinkId();
        ShareLink link = ShareLink.create(id, SHARER_WALL_ID, T0, TTL);
        when(repository.findById(id)).thenReturn(Optional.of(link));

        service.revoke(id, SHARER_WALL_ID, T0.plusSeconds(10));

        verify(repository).markRevoked(id, T0.plusSeconds(10));
        verify(shareViewStompRelay).pushRevocation(id);
    }

    /**
     * When {@code shareViewStompRelay} is null (stub mode or disabled), revoke must
     * complete without throwing a NullPointerException.
     */
    @Test
    void revoke_doesNotPushStomp_whenRelayIsNull() {
        ShareLinkId id = tokenGenerator.generateShareLinkId();
        ShareLink link = ShareLink.create(id, SHARER_WALL_ID, T0, TTL);
        when(repository.findById(id)).thenReturn(Optional.of(link));

        // Build a service instance with a null relay
        ShareLinkServiceImpl serviceWithNullRelay = new ShareLinkServiceImpl(
                repository, tokenGenerator, lifetimePolicy, capPolicy, null, fixedClock);

        // Must not throw
        serviceWithNullRelay.revoke(id, SHARER_WALL_ID, T0.plusSeconds(5));

        verify(repository).markRevoked(id, T0.plusSeconds(5));
    }

    // -----------------------------------------------------------------------
    // Priority 1 — listBySharer
    // -----------------------------------------------------------------------

    /**
     * {@code listBySharer} returns only ACTIVE links — expired and revoked entries
     * are filtered out so the sharer's management UI shows only usable links.
     *
     * <p>Arrange: one ACTIVE link + one EXPIRED link + one REVOKED link for the same sharer.
     * <p>Act:     call {@code listBySharer}.
     * <p>Assert:  only the ACTIVE link is present in the result.
     */
    @Test
    void listBySharer_filtersToActiveOnly() {
        ShareLinkId activeId = tokenGenerator.generateShareLinkId();
        ShareLinkId expiredId = tokenGenerator.generateShareLinkId();
        ShareLinkId revokedId = tokenGenerator.generateShareLinkId();

        ShareLink active = ShareLink.create(activeId, SHARER_WALL_ID, T0, TTL);
        ShareLink expired = ShareLink.create(expiredId, SHARER_WALL_ID, T0.minus(TTL).minusSeconds(1), TTL);
        ShareLink revoked = ShareLink.create(revokedId, SHARER_WALL_ID, T0, TTL);
        revoked.revoke(SHARER_WALL_ID, T0.plusSeconds(1));

        when(repository.findAllBySharer(SHARER_WALL_ID)).thenReturn(List.of(active, expired, revoked));

        List<ShareLink> result = service.listBySharer(SHARER_WALL_ID, T0);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).id()).isEqualTo(activeId);
    }
}
