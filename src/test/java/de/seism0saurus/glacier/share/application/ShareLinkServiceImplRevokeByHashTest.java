package de.seism0saurus.glacier.share.application;

import de.seism0saurus.glacier.share.domain.SecureRandomTokenGenerator;
import de.seism0saurus.glacier.share.domain.ShareLink;
import de.seism0saurus.glacier.share.domain.ShareLinkCapPolicy;
import de.seism0saurus.glacier.share.domain.ShareLinkId;
import de.seism0saurus.glacier.share.domain.ShareLinkLifetimePolicy;
import de.seism0saurus.glacier.share.domain.ShareLinkRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Focused unit tests for {@link ShareLinkServiceImpl#revokeByHash8} — the revoke path used by
 * the sharer's self-management list, which only sees the non-secret {@code idHash8}.
 *
 * <p>The registry is a Mockito mock so {@code getActiveLinks} can be controlled directly,
 * isolating the two branches:
 * <ul>
 *   <li><b>registry hit</b> — the full token is recovered and the standard {@link
 *       ShareLinkServiceImpl#revoke} machinery is reused (DB mark + {@link ShareLinkRevokedEvent}
 *       → relay live control frame).</li>
 *   <li><b>registry miss</b> — the link is revoked authoritatively at the persistence layer via
 *       {@link ShareLinkRepository#markRevokedByHash8}; no event is published (no token).</li>
 * </ul>
 *
 * <p>Anti-enumeration (T-07): malformed, unknown, not-owned, and already-revoked all surface as
 * {@link ShareLinkNotFoundOrNotAuthorisedException}.
 */
@ExtendWith(MockitoExtension.class)
class ShareLinkServiceImplRevokeByHashTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
    private static final Duration TTL = Duration.ofDays(7);
    private static final String WALL_ID = "sharer-wall-id-00000000000000000000000";

    @Mock
    private ShareLinkRepository repository;
    @Mock
    private ApplicationEventPublisher eventPublisher;
    @Mock
    private ShareLinkActivityRegistry registry;

    private ShareLinkServiceImpl service;
    private ShareLinkId activeLinkId;

    @BeforeEach
    void setUp() {
        Clock fixedClock = Clock.fixed(T0, ZoneOffset.UTC);
        ShareLinkLifetimePolicy lifetimePolicy = new ShareLinkLifetimePolicy();
        lifetimePolicy.setTtl(TTL);
        ShareLinkCapPolicy capPolicy = new ShareLinkCapPolicy();
        SecureRandomTokenGenerator tokenGenerator = new SecureRandomTokenGenerator();
        activeLinkId = tokenGenerator.generateShareLinkId();
        service = new ShareLinkServiceImpl(
                repository, tokenGenerator, lifetimePolicy, capPolicy, eventPublisher, registry, fixedClock);
    }

    @Test
    void registryHit_recoversToken_reusesRevokeAndPublishesEvent() {
        // Arrange: the link is active in the in-memory registry (holds the full token).
        when(registry.getActiveLinks(WALL_ID)).thenReturn(Set.of(activeLinkId));
        ShareLink active = ShareLink.create(activeLinkId, WALL_ID, T0, TTL);
        when(repository.findById(activeLinkId)).thenReturn(Optional.of(active));

        // Act: revoke by the non-secret idHash8.
        service.revokeByHash8(activeLinkId.hash8(), WALL_ID, T0.plusSeconds(60));

        // Assert: standard revoke machinery ran — DB mark + revocation event (→ live viewer kick).
        verify(repository).markRevoked(activeLinkId, T0.plusSeconds(60));
        verify(eventPublisher).publishEvent(any(ShareLinkRevokedEvent.class));
        // The DB-only fallback must NOT run when the registry served the token.
        verify(repository, never()).markRevokedByHash8(any(), any(), any());
    }

    @Test
    void registryMiss_butActiveInDb_revokesAtPersistenceLayer_noEvent() {
        // Arrange: not in the registry (e.g. created before a restart) but active in the DB.
        when(registry.getActiveLinks(WALL_ID)).thenReturn(Set.of());
        when(repository.markRevokedByHash8(eq("deadbeef"), eq(WALL_ID), any())).thenReturn(true);

        // Act
        service.revokeByHash8("deadbeef", WALL_ID, T0.plusSeconds(60));

        // Assert: DB-authoritative revoke; no token → no live control frame event.
        verify(repository).markRevokedByHash8("deadbeef", WALL_ID, T0.plusSeconds(60));
        verify(eventPublisher, never()).publishEvent(any());
        verify(repository, never()).markRevoked(any(), any());
    }

    @Test
    void notFoundAnywhere_throwsNotFoundOrNotAuthorised() {
        when(registry.getActiveLinks(WALL_ID)).thenReturn(Set.of());
        when(repository.markRevokedByHash8(eq("deadbeef"), eq(WALL_ID), any())).thenReturn(false);

        assertThatThrownBy(() -> service.revokeByHash8("deadbeef", WALL_ID, T0))
                .isInstanceOf(ShareLinkNotFoundOrNotAuthorisedException.class);
    }

    @Test
    void malformedIdHash8_throwsBeforeAnyLookup() {
        // Not 8 lowercase hex chars → rejected as not-found before touching registry or repository.
        assertThatThrownBy(() -> service.revokeByHash8("NOT-HEX!", WALL_ID, T0))
                .isInstanceOf(ShareLinkNotFoundOrNotAuthorisedException.class);

        verifyNoInteractions(repository, registry, eventPublisher);
    }

    @Test
    void nullIdHash8_throwsBeforeAnyLookup() {
        assertThatThrownBy(() -> service.revokeByHash8(null, WALL_ID, T0))
                .isInstanceOf(ShareLinkNotFoundOrNotAuthorisedException.class);

        verifyNoInteractions(repository, registry, eventPublisher);
    }
}
