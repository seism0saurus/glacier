package de.seism0saurus.glacier;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/**
 * Startup-validated {@code @ConfigurationProperties} bean for core Glacier settings.
 *
 * <p>Applying {@code @Validated} here means Spring Boot's
 * {@code ConfigurationPropertiesBindingPostProcessor} runs Jakarta Validation during
 * context startup — a blank or missing {@code glacier.domain} causes an immediate
 * {@code BindException} rather than a silent NPE at runtime (SR-PQ-12-FU, CWE-20).
 *
 * <p>Bound properties:
 * <ul>
 *   <li>{@code glacier.domain} — the operator's public hostname (e.g. {@code glacier.events});
 *       drives CORS, CSP frame-ancestors, CSRF origin checks, and share-link URL generation.
 *       Must not be blank.</li>
 * </ul>
 */
@Component
@ConfigurationProperties(prefix = "glacier")
@Validated
public class GlacierCoreProperties {

    @NotBlank(message = "glacier.domain must not be blank — set MY_DOMAIN to the operator hostname")
    private String domain;

    public String getDomain() {
        return domain;
    }

    public void setDomain(final String domain) {
        this.domain = domain;
    }
}
