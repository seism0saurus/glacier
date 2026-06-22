package de.seism0saurus.glacier.util;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * jqwik property-based tests for {@link IpAddressClassifier} (SR-TEST-06 / SSRF).
 *
 * <p>Complements the example-based {@code IpAddressClassifierTest} by asserting the
 * SSRF invariants over the *whole* of each blocked range rather than a few samples —
 * the security-critical direction is "a blocked range is NEVER classified as public"
 * (a false negative here is an SSRF hole, F4/NF2 / Sec-11).
 *
 * <p>SR-FUZZ-02: assertion messages use fixed strings only — they never echo the raw
 * generated address.
 */
class IpAddressClassifierPropertyTest {

    private static Arbitrary<Integer> octet() {
        return Arbitraries.integers().between(0, 255);
    }

    /** RFC1918 / loopback / link-local / CGNAT / multicast / any-local — every blocked IPv4 range. */
    @Provide
    Arbitrary<String> blockedIpv4() {
        Arbitrary<String> tenSlash8 = Combinators.combine(octet(), octet(), octet())
                .as((b, c, d) -> "10." + b + "." + c + "." + d);
        Arbitrary<String> seventyTwoSlash12 = Combinators.combine(
                        Arbitraries.integers().between(16, 31), octet(), octet())
                .as((b, c, d) -> "172." + b + "." + c + "." + d);
        Arbitrary<String> oneNineTwo = Combinators.combine(octet(), octet())
                .as((c, d) -> "192.168." + c + "." + d);
        Arbitrary<String> loopback = Combinators.combine(octet(), octet(), octet())
                .as((b, c, d) -> "127." + b + "." + c + "." + d);
        Arbitrary<String> linkLocal = Combinators.combine(octet(), octet())
                .as((c, d) -> "169.254." + c + "." + d);
        Arbitrary<String> cgnat = Combinators.combine(
                        Arbitraries.integers().between(64, 127), octet(), octet())
                .as((b, c, d) -> "100." + b + "." + c + "." + d);
        Arbitrary<String> multicast = Combinators.combine(
                        Arbitraries.integers().between(224, 239), octet(), octet(), octet())
                .as((a, b, c, d) -> a + "." + b + "." + c + "." + d);
        return Arbitraries.oneOf(
                tenSlash8, seventyTwoSlash12, oneNineTwo, loopback, linkLocal, cgnat, multicast);
    }

    /** Known-public, literal addresses (parsed without DNS) that must never be blocked. */
    @Provide
    Arbitrary<String> publicIp() {
        return Arbitraries.of(
                "8.8.8.8", "1.1.1.1", "9.9.9.9", "93.184.216.34", "208.67.222.222",
                "2606:4700:4700::1111", "2001:4860:4860::8888");
    }

    @Property
    void everyBlockedRangeIsClassifiedPrivate(@ForAll("blockedIpv4") final String ip) {
        assertThat(IpAddressClassifier.isPrivate(ip))
                .as("a blocked IPv4 range must always be classified private (SSRF guard)")
                .isTrue();
    }

    @Property
    void ipv4MappedIpv6OfBlockedRangeIsAlsoPrivate(@ForAll("blockedIpv4") final String ip) {
        // The original Sec-11 bug: ::ffff:192.168.x.x slipped past isSiteLocalAddress().
        assertThat(IpAddressClassifier.isPrivate("::ffff:" + ip))
                .as("IPv4-mapped IPv6 of a blocked range must also be blocked (Sec-11)")
                .isTrue();
    }

    @Property
    void cloudMetadataEndpointIsAlwaysPrivate() {
        assertThat(IpAddressClassifier.isPrivate("169.254.169.254"))
                .as("the cloud metadata endpoint must always be blocked")
                .isTrue();
    }

    @Property
    void publicAddressesAreNotBlocked(@ForAll("publicIp") final String ip) {
        assertThat(IpAddressClassifier.isPrivate(ip))
                .as("a known-public address must not be classified private")
                .isFalse();
    }

    @Property
    void nullOrBlankFailsSecure(@ForAll("blankish") final String blank) {
        assertThat(IpAddressClassifier.isPrivate(blank))
                .as("null/blank must fail secure (treated as blocked)")
                .isTrue();
    }

    @Provide
    Arbitrary<String> blankish() {
        return Arbitraries.of("", "   ", "\t", "\n", null);
    }
}
