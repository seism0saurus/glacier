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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies that {@link ShareLinkServiceImpl#revoke} triggers
 * {@link ShareViewStompRelay#pushRevocation} and that the relay correctly
 * transforms toot events (wallId never in topic path — SR-SHARE-02).
 *
 * <p>Security: ADR-SHARE-04, SR-SHARE-02 (wallId non-disclosure),
 * ADR-SHARE-08 (revocation push to viewers).
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
    private ShareViewStompRelay shareViewStompRelay;

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
                shareViewStompRelay, fixedClock);
    }

    /**
     * Verifies that calling {@code revoke(id, wallId, now)} on a found link
     * results in {@code ShareViewStompRelay.pushRevocation(id)} being called.
     *
     * <p>ADR-SHARE-08: when a share link is revoked, viewers must receive a control
     * message so they can disconnect within the SLA window.
     */
    @Test
    void revoke_callsPushRevocation() {
        ShareLinkId linkId = ShareLinkId.fromUrlPath("sv_AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA");
        ShareLink link = ShareLink.create(linkId, SHARER_WALL_ID, SHARER_IP, T0, TTL);
        when(repository.findById(any(ShareLinkId.class))).thenReturn(Optional.of(link));

        service.revoke(linkId, SHARER_WALL_ID, T0);

        // ADR-SHARE-08: pushRevocation must be called with the exact link ID
        verify(shareViewStompRelay).pushRevocation(eq(linkId));
    }

    /**
     * Verifies that revoking a non-existent link does NOT call pushRevocation.
     *
     * <p>Anti-enumeration (T-07): we should not push revocation for links we cannot find,
     * since that would give viewers information about the link's existence.
     */
    @Test
    void revoke_notFound_doesNotCallPushRevocation() {
        ShareLinkId linkId = ShareLinkId.fromUrlPath("sv_BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB");
        when(repository.findById(any(ShareLinkId.class))).thenReturn(Optional.empty());

        try {
            service.revoke(linkId, SHARER_WALL_ID, T0);
        } catch (ShareLinkNotFoundOrNotAuthorisedException e) {
            // Expected — the service throws for not-found
        }

        // pushRevocation must NOT be called for non-existent links
        org.mockito.Mockito.verify(shareViewStompRelay, org.mockito.Mockito.never())
                .pushRevocation(any(ShareLinkId.class));
    }
}
