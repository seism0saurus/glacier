package de.seism0saurus.glacier.webservice;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Test-only {@link FallbackAuthGuard} that accepts every request and uses
 * the raw {@code wallId} cookie value as the principal.
 *
 * <p>FIX E: restricted to the {@code test} Spring profile via {@link Profile} so that
 * this bean is NEVER present in production or integration-test contexts that do not
 * activate the {@code test} profile explicitly. The production bean
 * ({@link CookieBasedFallbackAuthGuard}, {@code @Primary}) supersedes this in all
 * contexts where both are present; {@link Profile} provides a second fence so that
 * injection via {@code @Qualifier} in production code is impossible.
 *
 * <p>Tests that need a passthrough guard can either:
 * <ul>
 *   <li>Activate the {@code test} profile ({@code @ActiveProfiles("test")}), or</li>
 *   <li>Instantiate this class directly (it is a plain POJO), or</li>
 *   <li>Use {@code @MockBean FallbackAuthGuard}.</li>
 * </ul>
 */
@Component
@Profile("test")
@ConditionalOnMissingBean(value = FallbackAuthGuard.class, ignored = PassthroughFallbackAuthGuard.class)
public class PassthroughFallbackAuthGuard implements FallbackAuthGuard {

    @Override
    public AuthResult authenticate(final HttpServletRequest request, final String rawWallId) {
        if (rawWallId == null || rawWallId.isBlank()) {
            return new AuthResult(false, null);
        }
        return new AuthResult(true, rawWallId);
    }
}
