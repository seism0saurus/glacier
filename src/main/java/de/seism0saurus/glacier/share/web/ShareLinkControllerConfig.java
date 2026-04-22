package de.seism0saurus.glacier.share.web;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * MVC configuration for the share-link web layer.
 *
 * <p>This class registers shared web components for the {@code /rest/share-links} API.
 * Currently a placeholder — the CSRF guard/interceptor ({@code ShareCsrfGuard}) is owned by
 * the {@code secure-tdd-implementer} lane and will be registered here via a Spring
 * {@link WebMvcConfigurer} extension once that lane delivers the interceptor interface.
 *
 * <p>Extending via {@link WebMvcConfigurer} keeps the configuration pluggable:
 * {@code secure-tdd-implementer} can import this class and add interceptors without
 * modifying {@link ShareLinkController} itself.
 */
@Configuration
public class ShareLinkControllerConfig implements WebMvcConfigurer {
    // Interceptors for CSRF protection will be registered here by the secure-tdd-implementer lane.
    // This class is intentionally minimal in Round 1.
}
