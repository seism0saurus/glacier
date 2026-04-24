package de.seism0saurus.glacier.webservice.messaging;

import java.util.Objects;

/**
 * Principal for a sharer connected via the main {@code /websocket} endpoint.
 *
 * <p>{@code getName()} returns the wallId cookie value (a UUID or equivalent opaque token
 * issued by {@code InformationController#readCookie}).
 *
 * <p>Equality is solely by wallId string; two sessions with the same wallId are considered
 * the same logical identity (the user reconnected on a new STOMP session).
 *
 * <p>Security: ADR-SHARE-05 (revised). A {@link WallPrincipal} with a name that happens
 * to start with {@code sv_} is NOT equal to a {@link ShareViewerPrincipal} with the same
 * name — the type discriminates, the prefix does not.
 */
public record WallPrincipal(String wallId) implements GlacierPrincipal {

    public WallPrincipal {
        Objects.requireNonNull(wallId, "wallId must not be null");
    }

    /**
     * Returns the wallId. Satisfies {@link java.security.Principal#getName()}.
     */
    @Override
    public String getName() {
        return wallId;
    }
}
