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
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests proving that {@link ShareLinkServiceImpl} emits the correct domain events
 * via {@link ApplicationEventPublisher} and no longer has any dependency on
 * {@link ShareViewStompRelay} (ARCH-RELAY-06; SR-RELAY-23).
 *
 * <p>Arrange / Act / Assert:
 * <ul>
 *   <li><b>Arrange</b> — fixed clock, mocked repository + event publisher.</li>
 *   <li><b>Act</b>    — call {@code create()} or {@code revoke()}.</li>
 *   <li><b>Assert</b> — {@link ArgumentCaptor} on {@code publishEvent()} verifies event type
 *       and field values.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class ShareLinkServiceImplEventEmissionTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
    private static final Duration TTL = Duration.ofDays(7);
    private static final String SHARER_WALL_ID = "sharer-wall-id-event-emission-000000";
    private static final String SHARER_IP = "10.0.0.42";

    @Mock
    private ShareLinkRepository repository;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    private ShareLinkServiceImpl service;
    private SecureRandomTokenGenerator tokenGenerator;

    @BeforeEach
    void setUp() {
        Clock fixedClock = Clock.fixed(T0, ZoneOffset.UTC);
        ShareLinkLifetimePolicy lifetimePolicy = new ShareLinkLifetimePolicy();
        lifetimePolicy.setTtl(TTL);
        ShareLinkCapPolicy capPolicy = new ShareLinkCapPolicy();
        tokenGenerator = new SecureRandomTokenGenerator();

        // Service is constructed WITHOUT ShareViewStompRelay — ARCH-RELAY-06
        service = new ShareLinkServiceImpl(
                repository, tokenGenerator, lifetimePolicy, capPolicy, eventPublisher,
                new ShareLinkActivityRegistry(), fixedClock);
    }

    // -----------------------------------------------------------------------
    // create() — emits ShareLinkActivatedEvent after save
    // -----------------------------------------------------------------------

    /**
     * After a successful {@code create()}, a {@link ShareLinkActivatedEvent} must be
     * published with the correct sharerWallId and the generated shareLinkId.
     *
     * <p>Arrange: repository caps are satisfied; save echoes the argument.
     * <p>Act:     call {@code create(SHARER_WALL_ID, SHARER_IP, T0)}.
     * <p>Assert:  publishEvent receives a ShareLinkActivatedEvent whose sharerWallId
     *             matches SHARER_WALL_ID.
     */
    @Test
    void create_publishesActivatedEvent_afterSave() {
        when(repository.countActiveForSharer(SHARER_WALL_ID, T0)).thenReturn(0);
        when(repository.countActiveForIp(SHARER_IP, T0)).thenReturn(0);
        when(repository.save(any(ShareLink.class))).thenAnswer(inv -> inv.getArgument(0));

        ShareLink created = service.create(SHARER_WALL_ID, SHARER_IP, T0);

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher).publishEvent(captor.capture());

        Object event = captor.getValue();
        assertThat(event).isInstanceOf(ShareLinkActivatedEvent.class);

        ShareLinkActivatedEvent activatedEvent = (ShareLinkActivatedEvent) event;
        assertThat(activatedEvent.sharerWallId()).isEqualTo(SHARER_WALL_ID);
        assertThat(activatedEvent.shareLinkId()).isEqualTo(created.id());
    }

    /**
     * The {@link ShareLinkActivatedEvent} must carry the correct sharerWallId.
     *
     * <p>Separate from the above to explicitly pin field correctness.
     */
    @Test
    void create_activatedEvent_contains_correctSharerWallId() {
        when(repository.countActiveForSharer(SHARER_WALL_ID, T0)).thenReturn(0);
        when(repository.countActiveForIp(SHARER_IP, T0)).thenReturn(0);
        when(repository.save(any(ShareLink.class))).thenAnswer(inv -> inv.getArgument(0));

        service.create(SHARER_WALL_ID, SHARER_IP, T0);

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher).publishEvent(captor.capture());

        ShareLinkActivatedEvent event = (ShareLinkActivatedEvent) captor.getValue();
        assertThat(event.sharerWallId()).isEqualTo(SHARER_WALL_ID);
    }

    // -----------------------------------------------------------------------
    // revoke() — emits ShareLinkRevokedEvent after markRevoked
    // -----------------------------------------------------------------------

    /**
     * After a successful {@code revoke()}, a {@link ShareLinkRevokedEvent} must be
     * published with the correct sharerWallId and shareLinkId.
     *
     * <p>Arrange: repository contains an active link for SHARER_WALL_ID.
     * <p>Act:     call {@code revoke(id, SHARER_WALL_ID, T0)}.
     * <p>Assert:  publishEvent receives a ShareLinkRevokedEvent with correct fields.
     */
    @Test
    void revoke_publishesRevokedEvent_afterMarkRevoked() {
        ShareLinkId id = tokenGenerator.generateShareLinkId();
        ShareLink link = ShareLink.create(id, SHARER_WALL_ID, T0, TTL);
        when(repository.findById(id)).thenReturn(Optional.of(link));

        service.revoke(id, SHARER_WALL_ID, T0);

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher).publishEvent(captor.capture());

        Object event = captor.getValue();
        assertThat(event).isInstanceOf(ShareLinkRevokedEvent.class);

        ShareLinkRevokedEvent revokedEvent = (ShareLinkRevokedEvent) event;
        assertThat(revokedEvent.shareLinkId()).isEqualTo(id);
        assertThat(revokedEvent.sharerWallId()).isEqualTo(SHARER_WALL_ID);
    }

    /**
     * The {@link ShareLinkRevokedEvent} must carry the correct sharerWallId.
     *
     * <p>Separate test to explicitly pin field correctness independent of ID verification.
     */
    @Test
    void revoke_revokedEvent_contains_correctSharerWallId() {
        ShareLinkId id = tokenGenerator.generateShareLinkId();
        ShareLink link = ShareLink.create(id, SHARER_WALL_ID, T0, TTL);
        when(repository.findById(id)).thenReturn(Optional.of(link));

        service.revoke(id, SHARER_WALL_ID, T0);

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher).publishEvent(captor.capture());

        ShareLinkRevokedEvent event = (ShareLinkRevokedEvent) captor.getValue();
        assertThat(event.sharerWallId()).isEqualTo(SHARER_WALL_ID);
    }

    // -----------------------------------------------------------------------
    // ARCH-RELAY-06: ShareViewStompRelay not in constructor
    // -----------------------------------------------------------------------

    /**
     * Proves that {@link ShareLinkServiceImpl} can be constructed without any
     * {@link ShareViewStompRelay} parameter, confirming ARCH-RELAY-06 compliance.
     *
     * <p>The constructor signature under test is:
     * {@code (ShareLinkRepository, SecureRandomTokenGenerator, ShareLinkLifetimePolicy,
     *          ShareLinkCapPolicy, ApplicationEventPublisher, Clock)}.
     *
     * <p>If a refactor inadvertently reintroduces a {@code ShareViewStompRelay} parameter,
     * the {@code setUp()} in this test class will fail to compile (or the ArchUnit rule
     * ARCH-RELAY-06 will fire at build time).
     */
    @Test
    void service_constructedWithoutShareViewStompRelay_doesNotThrow() {
        // If this test compiles and setUp() above does not fail, the service
        // does not depend on ShareViewStompRelay (ARCH-RELAY-06).
        assertThat(service).isNotNull();
    }
}
