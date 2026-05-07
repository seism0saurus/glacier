package de.seism0saurus.glacier.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link IpAddressClassifier}.
 *
 * <p>Covers IPv4, IPv6, and IPv4-mapped IPv6 edge cases for the private/blocked
 * classification, including the key fix for the silent-failure on IPv4-mapped IPv6
 * addresses ({@code ::ffff:a.b.c.d}) where {@code InetAddress.isSiteLocalAddress()}
 * returns {@code false} on most JVMs.
 *
 * <p>References: Sec-11/P1-10; OWASP SSRF Prevention Cheat Sheet; NIST SP 800-53 SC-7.
 */
class IpAddressClassifierTest {

    // -----------------------------------------------------------------------
    // Null / blank inputs — fail-secure
    // -----------------------------------------------------------------------

    @Test
    void isPrivate_null_returnsTrue() {
        assertThat(IpAddressClassifier.isPrivate(null)).isTrue();
    }

    @Test
    void isPrivate_blankString_returnsTrue() {
        assertThat(IpAddressClassifier.isPrivate("   ")).isTrue();
    }

    @Test
    void isPrivate_emptyString_returnsTrue() {
        assertThat(IpAddressClassifier.isPrivate("")).isTrue();
    }

    // -----------------------------------------------------------------------
    // IPv4 — loopback
    // -----------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"127.0.0.1", "127.0.0.255", "127.255.255.255"})
    void isPrivate_ipv4Loopback_returnsTrue(String ip) {
        assertThat(IpAddressClassifier.isPrivate(ip))
                .as("IPv4 loopback %s should be private", ip)
                .isTrue();
    }

    // -----------------------------------------------------------------------
    // IPv4 — link-local (169.254.x.x)
    // -----------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"169.254.0.1", "169.254.169.254", "169.254.255.255"})
    void isPrivate_ipv4LinkLocal_returnsTrue(String ip) {
        assertThat(IpAddressClassifier.isPrivate(ip))
                .as("IPv4 link-local %s should be private", ip)
                .isTrue();
    }

