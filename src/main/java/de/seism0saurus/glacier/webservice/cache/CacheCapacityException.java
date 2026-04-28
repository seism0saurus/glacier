package de.seism0saurus.glacier.webservice.cache;

/**
 * Thrown by {@link MessageCache#provisionHashtag} when adding a new
 * {@code (principal, hashtag)} tuple would exceed a configured memory-DoS
 * cap (D-11: 10 hashtags per principal or 10 000 principals total).
 *
 * <p>This is an unchecked exception; callers that want to surface a rejection
 * to the WebSocket client must catch it explicitly (see
 * {@code SubscriptionController#subscribe}).
 */
public class CacheCapacityException extends RuntimeException {

    /**
     * Constructs a new CacheCapacityException with the supplied detail message.
     *
     * @param message human-readable explanation of which cap was hit
     */
    public CacheCapacityException(final String message) {
        super(message);
    }
}
