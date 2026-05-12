package de.seism0saurus.glacier.share.infrastructure;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.autoconfigure.validation.ValidationAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test verifying fail-closed behaviour of {@link SharePersistenceProperties}.
 *
 * <p>When {@code glacier.share.db.path} is set but the required {@code ip-hmac-key} is
 * absent or too short, the Spring context must refuse to start with a {@code BindException}
 * (SR-SQLITE-17; OWASP A05:2021 — fail-secure defaults).
 *
 * <p>Uses {@link ApplicationContextRunner} rather than a full {@code @SpringBootTest} to
 * keep the test fast and independent of the complete application context. The two
 * auto-configurations applied ({@code ValidationAutoConfiguration} and
 * {@code ConfigurationPropertiesAutoConfiguration}) are the minimal set required to trigger
 * the {@code @Validated} pipeline on {@code @ConfigurationProperties} beans.
 *
 * <p>References: SR-SQLITE-17; SR-SQLITE-22; ADR-SQLITE-01;
 * OWASP A05:2021 — Security Misconfiguration.
 */
class ShareLinkPersistencePropertiesValidationIT {

    /**
     * Base runner with validation auto-configurations and the properties bean registered.
     * Tests add property values via {@link ApplicationContextRunner#withPropertyValues}.
     */
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    ValidationAutoConfiguration.class,
                    ConfigurationPropertiesAutoConfiguration.class))
            .withUserConfiguration(SharePersistenceProperties.class);

    // -------------------------------------------------------------------------
    // Fail-closed: path set + key absent → BindException
    // SR-SQLITE-17; SR-SQLITE-22
    // -------------------------------------------------------------------------

    @Test
    void pathSetButIpHmacKeyAbsent_failsContextStartup() {
        // SR-SQLITE-17: missing required secret must prevent context startup (fail-closed)
        runner.withPropertyValues("glacier.share.db.path=/var/glacier.db")
                .run(context -> assertThat(context)
                        .as("Context must fail when glacier.share.db.path is set "
                                + "but glacier.share.db.ip-hmac-key is absent (SR-SQLITE-17)")
                        .hasFailed());
    }

    @Test
    void pathSetAndKeyTooShort_failsContextStartup() {
        // SR-SQLITE-22: @Size(min=44) rejects keys shorter than 256-bit Base64
        runner.withPropertyValues(
                        "glacier.share.db.path=/var/glacier.db",
                        "glacier.share.db.ip-hmac-key=" + "A".repeat(43)) // 43 chars — one below min
                .run(context -> assertThat(context)
                        .as("Context must fail when ip-hmac-key is 43 chars (below min=44, SR-SQLITE-22)")
                        .hasFailed());
    }

    @Test
    void pathSetAndKeyBlank_failsContextStartup() {
        // @NotBlank: blank key must fail
        runner.withPropertyValues(
                        "glacier.share.db.path=/var/glacier.db",
                        "glacier.share.db.ip-hmac-key=   ")
                .run(context -> assertThat(context)
                        .as("Context must fail when ip-hmac-key is blank (SR-SQLITE-17)")
                        .hasFailed());
    }

    @Test
    void pathSetAndKeyHasTraversal_failsContextStartup() {
        // @SafeFilesystemPath: traversal in path must fail
        runner.withPropertyValues(
                        "glacier.share.db.path=../../etc/shadow",
                        "glacier.share.db.ip-hmac-key=" + "A".repeat(44))
                .run(context -> assertThat(context)
                        .as("Context must fail when glacier.share.db.path contains traversal "
                                + "(SR-SQLITE-08; CWE-22)")
                        .hasFailed());
    }

    // -------------------------------------------------------------------------
    // Happy path: valid path + sufficient key → context loads
    // -------------------------------------------------------------------------

    @Test
    void pathSetAndSufficientKey_contextLoadsSuccessfully() {
        // SR-SQLITE-17 / SR-SQLITE-22: valid config must succeed
        runner.withPropertyValues(
                        "glacier.share.db.path=/var/glacier.db",
                        "glacier.share.db.ip-hmac-key=" + "A".repeat(44))
                .run(context -> assertThat(context)
                        .as("Context must start successfully with valid glacier.share.db.* config")
                        .hasNotFailed());
    }

    @Test
    void pathAbsent_contextLoadsSuccessfully_inMemoryAdapterActive() {
        // ADR-SQLITE-01: when path is absent, SharePersistenceProperties is not active;
        // no validation runs, no failure
        runner.run(context -> assertThat(context)
                .as("Context must start when glacier.share.db.path is absent "
                        + "(in-memory adapter is the default)")
                .hasNotFailed());
    }

    @Test
    void minimumKeyLengthOf44_isAccepted() {
        // Boundary: exactly 44 chars must pass @Size(min=44)
        runner.withPropertyValues(
                        "glacier.share.db.path=/var/glacier.db",
                        "glacier.share.db.ip-hmac-key=" + "A".repeat(44))
                .run(context -> assertThat(context)
                        .as("Exactly 44-char ip-hmac-key must satisfy @Size(min=44) (SR-SQLITE-22)")
                        .hasNotFailed());
    }
}
