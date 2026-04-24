package de.seism0saurus.glacier.share.application;

import de.seism0saurus.glacier.share.domain.ShareLinkId;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Thread-safe in-process counter tracking the number of active viewer WebSocket sessions
 * per share link.
 *
 * <p>Enforces the {@code glacier.share.maxViewersPerLink} cap declared in
 * {@link de.seism0saurus.glacier.share.domain.ShareLinkCapPolicy} (SR-SHARE-05).
 *
 * <p>Usage pattern (enforced by {@link de.seism0saurus.glacier.webservice.messaging.ShareViewPrincipalHandler}):
 * <ol>
 *   <li>At STOMP handshake: call {@link #increment(ShareLinkId)} and check the returned count.
 *       If the new count exceeds the cap, call {@link #decrement(ShareLinkId)} and reject the
 *       handshake (fail-closed — OWASP API4, NIST SP 800-53 SC-5).</li>
 *   <li>On STOMP session disconnect: call {@link #decrement(ShareLinkId)} to free the slot.</li>
 * </ol>
 *
 * <p>Security: OWASP API4 (Unrestricted Resource Consumption), ADR-SHARE-05, SR-SHARE-05.
 * Only {@link de.seism0saurus.glacier.webservice.messaging.ShareViewerPrincipal} sessions
 * count against the cap — {@link de.seism0saurus.glacier.webservice.messaging.WallPrincipal}
 * sessions are never tracked here (glacier-fallback-mode-discipline: isolation of viewer and
 * sharer namespaces).
 */
@Component
public class ShareLinkViewerCounter {

    /**
     * Per-link counter map. Entries are added lazily on first increment and never removed —
     * the zero-value entry is cheap and avoids a race between remove() and concurrent
     * increment()/decrement() calls.
     */
    private final ConcurrentHashMap<ShareLinkId, AtomicInteger> counters = new ConcurrentHashMap<>();

    /**
     * Atomically increments the viewer count for the given share link and returns the new count.
     *
     * <p>The caller must check the returned value against the cap and call
     * {@link #decrement(ShareLinkId)} to refund if the cap is exceeded (fail-closed pattern).
     *
     * @param shareLinkId the share link to increment; must not be null
     * @return the new viewer count after the increment
     */
    public int increment(final ShareLinkId shareLinkId) {
        return counters
                .computeIfAbsent(shareLinkId, id -> new AtomicInteger(0))
                .incrementAndGet();
    }

    /**
     * Atomically decrements the viewer count for the given share link.
     * The count will never go below zero.
     *
     * @param shareLinkId the share link to decrement; must not be null
     */
    public void decrement(final ShareLinkId shareLinkId) {
        AtomicInteger counter = counters.get(shareLinkId);
        if (counter == null) {
            return; // nothing to decrement
        }
        // getAndUpdate: ensure we never go below 0 (guard against spurious decrements)
        counter.updateAndGet(current -> current > 0 ? current - 1 : 0);
    }

    /**
     * Returns the current viewer count for the given share link.
     * Returns {@code 0} if no viewers have ever connected to this link.
     *
     * @param shareLinkId the share link to query; must not be null
     * @return the current viewer count; always &ge; 0
     */
    public int get(final ShareLinkId shareLinkId) {
        AtomicInteger counter = counters.get(shareLinkId);
        return counter == null ? 0 : counter.get();
    }
}
