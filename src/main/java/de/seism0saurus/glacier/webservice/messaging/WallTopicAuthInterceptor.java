package de.seism0saurus.glacier.webservice.messaging;

import de.seism0saurus.glacier.util.LogScrubber;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.support.ChannelInterceptor;

import java.security.Principal;

/**
 * STOMP channel interceptor that enforces per-principal topic isolation for
 * {@code /topic/hashtags/{wallId}/...} destinations.
 *
 * <h2>Threat model (OWASP API1 — BOLA)</h2>
 * <p>Spring's in-memory broker does not scope {@code /topic/...} destinations to
 * principals. Without this interceptor any authenticated client can subscribe to
 * another user's toot stream once they learn the victim's {@code wallId} UUID.
 *
 * <h2>What this interceptor does</h2>
 * <ol>
 *   <li>Ignores all frames that are NOT of type SUBSCRIBE.</li>
 *   <li>Ignores destinations that do NOT start with {@code /topic/hashtags/}.</li>
 *   <li>Ignores frames whose principal is a {@link ShareViewerPrincipal} — those
 *       are handled by a separate interceptor.</li>
 *   <li>For all other SUBSCRIBE frames, extracts the {@code wallId} path segment
 *       (position 3 after splitting by {@code /}) and compares it against
 *       {@link WallPrincipal#getName()}. Mismatch → null return (message dropped)
 *       and an AUDIT event is emitted.</li>
 *   <li>Rejects (null) frames from a null principal or a malformed destination.</li>
 * </ol>
 *
 * <h2>Registration</h2>
 * <p>Registered in {@link WebSocketConfiguration#configureClientInboundChannel}.
 *
 * <h2>Security note on logging (D-13, SR-8)</h2>
 * <p>Raw wallId values (UUID-formatted) must NEVER be emitted into log messages.
 * This interceptor uses {@link LogScrubber#hash8} to produce short fingerprints
 * suitable for correlation. The raw {@code destination} string is also NEVER logged
 * because it contains the victim's wallId.
 */
public class WallTopicAuthInterceptor implements ChannelInterceptor {

    /** Destination prefix that this interceptor guards. */
    static final String HASHTAGS_TOPIC_PREFIX = "/topic/hashtags/";

    /**
     * Standard application logger — operational diagnostics only.
     * SECURITY: never log raw destination strings or wallId values here.
     */
    private static final Logger LOG = LoggerFactory.getLogger(WallTopicAuthInterceptor.class);

    /**
     * Dedicated audit logger for security-relevant events (cross-principal rejections).
     * Routed to the AUDIT appender defined in logback.xml.
     */
    private static final Logger AUDIT = LoggerFactory.getLogger("AUDIT");

    /**
     * {@inheritDoc}
     *
     * <p>Enforces that a {@link WallPrincipal} can only subscribe to the
     * {@code /topic/hashtags/{ownWallId}/...} path matching their principal name.
     *
     * @return the message unchanged when allowed; {@code null} to drop when rejected.
     */
    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        SimpMessageHeaderAccessor accessor = SimpMessageHeaderAccessor.wrap(message);

        // Only intercept SUBSCRIBE frames — SEND, CONNECT, etc. are not relevant here.
        if (SimpMessageType.SUBSCRIBE != accessor.getMessageType()) {
            return message;
        }

        String destination = accessor.getDestination();

        // Only guard /topic/hashtags/... destinations.
        if (destination == null || !destination.startsWith(HASHTAGS_TOPIC_PREFIX)) {
            return message;
        }

        Principal principal = accessor.getUser();

        // ShareViewerPrincipal subscriptions are handled by ShareViewTopicAuthInterceptor.
        if (principal instanceof ShareViewerPrincipal) {
            return message;
        }

        // Null principal: reject (OWASP A07 — authentication required).
        // SECURITY: do NOT include the destination in this log message — it contains wallId.
        if (principal == null) {
            LOG.warn("SUBSCRIBE rejected: no principal attached to hashtags destination");
            return null; // drop the message
        }

        // Extract the wallId segment from /topic/hashtags/{wallId}/...
        // Split: ["", "topic", "hashtags", "{wallId}", ...]
        String[] segments = destination.split("/", -1);
        // segments[0] = "" (empty before leading /), [1] = "topic", [2] = "hashtags", [3] = wallId
        if (segments.length < 4 || segments[3].isEmpty()) {
            // SECURITY: do NOT include the raw destination in this log message.
            LOG.warn("SUBSCRIBE rejected: malformed hashtags destination (no wallId segment)");
            return null; // drop the message
        }

        String destinationWallId = segments[3];
        String principalName = principal.getName();

        if (!principalName.equals(destinationWallId)) {
            // BOLA rejection — emit audit event with fingerprints only (never raw IDs).
            // SECURITY (D-13/SR-8): hash BOTH the principal name AND the destination wallId
            // so that neither raw UUID appears in the log aggregator.
            // Uses LogScrubber.hash8 (SHA-256 truncated to 8 hex chars) — NOT String.hashCode().
            AUDIT.warn(
                    "stomp.subscribe.rejected reason=cross_principal principal-hash8={} destination-wallId-hash8={}",
                    LogScrubber.hash8(principalName),
                    LogScrubber.hash8(destinationWallId)
            );
            return null; // drop the message — fail secure
        }

        // Allowed: subscriber's wallId matches destination wallId.
        return message;
    }
}
