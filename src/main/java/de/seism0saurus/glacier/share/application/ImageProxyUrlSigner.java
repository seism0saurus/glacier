package de.seism0saurus.glacier.share.application;

import de.seism0saurus.glacier.share.domain.ShareLinkId;

/**
 * Application-layer port for signing image proxy URLs.
 *
 * <p>This interface breaks the dependency from the application layer
 * ({@link ShareRenderingService}) on the web infrastructure layer
 * ({@link de.seism0saurus.glacier.share.web.ShareImageProxyUrlBuilder}).
 *
 * <p>Security: ADR-SHARE-07 — only {@link ShareRenderingService} calls this port;
 * the signing implementation uses HMAC-SHA256 with the configured secret.
 */
public interface ImageProxyUrlSigner {
    /**
     * Signs {@code originalUrl} and returns a proxy URL with HMAC signature.
     *
     * @param originalUrl the image URL from the federation instance (already scheme-validated)
     * @param shareLinkId the active share link scope
     * @return the signed proxy URL, or {@code null} if signing fails
     */
    String sign(String originalUrl, ShareLinkId shareLinkId);
}
