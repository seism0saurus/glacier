package de.seism0saurus.glacier.share.application;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the <strong>dev-only</strong> instance-host SSRF exemption in
 * {@link DefaultSafeUrlValidator}.
 *
 * <p>Context: the e2e / local-dev Mastodon instance runs under an internal hostname
 * (e.g. {@code proxy}) that resolves to a private (RFC1918) address. In production the
 * SSRF guard MUST block such hosts. In dev mode ({@code glacier.devmode=true}, which
 * {@code StartupSanityChecker} only permits under the {@code dev}/{@code test} profiles)
 * the single configured {@code mastodon.instance} host is exempted from the private-IP
 * blocklist so the streaming/embed fan-out works against the internal fixture. Every
 * other guard (scheme allowlist, userinfo, bidi, resolution) stays in force, and no
 * other private host is exempted.
 *
 * <p>Literal private IPs are used as the configured instance host so the tests need no
 * DNS resolution: {@code InetAddress.getAllByName("10.1.2.3")} returns the literal
 * address, which {@code isSiteLocalAddress()} classifies as private (10/8).
 */
class DefaultSafeUrlValidatorDevExemptionTest {

    /** Toot embed URL whose host equals the configured instance and resolves to a private IP. */
    private static final String INSTANCE_EMBED = "http://10.1.2.3/@user/116/embed";

    @Test
    void prodMode_blocksConfiguredInstanceHostResolvingToPrivateIp() {
        DefaultSafeUrlValidator validator = new DefaultSafeUrlValidator(false, "10.1.2.3");

        Optional<URI> result = validator.validate(INSTANCE_EMBED);

        assertThat(result)
                .as("production (devmode=false) must block a private-IP instance host")
                .isEmpty();
    }

    @Test
    void devMode_allowsConfiguredInstanceHostResolvingToPrivateIp() {
        DefaultSafeUrlValidator validator = new DefaultSafeUrlValidator(true, "10.1.2.3");

        Optional<URI> result = validator.validate(INSTANCE_EMBED);

        assertThat(result)
                .as("dev mode must exempt the configured instance host from the private-IP block")
                .isPresent();
    }

    @Test
    void devMode_stillBlocksOtherPrivateHost() {
        DefaultSafeUrlValidator validator = new DefaultSafeUrlValidator(true, "10.1.2.3");

        Optional<URI> result = validator.validate("http://10.9.9.9/x/embed");

        assertThat(result)
                .as("the dev exemption must be scoped to the configured host only")
                .isEmpty();
    }

    @Test
    void devMode_withNoConfiguredInstance_blocksPrivateHost() {
        DefaultSafeUrlValidator validator = new DefaultSafeUrlValidator(true, "");

        Optional<URI> result = validator.validate(INSTANCE_EMBED);

        assertThat(result)
                .as("with no configured instance host there is nothing to exempt")
                .isEmpty();
    }

    @Test
    void devMode_exemptionDoesNotRelaxSchemeAllowlist() {
        DefaultSafeUrlValidator validator = new DefaultSafeUrlValidator(true, "10.1.2.3");

        Optional<URI> result = validator.validate("file://10.1.2.3/etc/passwd");

        assertThat(result)
                .as("dev exemption must not relax the scheme allowlist")
                .isEmpty();
    }
}
