package de.seism0saurus.glacier.share.application;

/**
 * Thrown when a {@link ShareLinkService#create} call would violate a capacity cap.
 *
 * <p>The message identifies the exceeded axis (e.g., "sharer cap exceeded" or "ip cap exceeded")
 * and is safe to log at DEBUG — it contains no raw wallId or IP values.
 *
 * <p>Controllers map this to HTTP 429 Too Many Requests (the same status as rate limiting,
 * because the user-visible effect is identical: the creation request is refused).
 */
public class CapacityExceededException extends RuntimeException {

    /**
     * @param message a log-safe description of which cap was exceeded (e.g., "sharer", "ip",
     *                "global"); never include raw wallId or IP values
     */
    public CapacityExceededException(final String message) {
        super(message);
    }
}
