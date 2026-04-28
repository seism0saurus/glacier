package de.seism0saurus.glacier.share.domain;

/**
 * Domain exception thrown when a revocation attempt is rejected at the aggregate level.
 *
 * <p>This exception is thrown by {@link ShareLink#revoke} when the calling wallId does not
 * match {@code sharerWallId}.  It surfaces as a domain-layer violation — the application
 * layer maps it to the uniform anti-enumeration response via
 * {@link de.seism0saurus.glacier.share.application.ShareLinkNotFoundOrNotAuthorisedException}.
 *
 * <p>The exception message MUST NOT contain the raw wallId values — it may contain only
 * structural information (e.g., "caller wallId does not match sharerWallId") suitable for
 * DEBUG-level logging through {@link de.seism0saurus.glacier.util.LogScrubber} fingerprints.
 */
public class ShareLinkRevocationException extends RuntimeException {

    /**
     * @param message a log-safe message (no raw wallId values)
     */
    public ShareLinkRevocationException(final String message) {
        super(message);
    }
}
