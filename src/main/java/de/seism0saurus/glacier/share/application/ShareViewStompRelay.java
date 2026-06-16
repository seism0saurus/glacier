package de.seism0saurus.glacier.share.application;

import de.seism0saurus.glacier.share.domain.ShareLinkId;
import de.seism0saurus.glacier.util.LogScrubber;
import de.seism0saurus.glacier.webservice.cache.CacheEntry;
import de.seism0saurus.glacier.webservice.cache.MessageCache;
import de.seism0saurus.glacier.webservice.cache.Snapshot;
import de.seism0saurus.glacier.webservice.cache.UnknownSubscriptionException;
import de.seism0saurus.glacier.webservice.messaging.PrincipalKey;
import de.seism0saurus.glacier.webservice.messaging.PrincipalKind;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Relays sharer STOMP toot events to viewer-scoped share topics, and handles the
 * share-link lifecycle via Spring {@link EventListener} methods.
 *
 * <p>The sharer's wall receives toots on:
 * {@code /topic/hashtags/{wallId}/{hashtag}/{creation|modification|deletion}}
 *
 * <p>This relay re-publishes the same payload to all active share links for the
 * sharer's wallId, using viewer-safe topics:
 * {@code /topic/share/{shareLinkId}/{hashtag}/{eventType}}
 *
 * <p>Revocation is pushed as a control message:
 * {@code /topic/share/{shareLinkId}/control} with payload {@code {type:"revoked"}}.
 * Viewers that receive this must disconnect within the SLA window (ADR-SHARE-08).
 *
 * <h2>Routing table</h2>
 * <p>Active share-link IDs are tracked in {@link ShareLinkActivityRegistry}, which is
 * populated by {@link #onActivate(ShareLinkActivatedEvent)} and drained by
 * {@link #onRevoke(ShareLinkRevokedEvent)}. The deprecated
 * {@link ShareLinkService#listBySharer} method is no longer called here (ADR-RELAY-01).
 *
 * <h2>Security invariants</h2>
 * <ul>
 *   <li>The sharer's {@code wallId} NEVER appears in any viewer-facing topic path (SR-SHARE-02).</li>
 *   <li>Topics are scoped by {@code shareLinkId}, not by wallId, so viewers cannot
 *       discover other viewer sessions or the sharer's identity.</li>
 *   <li>The relay only publishes to ACTIVE links as recorded in {@link ShareLinkActivityRegistry}.</li>
 *   <li>Event listeners are synchronous — {@code @Async} is forbidden in this package
 *       (ARCH-RELAY-01; ARCH-RELAY-05; SR-RELAY-01).</li>
 *   <li>{@code onRevoke} calls {@code registry.unregister()} + {@code pushRevocation()}
 *       atomically under the per-linkId lock inside {@link ShareLinkActivityRegistry}
 *       (SR-RELAY-02; SR-RELAY-07).</li>
 * </ul>
 *
 * <p>ADR-SHARE-04, ADR-RELAY-01, SR-SHARE-02, SR-SHARE-06, OWASP API1 (BOLA).
 */
@Service
public class ShareViewStompRelay {

    private static final Logger log = LoggerFactory.getLogger(ShareViewStompRelay.class);
    private static final Logger AUDIT = LoggerFactory.getLogger("AUDIT");

    /** Viewer-facing STOMP topic prefix; wallId is NEVER part of this path (SR-SHARE-02). */
    private static final String SHARE_TOPIC_PREFIX = "/topic/share/";

    /**
     * Minimum interval between {@code share.relay.no_viewers} AUDIT.warn entries for the
     * same sharerWallId (SR-RELAY-20).
     */
    private static final Duration NO_VIEWERS_DEBOUNCE = Duration.ofSeconds(30);

    private final SimpMessagingTemplate messagingTemplate;
    private final ShareLinkService shareLinkService;
    private final MessageCache messageCache;
    private final ShareLinkActivityRegistry registry;

    /**
     * Clock used by {@link #emitNoViewersWarnWithDebounce} for debounce timing.
     * Injected so tests can substitute a {@link Clock#fixed} for deterministic assertion
     * (ACC-03 behavioral debounce test, SR-RELAY-20).
     */
    private final Clock clock;

    /**
     * Debounce state for the {@code share.relay.no_viewers} AUDIT.warn (SR-RELAY-09, SR-RELAY-20).
     * Maps sharerWallId to the instant of the last emitted warn. Grows with distinct
     * sharerWallId values seen (Open Risk R3 — negligible at Glacier scale).
     */
    private final ConcurrentHashMap<String, Instant> noViewersLastWarnAt = new ConcurrentHashMap<>();

    /**
     * Spring-managed constructor.
     *
     * @param messagingTemplate STOMP messaging template (lazy to break circular dep chain)
     * @param shareLinkService  share link service (lazy to break circular dep chain)
     * @param messageCache      message cache (lazy to break circular dep chain)
     * @param registry          active share link routing table
     * @param clock             clock for debounce timing; injected so tests can freeze time
     *                          (ACC-03 behavioral assertion, SR-RELAY-20)
     */
    public ShareViewStompRelay(
            // @Lazy on SimpMessagingTemplate and ShareLinkService breaks the mutual circular
            // dependency chain:
            //   shareViewStompRelay → SimpMessagingTemplate → WebSocketConfig →
            //     ShareViewTopicAuthInterceptor → shareLinkServiceImpl → applicationEvents → shareViewStompRelay
            @Lazy final SimpMessagingTemplate messagingTemplate,
            @Lazy final ShareLinkService shareLinkService,
            // @Lazy on MessageCache prevents a secondary cycle via the same chain.
            @Lazy final MessageCache messageCache,
            final ShareLinkActivityRegistry registry,
            final Clock clock) {
        this.messagingTemplate = messagingTemplate;
        this.shareLinkService = shareLinkService;
        this.messageCache = messageCache;
        this.registry = registry;
        this.clock = clock;
    }

    // -------------------------------------------------------------------------
    // EventListener — lifecycle events from ShareLinkServiceImpl
    // -------------------------------------------------------------------------

    /**
     * Registers the newly created share link in the routing table.
     *
     * <p>Called synchronously by the Spring event bus when {@link ShareLinkServiceImpl#create}
     * publishes a {@link ShareLinkActivatedEvent} after persisting the link. The link is
     * re-resolved under the per-linkId lock inside {@link ShareLinkActivityRegistry#register}
     * to guard against a concurrent revocation that may have arrived between the emission
     * of this event and its processing (ADR-RELAY-03, SR-RELAY-06).
     *
     * <p>SR-RELAY-14: AUDIT.info emitted with hash-sanitised IDs.
     *
     * @param event the activation event carrying sharerWallId and shareLinkId
     */
    @EventListener
    public void onActivate(final ShareLinkActivatedEvent event) {
        registry.register(event.sharerWallId(), event.shareLinkId(), shareLinkService, Instant.now());
        AUDIT.info("share.link.activated shareId-hash8={} wallId-hash8={}",
                event.shareLinkId().hash8(), LogScrubber.hash8(event.sharerWallId()));
    }

    /**
     * Removes the revoked share link from the routing table and pushes a revocation
     * control message to all connected viewers.
     *
     * <p>Called synchronously by the Spring event bus when {@link ShareLinkServiceImpl#revoke}
     * publishes a {@link ShareLinkRevokedEvent}. The {@code unregister()} call acquires the
     * per-linkId lock inside the registry, and the AUDIT log entry and {@code pushRevocation()}
     * call follow inside the same execution — ensuring atomicity from the caller's perspective
     * (SR-RELAY-02, SR-RELAY-07, SR-RELAY-22).
     *
     * <p>SR-RELAY-15: AUDIT.info emitted after {@code unregister()} and before
     * {@code pushRevocation()} (audit ordering correctness).
     *
     * @param event the revocation event carrying sharerWallId and shareLinkId
     */
    @EventListener
    public void onRevoke(final ShareLinkRevokedEvent event) {
        // unregister acquires the per-linkId lock internally (SR-RELAY-07)
        registry.unregister(event.sharerWallId(), event.shareLinkId());
        // SR-RELAY-22: AUDIT.info after unregister(), before pushRevocation()
        AUDIT.info("share.link.relay_revoked shareId-hash8={} wallId-hash8={}",
                event.shareLinkId().hash8(), LogScrubber.hash8(event.sharerWallId()));
        pushRevocation(event.shareLinkId());
    }

    // -------------------------------------------------------------------------
    // Relay
    // -------------------------------------------------------------------------

    /**
     * Re-publishes a toot event payload to all active share link topics for the given wall.
     *
     * <p>Called after a toot is published to the sharer's own topic tree.
     * The wallId is used only server-side to look up share links via the registry;
     * it never reaches any topic destination string (SR-SHARE-02).
     *
     * <p>When no active links are registered for the sharer and the 30-second debounce
     * window has elapsed, a single {@code AUDIT.warn("share.relay.no_viewers ...")} is
     * emitted (SR-RELAY-09, SR-RELAY-20).
     *
     * @param wallId    the sharer's wallId (server-side use only; never in topic path)
     * @param hashtag   the hashtag that produced the event
     * @param eventType one of {@code creation}, {@code modification}, {@code deletion}
     * @param payload   the original toot message payload
     */
    public void relayTootEvent(
            final String wallId,
            final String hashtag,
            final String eventType,
            final Object payload) {

        if (wallId == null || wallId.isBlank()) {
            // Defensive: null wallId means no share links to relay to
            return;
        }

        Set<ShareLinkId> activeLinks = registry.getActiveLinks(wallId);

        if (activeLinks.isEmpty()) {
            emitNoViewersWarnWithDebounce(wallId);
            return;
        }

        for (ShareLinkId shareLinkId : activeLinks) {
            // Topic path: /topic/share/{shareLinkId}/{hashtag}/{eventType}
            // wallId is DELIBERATELY absent from this path (SR-SHARE-02)
            String topic = SHARE_TOPIC_PREFIX + shareLinkId.value()
                    + "/" + hashtag
                    + "/" + eventType;
            try {
                messagingTemplate.convertAndSend(topic, payload);
                log.debug("share.relay.published shareId-hash={} hashtag={} event={}",
                        LogScrubber.hash8(shareLinkId.value()), hashtag, eventType);
            } catch (Exception e) {
                log.warn("share.relay.publish_failed shareId-hash={} reason={}",
                        LogScrubber.hash8(shareLinkId.value()), e.getMessage());
            }
        }
    }

    // -------------------------------------------------------------------------
    // Fallback polling support
    // -------------------------------------------------------------------------

    /**
     * Returns recent messages for the given share link and hashtag from the in-memory ring buffer.
     *
     * <p>This method provides the backing data for the fallback polling endpoint
     * {@code GET /rest/share/{id}/messages?hashtag=...&since=...}. It looks up the
     * sharer's wallId from the active share link, then queries the message cache
     * for events newer than {@code since}.
     *
     * <p>The sharer's wallId is used server-side to query the cache; it is NEVER
     * returned to callers (SR-SHARE-02). The {@link ShareLinkService#resolve} method
     * is the only remaining use of {@code shareLinkService} in this class (SR-RELAY-10).
     *
     * @param shareLinkId the active share link ID
     * @param hashtag     the hashtag to fetch events for
     * @param since       sequence cursor (events with sequence &gt; since are returned);
     *                    {@code null} returns all cached events
     * @param now         the current time (for active-link resolution)
     * @return list of {@link CacheEntry} objects, empty if none found or link not active
     */
    public List<CacheEntry> getRecentMessages(
            final ShareLinkId shareLinkId,
            final String hashtag,
            final Long since,
            final Instant now) {

        try {
            // Resolve the active share link to find the sharer's wallId (server-side only)
            Optional<de.seism0saurus.glacier.share.domain.ShareLink> matchingLink =
                    shareLinkService.resolve(shareLinkId, now);
            if (matchingLink.isEmpty()) {
                return List.of();
            }

            // Use the sharer's wallId to query the message cache.
            // ADR-SHARE-05 (revised): wrap wallId in PrincipalKey(WALL) to prevent
            // cross-namespace collision in MessageCacheImpl's PrincipalKey-keyed map.
            String sharerWallId = matchingLink.get().sharerWallId();
            PrincipalKey sharerKey = new PrincipalKey(PrincipalKind.WALL, sharerWallId);

            try {
                Snapshot snapshot = messageCache.snapshot(sharerKey, hashtag, since);
                return snapshot.events();
            } catch (UnknownSubscriptionException e) {
                log.debug("share.relay.snapshot_miss shareId-hash={} hashtag={} reason={}",
                        LogScrubber.hash8(shareLinkId.value()), hashtag, e.getMessage());
                return List.of();
            }
        } catch (Exception e) {
            log.warn("share.relay.getRecentMessages_failed shareId-hash={} hashtag={} reason={}",
                    LogScrubber.hash8(shareLinkId.value()), hashtag, e.getMessage());
            return List.of();
        }
    }

    // -------------------------------------------------------------------------
    // Revocation push
    // -------------------------------------------------------------------------

    /**
     * Pushes a revocation control message to the viewer topic for the given share link.
     *
     * <p>Viewers that receive this message MUST disconnect within the 1-second SLA window.
     * The Angular client should treat this as a terminal state and redirect to an expiry page.
     *
     * <p>Topic: {@code /topic/share/{shareLinkId}/control}
     * Payload: {@code {"type":"revoked"}}
     *
     * @param shareLinkId the revoked share link (not-null)
     */
    public void pushRevocation(final ShareLinkId shareLinkId) {
        String topic = SHARE_TOPIC_PREFIX + shareLinkId.value() + "/control";
        Map<String, String> controlPayload = Map.of("type", "revoked");

        try {
            messagingTemplate.convertAndSend(topic, controlPayload);
            AUDIT.info("share.revoke.pushed shareId-hash={}", LogScrubber.hash8(shareLinkId.value()));
        } catch (Exception e) {
            log.warn("share.revoke.push_failed shareId-hash={} reason={}",
                    LogScrubber.hash8(shareLinkId.value()), e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Emits {@code AUDIT.warn("share.relay.no_viewers ...")} at most once per
     * {@link #NO_VIEWERS_DEBOUNCE} window per sharerWallId (SR-RELAY-09, SR-RELAY-20).
     *
     * <p>The debounce prevents log flooding when a sharer has no active viewers for
     * an extended period. A warn is emitted on the first call for a given wallId, and
     * again only after the debounce window elapses.
     *
     * @param wallId the sharer's wallId (logged as hash8 — never raw)
     */
    private void emitNoViewersWarnWithDebounce(final String wallId) {
        // ACC-03: use injected clock so tests can freeze time and prove the 30-second suppression
        // window behaviorally (SR-RELAY-20).
        Instant now = clock.instant();
        Instant lastWarn = noViewersLastWarnAt.get(wallId);
        if (lastWarn == null || Duration.between(lastWarn, now).compareTo(NO_VIEWERS_DEBOUNCE) >= 0) {
            noViewersLastWarnAt.put(wallId, now);
            AUDIT.warn("share.relay.no_viewers wallId-hash8={}", LogScrubber.hash8(wallId));
        }
    }
}
