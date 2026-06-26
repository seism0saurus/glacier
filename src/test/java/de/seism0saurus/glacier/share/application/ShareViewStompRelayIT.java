package de.seism0saurus.glacier.share.application;

import de.seism0saurus.glacier.share.domain.ShareLink;
import de.seism0saurus.glacier.share.domain.ShareLinkCapPolicy;
import de.seism0saurus.glacier.share.domain.ShareLinkId;
import de.seism0saurus.glacier.share.domain.ShareLinkLifetimePolicy;
import de.seism0saurus.glacier.share.domain.ShareLinkRepository;
import de.seism0saurus.glacier.share.domain.SecureRandomTokenGenerator;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies that {@link ShareLinkServiceImpl#revoke} publishes a {@link ShareLinkRevokedEvent}
 * via {@link ApplicationEventPublisher} — the event triggers {@link ShareViewStompRelay#onRevoke}
 * which calls {@code pushRevocation()} (ADR-RELAY-01; ARCH-RELAY-06; SR-RELAY-23).
 *
 * <p>Security: ADR-SHARE-04, ADR-RELAY-01, SR-SHARE-02 (wallId non-disclosure),
 * ADR-SHARE-08 (revocation push to viewers via event).
 */
@ExtendWith(MockitoExtension.class)
class ShareViewStompRelayIT {

    private static final Instant T0 = Instant.parse("2025-06-01T12:00:00Z");
    private static final Duration TTL = Duration.ofDays(7);
    private static final String SHARER_WALL_ID = "sharer-wall-id-fixture-test-0000000";
    private static final String SHARER_IP = "10.0.0.1";

    @Mock
    private ShareLinkRepository repository;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    private ShareLinkServiceImpl service;

    @BeforeEach
    void setUp() {
        Clock fixedClock = Clock.fixed(T0, ZoneOffset.UTC);
        ShareLinkLifetimePolicy lifetimePolicy = new ShareLinkLifetimePolicy();
        lifetimePolicy.setTtl(TTL);
        ShareLinkCapPolicy capPolicy = new ShareLinkCapPolicy();
        SecureRandomTokenGenerator tokenGenerator = new SecureRandomTokenGenerator();

        service = new ShareLinkServiceImpl(
                repository, tokenGenerator, lifetimePolicy, capPolicy,
                eventPublisher, new ShareLinkActivityRegistry(), fixedClock);
    }

    /**
     * Verifies that calling {@code revoke(id, wallId, now)} on a found link
     * results in a {@link ShareLinkRevokedEvent} being published via the event bus.
     *
     * <p>ADR-RELAY-01 / ARCH-RELAY-06: the service no longer calls
     * {@code shareViewStompRelay.pushRevocation()} directly — it publishes an event
     * and lets {@link ShareViewStompRelay#onRevoke} handle the push.
     */
    @Test
    void revoke_publishesRevokedEvent_withCorrectLinkId() {
        ShareLinkId linkId = ShareLinkId.fromUrlPath("sv_AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA");
        ShareLink link = ShareLink.create(linkId, SHARER_WALL_ID, SHARER_IP, T0, TTL);
        when(repository.findById(any(ShareLinkId.class))).thenReturn(Optional.of(link));

        service.revoke(linkId, SHARER_WALL_ID, T0);

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher).publishEvent(captor.capture());

        assertThat(captor.getValue()).isInstanceOf(ShareLinkRevokedEvent.class);
        ShareLinkRevokedEvent event = (ShareLinkRevokedEvent) captor.getValue();
        assertThat(event.shareLinkId()).isEqualTo(linkId);
    }

    /**
     * Verifying that revoking a non-existent link does NOT publish a revoked event.
     *
     * <p>Anti-enumeration (T-07): we should not emit a revocation event for links we cannot find.
     */
    @Test
    void revoke_notFound_doesNotPublishRevokedEvent() {
        ShareLinkId linkId = ShareLinkId.fromUrlPath("sv_BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB");
        when(repository.findById(any(ShareLinkId.class))).thenReturn(Optional.empty());

        try {
            service.revoke(linkId, SHARER_WALL_ID, T0);
        } catch (ShareLinkNotFoundOrNotAuthorisedException e) {
            // Expected — the service throws for not-found
        }

        verify(eventPublisher, never()).publishEvent(any(ShareLinkRevokedEvent.class));
    }
}
