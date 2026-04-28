package de.seism0saurus.glacier.webservice;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Hook point for authentication of the HTTP fallback endpoint.
 *
 * <p>The default implementation ({@link PassthroughFallbackAuthGuard}) accepts all
 * requests so that the happy-path behaviour is testable in isolation.
 * The {@code secure-tdd-implementer} will replace it with a cookie-validation
 * implementation that enforces {@code wallId} presence and ownership (D-08, ADR-06).
 *
 * <p>A guard implementation must be a Spring bean; {@link FallbackController} picks it
 * up via constructor injection.
 *
 * <!-- TODO SECURITY_IMPL: replace PassthroughFallbackAuthGuard with CookieBasedFallbackAuthGuard
 *      that validates wallId cookie presence, maps it to a principal, and rejects (401) when
 *      the cookie is absent or does not match the subscription owner. See D-08, ADR-06. -->
 */
public interface FallbackAuthGuard {

    /**
     * Result of an authentication check.
     *
     * @param authenticated {@code true} when the request carries valid credentials
     * @param principal     the resolved principal (wallId); {@code null} when not authenticated
     */
    record AuthResult(boolean authenticated, String principal) {
    }

    /**
     * Validates the incoming request and resolves the principal.
     *
     * @param request     the HTTP request to inspect
     * @param rawWallId   the {@code wallId} cookie value extracted by Spring MVC;
     *                    may be {@code null} if the cookie was absent
     * @return an {@link AuthResult} indicating whether authentication succeeded and,
     *         if so, the resolved principal string
     */
    AuthResult authenticate(HttpServletRequest request, String rawWallId);
}
