package de.seism0saurus.glacier.share.application;

import de.seism0saurus.glacier.share.domain.ShareLink;
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
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Relays sharer STOMP toot events to viewer-scoped share topics.
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
 * <p>Security invariants:
 * <ul>
 *   <li>The sharer's {@code wallId} NEVER appears in any viewer-facing topic path (SR-SHARE-02).</li>
 *   <li>Topics are scoped by {@code shareLinkId}, not by wallId, so viewers cannot
 *       discover other viewer sessions or the sharer's identity.</li>
 *   <li>The relay only publishes to ACTIVE links (as returned by {@link ShareLinkService#listBySharer}).</li>
 * </ul>
 *
 * <p>ADR-SHARE-04, SR-SHARE-02, SR-SHARE-06, OWASP API1 (Broken Object Level Authorization).
 */
@Service
public class ShareViewStompRelay {

    private static final Logger log = LoggerFactory.getLogger(ShareViewStompRelay.class);
    private static final Logger AUDIT = LoggerFactory.getLogger("AUDIT");

    /** Topic prefix for viewer-facing share events. wallId is NEVER part of this path. */
    private static final String SHARE_TOPIC_PREFIX = "/topic/share/";

    private final SimpMessagingTemplate messagingTemplate;
    private final ShareLinkService shareLinkService;
    private final MessageCache messageCache;

    public ShareViewStompRelay(
            // @Lazy on both SimpMessagingTemplate and ShareLinkService breaks the mutual
            // circular dependency:
            //   shareViewStompRelay ↔ shareLinkServiceImpl (direct cycle via constructor params 0 and 1)
            //   shareViewStompRelay → SimpMessagingTemplate → WebSocketConfig →
            //     ShareViewTopicAuthInterceptor → shareLinkServiceImpl → shareViewStompRelay
            // All three dependencies are used only at runtime (publish/relay/revoke), never at startup.
            @Lazy final SimpMessagingTemplate messagingTemplate,
            @Lazy final ShareLinkService shareLinkService,
            // @Lazy on MessageCache prevents a secondary cycle via the same chain.
            @Lazy final MessageCache messageCache) {
        this.messagingTemplate = messagingTemplate;
        this.shareLinkService = shareLinkService;
        this.messageCache = messageCache;
    }

    /**
     * Re-publishes a toot event payload to all active share link topics for the given wall.
     *
     * <p>Called after a toot is published to the sharer's own topic tree.
     * The wallId is used only server-side to look up share links; it never reaches
     * any topic destination string (SR-SHARE-02).
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

        List<ShareLink> activeLinks;
        try {
            @SuppressWarnings("deprecation")
            List<ShareLink> activeLinksTmp = shareLinkService.listBySharer(wallId, Instant.now());
            activeLinks = activeLinksTmp;
        } catch (Exception e) {
            log.warn("share.relay.lookup_failed wallId-hash={} reason={}",
                    LogScrubber.hash8(wallId), e.getMessage());
            return;
        }

        for (ShareLink link : activeLinks) {
            // Topic path: /topic/share/{shareLinkId}/{hashtag}/{eventType}
            // wallId is DELIBERATELY absent from this path (SR-SHARE-02)
            String topic = SHARE_TOPIC_PREFIX + link.id().value()
                    + "/" + hashtag
                    + "/" + eventType;
            try {
                messagingTemplate.convertAndSend(topic, payload);
                log.debug("share.relay.published shareId-hash={} hashtag={} event={}",
                        LogScrubber.hash8(link.id().value()), hashtag, eventType);
            } catch (Exception e) {
                log.warn("share.relay.publish_failed shareId-hash={} reason={}",
                        LogScrubber.hash8(link.id().value()), e.getMessage());
            }
        }
    }

    /**
     * Returns recent messages for the given share link and hashtag from the in-memory ring buffer.
     *
     * <p>This method provides the backing data for the fallback polling endpoint
     * {@code GET /rest/share/{id}/messages?hashtag=...&since=...}. It looks up the
     * sharer's wallId from the active share link, then queries the message cache
     * for events newer than {@code since}.
     *
     * <p>The sharer's wallId is used server-side to query the cache; it is NEVER
     * returned to callers (SR-SHARE-02).
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

        // Resolve the active share link to find the sharer's wallId (server-side only)
        List<ShareLink> activeLinks;
        try {
            // We search all active links for the sharer to find the one with this ID
            // The shareLinkId must be active; if not, return empty (viewer should get 404)
            Optional<ShareLink> matchingLink = shareLinkService.resolve(shareLinkId, now);
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
}
