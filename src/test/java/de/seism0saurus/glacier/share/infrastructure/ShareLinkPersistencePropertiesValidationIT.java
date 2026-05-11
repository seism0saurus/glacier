package de.seism0saurus.glacier.share.infrastructure;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.autoconfigure.validation.ValidationAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test verifying that an invalid {@link SharePersistenceProperties}
 * configuration causes a {@code BindException} at startup rather than a silent failure.
 *
 * <p>Two cases are tested:
 * <ol>
 *   <li>Blank {@code glacier.share.db.ip-hmac-key} with a valid path → context fails
 *       immediately at binding (SR-SQLITE-17; SR-SQLITE-22).</li>
 *   <li>Path traversal in {@code glacier.share.db.path} → context fails at binding
 *       (SR-SQLITE-08; SR-SQLITE-17).</li>
 * </ol>
 *
 * <p>References: ADR-SQLITE-01; SR-SQLITE-17; SR-SQLITE-22; SR-SQLITE-08;
 * CWE-20; ASVS V5.1.3 (L1); OWASP A05:2021 Security Misconfiguration.
 */
class ShareLinkPersistencePropertiesValidationIT {

    /** 44-char base64 key (valid for all tests that don't test the key). */
    private static final String VALID_KEY = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";
    /** Valid DB path (Linux absolute). */
    private static final String VALID_PATH = "/var/data/glacier/share.db";

    private ApplicationContextRunner baseRunner() {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        ValidationAutoConfiguration.class,
                        ConfigurationPropertiesAutoConfiguration.class))
                .withUserConfiguration(SharePersistenceProperties.class);
    }

    // -------------------------------------------------------------------------
    // Blank ipHmacKey → context must fail
    // -------------------------------------------------------------------------

    /**
     * SR-SQLITE-22: blank {@code glacier.share.db.ip-hmac-key} must cause a startup
     * {@code BindException} (or {@code BindValidationException}).  An empty HMAC key
     * would silently produce a predictable HMAC, defeating the IP pseudonymisation.
     */
    @Test
    void blankIpHmacKey_causesStartupFailure() {
        baseRunner()
                .withPropertyValues(
                        "glacier.share.db.path=" + VALID_PATH,
                        "glacier.share.db.ip-hmac-key=   "   // blank
                )
                .run(context -> assertThat(context)
                        .as("Blank glacier.share.db.ip-hmac-key must cause startup failure "
                                + "(SR-SQLITE-22, SR-SQLITE-17, ASVS V5.1.3 L1)")
                        .hasFailed());
    }

    /**
     * SR-SQLITE-22: missing {@code glacier.share.db.ip-hmac-key} must cause a startup failure.
     */
    @Test
    void missingIpHmacKey_causesStartupFailure() {
        baseRunner()
                .withPropertyValues(
                        "glacier.share.db.path=" + VALID_PATH
                        // ip-hmac-key intentionally absent
                )
                .run(context -> assertThat(context)
                        .as("Missing glacier.share.db.ip-hmac-key must cause startup failure "
                                + "(SR-SQLITE-22, SR-SQLITE-17)")
                        .hasFailed());
    }

    /**
     * SR-SQLITE-22: too-short {@code glacier.share.db.ip-hmac-key} (43 chars, below min=44)
     * must cause a startup failure.
     */
    @Test
    void tooShortIpHmacKey_causesStartupFailure() {
        baseRunner()
                .withPropertyValues(
                        "glacier.share.db.path=" + VALID_PATH,
                        "glacier.share.db.ip-hmac-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" // 43 chars
                )
                .run(context -> assertThat(context)
                        .as("43-char glacier.share.db.ip-hmac-key must fail @Size(min=44) at startup "
                                + "(SR-SQLITE-22, SR-SQLITE-17)")
                        .hasFailed());
    }

    // -------------------------------------------------------------------------
    // Path traversal in path → context must fail
    // -------------------------------------------------------------------------

    /**
     * SR-SQLITE-08: path traversal in {@code glacier.share.db.path} must cause a startup failure.
     */
    @Test
    void pathTraversalInDbPath_causesStartupFailure() {
        baseRunner()
                .withPropertyValues(
                        "glacier.share.db.path=../etc/passwd",
                        "glacier.share.db.ip-hmac-key=" + VALID_KEY
                )
                .run(context -> assertThat(context)
                        .as("Path traversal in glacier.share.db.path must cause startup failure "
                                + "(SR-SQLITE-08, SR-SQLITE-17, CWE-22)")
                        .hasFailed());
    }

    // -------------------------------------------------------------------------
    // Valid properties — context must succeed
    // -------------------------------------------------------------------------

    /**
     * Valid properties with {@code glacier.share.db.path} set must produce a successful
     * binding (the full Spring context is not started here — only the properties bean).
     *
     * <p>SR-SQLITE-17: valid configuration must not fail at startup.
     */
    @Test
    void validProperties_bindSuccessfully() {
        baseRunner()
                .withPropertyValues(
                        "glacier.share.db.path=" + VALID_PATH,
                        "glacier.share.db.ip-hmac-key=" + VALID_KEY
                )
                .run(context -> {
                    // The bean itself (not a full Spring context) should be present
                    assertThat(context)
                            .as("Valid SharePersistenceProperties must bind without errors "
                                    + "(SR-SQLITE-17, ADR-SQLITE-01)")
                            .hasNotFailed();
                });
    }
}
