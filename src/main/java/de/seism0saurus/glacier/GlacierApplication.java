package de.seism0saurus.glacier;

import jakarta.annotation.Generated;
import org.jetbrains.annotations.NotNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.time.Clock;

/**
 * The GlacierApplication class is the main class for running the Glacier application.
 * It is annotated with the @SpringBootApplication annotation to enable Spring Boot features and configuration.
 * The @EnableScheduling annotation is used to enable scheduling support in the application
 * (required for {@code FallbackRateLimiter.evictStaleBuckets()}).
 * It contains a main method that starts the application.
 *
 * <p>CORS (D-08, ADR-06, SR-6):
 * Allowed origins for {@code /rest/*} are exactly:
 * <ul>
 *   <li>{@code http://localhost:4200} — Angular dev server</li>
 *   <li>{@code https://${glacier.domain}} — production origin</li>
 * </ul>
 * {@code allowCredentials} is NOT set (defaults to false — never "*" origins with credentials).
 *
 * <p>DataSource auto-configuration exclusions (P3-05; ADR-SQLITE-01):
 * {@link DataSourceAutoConfiguration}, {@link DataSourceTransactionManagerAutoConfiguration},
 * and {@link JdbcTemplateAutoConfiguration} are excluded so that Spring Boot does NOT attempt
 * to auto-configure a global DataSource from {@code application.properties}. The vast majority
 * of Glacier operators do NOT set {@code glacier.share.db.path}, so no DataSource URL is
 * available. Without these exclusions, Boot would fail at startup with a missing-URL error.
 * The SQLite DataSource is opt-in: it is wired by {@code SqliteDataSourceConfig} only when
 * {@code glacier.share.db.path} is present (ADR-SQLITE-01; OWASP A05 — fail-secure defaults).
 */
@SpringBootApplication(exclude = {
        DataSourceAutoConfiguration.class,
        DataSourceTransactionManagerAutoConfiguration.class,
        JdbcTemplateAutoConfiguration.class
})
@EnableScheduling
public class GlacierApplication {

    @Generated(value = "GlacierApplication")
    public static void main(String[] args) {
        SpringApplication.run(GlacierApplication.class, args);
    }

    /**
     * Configures CORS for the {@code /rest/*} path.
     *
     * <p>Security controls (D-08, ADR-06, SR-6, OWASP A05, T-18):
     * <ul>
     *   <li>Exact allowed-origins list — never wildcard {@code *}</li>
     *   <li>{@code allowCredentials} not set (false by default) — prevents cookie exfil
     *       via cross-origin credentialed requests</li>
     *   <li>Production origin ({@code https://${glacier.domain}}) explicitly listed so that
     *       only the operator's domain is permitted, not any subdomain</li>
     * </ul>
     *
     * @param domain the configured {@code glacier.domain} value
     */
    @Bean
    public WebMvcConfigurer corsConfigurer(@Value("${glacier.domain:example.com}") String domain) {
        return new WebMvcConfigurer() {
            @Override
            public void addCorsMappings(@NotNull CorsRegistry registry) {
                // SR-6 / ADR-06: exact origin allowlist, no wildcard, no allowCredentials
                registry.addMapping("/rest/*")
                        .allowedOrigins(
                                "http://localhost:4200",       // Angular dev server
                                "https://" + domain            // production origin
                        );
                // allowCredentials() not called — defaults to false (OWASP A05)
            }
        };
    }

    /**
     * Provides a UTC {@link Clock} bean for injection into {@link de.seism0saurus.glacier.webservice.cache.FallbackRateLimiter}.
     *
     * <p>H-1 fix: externalising the clock makes time-dependent eviction logic fully
     * deterministic in tests — test code can inject a controlled {@link Clock} instance
     * instead of relying on reflection to manipulate {@link java.time.Instant} fields.
     *
     * @return {@link Clock#systemUTC()} for production use
     */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

}
