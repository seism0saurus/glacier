package de.seism0saurus.glacier;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.autoconfigure.validation.ValidationAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test verifying ADR-P3A-8: {@code mastodon.https} must be a boxed
 * {@code Boolean} with {@code @NotNull}, NOT a primitive {@code boolean}.
 *
 * <p>A primitive {@code boolean} in {@code @ConfigurationProperties} silently defaults
 * to {@code false} when the property is absent, causing a silent HTTP downgrade
 * (Mastodon connection without TLS). A boxed {@code Boolean + @NotNull} causes
 * context startup to FAIL with a {@code BindException} when the property is absent.
 *
 * <p>References: ADR-P3A-8; SR-P3A-03; AC-P3A-02; OWASP A02:2021; ASVS V9.1.1 (L1).
 */
class MastodonPropertiesMissingHttpsIT {

    @Test
    void missingHttps_failsContextStartup_notSilentFalse() {
        // ADR-P3A-8: absent mastodon.https must NOT silently default to false
        // (which would silently downgrade to HTTP). Must fail instead.
        new ApplicationContextRunner()
                // Both auto-configurations required for @NotNull on boxed Boolean to fire:
                // - ValidationAutoConfiguration: JSR-380 Validator bean
                // - ConfigurationPropertiesAutoConfiguration: full binding pipeline + @Validated trigger
                // (ADR-P3A-8: absent mastodon.https must fail, not silently default to false)
                .withConfiguration(AutoConfigurations.of(
                        ValidationAutoConfiguration.class,
                        ConfigurationPropertiesAutoConfiguration.class))
                .withUserConfiguration(MastodonProperties.class)
                .withPropertyValues(
                        "mastodon.instance=example.com",
                        "mastodon.accessToken=dummy_token",
                        // mastodon.https is intentionally NOT set
                        "mastodon.port=443",
                        "mastodon.readTimeout=240",
                        "mastodon.writeTimeout=240",
                        "mastodon.connectTimeout=240",
                        "mastodon.handle=glacier@example.com"
                )
                .run(context -> {
                    assertThat(context)
                            .as("Absent mastodon.https must cause context startup failure "
                                    + "(ADR-P3A-8: boxed Boolean + @NotNull, NOT primitive boolean). "
                                    + "Primitive boolean would silently default to false = HTTP downgrade "
                                    + "(OWASP A02:2021; ASVS V9.1.1 L1)")
                            .hasFailed();
                });
    }
}
