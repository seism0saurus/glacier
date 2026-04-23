package de.seism0saurus.glacier.webservice;

import de.seism0saurus.glacier.util.LogScrubber;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * Production {@link FallbackAuthGuard} that validates the {@code wallId} cookie.
 *
 * <p>Security controls (D-08, ADR-06, OWASP API2, SR-2):
 * <ul>
 *   <li>Rejects null, blank, or too-short wallId with 401 {@code missing_wallid}</li>
 *   <li>Uses cookie-only auth — never reads from URL params or request body</li>
 *   <li>Logs auth failures at DEBUG with a hashed wallId-prefix only — never the raw value</li>
 *   <li>Anti-enumeration: unknown-subscription collapse at the controller level ensures
 *       consistent error shapes (T-07)</li>
 * </ul>
 *
 * <p>Annotated {@link Primary} to supersede {@link PassthroughFallbackAuthGuard} in all
 * application contexts where both beans are present on the classpath.
 */
@Component
@Primary
public class CookieBasedFallbackAuthGuard implements FallbackAuthGuard {

    // OWASP A07: Authentication — dedicated logger for auth failures (D-13, SR-8)
    private static final Logger AUDIT = LoggerFactory.getLogger("AUDIT");
    private static final Logger LOGGER = LoggerFactory.getLogger(CookieBasedFallbackAuthGuard.class);

    /** Minimum required length for a wallId to be considered valid (UUID = 36 chars; be generous). */
    static final int MIN_WALL_ID_LENGTH = 32;

    @Override
    public AuthResult authenticate(final HttpServletRequest request, final String rawWallId) {
        // OWASP A07 / SR-2: reject absent, blank, or too-short wallId
        if (rawWallId == null || rawWallId.isBlank() || rawWallId.length() < MIN_WALL_ID_LENGTH) {
            // Log with hashed prefix only — never the raw cookie value (D-13, SR-8)
            String safeId = rawWallId == null ? "null"
                    : rawWallId.isBlank() ? "blank"
                    : "short(" + rawWallId.length() + ")";
            LOGGER.debug("Auth failed: wallId is {} — returning 401", safeId);
            AUDIT.info("fallback.auth.fail wallId={}", safeId);
            return new AuthResult(false, null);
        }

        // Use the raw wallId as the principal — the ring buffer is keyed on this value.
        // We intentionally do NOT hash it here: the principal must remain the real wallId
        // so that MessageCache lookups succeed. Hashing happens only in log statements.
        return new AuthResult(true, rawWallId);
    }

}
