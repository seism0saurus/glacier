package de.seism0saurus.glacier.mastodon;

import org.apache.hc.client5.http.DnsResolver;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.UnknownHostException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link SsrfBlockingDnsResolver} (F4 / NF2).
 *
 * <p>Verifies the connect-time SSRF DNS guard: public addresses pass, blocked ranges
 * (loopback / RFC1918) fail closed, a mixed result fails closed, and the dev-mode
 * exemption applies ONLY to the configured instance host and NEVER in production.
 *
 * <p>Literal IP strings are used so {@link InetAddress#getByName(String)} performs no
 * network DNS lookup — the tests are deterministic and offline.
 */
class SsrfBlockingDnsResolverTest {

    /** A delegate that resolves any host to a fixed set of literal IPs (no real DNS). */
    private static DnsResolver fixedDelegate(final String... ips) {
        return new DnsResolver() {
            @Override
            public InetAddress[] resolve(final String host) throws UnknownHostException {
                final InetAddress[] out = new InetAddress[ips.length];
                for (int i = 0; i < ips.length; i++) {
                    out[i] = InetAddress.getByName(ips[i]);
                }
                return out;
            }

            @Override
            public String resolveCanonicalHostname(final String host) {
                return host;
            }
        };
    }

    @Test
    void allowsPublicAddress() throws Exception {
        final SsrfBlockingDnsResolver resolver =
                new SsrfBlockingDnsResolver(fixedDelegate("93.184.216.34"), false, "");
        assertThat(resolver.resolve("example.com")).hasSize(1);
    }

    @Test
    void blocksLoopbackAddress() {
        final SsrfBlockingDnsResolver resolver =
                new SsrfBlockingDnsResolver(fixedDelegate("127.0.0.1"), false, "");
        assertThatThrownBy(() -> resolver.resolve("evil.example.com"))
                .isInstanceOf(UnknownHostException.class);
    }

    @Test
    void blocksRfc1918PrivateAddress() {
        final SsrfBlockingDnsResolver resolver =
                new SsrfBlockingDnsResolver(fixedDelegate("192.168.1.10"), false, "");
        assertThatThrownBy(() -> resolver.resolve("rebind.example.com"))
                .isInstanceOf(UnknownHostException.class);
    }

    @Test
    void failsClosedWhenAnyResolvedAddressIsPrivate() {
        final SsrfBlockingDnsResolver resolver =
                new SsrfBlockingDnsResolver(fixedDelegate("93.184.216.34", "10.0.0.5"), false, "");
        assertThatThrownBy(() -> resolver.resolve("mixed.example.com"))
                .isInstanceOf(UnknownHostException.class);
    }

    @Test
    void devModeExemptsConfiguredInstanceHostFromPrivateBlock() throws Exception {
        final SsrfBlockingDnsResolver resolver =
                new SsrfBlockingDnsResolver(fixedDelegate("172.18.0.5"), true, "proxy");
        // The dev fixture instance host is allowed to resolve to a private IP.
        assertThat(resolver.resolve("proxy")).hasSize(1);
    }

    @Test
    void devModeDoesNotExemptOtherHosts() {
        final SsrfBlockingDnsResolver resolver =
                new SsrfBlockingDnsResolver(fixedDelegate("172.18.0.5"), true, "proxy");
        assertThatThrownBy(() -> resolver.resolve("not-the-instance"))
                .isInstanceOf(UnknownHostException.class);
    }

    @Test
    void productionNeverExemptsEvenTheInstanceHost() {
        final SsrfBlockingDnsResolver resolver =
                new SsrfBlockingDnsResolver(fixedDelegate("172.18.0.5"), false, "proxy");
        assertThatThrownBy(() -> resolver.resolve("proxy"))
                .isInstanceOf(UnknownHostException.class);
    }
}
