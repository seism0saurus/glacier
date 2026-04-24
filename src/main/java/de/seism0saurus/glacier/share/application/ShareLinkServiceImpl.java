package de.seism0saurus.glacier.share.application;

import de.seism0saurus.glacier.share.domain.SecureRandomTokenGenerator;
import de.seism0saurus.glacier.share.domain.ShareLink;
import de.seism0saurus.glacier.share.domain.ShareLinkCapPolicy;
import de.seism0saurus.glacier.share.domain.ShareLinkId;
import de.seism0saurus.glacier.share.domain.ShareLinkLifetimePolicy;
import de.seism0saurus.glacier.share.domain.ShareLinkRepository;
import de.seism0saurus.glacier.share.domain.ShareLinkRevocationException;
import de.seism0saurus.glacier.share.domain.ShareLinkStatus;
import de.seism0saurus.glacier.util.LogScrubber;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Default implementation of {@link ShareLinkService}.
 *
 * <p>Orchestrates the share-link lifecycle by composing:
 * <ul>
 *   <li>{@link ShareLinkRepository} — persistence (ConcurrentHashMap-backed in-memory store).</li>
 *   <li>{@link SecureRandomTokenGenerator} — ID generation.</li>
 *   <li>{@link ShareLinkLifetimePolicy} — TTL configuration.</li>
 *   <li>{@link ShareLinkCapPolicy} — per-sharer and per-IP caps.</li>
 *   <li>{@link Clock} — injected for test determinism (never calls {@code Instant.now()}
 *       directly).</li>
 * </ul>
 *
 * <p>Logging discipline (D-13, SR-8):
 * <ul>
 *   <li>Share link IDs logged as {@link ShareLinkId#hash8()} only.</li>
 *   <li>WallIds logged as {@link LogScrubber#hash8(String)} only.</li>
 *   <li>IPs logged as {@link LogScrubber#maskIp(String)} only.</li>
 *   <li>Security events routed to the {@code AUDIT} logger.</li>
 * </ul>
 */
@Service
public class ShareLinkServiceImpl implements ShareLinkService {

    private static final Logger LOGGER = LoggerFactory.getLogger(ShareLinkServiceImpl.class);
    private static final Logger AUDIT = LoggerFactory.getLogger("AUDIT");

    private final ShareLinkRepository repository;
    private final SecureRandomTokenGenerator tokenGenerator;
    private final ShareLinkLifetimePolicy lifetimePolicy;
    private final ShareLinkCapPolicy capPolicy;
    private final ShareViewStompRelay shareViewStompRelay;
    private final Clock clock;

    /**
     * Constructs the service with all required collaborators.
     *
     * @param repository          persistence for {@link ShareLink} aggregates
     * @param tokenGenerator      cryptographically strong ID generator
     * @param lifetimePolicy      TTL and sweep configuration
     * @param capPolicy           per-sharer and per-IP caps
     * @param shareViewStompRelay relay used to push revocation control messages to viewers
     * @param clock               injected clock for time operations (use {@link Clock#fixed} in tests)
     */
    public ShareLinkServiceImpl(
            final ShareLinkRepository repository,
            final SecureRandomTokenGenerator tokenGenerator,
            final ShareLinkLifetimePolicy lifetimePolicy,
            final ShareLinkCapPolicy capPolicy,
            final ShareViewStompRelay shareViewStompRelay,
            final Clock clock) {
        this.repository = repository;
        this.tokenGenerator = tokenGenerator;
        this.lifetimePolicy = lifetimePolicy;
        this.capPolicy = capPolicy;
        this.shareViewStompRelay = shareViewStompRelay;
        this.clock = clock;
    }

    // -------------------------------------------------------------------------
    // ShareLinkService implementation
    // -------------------------------------------------------------------------

    @Override
    public ShareLink create(final String sharerWallId, final String sharerIp, final Instant now) {
        // Per-sharer cap check
        int activeForSharer = repository.countActiveForSharer(sharerWallId, now);
        if (activeForSharer >= capPolicy.getMaxActivePerSharer()) {
            AUDIT.info("share.cap.exceeded axis=sharer wallId-hash8={} limit={} activeCount={}",
                    LogScrubber.hash8(sharerWallId), capPolicy.getMaxActivePerSharer(), activeForSharer);
            throw new CapacityExceededException(
                    "sharer cap exceeded: " + activeForSharer + " >= " + capPolicy.getMaxActivePerSharer());
        }

        // Per-IP cap check
        int activeForIp = repository.countActiveForIp(sharerIp, now);
        if (activeForIp >= capPolicy.getMaxActivePerIp()) {
            AUDIT.info("share.cap.exceeded axis=ip ip={} limit={} activeCount={}",
                    LogScrubber.maskIp(sharerIp), capPolicy.getMaxActivePerIp(), activeForIp);
            throw new CapacityExceededException(
                    "ip cap exceeded: " + activeForIp + " >= " + capPolicy.getMaxActivePerIp());
        }

        ShareLinkId id = tokenGenerator.generateShareLinkId();
        ShareLink link = ShareLink.create(id, sharerWallId, sharerIp, now, lifetimePolicy.getTtl());
        repository.save(link);

        AUDIT.info("share.link.created shareId-hash8={} wallId-hash8={} expiresAt={} activeCount={}",
                id.hash8(), LogScrubber.hash8(sharerWallId), link.expiresAt(), activeForSharer + 1);

        return link;
    }

    @Override
    public Optional<ShareLink> resolve(final ShareLinkId id, final Instant now) {
        // Constant-time lookup: both "not found" and "not ACTIVE" return empty.
        // ConcurrentHashMap.get() is O(1) average case; timing variance at pathological
        // hash-collision scale is accepted (see ADR-SHARE-01 uncertainty §12.4).
        Optional<ShareLink> found = repository.findById(id);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        ShareLink link = found.get();
        if (link.status(now) != ShareLinkStatus.ACTIVE) {
            // Do NOT distinguish EXPIRED from REVOKED to callers — both are "not usable"
            return Optional.empty();
        }
        return Optional.of(link);
    }

    @Override
    public void revoke(final ShareLinkId id, final String callerWallId, final Instant now) {
        Optional<ShareLink> found = repository.findById(id);

        // Anti-enumeration (T-07): not-found and not-authorised throw the same exception type
        if (found.isEmpty()) {
            LOGGER.debug("revoke: link not found shareId-hash8={}", id.hash8());
            AUDIT.info("share.link.revoked shareId-hash8={} wallId-hash8={} outcome=notfound",
                    id.hash8(), LogScrubber.hash8(callerWallId));
            throw new ShareLinkNotFoundOrNotAuthorisedException("not found");
        }

        ShareLink link = found.get();
        try {
            link.revoke(callerWallId, now);
        } catch (ShareLinkRevocationException e) {
            // Domain-layer rejection: wallId mismatch → map to anti-enumeration exception
            LOGGER.debug("revoke: wallId mismatch shareId-hash8={}", id.hash8());
            AUDIT.info("share.link.revoked shareId-hash8={} wallId-hash8={} outcome=forbidden",
                    id.hash8(), LogScrubber.hash8(callerWallId));
            throw new ShareLinkNotFoundOrNotAuthorisedException("not authorised");
        }

        repository.markRevoked(id, now);
        AUDIT.info("share.link.revoked shareId-hash8={} wallId-hash8={} outcome=revoked",
                id.hash8(), LogScrubber.hash8(callerWallId));

        // ADR-SHARE-08: push revocation control message to viewers via STOMP
        // Viewers must disconnect within the SLA window after receiving the revoked message
        if (shareViewStompRelay != null) {
            shareViewStompRelay.pushRevocation(id);
        }
    }

    @Override
    public List<ShareLink> listBySharer(final String sharerWallId, final Instant now) {
        return repository.findAllBySharer(sharerWallId).stream()
                .filter(link -> link.status(now) == ShareLinkStatus.ACTIVE)
                .toList();
    }
}
