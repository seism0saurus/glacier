package de.seism0saurus.glacier;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link MastodonProperties} startup-validation guards.
 *
 * <p>Uses the Jakarta Validation API directly — no Spring context required.
 * Each test verifies that exactly the right constraint fires for invalid input,
 * and that valid input passes all constraints.
 *
 * <p>Security requirements addressed:
 * <ul>
 *   <li>SR-P3A-03: mastodon.https must be Boolean + @NotNull (ASVS V9.1.1 L1)</li>
 *   <li>SR-P3A-13: mastodon.handle @Pattern must use \A/\z anchors, exclude controls</li>
 *   <li>SR-P3A-15: timeout @Min(1) not @Min(0) — zero timeout = no-timeout DoS</li>
 * </ul>
 */
class MastodonPropertiesTest {

    private static Validator validator;

    @BeforeAll
    static void buildValidator() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            validator = factory.getValidator();
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private MastodonProperties validProperties() {
        MastodonProperties p = new MastodonProperties();
        p.setInstance("example.com");
        p.setAccessToken("dummy_access_key");
        p.setHttps(Boolean.TRUE);
        p.setPort(443);
        p.setReadTimeout(240);
        p.setWriteTimeout(240);
        p.setConnectTimeout(240);
        p.setHandle("glacier@instance.social");
        return p;
    }

    private Set<ConstraintViolation<MastodonProperties>> violationsFor(MastodonProperties p) {
        return validator.validate(p);
    }

    // -------------------------------------------------------------------------
    // @NotBlank / @NotNull guards
    // -------------------------------------------------------------------------

    @Test
    void blankInstance_isRejected() {
        MastodonProperties p = validProperties();
        p.setInstance("");
        assertThat(violationsFor(p))
                .as("blank mastodon.instance must be rejected")
                .isNotEmpty();
    }

    @Test
    void blankAccessToken_isRejected() {
        MastodonProperties p = validProperties();
        p.setAccessToken("  ");
        assertThat(violationsFor(p))
                .as("blank mastodon.accessToken must be rejected")
                .isNotEmpty();
    }

    @Test
    void blankHandle_isRejected() {
        MastodonProperties p = validProperties();
        p.setHandle("");
        assertThat(violationsFor(p))
                .as("blank mastodon.handle must be rejected")
                .isNotEmpty();
    }

    @Test
    void handleWithoutAt_isRejected() {
        // "alice" — no @ separator — is not a valid Mastodon handle
        MastodonProperties p = validProperties();
        p.setHandle("alice");
        assertThat(violationsFor(p))
                .as("handle without @ must fail @Pattern (SR-P3A-13)")
                .isNotEmpty();
    }

    @Test
    void handleWithCRLF_isRejected() {
        // CRLF injection attempt (SR-P3A-13: \A/\z anchors must prevent this)
        MastodonProperties p = validProperties();
        p.setHandle("alice@srv\r\nX-Injected: 1");
        assertThat(violationsFor(p))
                .as("handle with CRLF must fail @Pattern (SR-P3A-13, CWE-117)")
                .isNotEmpty();
    }

    // -------------------------------------------------------------------------
    // Port range guards
    // -------------------------------------------------------------------------

    @Test
    void portZero_isRejected() {
        MastodonProperties p = validProperties();
        p.setPort(0);
        assertThat(violationsFor(p))
                .as("port 0 must fail @Min(1)")
                .isNotEmpty();
    }

    @Test
    void portTooHigh_isRejected() {
        MastodonProperties p = validProperties();
        p.setPort(65536);
        assertThat(violationsFor(p))
                .as("port 65536 must fail @Max(65535)")
                .isNotEmpty();
    }

    // -------------------------------------------------------------------------
    // Timeout guards — SR-P3A-15: zero timeout = no-timeout DoS
    // -------------------------------------------------------------------------

    @Test
    void readTimeoutZero_isRejected() {
        // SR-P3A-15: @Min(1) not @Min(0) — timeout 0 means no timeout in OkHttp
        MastodonProperties p = validProperties();
        p.setReadTimeout(0);
        assertThat(violationsFor(p))
                .as("readTimeout=0 must fail @Min(1) — 0 disables timeout (SR-P3A-15)")
                .isNotEmpty();
    }

    @Test
    void writeTimeoutZero_isRejected() {
        MastodonProperties p = validProperties();
        p.setWriteTimeout(0);
        assertThat(violationsFor(p))
                .as("writeTimeout=0 must fail @Min(1) — 0 disables timeout (SR-P3A-15)")
                .isNotEmpty();
    }

    @Test
    void connectTimeoutZero_isRejected() {
        MastodonProperties p = validProperties();
        p.setConnectTimeout(0);
        assertThat(violationsFor(p))
                .as("connectTimeout=0 must fail @Min(1) — 0 disables timeout (SR-P3A-15)")
                .isNotEmpty();
    }

    // -------------------------------------------------------------------------
    // ADR-P3A-8: Boolean (boxed) + @NotNull — null must be rejected
    // -------------------------------------------------------------------------

    @Test
    void https_null_isRejected() {
        // ADR-P3A-8: primitive boolean silently defaults to false (HTTP downgrade);
        // boxed Boolean + @NotNull must reject absent value (ASVS V9.1.1 L1)
        MastodonProperties p = validProperties();
        p.setHttps(null);
        assertThat(violationsFor(p))
                .as("null mastodon.https must fail @NotNull (ADR-P3A-8, ASVS V9.1.1 L1)")
                .isNotEmpty();
    }

    // -------------------------------------------------------------------------
    // Happy path — all valid values must pass
    // -------------------------------------------------------------------------

    @Test
    void validValues_pass() {
        MastodonProperties p = validProperties();
        assertThat(violationsFor(p))
                .as("all valid values must pass validation")
                .isEmpty();
    }

    @Test
    void handleWithLeadingAt_pass() {
        // @alice@instance.social — leading @ is optional but valid
        MastodonProperties p = validProperties();
        p.setHandle("@glacier@instance.social");
        assertThat(violationsFor(p))
                .as("handle with leading @ must pass @Pattern")
                .isEmpty();
    }
}
