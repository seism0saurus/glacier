package de.seism0saurus.glacier.share.web;

import de.seism0saurus.glacier.util.LogScrubber;
import de.seism0saurus.glacier.webservice.messaging.ShareViewPrincipalHandler;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Authentication guard for viewer-only share endpoints.
 *
 * <p>Validates that the {@code __Host-shareViewerId} cookie:
 * <ol>
 *   <li>Is non-null and non-blank</li>
 *   <li>Starts with the mandatory {@code sv_} prefix (ADR-SHARE-05)</li>
 *   <li>Is at least {@value ShareViewPrincipalHandler#MIN_VIEWER_ID_LENGTH} characters</li>
 * </ol>
 *
 * <p>This guard is analogous to {@code CookieBasedFallbackAuthGuard} but for the
 * viewer-context cookie. A wallId-style UUID (no {@code sv_} prefix) is explicitly
 * rejected to prevent cross-endpoint privilege escalation.
 *
 * <p>Security: SR-SHARE-06, ADR-SHARE-05, OWASP API2 (Broken Authentication).
 */
@Component
public class ShareViewAuthGuard {

    private static final Logger AUDIT = LoggerFactory.getLogger("AUDIT");
    private static final Logger log = LoggerFactory.getLogger(ShareViewAuthGuard.class);

    /**
     * Result of viewer authentication.
     *
     * @param authenticated {@code true} when the cookie is valid
     * @param principal     the resolved viewerId; {@code null} when not authenticated
     */
    public record AuthResult(boolean authenticated, String principal) {}

    /**
     * Validates the {@code shareViewerId} cookie value.
     *
     * @param request     the HTTP request (for AUDIT logging context)
     * @param rawViewerId the {@code __Host-shareViewerId} or {@code shareViewerId} cookie value;
     *                    may be {@code null}
     * @return an {@link AuthResult}
     */
    public AuthResult authenticate(final HttpServletRequest request, final String rawViewerId) {
        if (!ShareViewPrincipalHandler.isValidShareViewerId(rawViewerId)) {
            String reason = rawViewerId == null ? "null"
                    : rawViewerId.isBlank() ? "blank"
                    : !rawViewerId.startsWith(ShareViewPrincipalHandler.SV_PREFIX) ? "missing_sv_prefix"
                    : "too_short(" + rawViewerId.length() + ")";
            log.debug("Share viewer auth failed: viewerId={}", reason);
            AUDIT.info("viewer.auth.fail reason={} viewerId-hash={}", reason, LogScrubber.hash8(rawViewerId));
            return new AuthResult(false, null);
        }
        return new AuthResult(true, rawViewerId);
    }
}
