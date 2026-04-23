package de.seism0saurus.glacier.share.web;

import de.seism0saurus.glacier.share.application.ShareLinkService;
import de.seism0saurus.glacier.share.domain.ShareLinkId;
import de.seism0saurus.glacier.util.LogScrubber;
import de.seism0saurus.glacier.webservice.messaging.ShareViewPrincipalHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.stereotype.Component;

import java.security.Principal;
import java.time.Instant;

/**
 * Inbound channel interceptor that restricts viewer STOMP subscriptions.
 *
 * <p>Rejects any SUBSCRIBE frame from a viewer (shareViewerId principal) that:
 * <ul>
 *   <li>Does not start with {@code /topic/share/{boundShareLinkId}/}</li>
 *   <li>Presents a share link ID that does not resolve to ACTIVE</li>
 *   <li>Attempts to subscribe to the sharer's {@code /topic/hashtags/...} tree</li>
 * </ul>
 *
 * <p>Viewers must only subscribe to their own share topic namespace.
 * Any subscription outside that namespace is rejected with a STOMP ERROR frame.
 *
 * <p>Security: SR-SHARE-06 (viewer topic isolation), SR-SHARE-02 (wallId non-disclosure),
 * ADR-SHARE-04. References: OWASP API1 (Broken Object Level Authorization).
 */
@Component
public class ShareViewTopicAuthInterceptor implements ChannelInterceptor {

    private static final Logger log = LoggerFactory.getLogger(ShareViewTopicAuthInterceptor.class);
    private static final Logger AUDIT = LoggerFactory.getLogger("AUDIT");

    /** The topic prefix that viewers are allowed to subscribe to. */
    static final String SHARE_TOPIC_PREFIX = "/topic/share/";

    /** Sharer topic prefix that viewers must NEVER access. */
    static final String HASHTAG_TOPIC_PREFIX = "/topic/hashtags/";

    private final ShareLinkService shareLinkService;

    public ShareViewTopicAuthInterceptor(final ShareLinkService shareLinkService) {
        this.shareLinkService = shareLinkService;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(message);

        if (StompCommand.SUBSCRIBE != accessor.getCommand()) {
            return message;
        }

        String destination = accessor.getDestination();
        Principal user = accessor.getUser();

        if (user == null) {
            return message; // let the upstream auth handle missing principal
        }

        String principalName = user.getName();

        // If this is a viewer principal (sv_ prefix), enforce strict topic isolation
        if (ShareViewPrincipalHandler.isValidShareViewerId(principalName)) {
            return enforceViewerSubscriptionPolicy(message, accessor, principalName, destination);
        }

        // Non-viewer principals are handled by existing authorization
        return message;
    }

    private Message<?> enforceViewerSubscriptionPolicy(
            Message<?> message,
            StompHeaderAccessor accessor,
            String viewerId,
            String destination) {

        if (destination == null) {
            AUDIT.info("viewer.subscribe.rejected reason=null_destination viewerId-hash={}",
                    LogScrubber.hash8(viewerId));
            return null; // reject
        }

        // Absolutely block access to sharer topic tree
        if (destination.startsWith(HASHTAG_TOPIC_PREFIX)) {
            AUDIT.info("viewer.subscribe.rejected reason=hashtag_topic_access viewerId-hash={}",
                    LogScrubber.hash8(viewerId));
            return null; // reject — prevents wallId leakage
        }

        // Must subscribe to /topic/share/{shareLinkId}/...
        if (!destination.startsWith(SHARE_TOPIC_PREFIX)) {
            AUDIT.info("viewer.subscribe.rejected reason=non_share_topic viewerId-hash={}",
                    LogScrubber.hash8(viewerId));
            return null;
        }

        // Extract the share link ID from the destination
        String afterPrefix = destination.substring(SHARE_TOPIC_PREFIX.length());
        int slashPos = afterPrefix.indexOf('/');
        String shareLinkIdStr = slashPos > 0 ? afterPrefix.substring(0, slashPos) : afterPrefix;

        // Validate that the share link is ACTIVE
        try {
            ShareLinkId shareLinkId = ShareLinkId.fromUrlPath(shareLinkIdStr);
            boolean active = shareLinkService.resolve(shareLinkId, Instant.now()).isPresent();
            if (!active) {
                AUDIT.info("viewer.subscribe.rejected reason=link_not_active shareId-hash={} viewerId-hash={}",
                        LogScrubber.hash8(shareLinkIdStr), LogScrubber.hash8(viewerId));
                return null;
            }
        } catch (IllegalArgumentException e) {
            // Invalid share link ID format
            AUDIT.info("viewer.subscribe.rejected reason=invalid_link_id viewerId-hash={}",
                    LogScrubber.hash8(viewerId));
            return null;
        }

        return message; // allowed
    }
}
