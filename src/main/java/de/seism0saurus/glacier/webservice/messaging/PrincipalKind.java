package de.seism0saurus.glacier.webservice.messaging;

/**
 * Discriminant for the {@link GlacierPrincipal} sealed hierarchy.
 *
 * <p>Used as the first component of {@link PrincipalKey} so that bucket maps
 * (e.g., in {@code MessageCacheImpl} and {@code FallbackRateLimiter}) cannot
 * collide across principal kinds even when the {@code name} values are identical.
 *
 * <p>Security: ADR-SHARE-05 (revised). Without this discriminant a forged
 * {@link WallPrincipal} with an {@code sv_…} name could overwrite a
 * {@link ShareViewerPrincipal}'s cache bucket.
 */
public enum PrincipalKind {
    /** Sharer identity — connected via {@code /websocket}. */
    WALL,

    /** Viewer identity — connected via {@code /share-view-ws}. */
    SHARE_VIEWER
}
