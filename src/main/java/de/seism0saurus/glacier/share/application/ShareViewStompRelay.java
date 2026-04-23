package de.seism0saurus.glacier.share.application;

import de.seism0saurus.glacier.share.domain.ShareLink;
import de.seism0saurus.glacier.share.domain.ShareLinkId;
import de.seism0saurus.glacier.util.LogScrubber;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;

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

    public ShareViewStompRelay(
            final SimpMessagingTemplate messagingTemplate,
            final ShareLinkService shareLinkService) {
        this.messagingTemplate = messagingTemplate;
        this.shareLinkService = shareLinkService;
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
            activeLinks = shareLinkService.listBySharer(wallId, Instant.now());
        } catch (Exception e) {
            log.warn("share.relay.lookup_failed wallId-hash={} reason={}",
                    LogScrubber.hash8(wallId), e.getMessage());
            return;
        }

        for (ShareLink link : activeLinks) {
            // Topic path: /topic/share/{shareLinkId}/{hashtag}/{eventType}
            // wallId is DELIBERATELY absent from this path (SR-SHARE-02)
            String topic = SHARE_TOPIC_PREFIX + link.getId().getValue()
                    + "/" + hashtag
                    + "/" + eventType;
            try {
                messagingTemplate.convertAndSend(topic, payload);
                log.debug("share.relay.published shareId-hash={} hashtag={} event={}",
                        LogScrubber.hash8(link.getId().getValue()), hashtag, eventType);
            } catch (Exception e) {
                log.warn("share.relay.publish_failed shareId-hash={} reason={}",
                        LogScrubber.hash8(link.getId().getValue()), e.getMessage());
            }
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
        String topic = SHARE_TOPIC_PREFIX + shareLinkId.getValue() + "/control";
        Map<String, String> controlPayload = Map.of("type", "revoked");

        try {
            messagingTemplate.convertAndSend(topic, controlPayload);
            AUDIT.info("share.revoke.pushed shareId-hash={}", LogScrubber.hash8(shareLinkId.getValue()));
        } catch (Exception e) {
            log.warn("share.revoke.push_failed shareId-hash={} reason={}",
                    LogScrubber.hash8(shareLinkId.getValue()), e.getMessage());
        }
    }
}
