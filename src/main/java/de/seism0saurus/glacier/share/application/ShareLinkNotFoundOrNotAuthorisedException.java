package de.seism0saurus.glacier.share.application;

/**
 * Uniform exception for the anti-enumeration requirement on revocation.
 *
 * <p>Both "link not found" and "caller wallId does not match sharerWallId" surface as this
 * single exception type.  The controller maps it to a {@code 404 Not Found} response with an
 * identical body for both cases — preventing callers from determining whether a given shareId
 * exists by observing the error code or response shape (T-07 anti-enumeration principle).
 *
 * <p>The message may contain structural information (e.g., "not found" or "not authorised")
 * for DEBUG logging.  It must never contain the raw shareId, wallId, or IP values.
 */
public class ShareLinkNotFoundOrNotAuthorisedException extends RuntimeException {

    /**
     * @param message a log-safe description; must not contain raw sensitive values
     */
    public ShareLinkNotFoundOrNotAuthorisedException(final String message) {
        super(message);
    }
}
