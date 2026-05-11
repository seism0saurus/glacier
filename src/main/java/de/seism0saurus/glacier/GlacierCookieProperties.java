package de.seism0saurus.glacier;

import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/**
 * Startup-validated {@code @ConfigurationProperties} bean for cookie security settings.
 *
 * <p>Owned keys under {@code glacier.cookie.*}:
 * <ul>
 *   <li>{@code glacier.cookie.secure} — whether the {@code wallId} cookie (and all share
 *       cookies) carry the {@code Secure} attribute. {@code true} in production (HTTPS);
 *       {@code false} only in local dev/loopback mode.</li>
 * </ul>
 *
 * <p>This bean does NOT own any {@code glacier.devmode.*} or {@code glacier.domain} keys
 * (see {@link GlacierCoreProperties} for {@code glacier.domain}).
 *
 * <p>Security: {@code @NotNull} enforces fail-fast startup on missing property — prevents
 * silent HTTP downgrade via primitive {@code boolean} default-false (CWE-1188, ADR-P3B-3,
 * ADR-P3A-8). Use {@code Boolean.TRUE.equals(getSecure())} in consumers for null-safe
 * unboxing (defensive against test-mocking paths).
 *
 * <p>References: ADR-P3B-1; OWASP A02:2021; ASVS V14.1.1 (L1); CWE-1188.
 */
@Component
@ConfigurationProperties(prefix = "glacier.cookie")
@Validated
public class GlacierCookieProperties {

    /**
     * Whether cookies carry the {@code Secure} attribute.
     *
     * <p>Must be set explicitly — there is no default. {@code true} in production (HTTPS
     * deployment); {@code false} only in local dev/loopback mode where HTTPS is not available.
     *
     * <p>MUST be {@link Boolean} (boxed), NOT {@code boolean} (primitive).
     * Primitive {@code boolean} in {@code @ConfigurationProperties} silently defaults
     * to {@code false} when the property is absent — causing cookies to be sent over
     * plain HTTP without the {@code Secure} attribute (session hijackable).
     * Boxed {@code Boolean + @NotNull} causes context startup to fail when absent
     * (ADR-P3B-3; CWE-1188; ASVS V14.1.1 L1; OWASP A02:2021).
     */
    @NotNull(message = "glacier.cookie.secure must be set explicitly (true in production, "
            + "false only in local dev/loopback mode) — see ADR-P3A-8, ADR-P3B-1")
    private Boolean secure;

    /**
     * Returns whether cookies should carry the {@code Secure} attribute.
     *
     * <p>Returns {@link Boolean} (boxed), not {@code boolean}. Callers must use
     * {@code Boolean.TRUE.equals(getSecure())} for null-safe unboxing (ADR-P3B-3).
     *
     * @return {@code true} for HTTPS production, {@code false} for dev/loopback mode;
     *         never {@code null} in a running application (enforced by {@code @NotNull})
     */
    public Boolean getSecure() {
        return secure;
    }

    /**
     * Sets whether cookies should carry the {@code Secure} attribute.
     *
     * <p>Called by Spring Boot's {@code @ConfigurationProperties} binding infrastructure.
     * Application code must not call this method directly.
     *
     * @param secure {@code true} for production HTTPS; {@code false} for dev/loopback mode
     */
    public void setSecure(final Boolean secure) {
        this.secure = secure;
    }
}