    // -----------------------------------------------------------------------
    // IPv4 — site-local / RFC1918
    // -----------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {
            "10.0.0.1", "10.255.255.255",
            "172.16.0.1", "172.31.255.255",
            "192.168.0.1", "192.168.255.255"
    })
    void isPrivate_ipv4SiteLocal_returnsTrue(String ip) {
        assertThat(IpAddressClassifier.isPrivate(ip))
                .as("IPv4 site-local / RFC1918 %s should be private", ip)
                .isTrue();
    }

    // -----------------------------------------------------------------------
    // IPv4 — CGNAT (100.64.0.0/10)
    // -----------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"100.64.0.1", "100.100.100.100", "100.127.255.255"})
    void isPrivate_ipv4Cgnat_returnsTrue(String ip) {
        assertThat(IpAddressClassifier.isPrivate(ip))
                .as("IPv4 CGNAT %s should be private", ip)
                .isTrue();
    }

    // -----------------------------------------------------------------------
    // IPv4 — cloud metadata endpoint
    // -----------------------------------------------------------------------

    @Test
    void isPrivate_cloudMetadataEndpoint_returnsTrue() {
        assertThat(IpAddressClassifier.isPrivate("169.254.169.254")).isTrue();
    }

    // -----------------------------------------------------------------------
    // IPv4 — public addresses
    // -----------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {
            "1.1.1.1",          // Cloudflare DNS
            "8.8.8.8",          // Google DNS
            "203.0.113.1",      // TEST-NET-3 (documentation range, but NOT private)
            "93.184.216.34",    // example.com
            "140.82.121.4"      // github.com
    })
    void isPrivate_ipv4PublicAddresses_returnsFalse(String ip) {
        assertThat(IpAddressClassifier.isPrivate(ip))
                .as("IPv4 public address %s should NOT be private", ip)
                .isFalse();
    }

    // -----------------------------------------------------------------------
    // IPv6 — loopback (::1)
    // -----------------------------------------------------------------------

    @Test
    void isPrivate_ipv6Loopback_returnsTrue() {
        assertThat(IpAddressClassifier.isPrivate("::1")).isTrue();
    }

    // -----------------------------------------------------------------------
    // IPv6 — link-local (fe80::/10)
    // -----------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"fe80::1", "fe80::1%eth0", "fe80::dead:beef"})
    void isPrivate_ipv6LinkLocal_returnsTrue(String ip) {
        // %eth0 zone ID may cause UnknownHostException on some platforms — we're testing
        // the non-scoped forms here; scoped forms with % are treated as unresolvable (blocked)
        // unless the platform resolves them. Both outcomes (blocked=true) are acceptable.
        boolean result = IpAddressClassifier.isPrivate(ip);
        assertThat(result)
                .as("IPv6 link-local %s should be private (or unresolvable=blocked)", ip)
                .isTrue();
    }

    // -----------------------------------------------------------------------
    // IPv6 — ULA (fc00::/7)
    // -----------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"fc00::1", "fd00::1", "fd12:3456:789a::1"})
    void isPrivate_ipv6Ula_returnsTrue(String ip) {
        assertThat(IpAddressClassifier.isPrivate(ip))
                .as("IPv6 ULA %s should be private", ip)
                .isTrue();
    }

    // -----------------------------------------------------------------------
    // IPv6 — public addresses
    // -----------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {
            "2001:4860:4860::8888",  // Google DNS
            "2606:4700:4700::1111"   // Cloudflare DNS
    })
    void isPrivate_ipv6PublicAddresses_returnsFalse(String ip) {
        assertThat(IpAddressClassifier.isPrivate(ip))
                .as("IPv6 public address %s should NOT be private", ip)
                .isFalse();
    }

    // -----------------------------------------------------------------------
    // IPv4-mapped IPv6 — THE KEY FIX (Sec-11)
    // -----------------------------------------------------------------------

    /**
     * Critical regression test: IPv4-mapped IPv6 loopback (::ffff:127.0.0.1)
     * must be treated as private.
     *
     * <p>On most JVMs, {@code InetAddress.getByName("::ffff:127.0.0.1").isLoopbackAddress()}
     * returns {@code false}, which is the silent-failure this helper fixes.
     */
    @Test
    void isPrivate_ipv4MappedLoopback_returnsTrue() {
        assertThat(IpAddressClassifier.isPrivate("::ffff:127.0.0.1"))
                .as("IPv4-mapped IPv6 loopback ::ffff:127.0.0.1 must be private (Sec-11 fix)")
                .isTrue();
    }

    @Test
    void isPrivate_ipv4MappedLoopback_fullNotation_returnsTrue() {
        assertThat(IpAddressClassifier.isPrivate("::ffff:7f00:1"))
                .as("IPv4-mapped IPv6 loopback in hex notation ::ffff:7f00:1 must be private")
                .isTrue();
    }

    /**
     * IPv4-mapped IPv6 site-local (::ffff:192.168.1.1) must be treated as private.
     *
     * <p>This is the main SSRF bypass vector: {@code InetAddress.isSiteLocalAddress()}
     * returns {@code false} for {@code ::ffff:192.168.1.1} on most JVMs.
     */
    @Test
    void isPrivate_ipv4MappedSiteLocal_returnsTrue() {
        assertThat(IpAddressClassifier.isPrivate("::ffff:192.168.1.1"))
                .as("IPv4-mapped IPv6 site-local ::ffff:192.168.1.1 must be private (Sec-11 fix)")
                .isTrue();
    }

    @Test
    void isPrivate_ipv4MappedSiteLocal_10Net_returnsTrue() {
        assertThat(IpAddressClassifier.isPrivate("::ffff:10.0.0.1"))
                .as("IPv4-mapped IPv6 ::ffff:10.0.0.1 must be private")
                .isTrue();
    }

    @Test
    void isPrivate_ipv4MappedSiteLocal_172Net_returnsTrue() {
        assertThat(IpAddressClassifier.isPrivate("::ffff:172.16.0.1"))
                .as("IPv4-mapped IPv6 ::ffff:172.16.0.1 must be private")
                .isTrue();
    }

    /**
     * IPv4-mapped IPv6 link-local (::ffff:169.254.169.254) must be treated as private.
     *
     * <p>This is the cloud metadata endpoint bypass vector.
     */
    @Test
    void isPrivate_ipv4MappedCloudMetadata_returnsTrue() {
        assertThat(IpAddressClassifier.isPrivate("::ffff:169.254.169.254"))
                .as("IPv4-mapped cloud metadata endpoint must be private (Sec-11 fix)")
                .isTrue();
    }

    /**
     * IPv4-mapped IPv6 public address (::ffff:1.1.1.1) must NOT be treated as private.
     */
    @Test
    void isPrivate_ipv4MappedPublicAddress_returnsFalse() {
        assertThat(IpAddressClassifier.isPrivate("::ffff:1.1.1.1"))
                .as("IPv4-mapped public address ::ffff:1.1.1.1 should NOT be private")
                .isFalse();
    }

    @Test
    void isPrivate_ipv4MappedPublicAddress_8888_returnsFalse() {
        assertThat(IpAddressClassifier.isPrivate("::ffff:8.8.8.8"))
                .as("IPv4-mapped Google DNS ::ffff:8.8.8.8 should NOT be private")
                .isFalse();
    }

    // -----------------------------------------------------------------------
    // Malformed / unresolvable inputs — fail-secure
    // -----------------------------------------------------------------------

    @Test
    void isPrivate_malformedAddress_returnsTrue() {
        // Unresolvable → fail-secure (treat as blocked)
        assertThat(IpAddressClassifier.isPrivate("not-an-ip-address")).isTrue();
    }

    @Test
    void isPrivate_portIncludedString_returnsTrue() {
        // "127.0.0.1:8080" is not a valid InetAddress — unresolvable → blocked
        assertThat(IpAddressClassifier.isPrivate("127.0.0.1:8080")).isTrue();
    }
}
