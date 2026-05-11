package de.seism0saurus.glacier;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.autoconfigure.validation.ValidationAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test verifying that invalid {@code glacier.operator.*} configuration
 * values prevent the Spring context from starting (fail-fast behavior).
 *
 * <p>References: ADR-P3A-3; SR-P3A-11; SR-P3A-12.
 */
class GlacierOperatorPropertiesBindingFailureIT {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            // Both auto-configurations required for @Email/@Pattern/@SafeOperatorString
            // constraints to fire on @ConfigurationProperties beans during context startup.
            // - ValidationAutoConfiguration: JSR-380 Validator bean
            // - ConfigurationPropertiesAutoConfiguration: full binding pipeline + @Validated trigger
            .withConfiguration(AutoConfigurations.of(
                    ValidationAutoConfiguration.class,
                    ConfigurationPropertiesAutoConfiguration.class))
            .withUserConfiguration(GlacierOperatorProperties.class)
            .withPropertyValues(
                    "glacier.operator.name=Jon Doe",
                    "glacier.operator.streetAndNumber=somewhere 1",
                    "glacier.operator.zipcode=12345",
                    "glacier.operator.city=somecity",
                    "glacier.operator.country=Germany",
                    "glacier.operator.phone=+123456789",
                    "glacier.operator.mail=mail@example.com",
                    "glacier.operator.website=example.com"
            );

    @Test
    void invalidMail_failsContextStartup() {
        // @Email constraint: "garbage" is not a valid email address
        runner.withPropertyValues("glacier.operator.mail=garbage")
                .run(context -> {
                    assertThat(context)
                            .as("glacier.operator.mail=garbage must cause context startup failure")
                            .hasFailed();
                });
    }

    @Test
    void javascriptWebsite_failsContextStartup() {
        // SR-P3A-11: javascript: URI must be rejected at bind time, not reach the REST endpoint
        runner.withPropertyValues("glacier.operator.website=javascript:alert(1)")
                .run(context -> {
                    assertThat(context)
                            .as("glacier.operator.website=javascript:alert(1) must cause context startup failure "
                                    + "(SR-P3A-11, CWE-79)")
                            .hasFailed();
                });
    }

    @Test
    void crlfInName_failsContextStartup() {
        // @SafeOperatorString: CRLF in name must cause startup failure
        runner.withPropertyValues("glacier.operator.name=Evil\r\nAdmin")
                .run(context -> {
                    assertThat(context)
                            .as("CRLF in glacier.operator.name must cause context startup failure")
                            .hasFailed();
                });
    }

    @Test
    void blankName_failsContextStartup() {
        runner.withPropertyValues("glacier.operator.name= ")
                .run(context -> {
                    assertThat(context)
                            .as("blank glacier.operator.name must cause context startup failure")
                            .hasFailed();
                });
    }
}
