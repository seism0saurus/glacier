package de.seism0saurus.glacier;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link DomainSafetyValidator} applied to {@link GlacierCoreProperties}.
 *
 * <p>Verifies that {@code glacier.domain} rejects loopback/internal addresses, CRLF
 * characters, null bytes, and tab characters at startup.
 *
 * <p>References: Sec-12/P1-11; OWASP A05:2021 Security Misconfiguration;
 * ASVS V5.1.3 (L1) — no CRLF in header values.
 */
class DomainSafetyValidatorTest {

    private static Validator validator;

    @BeforeAll
    static void buildValidator() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            validator = factory.getValidator();
        }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private Set<ConstraintViolation<GlacierCoreProperties>> validate(String domain) {
        GlacierCoreProperties props = new GlacierCoreProperties();
        props.setDomain(domain);
        return validator.validate(props);
    }

    // -----------------------------------------------------------------------
    // Valid domains — must pass
    // -----------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {
            "glacier.events",
            "example.com",
            "my-wall.social",
            "wall.example.org",
            "wall123.example.com",
            "glacier.seism0saurus.de"
    })
    void validDomain_passesValidation(String domain) {
        assertThat(validate(domain))
                .as("Valid domain '%s' should pass validation", domain)
                .isEmpty();
    }

    // -----------------------------------------------------------------------
    // Loopback / reserved literals
    // -----------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"localhost", "LOCALHOST", "Localhost"})
    void localhostDomain_violatesConstraint(String domain) {
        assertThat(validate(domain))
                .as("'%s' must be rejected as loopback (Sec-12)", domain)
                .isNotEmpty();
    }

    @Test
    void ipv4Loopback_127_0_0_1_violatesConstraint() {
        assertThat(validate("127.0.0.1"))
                .as("127.0.0.1 must be rejected as loopback (Sec-12)")
                .isNotEmpty();
    }

    @Test
    void ipv4LoopbackRange_127_x_x_x_violatesConstraint() {
        assertThat(validate("127.99.99.99"))
                .as("127.x.x.x range must be rejected as loopback (Sec-12)")
                .isNotEmpty();
    }

    @Test
    void ipv6Loopback_violatesConstraint() {
        assertThat(validate("::1"))
                .as("::1 must be rejected as loopback (Sec-12)")
                .isNotEmpty();
    }

    @Test
    void anyAddress_0_0_0_0_violatesConstraint() {
        assertThat(validate("0.0.0.0"))
                .as("0.0.0.0 must be rejected (Sec-12)")
                .isNotEmpty();
    }

    // -----------------------------------------------------------------------
    // RFC1918 / private IP ranges
    // -----------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {
            "192.168.1.1",
            "192.168.0.1",
            "192.168.255.255"
    })
    void rfc1918_192_168_range_violatesConstraint(String domain) {
        assertThat(validate(domain))
                .as("192.168.x.x must be rejected as private (Sec-12)", domain)
                .isNotEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "10.0.0.1",
            "10.255.255.255"
    })
    void rfc1918_10_range_violatesConstraint(String domain) {
        assertThat(validate(domain))
                .as("10.x.x.x must be rejected as private (Sec-12)", domain)
                .isNotEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "172.16.0.1",
            "172.31.255.255"
    })
    void rfc1918_172_range_violatesConstraint(String domain) {
        assertThat(validate(domain))
                .as("172.16-31.x.x must be rejected as private (Sec-12)", domain)
                .isNotEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "172.15.0.1",   // Just outside range — should pass
            "172.32.0.1"    // Just outside range — should pass
    })
    void rfc1918_172_outside_range_passesValidation(String domain) {
        // These are NOT private RFC1918 — should pass validation
        // (172.15 and 172.32 are public ranges)
        assertThat(validate(domain))
                .as("172.%s is outside RFC1918 range — should pass", domain)
                .isEmpty();
    }

    @Test
    void linkLocal_169_254_violatesConstraint() {
        assertThat(validate("169.254.1.1"))
                .as("169.254.x.x link-local must be rejected (Sec-12)")
                .isNotEmpty();
    }

    // -----------------------------------------------------------------------
    // CRLF injection — Sec-12, ASVS V5.1.3 (L1)
    // -----------------------------------------------------------------------

    @Test
    void crlfInDomain_carriageReturn_violatesConstraint() {
        assertThat(validate("glacier.events\rSet-Cookie: evil=1"))
                .as("CR in domain must be rejected (header injection — Sec-12, ASVS V5.1.3)")
                .isNotEmpty();
    }

    @Test
    void crlfInDomain_lineFeed_violatesConstraint() {
        assertThat(validate("glacier.events\nX-Injected: header"))
                .as("LF in domain must be rejected (header injection — Sec-12, ASVS V5.1.3)")
                .isNotEmpty();
    }

    @Test
    void crlfInDomain_crlfSequence_violatesConstraint() {
        assertThat(validate("glacier.events\r\nX-Evil: injected"))
                .as("CRLF sequence in domain must be rejected (Sec-12, ASVS V5.1.3)")
                .isNotEmpty();
    }

    // -----------------------------------------------------------------------
    // Null bytes — Sec-12
    // -----------------------------------------------------------------------

    @Test
    void nullByteInDomain_violatesConstraint() {
        assertThat(validate("glacier.events\0.evil.com"))
                .as("Null byte in domain must be rejected (path truncation — Sec-12)")
                .isNotEmpty();
    }

    // -----------------------------------------------------------------------
    // Tab characters — Sec-12
    // -----------------------------------------------------------------------

    @Test
    void tabInDomain_violatesConstraint() {
        assertThat(validate("glacier.events\tX-Tab: injected"))
                .as("Tab in domain must be rejected (Sec-12)")
                .isNotEmpty();
    }

    // -----------------------------------------------------------------------
    // IPv6 link-local
    // -----------------------------------------------------------------------

    @Test
    void ipv6LinkLocal_fe80_violatesConstraint() {
        assertThat(validate("fe80::1"))
                .as("fe80:: IPv6 link-local must be rejected (Sec-12)")
                .isNotEmpty();
    }
}
