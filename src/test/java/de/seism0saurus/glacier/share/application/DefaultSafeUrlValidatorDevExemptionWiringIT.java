package de.seism0saurus.glacier.share.application;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spring wiring test for the dev-only instance-host SSRF exemption.
 *
 * <p>The unit test ({@code DefaultSafeUrlValidatorDevExemptionTest}) proves the logic with a
 * hand-constructed validator. This test proves the <em>wiring</em>: that Spring binds
 * {@code glacier.devmode} and {@code mastodon.instance} into the {@code @Autowired} constructor
 * (and does NOT fall back to the production-strict no-arg constructor). If the bindings or the
 * constructor selection were wrong, {@code devMode} would always be {@code false} and the
 * exemption would silently never apply.
 */
class DefaultSafeUrlValidatorDevExemptionWiringIT {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(PropertyPlaceholderAutoConfiguration.class))
            .withUserConfiguration(DefaultSafeUrlValidator.class);

    @Test
    void devModeWiring_exemptsConfiguredInstanceHostButNotOtherPrivateHosts() {
        runner.withPropertyValues("glacier.devmode=true", "mastodon.instance=10.1.2.3")
                .run(ctx -> {
                    SafeUrlValidator validator = ctx.getBean(SafeUrlValidator.class);
                    assertThat(validator.validate("http://10.1.2.3/@user/116/embed"))
                            .as("configured instance host is exempt in dev mode")
                            .isPresent();
                    assertThat(validator.validate("http://10.9.9.9/embed"))
                            .as("other private hosts remain blocked in dev mode")
                            .isEmpty();
                });
    }

    @Test
    void prodWiring_blocksConfiguredInstanceHost() {
        runner.withPropertyValues("glacier.devmode=false", "mastodon.instance=10.1.2.3")
                .run(ctx -> {
                    SafeUrlValidator validator = ctx.getBean(SafeUrlValidator.class);
                    assertThat(validator.validate("http://10.1.2.3/@user/116/embed"))
                            .as("production binds devmode=false → no exemption")
                            .isEmpty();
                });
    }
}
