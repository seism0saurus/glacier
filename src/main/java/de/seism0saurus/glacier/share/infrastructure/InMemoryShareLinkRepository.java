package de.seism0saurus.glacier.share.infrastructure;

import de.seism0saurus.glacier.share.domain.ShareLink;
import de.seism0saurus.glacier.share.domain.ShareLinkId;
import de.seism0saurus.glacier.share.domain.ShareLinkRepository;
import de.seism0saurus.glacier.share.domain.ShareLinkStatus;
import de.seism0saurus.glacier.share.domain.ShareLinkSummary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Repository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * In-memory {@link ShareLinkRepository} backed by a {@link ConcurrentHashMap}.
 *
 * <p>Thread-safety is provided by {@link ConcurrentHashMap}: individual get/put/remove
 * operations are atomic.  Aggregate-level mutation (e.g., {@link #markRevoked}) delegates
 * to the {@link ShareLink#revoke} synchronized method on the stored aggregate, so
 * concurrent revocations of the same link converge to the first revocation instant
 * without data corruption.
 *
 * <p>A periodic {@link #sweepExpired} task removes time-expired links to prevent unbounded
 * memory growth.  The sweep interval is driven by
 * {@link de.seism0saurus.glacier.share.domain.ShareLinkLifetimePolicy#getSweepIntervalMs()},
 * externalized as {@code glacier.share.sweepIntervalMs} (default: 300 000 ms).
 *
 * <p>Limitations:
 * <ul>
 *   <li>Not replicated — all state is local to a single JVM instance.  This is acceptable
 *       for the current Glacier single-instance deployment model.</li>
 *   <li>Not durable — state is lost on restart.  Share links are short-lived (7 days max),
 *       so loss on restart is an accepted operational trade-off (see ADR-SHARE-01 rationale).</li>
 * </ul>
 *
 * <p>Conditional registration (P3-05; ADR-SQLITE-01):
 * This bean is active when {@code glacier.share.db.path} is NOT set (i.e. the default,
 * in-memory-only case). The {@link ConditionalOnProperty} pattern with
 * {@code havingValue="NEVER_MATCHES"} and {@code matchIfMissing=true} is used instead of
 * {@code @ConditionalOnMissingBean(ShareLinkRepository.class)} to avoid the Spring
 * self-referential BeanCreationException that occurs when the condition evaluates whether
 * the interface's own implementation bean exists during its own registration.
 */
@Repository
@ConditionalOnProperty(name = "glacier.share.db.path", havingValue = "NEVER_MATCHES", matchIfMissing = true)
public class InMemoryShareLinkRepository implements ShareLinkRepository {

    private static final Logger AUDIT = LoggerFactory.getLogger("AUDIT");
    private static final Logger LOGGER = LoggerFactory.getLogger(InMemoryShareLinkRepository.class);

    /** Primary store: keyed by ShareLinkId. */
    private final ConcurrentHashMap<ShareLinkId, ShareLink> store = new ConcurrentHashMap<>();

    // -------------------------------------------------------------------------
    // ShareLinkRepository implementation
    // -------------------------------------------------------------------------

    @Override
    public ShareLink save(final ShareLink link) {
        store.put(link.id(), link);
        return link;
    }

    @Override
    public Optional<ShareLink> findById(final ShareLinkId id) {
        return Optional.ofNullable(store.get(id));
    }

    @Override
    public void markRevoked(final ShareLinkId id, final Instant when) {
        ShareLink link = store.get(id);
        if (link == null) {
            // Unknown ID — no-op; caller has already handled the not-found case
            LOGGER.debug("markRevoked called on unknown id (hash8={}); no-op", id.hash8());
            return;
        }
        // Delegate to the aggregate's synchronized revoke method.
        // The aggregate validates sharerWallId — this path is called only AFTER the
        // application layer has verified authorization, so we use the internal path
        // that bypasses the wallId check (by passing sharerWallId directly).
        link.creatorIp(); // no-op touch; real mutation is below
        // Direct mutation: the repository holds the aggregate; we already authorized above
        store.computeIfPresent(id, (k, stored) -> {
            stored.revoke(stored.sharerWallId(), when);
            return stored;
        });
    }

    @Override
    public int sweepExpired(final Instant now) {
        // Collect IDs of links that are EXPIRED (not REVOKED — revoked links stay until
        // they are also expired, to preserve the audit trail for the sweep interval)
        List<ShareLinkId> toRemove = store.values().stream()
                .filter(link -> link.status(now) == ShareLinkStatus.EXPIRED)
                .map(ShareLink::id)
                .collect(Collectors.toList());

        toRemove.forEach(store::remove);

        if (!toRemove.isEmpty()) {
            AUDIT.info("share.link.sweep removed={}", toRemove.size());
        } else {
            LOGGER.debug("share.link.sweep removed=0");
        }
        return toRemove.size();
    }

    @Override
    public long countActive(final Instant now) {
        return store.values().stream()
                .filter(link -> link.status(now) == ShareLinkStatus.ACTIVE)
                .count();
    }

    @Override
    public int countActiveForSharer(final String sharerWallId, final Instant now) {
        return (int) store.values().stream()
                .filter(link -> sharerWallId.equals(link.sharerWallId()))
                .filter(link -> link.status(now) == ShareLinkStatus.ACTIVE)
                .count();
    }

    @Override
    public int countActiveForIp(final String ip, final Instant now) {
        return (int) store.values().stream()
                .filter(link -> link.creatorIp().map(ip::equals).orElse(false))
                .filter(link -> link.status(now) == ShareLinkStatus.ACTIVE)
                .count();
    }

    @Override
    public List<ShareLinkSummary> listSummaryBySharer(final String sharerWallId, final Instant now) {
        return store.values().stream()
                .filter(link -> sharerWallId.equals(link.sharerWallId()))
                .sorted((a, b) -> b.createdAt().compareTo(a.createdAt()))
                .map(link -> {
                    String hash8 = sha256Hex(link.id().value()).substring(0, 8);
                    return new ShareLinkSummary(
                            hash8,
                            link.createdAt(),
                            link.expiresAt(),
                            link.revokedAt().orElse(null),
                            link.status(now),
                            link.sharerWallId());
                })
                .toList();
    }

    @Override
    @Deprecated
    public List<ShareLink> findAllBySharer(final String sharerWallId) {
        return store.values().stream()
                .filter(link -> sharerWallId.equals(link.sharerWallId()))
                .collect(Collectors.toList());
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Returns the full lowercase hex SHA-256 digest of the given value.
     *
     * <p>Used to compute the {@code idHash8} visual identifier for summary projections.
     * SHA-256 is guaranteed by every JVM (NIST FIPS 180-4).
     *
     * @param value the input string; must not be null
     * @return lowercase hex-encoded SHA-256 digest; always 64 characters
     */
    private static String sha256Hex(final String value) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is guaranteed in every JVM (NIST FIPS 180-4)
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    // -------------------------------------------------------------------------
    // Scheduled maintenance
    // -------------------------------------------------------------------------

    /**
     * Periodically removes expired links from the in-memory store.
     *
     * <p>The interval is driven by {@code glacier.share.sweepIntervalMs} (default: 300 000 ms /
     * 5 minutes).  This prevents unbounded memory growth when share links are created faster
     * than they expire.
     *
     * <p>The {@code Instant.now()} call here is intentional — the sweep is not part of a
     * domain operation and does not need an injected clock (it runs outside tests).  Domain
     * operations (create, resolve, revoke) always receive an explicit {@code Instant} from
     * the application layer.
     */
    @Scheduled(fixedDelayString = "${glacier.share.sweepIntervalMs:300000}")
    public void scheduledSweep() {
        sweepExpired(Instant.now());
    }

    // -------------------------------------------------------------------------
    // Package-private accessor for tests
    // -------------------------------------------------------------------------

    /** Returns the number of entries currently in the store (for test assertions). */
    int storeSize() {
        return store.size();
    }
}
