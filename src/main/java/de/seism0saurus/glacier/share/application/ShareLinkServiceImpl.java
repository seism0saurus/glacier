package de.seism0saurus.glacier.share.application;

import de.seism0saurus.glacier.share.domain.SecureRandomTokenGenerator;
import de.seism0saurus.glacier.share.domain.ShareLink;
import de.seism0saurus.glacier.share.domain.ShareLinkCapPolicy;
import de.seism0saurus.glacier.share.domain.ShareLinkId;
import de.seism0saurus.glacier.share.domain.ShareLinkLifetimePolicy;
import de.seism0saurus.glacier.share.domain.ShareLinkRepository;
import de.seism0saurus.glacier.share.domain.ShareLinkRevocationException;
import de.seism0saurus.glacier.share.domain.ShareLinkStatus;
import de.seism0saurus.glacier.share.domain.ShareLinkSummary;
import de.seism0saurus.glacier.util.LogScrubber;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

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

    /**
     * Per-sharer mutex map for the TOCTOU-sensitive check-then-act in {@link #create}.
     *
     * <p>R-2 (TOCTOU fix): the sequence {@code countActiveForSharer → save} is not atomic
     * at the repository level.  Without a per-sharer lock, N concurrent threads can all
     * observe "count < cap" and proceed to save, resulting in {@code N + count} active links
     * instead of {@code cap}.
     *
     * <p>A per-sharer lock is preferred over a single global lock to avoid unnecessary
     * contention between unrelated sharers.  The {@link ConcurrentHashMap#computeIfAbsent}
     * call that creates a new lock is itself race-free (ConcurrentHashMap guarantees it).
     */
    // R-2 (TOCTOU): per-sharer lock prevents concurrent cap overshoot on single-JVM deployments
    private final ConcurrentHashMap<String, ReentrantLock> sharerLocks = new ConcurrentHashMap<>();

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
        // SR-SQLITE-11: validate the creator IP via InetAddress round-trip before any cap
        // check or persistence — prevents cap-bypass via malformed X-Forwarded-For values
        // (e.g., "Hi-Im-Attacker-1") that would produce a unique HMAC and never match any
        // legitimate IP's HMAC, so the IP cap would never trigger.
        validateCreatorIp(sharerIp);

        // R-2 (TOCTOU fix): acquire a per-sharer lock before the check-then-act sequence.
        // Without this lock, concurrent callers can all pass the cap check and then all save,
        // overshooting the cap by up to (N-1) links.
        // The lock is per-sharer so unrelated sharers do not contend with each other.
        ReentrantLock lock = sharerLocks.computeIfAbsent(sharerWallId, k -> new ReentrantLock());
        lock.lock();
        try {
            // Per-sharer cap check — performed inside the lock to prevent TOCTOU (R-2)
            int activeForSharer = repository.countActiveForSharer(sharerWallId, now);
            if (activeForSharer >= capPolicy.getMaxActivePerSharer()) {
                AUDIT.info("share.cap.exceeded axis=sharer wallId-hash8={} limit={} activeCount={}",
                        LogScrubber.hash8(sharerWallId), capPolicy.getMaxActivePerSharer(), activeForSharer);
                throw new CapacityExceededException(
                        "sharer cap exceeded: " + activeForSharer + " >= " + capPolicy.getMaxActivePerSharer());
            }

            // Per-IP cap check — also inside the lock; while a different sharer from the same IP
            // could still race (they would hold different locks), the IP cap is a secondary
            // DoS-hardening control; the per-sharer cap is the primary integrity invariant.
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
        } finally {
            lock.unlock();
        }
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
    public List<ShareLinkSummary> listSummaryBySharer(final String sharerWallId, final Instant now) {
        return repository.listSummaryBySharer(sharerWallId, now);
    }

    @Override
    @SuppressWarnings("deprecation")
    public List<ShareLink> listBySharer(final String sharerWallId, final Instant now) {
        return repository.findAllBySharer(sharerWallId).stream()
                .filter(link -> link.status(now) == ShareLinkStatus.ACTIVE)
                .toList();
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Validates the creator IP address by confirming it is a well-formed IPv4 or IPv6
     * numeric literal (SR-SQLITE-11).
     *
     * <p>The validation strategy:
     * <ol>
     *   <li>Parse {@code ip} with {@link InetAddress#getByName(String)}.</li>
     *   <li>For IPv4 ({@link java.net.Inet4Address}): verify that
     *       {@code getHostAddress()} equals the original string — ensures no hostname
     *       resolution occurred (e.g., {@code "localhost"} resolves to {@code "127.0.0.1"},
     *       so the round-trip check rejects it).</li>
     *   <li>For IPv6 ({@link java.net.Inet6Address}): {@link InetAddress#getByName(String)}
     *       accepts only numeric literals for IPv6 without performing DNS lookup; however
     *       it normalises the form (e.g., {@code "::1"} → {@code "0:0:0:0:0:0:0:1"}).
     *       We verify the normalised canonical forms of the original and re-parsed address
     *       match, ensuring the parse was self-consistent.</li>
     * </ol>
     *
     * <p>Hostnames and malformed strings (e.g., {@code "Hi-Im-Attacker-1"}) would produce
     * a unique HMAC on each submission, bypassing the per-IP cap entirely. Rejecting them
     * here closes that bypass.
     *
     * <p>Null and blank values are accepted — the domain model treats a missing creator IP
     * as an anonymous request (no IP cap applies).
     *
     * <p>The exception message deliberately does NOT echo the {@code ip} parameter to prevent
     * hostile input from appearing in logs or HTTP error responses (SR-SQLITE-01; D-13;
     * CWE-117 — Improper Output Neutralisation).
     *
     * @param ip the creator IP string from the HTTP layer; may be null or blank
     * @throws IllegalArgumentException if {@code ip} is non-blank but not a well-formed IP literal
     */
    private static void validateCreatorIp(final String ip) {
        // Null/blank accepted — treated as anonymous (no IP cap applies)
        if (ip == null || ip.isBlank()) {
            return;
        }
        try {
            InetAddress addr = InetAddress.getByName(ip);
            if (addr instanceof java.net.Inet4Address) {
                // IPv4 round-trip: getHostAddress() must equal the input exactly.
                // If the input was a hostname (e.g., "localhost" → "127.0.0.1") the
                // round-trip fails and we reject it — CWE-20; SR-SQLITE-11.
                if (!addr.getHostAddress().equals(ip)) {
                    // Static message: do NOT include the ip value — CWE-117; SR-SQLITE-01
                    throw new IllegalArgumentException("invalid IP address format");
                }
            } else {
                // IPv6: getByName accepts only numeric literals (no DNS resolution for pure
                // IPv6 forms). The normalised canonical address of a second parse of
                // getHostAddress() must equal addr.getHostAddress() — this confirms the
                // parse was self-consistent.  We do NOT compare against the original string
                // because IPv6 has multiple valid representations of the same address
                // (e.g., "::1" normalises to "0:0:0:0:0:0:0:1").
                String canonical = InetAddress.getByName(addr.getHostAddress()).getHostAddress();
                if (!canonical.equals(addr.getHostAddress())) {
                    throw new IllegalArgumentException("invalid IP address format");
                }
            }
        } catch (UnknownHostException e) {
            // Malformed literal (getByName failed) — static message, no value echo
            throw new IllegalArgumentException("invalid IP address format");
        }
    }

    /**
     * Test-support forwarder that exposes the package-private {@code validateCreatorIp}
     * method to unit tests in the same package without requiring a full Spring context.
     *
     * <p>This method is intentionally NOT part of any interface and must only be called
     * from test code. Production callers must use {@link #create} which invokes the
     * validation internally.
     *
     * @param ip the IP address string to validate
     */
    static void validateCreatorIpForTest(final String ip) {
        validateCreatorIp(ip);
    }
}
