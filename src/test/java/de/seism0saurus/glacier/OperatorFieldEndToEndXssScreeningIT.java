package de.seism0saurus.glacier;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.autoconfigure.validation.ValidationAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test verifying that the XSS-risky {@code glacier.operator.website}
 * values are rejected at BIND TIME — not at the REST endpoint.
 *
 * <p>The key security invariant is that {@code javascript:alert(1)} must never
 * reach {@link de.seism0saurus.glacier.webservice.InformationController#getInstanceOperator()}
 * because it would be embedded in HTML/JSON served to browsers.
 *
 * <p>References: SR-P3A-11; ADR-P3A-3; AC-P3A-14; CWE-79 (XSS).
 */
class OperatorFieldEndToEndXssScreeningIT {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            // Both auto-configurations required for @Pattern on website URL to fire:
            // - ValidationAutoConfiguration: JSR-380 Validator bean
            // - ConfigurationPropertiesAutoConfiguration: full binding pipeline + @Validated trigger
            // (SR-P3A-11: javascript:/data: URIs must fail at bind time, not reach REST endpoint)
            .withConfiguration(AutoConfigurations.of(
                    ValidationAutoConfiguration.class,
                    ConfigurationPropertiesAutoConfiguration.class))
            .withUserConfiguration(GlacierOperatorProperties.class)
            .withPropertyValues(
                    "glacier.operator.name=Legit Operator",
                    "glacier.operator.streetAndNumber=Main St 1",
                    "glacier.operator.zipcode=12345",
                    "glacier.operator.city=Berlin",
                    "glacier.operator.country=Germany",
                    "glacier.operator.phone=+4912345678",
                    "glacier.operator.mail=legit@example.com",
                    "glacier.operator.website=https://example.com"
            );

    @Test
    void legitimateWebsite_contextStartsSuccessfully() {
        // A real website URL must be accepted
        runner.run(context -> {
            assertThat(context)
                    .as("Legitimate operator website must allow context startup")
                    .hasNotFailed();
            GlacierOperatorProperties props = context.getBean(GlacierOperatorProperties.class);
            assertThat(props.getName())
                    .as("operator name must be 'Legit Operator'")
                    .isEqualTo("Legit Operator");
        });
    }

    @Test
    void javascriptWebsite_isRejectedAtBindTime() {
        // SR-P3A-11: javascript: URI must be rejected at bind time (not at REST endpoint)
        runner.withPropertyValues("glacier.operator.website=javascript:alert(1)")
                .run(context -> {
                    assertThat(context)
                            .as("glacier.operator.website=javascript:alert(1) must be rejected "
                                    + "at context startup (bind time), not reach the REST endpoint "
                                    + "(SR-P3A-11, CWE-79)")
                            .hasFailed();
                });
    }

    @Test
    void dataUriWebsite_isRejectedAtBindTime() {
        // SR-P3A-11: data: URI must also be rejected
        runner.withPropertyValues("glacier.operator.website=data:text/html,<script>alert(1)</script>")
                .run(context -> {
                    assertThat(context)
                            .as("glacier.operator.website with data: URI must be rejected at bind time "
                                    + "(SR-P3A-11, CWE-79)")
                            .hasFailed();
                });
    }
}
