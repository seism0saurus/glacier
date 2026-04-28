package de.seism0saurus.glacier.share.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Optional;
import java.util.Set;

/**
 * DefaultSafeUrlValidator is the production SSRF guard for outbound embed-check requests.
 *
 * <p>It enforces the following rules in order:</p>
 * <ol>
 *   <li>The raw URL must be non-null and non-blank.</li>
 *   <li>The URL must parse as a valid {@link URI}.</li>
 *   <li>The scheme must be {@code http} or {@code https}.</li>
 *   <li>A host component must be present.</li>
 *   <li>The host must resolve via DNS to at least one address that is not a loopback,
 *       link-local, site-local (RFC-1918), or otherwise private address.</li>
 * </ol>
 *
 * <p>Any violation causes the method to return {@link Optional#empty()}, indicating
 * that the caller must not proceed with the HTTP request.</p>
 *
 * <p>DNS resolution is intentionally performed at validation time so that the
 * resolved address is subject to Java's DNS cache TTL (configured by
 * {@link de.seism0saurus.glacier.SecurityBootstrap} to 30 s positive / 10 s negative),
 * preventing TOCTOU races where an attacker rotates DNS after the check.</p>
 */
@Component
public class DefaultSafeUrlValidator implements SafeUrlValidator {

    private static final Logger LOGGER = LoggerFactory.getLogger(DefaultSafeUrlValidator.class);

    private static final Set<String> ALLOWED_SCHEMES = Set.of("http", "https");

    /**
     * Validates that {@code rawUrl} is a non-private, non-loopback HTTP(S) URL.
     *
     * @param rawUrl the raw URL from a Mastodon event; may be {@code null}
     * @return {@link Optional#of(URI)} when the URL is safe to fetch,
     *         {@link Optional#empty()} when it is blocked
     */
    @Override
    public Optional<URI> validate(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            LOGGER.warn("SSRF guard blocked null or blank URL");
            return Optional.empty();
        }

        URI uri;
        try {
            uri = URI.create(rawUrl);
        } catch (IllegalArgumentException e) {
            LOGGER.warn("SSRF guard blocked malformed URL");
            return Optional.empty();
        }

        String scheme = uri.getScheme();
        if (scheme == null || !ALLOWED_SCHEMES.contains(scheme.toLowerCase())) {
            LOGGER.warn("SSRF guard blocked disallowed scheme: {}", scheme);
            return Optional.empty();
        }

        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            LOGGER.warn("SSRF guard blocked URL with missing host");
            return Optional.empty();
        }

        try {
            InetAddress[] addresses = InetAddress.getAllByName(host);
            for (InetAddress address : addresses) {
                if (isPrivateAddress(address)) {
                    LOGGER.warn("SSRF guard blocked URL resolving to private/loopback address");
                    return Optional.empty();
                }
            }
        } catch (UnknownHostException e) {
            LOGGER.warn("SSRF guard blocked URL with unresolvable host");
            return Optional.empty();
        }

        return Optional.of(uri);
    }

    /**
     * Returns {@code true} when the given address is a loopback, link-local,
     * site-local, or otherwise private address that must not be reached from
     * an internet-facing service.
     *
     * @param address the resolved {@link InetAddress} to inspect
     * @return {@code true} if the address is private or special-purpose
     */
    private static boolean isPrivateAddress(InetAddress address) {
        if (address.isLoopbackAddress()) {
            return true;
        }
        if (address.isLinkLocalAddress()) {
            return true;
        }
        if (address.isSiteLocalAddress()) {
            return true;
        }
        if (address.isMulticastAddress()) {
            return true;
        }
        if (address instanceof Inet4Address ipv4) {
            byte[] bytes = ipv4.getAddress();
            // 0.0.0.0/8
            if ((bytes[0] & 0xFF) == 0) {
                return true;
            }
            // 100.64.0.0/10 (CGNAT)
            if ((bytes[0] & 0xFF) == 100 && (bytes[1] & 0xFF) >= 64 && (bytes[1] & 0xFF) <= 127) {
                return true;
            }
            // 240.0.0.0/4 (reserved)
            if ((bytes[0] & 0xFF) >= 240) {
                return true;
            }
        }
        if (address instanceof Inet6Address ipv6) {
            byte[] bytes = ipv6.getAddress();
            // ::1 loopback already caught above
            // fc00::/7 (Unique local)
            if ((bytes[0] & 0xFE) == 0xFC) {
                return true;
            }
        }
        return false;
    }
}
