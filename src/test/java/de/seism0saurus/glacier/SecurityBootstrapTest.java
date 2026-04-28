package de.seism0saurus.glacier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.security.Security;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link SecurityBootstrap}.
 *
 * <p>SR-PT-09: verifies that the JVM DNS cache TTL properties are set to values
 * that bound the TOCTOU window in the SSRF guard:</p>
 * <ul>
 *   <li>Positive cache TTL ≤ 30 seconds.</li>
 *   <li>Negative cache TTL ≤ 10 seconds.</li>
 * </ul>
 *
 * <p>The test instantiates {@link SecurityBootstrap} to guarantee its {@code static}
 * initialiser fires, then reads the JVM security properties it sets.</p>
 */
public class SecurityBootstrapTest {

    @BeforeEach
    void ensureSecurityBootstrapIsInitialised() {
        // Instantiating the class is the reliable way to trigger the static initialiser.
        // Simply referencing SecurityBootstrap.class in a local variable does not guarantee
        // the static block runs in all JVM configurations.
        new SecurityBootstrap();
    }

    /**
     * SR-PT-09 (positive TTL): the JVM security property {@code networkaddress.cache.ttl}
     * must be set to a value ≤ 30 seconds to limit the SSRF TOCTOU window.
     *
     * Arrange: SecurityBootstrap is instantiated (static block fires).
     * Act: read {@code networkaddress.cache.ttl}.
     * Assert: value is non-null, parseable, and ≤ 30.
     */
    @Test
    public void securityBootstrap_positiveDnsCacheTtl_isAtMost30Seconds() {
        String ttl = Security.getProperty("networkaddress.cache.ttl");
        assertThat(ttl)
                .as("networkaddress.cache.ttl must be set")
                .isNotNull()
                .isNotBlank();

        int ttlSeconds = Integer.parseInt(ttl);
        assertThat(ttlSeconds)
                .as("Positive DNS cache TTL must be ≤ 30 seconds to limit the SSRF TOCTOU window")
                .isLessThanOrEqualTo(30);
    }

    /**
     * SR-PT-09 (negative TTL): the JVM security property {@code networkaddress.cache.negative.ttl}
     * must be set to a value ≤ 10 seconds.
     *
     * Arrange: SecurityBootstrap is instantiated (static block fires).
     * Act: read {@code networkaddress.cache.negative.ttl}.
     * Assert: value is non-null, parseable, and ≤ 10.
     */
    @Test
    public void securityBootstrap_negativeDnsCacheTtl_isAtMost10Seconds() {
        String negativeTtl = Security.getProperty("networkaddress.cache.negative.ttl");
        assertThat(negativeTtl)
                .as("networkaddress.cache.negative.ttl must be set")
                .isNotNull()
                .isNotBlank();

        int negativeTtlSeconds = Integer.parseInt(negativeTtl);
        assertThat(negativeTtlSeconds)
                .as("Negative DNS cache TTL must be ≤ 10 seconds")
                .isLessThanOrEqualTo(10);
    }

    /**
     * SR-PT-09 (exact values): verifies the exact property strings match the plan.
     *
     * Arrange: SecurityBootstrap is instantiated.
     * Act: read both properties.
     * Assert: "30" and "10" respectively.
     */
    @Test
    public void securityBootstrap_setsDnsCacheTtlToExactValues() {
        assertThat(Security.getProperty("networkaddress.cache.ttl"))
                .isEqualTo("30");
        assertThat(Security.getProperty("networkaddress.cache.negative.ttl"))
                .isEqualTo("10");
    }
}
