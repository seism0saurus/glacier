package de.seism0saurus.glacier.mastodon;

import de.seism0saurus.glacier.share.application.DefaultSafeUrlValidator;
import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.SystemDefaultDnsResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Locale;

/**
 * SSRF guard executed at HTTP <em>connection</em> time for the embed HEAD client.
 *
 * <p>Background (F4 / NF2): {@link StompCallback} validates a toot URL via
 * {@code SafeUrlValidator.validate(...)} (which resolves DNS and blocks private IPs)
 * and then issues a HEAD to {@code <url>/embed}. Because the HTTP client performs its
 * <em>own</em> DNS lookup at request time, a "validate-then-use" TOCTOU window exists:
 * an attacker-controlled hostname could resolve to a public IP during validation and
 * rebind to an internal IP by the time the HEAD connects (DNS rebinding).
 *
 * <p>This resolver closes that window by re-checking the resolved addresses at the moment
 * the connection is established — the authoritative point of use. Any resolved address in
 * a blocked range (RFC1918 / loopback / link-local / CGN / multicast / IPv6-private, via
 * {@link DefaultSafeUrlValidator#isBlockedAddress(InetAddress)}) causes the connection to
 * fail closed with {@link UnknownHostException}. Combined with redirect-following being
 * disabled (see {@link EmbedRestTemplateConfiguration}), this removes the remaining
 * embed-fetch SSRF vectors.
 *
 * <p>Dev-mode exemption: mirrors {@link DefaultSafeUrlValidator} — in dev mode only, the
 * single configured {@code mastodon.instance} host is exempt from the private-IP block so
 * the local/e2e fixture (an internal hostname) can be embedded. {@code devMode} is false in
 * production (gated by {@code StartupSanityChecker}), so the block stays strict there.
 *
 * <p>References: OWASP SSRF Prevention Cheat Sheet (DNS pinning), {@code spring-input-validation-ssrf}.
 */
public class SsrfBlockingDnsResolver implements DnsResolver {

    private static final Logger AUDIT = LoggerFactory.getLogger("AUDIT");

    /** Underlying resolver that performs the actual lookup (system default in production). */
    private final DnsResolver delegate;

    /** Whether dev mode is active ({@code glacier.devmode}); never {@code true} in production. */
    private final boolean devMode;

    /** Configured {@code mastodon.instance} host (lower-cased), exempt from the block in dev mode only. */
    private final String devInstanceHost;

    /**
     * Production constructor — wraps the system default DNS resolver.
     *
     * @param devMode          value of {@code glacier.devmode}
     * @param mastodonInstance value of {@code mastodon.instance} (bare host); dev-only exemption
     */
    public SsrfBlockingDnsResolver(final boolean devMode, final String mastodonInstance) {
        this(SystemDefaultDnsResolver.INSTANCE, devMode, mastodonInstance);
    }

    /**
     * Test/seam constructor allowing a custom delegate resolver.
     *
     * @param delegate         the underlying resolver to delegate the actual lookup to
     * @param devMode          value of {@code glacier.devmode}
     * @param mastodonInstance value of {@code mastodon.instance} (bare host); dev-only exemption
     */
    SsrfBlockingDnsResolver(final DnsResolver delegate, final boolean devMode, final String mastodonInstance) {
        this.delegate = delegate;
        this.devMode = devMode;
        this.devInstanceHost = mastodonInstance == null
                ? ""
                : mastodonInstance.trim().toLowerCase(Locale.ROOT);
    }

    @Override
    public InetAddress[] resolve(final String host) throws UnknownHostException {
        final InetAddress[] addresses = delegate.resolve(host);

        final boolean devInstanceExempt = devMode
                && !devInstanceHost.isEmpty()
                && host != null
                && host.toLowerCase(Locale.ROOT).equals(devInstanceHost);
        if (devInstanceExempt) {
            return addresses;
        }

        // Fail closed if ANY resolved address is in a blocked range — matches the validator's
        // semantics and prevents the connection from ever reaching an internal target.
        for (final InetAddress addr : addresses) {
            if (DefaultSafeUrlValidator.isBlockedAddress(addr)) {
                AUDIT.info("stomp.embed.dns_blocked reason=ssrf_blocked");
                throw new UnknownHostException("SSRF-blocked resolved address for embed host");
            }
        }
        return addresses;
    }

    @Override
    public String resolveCanonicalHostname(final String host) throws UnknownHostException {
        return delegate.resolveCanonicalHostname(host);
    }
}
