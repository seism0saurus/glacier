package de.seism0saurus.glacier.share.web;

import org.jetbrains.annotations.NotNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * MVC configuration for the share-link web layer.
 *
 * <p>Registers CORS mappings for share-link management endpoints. The sharer accesses
 * these endpoints from the main glacier domain (both http in dev and https in prod),
 * so both schemes must be allowed per the {@code glacier.cookie.secure} mode.
 *
 * <p>Security controls (SR-SHARE-05, OWASP A05, ADR-SHARE-09):
 * <ul>
 *   <li>Exact allowed-origin list — never wildcard</li>
 *   <li>{@code allowCredentials(true)} because POST/DELETE require the wallId cookie to be sent
 *       cross-origin (the Angular SPA on {@code localhost:4200} or the production domain
 *       sends credentials; the share management form is served from the glacier domain itself)</li>
 *   <li>Only state-changing methods (POST, DELETE) and GET (list) are allowed</li>
 * </ul>
 */
@Configuration
public class ShareLinkControllerConfig implements WebMvcConfigurer {

    private final String glacierDomain;

    public ShareLinkControllerConfig(
            @Value("${glacier.domain:example.com}") final String glacierDomain) {
        this.glacierDomain = glacierDomain;
    }

    /**
     * CORS mapping for share-link management endpoints.
     *
     * <p>Allows both the HTTP (dev/insecure) and HTTPS (production) origin for the configured
     * glacier domain. This is required so that {@link ShareCsrfGuard} can validate the
     * {@code Origin} header without being intercepted by the CORS 403 from the main
     * glacier CORS config (which only allows {@code https://}).
     *
     * <p>Without this mapping the CORS handler rejects POST/DELETE with 403 before the
     * controller is invoked, making it impossible for the CSRF guard to validate anything.
     *
     * <p>Security: SR-SHARE-05 (CSRF guard requires Origin), OWASP A05 (CORS misconfiguration),
     * NIST SP 800-53 SC-8 (transmission confidentiality and integrity).
     */
    @Override
    public void addCorsMappings(@NotNull CorsRegistry registry) {
        // SR-6 / ADR-06: exact origin list, allow both http (dev) and https (prod).
        // allowCredentials is required — the wallId cookie is a credential that the browser
        // sends only when credentials are explicitly permitted by the CORS response headers.
        registry.addMapping("/rest/share-links")
                .allowedOrigins(
                        "http://localhost:4200",            // Angular dev server
                        "http://" + glacierDomain,          // insecure / dev mode (glacier.cookie.secure=false)
                        "https://" + glacierDomain          // production
                )
                .allowedMethods("GET", "POST", "DELETE", "OPTIONS")
                .allowCredentials(true);

        registry.addMapping("/rest/share-links/**")
                .allowedOrigins(
                        "http://localhost:4200",
                        "http://" + glacierDomain,
                        "https://" + glacierDomain
                )
                .allowedMethods("GET", "POST", "DELETE", "OPTIONS")
                .allowCredentials(true);

        // Also allow CSRF token endpoint from the same origins
        registry.addMapping("/rest/share-csrf")
                .allowedOrigins(
                        "http://localhost:4200",
                        "http://" + glacierDomain,
                        "https://" + glacierDomain
                )
                .allowedMethods("GET", "OPTIONS")
                .allowCredentials(true);
    }
}
