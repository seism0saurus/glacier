package de.seism0saurus.glacier.webservice.messaging;

import de.seism0saurus.glacier.share.domain.ShareLinkId;

import java.util.Objects;

/**
 * Principal for a viewer connected via the {@code /share-view-ws} endpoint.
 *
 * <p>Carries both the viewer's identity token and the share link the viewer was
 * admitted for. The {@code boundShareLinkId} is used by
 * {@link de.seism0saurus.glacier.share.web.ShareViewTopicAuthInterceptor} to enforce
 * that this viewer may only subscribe to
 * {@code /topic/share/{shareLinkId}/...} matching their bound link.
 *
 * <p>{@code getName()} returns the viewerId (an {@code sv_}-prefixed opaque token issued
 * by {@link ShareViewPrincipalHandler}). The {@code sv_} prefix is retained as a log-aid
 * only — authorization is enforced by the type, not the prefix.
 *
 * <p>Security: ADR-SHARE-05 (revised). A {@link ShareViewerPrincipal} is structurally
 * distinct from a {@link WallPrincipal} even when both have the same {@code getName()}
 * value — this prevents cross-namespace cache or rate-limit bucket collision.
 */
public record ShareViewerPrincipal(String viewerId, ShareLinkId boundShareLinkId)
        implements GlacierPrincipal {

    public ShareViewerPrincipal {
        Objects.requireNonNull(viewerId, "viewerId must not be null");
        Objects.requireNonNull(boundShareLinkId, "boundShareLinkId must not be null");
    }

    /**
     * Returns the viewerId token. Satisfies {@link java.security.Principal#getName()}.
     */
    @Override
    public String getName() {
        return viewerId;
    }
}
