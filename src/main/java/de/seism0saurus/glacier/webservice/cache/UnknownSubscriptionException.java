package de.seism0saurus.glacier.webservice.cache;

/**
 * Thrown by {@link MessageCache#snapshot} when the requested
 * {@code (principal, hashtag)} tuple has never been provisioned or has
 * already been evicted.
 *
 * <p>{@code FallbackController} maps this exception to an HTTP 400 response
 * with a body containing {@code {"error": "unknown_subscription"}}.
 */
public class UnknownSubscriptionException extends RuntimeException {

    /**
     * Constructs a new UnknownSubscriptionException with the supplied detail message.
     *
     * @param message identifies the missing {@code (principal, hashtag)} tuple
     */
    public UnknownSubscriptionException(final String message) {
        super(message);
    }
}
