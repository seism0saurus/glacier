package de.seism0saurus.glacier;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.autoconfigure.validation.ValidationAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests verifying that invalid {@code mastodon.*} configuration values
 * prevent the Spring context from starting (fail-fast behavior).
 *
 * <p>Uses {@link ApplicationContextRunner} to exercise the full
 * {@link org.springframework.boot.context.properties.ConfigurationPropertiesBindingPostProcessor}
 * pipeline on a minimal context containing only the {@link MastodonProperties} bean —
 * no web layer, no Bigbone network calls.
 *
 * <p>References: ADR-P3A-1; SR-P3A-13; AC-P3A-02.
 */
class MastodonPropertiesBindingFailureIT {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            // Both auto-configurations are required for @Validated constraints to fire:
            // - ValidationAutoConfiguration: registers the JSR-380 Validator bean
            // - ConfigurationPropertiesAutoConfiguration: enables the full binding pipeline
            //   that triggers @Validated processing on @ConfigurationProperties beans
            // Without these, @Min/@Pattern/@NotNull constraints are silently skipped.
            .withConfiguration(AutoConfigurations.of(
                    ValidationAutoConfiguration.class,
                    ConfigurationPropertiesAutoConfiguration.class))
            .withUserConfiguration(MastodonProperties.class)
            .withPropertyValues(
                    "mastodon.instance=example.com",
                    "mastodon.accessToken=dummy_token",
                    "mastodon.https=true",
                    "mastodon.port=443",
                    "mastodon.readTimeout=240",
                    "mastodon.writeTimeout=240",
                    "mastodon.connectTimeout=240",
                    "mastodon.handle=glacier@example.com"
            );

    @Test
    void handleMissingAtSign_failsContextStartup() {
        // SR-P3A-13: mastodon.handle without the @server part fails @Pattern at bind time.
        // Note: actual CRLF in property values is stripped by Spring's binder before validation;
        // CRLF rejection is tested at the MastodonShortHandle.parse() level (MastodonShortHandleTest).
        runner.withPropertyValues("mastodon.handle=no-at-sign-here")
                .run(context -> {
                    assertThat(context)
                            .as("mastodon.handle without @server must cause context startup failure (SR-P3A-13)")
                            .hasFailed();
                });
    }

    @Test
    void blankAccessToken_failsContextStartup() {
        runner.withPropertyValues("mastodon.accessToken= ")
                .run(context -> {
                    assertThat(context)
                            .as("blank mastodon.accessToken must cause context startup failure")
                            .hasFailed();
                });
    }

    @Test
    void portZero_failsContextStartup() {
        runner.withPropertyValues("mastodon.port=0")
                .run(context -> {
                    assertThat(context)
                            .as("mastodon.port=0 must cause context startup failure")
                            .hasFailed();
                });
    }

    @Test
    void readTimeoutZero_failsContextStartup() {
        // SR-P3A-15: timeout=0 disables timeout in OkHttp — must be rejected
        runner.withPropertyValues("mastodon.readTimeout=0")
                .run(context -> {
                    assertThat(context)
                            .as("mastodon.readTimeout=0 must cause context startup failure (SR-P3A-15)")
                            .hasFailed();
                });
    }
}
