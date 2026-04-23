package de.seism0saurus.glacier.webservice.messaging;

import java.security.Principal;

/**
 * Sealed principal interface for all Glacier WebSocket identities.
 *
 * <p>Replaces the {@code sv_} string-prefix convention (ADR-SHARE-05, revised).
 * A string prefix is client-controlled (the cookie value comes from the client);
 * a sealed type hierarchy is compile-enforced and cannot be forged by constructing
 * a {@link WallPrincipal} with an {@code sv_}-prefixed name.
 *
 * <p>Permits:
 * <ul>
 *   <li>{@link WallPrincipal} — the sharer's identity on the main {@code /websocket} endpoint</li>
 *   <li>{@link ShareViewerPrincipal} — a viewer's identity on the {@code /share-view-ws} endpoint</li>
 * </ul>
 *
 * <p>Security: ADR-SHARE-05 (revised); cross-namespace collision prevention;
 * NIST SP 800-53 AC-3 (access control).
 */
public sealed interface GlacierPrincipal extends Principal
        permits WallPrincipal, ShareViewerPrincipal {
}
