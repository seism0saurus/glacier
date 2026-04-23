package de.seism0saurus.glacier.webservice.messaging;

import java.util.Objects;

/**
 * Composite key for bucket maps in {@code MessageCacheImpl} and {@code FallbackRateLimiter}.
 *
 * <p>Combines a {@link PrincipalKind} discriminant with the principal's name so that two
 * principals of different kinds with the same name hash to different map entries.
 *
 * <p>This is the compile-enforced replacement for the {@code sv_} string-prefix keying
 * convention (ADR-SHARE-05, revised). String maps keyed by raw principal name allowed
 * a forged {@link WallPrincipal} with an {@code sv_…} name to overwrite a
 * {@link ShareViewerPrincipal}'s bucket.
 *
 * <p>Usage:
 * <pre>{@code
 *     PrincipalKey key = PrincipalKey.of(principal);
 *     cache.computeIfAbsent(key, k -> new PerTagRing(capacity));
 * }</pre>
 *
 * <p>Security: ADR-SHARE-05 (revised); NIST SP 800-53 AC-3.
 */
public record PrincipalKey(PrincipalKind kind, String name) {

    public PrincipalKey {
        Objects.requireNonNull(kind, "kind must not be null");
        Objects.requireNonNull(name, "name must not be null");
    }

    /**
     * Factory: derives a {@link PrincipalKey} from a {@link GlacierPrincipal} via
     * pattern-matching on the sealed hierarchy.
     *
     * @param principal a non-null {@link GlacierPrincipal}
     * @return the corresponding key
     */
    public static PrincipalKey of(final GlacierPrincipal principal) {
        Objects.requireNonNull(principal, "principal must not be null");
        return switch (principal) {
            case WallPrincipal w -> new PrincipalKey(PrincipalKind.WALL, w.getName());
            case ShareViewerPrincipal sv -> new PrincipalKey(PrincipalKind.SHARE_VIEWER, sv.getName());
        };
    }
}
