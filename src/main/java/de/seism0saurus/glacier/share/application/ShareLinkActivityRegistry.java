package de.seism0saurus.glacier.share.application;

import de.seism0saurus.glacier.share.domain.ShareLinkId;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Collections;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * In-memory routing table that tracks which raw {@link ShareLinkId} values are currently
 * active for each sharer's wallId.
 *
 * <p>{@link de.seism0saurus.glacier.share.application.ShareViewStompRelay#relayTootEvent}
 * reads {@link #getActiveLinks(String)} to obtain the STOMP topic suffixes without querying
 * the database. This replaces the deprecated {@link ShareLinkService#listBySharer} call,
 * which silently returns empty under the SQLite adapter (production blocker ADR-RELAY-01).
 *
 * <p>The registry is populated via Spring {@link org.springframework.context.ApplicationEvent}s
 * emitted by {@link ShareLinkServiceImpl}: {@link ShareLinkActivatedEvent} (on create) and
 * {@link ShareLinkRevokedEvent} (on revoke). Listeners live in {@link ShareViewStompRelay}.
 *
 * <h2>Thread safety</h2>
 * <ul>
 *   <li>The outer map is a {@link ConcurrentHashMap} keyed by {@code sharerWallId}; values
 *       are {@link CopyOnWriteArraySet} instances, making read-heavy iteration safe without
 *       additional locking.</li>
 *   <li>Per-linkId sentinel locks ({@code linkLocks}) serialise concurrent
 *       {@link #register} / {@link #unregister} calls for the same link, closing the
 *       TOCTOU window between the STOMP handshake's {@code resolve()} call and the
 *       subsequent insertion into the registry (ADR-RELAY-03, SR-RELAY-07).</li>
 * </ul>
 *
 * <h2>Known growth characteristics (Open Risk R2 / R3)</h2>
 * <p>{@code linkLocks} grows monotonically with distinct {@link ShareLinkId} values seen
 * because revocation does not remove the sentinel — removal would reintroduce a race.
 * At Glacier scale (~1 000 active links), this is negligible. The registry is cleared
 * via {@link #clear()} on shutdown.
 *
 * <h2>Security notes</h2>
 * <ul>
 *   <li>{@link #register} is restricted to
 *       {@code webservice.messaging.ShareViewPrincipalHandler} (ARCH-RELAY-02).</li>
 *   <li>{@link #unregister} is restricted to
 *       {@code share.application.ShareViewStompRelay} (ARCH-RELAY-02b).</li>
 *   <li>{@link #getActiveLinks} returns an unmodifiable view — callers cannot mutate
 *       the internal set (SR-RELAY-08).</li>
 * </ul>
 *
 * @see ShareViewStompRelay
 * @see ShareLinkActivatedEvent
 * @see ShareLinkRevokedEvent
 */
@Component
public class ShareLinkActivityRegistry {

    /**
     * Primary routing table: sharerWallId → set of active ShareLinkIds.
     *
     * <p>Values are {@link CopyOnWriteArraySet} for iteration safety; the per-linkId
     * sentinel lock handles write-write races between register and unregister for the
     * same link.
     */
    private final ConcurrentHashMap<String, CopyOnWriteArraySet<ShareLinkId>> registry =
            new ConcurrentHashMap<>();

    /**
     * Per-linkId sentinel objects used as monitor locks.
     *
     * <p>Each {@link ShareLinkId} gets a dedicated sentinel so that register/unregister
     * for link A and register/unregister for link B never contend with each other.
     * Sentinels are created lazily via {@link ConcurrentHashMap#computeIfAbsent} (which
     * is itself race-free) and are never removed, avoiding the re-introduction of a race
     * on removal (ADR-RELAY-03; SR-RELAY-07).
     */
    private final ConcurrentHashMap<ShareLinkId, Object> linkLocks = new ConcurrentHashMap<>();

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Attempts to register the given share link in the routing table.
     *
     * <p>This method is called from the STOMP handshake path
     * ({@code ShareViewPrincipalHandler}) after the link passes the initial
     * {@code resolve()} check. Under the per-linkId lock, it re-resolves the link to
     * detect a concurrent revocation that arrived between the handshake's initial
     * {@code resolve()} and this call (TOCTOU mitigation — ADR-RELAY-03, SR-RELAY-06).
     *
     * <p>If the re-resolved link is not active, the registration is aborted and
     * {@code false} is returned. The caller ({@code ShareViewPrincipalHandler}) must
     * reject the handshake with HTTP 403 in that case (SR-RELAY-13).
     *
     * @param sharerWallId     the wallId of the sharer who owns the link
     * @param shareLinkId      the share-link ID to register
     * @param shareLinkService service used to re-resolve the link under the lock
     * @param now              current instant (forwarded to {@code shareLinkService.resolve})
     * @return {@code true} if the link is active and has been registered;
     *         {@code false} if the link was revoked or expired concurrently
     */
    public boolean register(
            final String sharerWallId,
            final ShareLinkId shareLinkId,
            final ShareLinkService shareLinkService,
            final Instant now) {

        Object lock = linkLocks.computeIfAbsent(shareLinkId, ignored -> new Object());
        synchronized (lock) {
            // Re-resolve under lock: a concurrent onRevoke may have called unregister()
            // between the caller's initial resolve() and this point (SR-RELAY-06, TOCTOU).
            Optional<de.seism0saurus.glacier.share.domain.ShareLink> active =
                    shareLinkService.resolve(shareLinkId, now);
            if (active.isEmpty()) {
                return false;
            }
            registry.computeIfAbsent(sharerWallId, ignored -> new CopyOnWriteArraySet<>())
                    .add(shareLinkId);
            return true;
        }
    }

    /**
     * Removes the given share link from the routing table for the specified sharer.
     *
     * <p>This method must be called from within the per-linkId lock acquired by
     * {@link de.seism0saurus.glacier.share.application.ShareViewStompRelay#onRevoke}
     * to ensure that the unregister and the subsequent
     * {@link ShareViewStompRelay#pushRevocation} happen atomically (SR-RELAY-02,
     * SR-RELAY-07, ARCH-RELAY-02b).
     *
     * <p>Calling this method for a link that is not registered is a no-op.
     *
     * @param sharerWallId the wallId of the sharer who owns the link
     * @param shareLinkId  the share-link ID to remove
     */
    public void unregister(final String sharerWallId, final ShareLinkId shareLinkId) {
        Object lock = linkLocks.computeIfAbsent(shareLinkId, ignored -> new Object());
        synchronized (lock) {
            CopyOnWriteArraySet<ShareLinkId> set = registry.get(sharerWallId);
            if (set != null) {
                set.remove(shareLinkId);
            }
        }
    }

    /**
     * Returns the set of currently active share-link IDs for the given sharer.
     *
     * <p>The returned set is unmodifiable — callers must not attempt mutation
     * (SR-RELAY-08). An empty set is returned when no links are registered for the
     * given sharer; {@code null} is never returned.
     *
     * @param sharerWallId the sharer's wallId to look up
     * @return an unmodifiable snapshot of the active link IDs; never {@code null}
     */
    public Set<ShareLinkId> getActiveLinks(final String sharerWallId) {
        CopyOnWriteArraySet<ShareLinkId> set = registry.get(sharerWallId);
        if (set == null || set.isEmpty()) {
            return Collections.emptySet();
        }
        return Collections.unmodifiableSet(set);
    }

    /**
     * Clears all entries from the registry and the lock sentinels.
     *
     * <p>Called by the Spring container on bean shutdown ({@link PreDestroy}) to ensure
     * clean state between integration test runs (SR-RELAY-21). Production restarts also
     * benefit from an orderly shutdown — in-memory state is not durable by design
     * (SR-RELAY-12).
     */
    @PreDestroy
    public void clear() {
        registry.clear();
        linkLocks.clear();
    }
}
