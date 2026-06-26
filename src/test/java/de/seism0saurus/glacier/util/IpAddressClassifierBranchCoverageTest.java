package de.seism0saurus.glacier.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetAddress;
import java.net.UnknownHostException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Branch-coverage tests for {@link IpAddressClassifier} targeting the ranges the SSRF blocklist
 * depends on but that the primary {@code IpAddressClassifierTest} does not exercise:
 *
 * <ul>
 *   <li>the {@link IpAddressClassifier#isBlockedInetAddress(InetAddress)} API directly (the form
 *       {@code DefaultSafeUrlValidator} calls after DNS resolution / pinning)</li>
 *   <li>multicast and any-local (wildcard) addresses</li>
 *   <li>the CGNAT 100.64.0.0/10 boundaries (RFC 6598) — off-by-one bypasses are the risk</li>
 *   <li>the full-form IPv4-mapped IPv6 prefix {@code 0:0:0:0:0:ffff:…} in both dotted and
 *       hex-colon notation</li>
 * </ul>
 *
 * <p>All inputs are valid IP literals so {@link InetAddress#getByName(String)} never performs a
 * DNS lookup — the tests are deterministic and offline.
 */
class IpAddressClassifierBranchCoverageTest {

    private static InetAddress addr(final String literal) {
        try {
            return InetAddress.getByName(literal);
        } catch (UnknownHostException e) {
            throw new IllegalStateException("not an IP literal: " + literal, e);
        }
    }

    // --- isBlockedInetAddress(InetAddress) directly (the validator's entry point) ---

    @ParameterizedTest
    @ValueSource(strings = {
            "127.0.0.1",      // loopback
            "169.254.1.1",    // link-local
            "10.1.2.3",       // site-local (RFC1918)
            "0.0.0.0",        // any-local (wildcard)
            "224.0.0.1",      // multicast
            "169.254.169.254",// cloud metadata
            "100.100.50.1",   // CGNAT
            "::1",            // IPv6 loopback
            "::",             // IPv6 any-local
            "fc00::1",        // IPv6 ULA (fc)
            "fd12:3456::1",   // IPv6 ULA (fd)
    })
    void isBlockedInetAddress_blockedRanges_returnTrue(final String literal) {
        assertThat(IpAddressClassifier.isBlockedInetAddress(addr(literal)))
                .as("%s must be classified as blocked", literal)
                .isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "8.8.8.8",                  // public IPv4
            "1.1.1.1",                  // public IPv4
            "2606:4700:4700::1111",     // public IPv6 (Cloudflare)
    })
    void isBlockedInetAddress_publicAddresses_returnFalse(final String literal) {
        assertThat(IpAddressClassifier.isBlockedInetAddress(addr(literal)))
                .as("%s is public and must NOT be blocked", literal)
                .isFalse();
    }

    // --- multicast + any-local via the string API ---

    @ParameterizedTest
    @ValueSource(strings = {"224.0.0.1", "239.255.255.250", "ff02::1"})
    void isPrivate_multicast_returnsTrue(final String ip) {
        assertThat(IpAddressClassifier.isPrivate(ip)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"0.0.0.0", "::"})
    void isPrivate_anyLocal_returnsTrue(final String ip) {
        assertThat(IpAddressClassifier.isPrivate(ip)).isTrue();
    }

    // --- CGNAT 100.64.0.0/10 boundaries (RFC 6598) ---

    @ParameterizedTest
    @ValueSource(strings = {"100.64.0.0", "100.64.0.1", "100.100.50.1", "100.127.255.255"})
    void isPrivate_insideCgnatRange_returnsTrue(final String ip) {
        assertThat(IpAddressClassifier.isPrivate(ip))
                .as("%s is inside 100.64.0.0/10 and must be blocked", ip)
                .isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"100.63.255.255", "100.128.0.0", "99.64.0.1", "101.64.0.1"})
    void isPrivate_justOutsideCgnatRange_returnsFalse(final String ip) {
        // Off-by-one on the second octet (63 / 128) or first octet (99 / 101) must NOT be blocked
        // — and must NOT be over-blocked either, which would break legitimate public fetches.
        assertThat(IpAddressClassifier.isPrivate(ip))
                .as("%s is outside 100.64.0.0/10 and must be public", ip)
                .isFalse();
    }

    // --- full-form IPv4-mapped IPv6 prefix (0:0:0:0:0:ffff:…) ---

    @Test
    void isPrivate_fullFormMappedPrefix_dottedEmbedded_isClassifiedByEmbeddedV4() {
        assertThat(IpAddressClassifier.isPrivate("0:0:0:0:0:ffff:10.0.0.1"))
                .as("full-form mapped site-local must be blocked")
                .isTrue();
        assertThat(IpAddressClassifier.isPrivate("0:0:0:0:0:ffff:8.8.8.8"))
                .as("full-form mapped public must NOT be blocked")
                .isFalse();
    }

    @Test
    void isPrivate_fullFormMappedPrefix_hexColonEmbedded_isConvertedToV4() {
        // 7f00:0001 == 127.0.0.1 (loopback) → blocked after hex-colon → dotted conversion.
        assertThat(IpAddressClassifier.isPrivate("0:0:0:0:0:ffff:7f00:1")).isTrue();
    }
}
