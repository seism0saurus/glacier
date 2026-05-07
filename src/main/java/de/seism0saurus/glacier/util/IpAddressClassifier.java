package de.seism0saurus.glacier.util;

import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * Shared utility for classifying IP addresses as private/blocked in the context of
 * SSRF prevention and GDPR log anonymisation.
 *
 * <p>This is the <em>sole authorised consumer</em> of {@link InetAddress#isLoopbackAddress()},
 * {@link InetAddress#isSiteLocalAddress()}, {@link InetAddress#isLinkLocalAddress()},
 * and {@link InetAddress#isMulticastAddress()} in the Glacier codebase. An ArchUnit gate
 * ({@code IpAddressClassifierArchitectureTest}) enforces this invariant to prevent future
 * drift between the SSRF blocklist ({@link de.seism0saurus.glacier.share.application.DefaultSafeUrlValidator})
 * and the GDPR log anonymisation ({@link de.seism0saurus.glacier.webservice.InformationController}).
 *
 * <h2>Problem addressed</h2>
 * <p>Both {@code InformationController.anonymiseIp} and {@code DefaultSafeUrlValidator.isBlockedAddress}
 * originally called {@code InetAddress.isXAddress()} directly. These methods silently fail on
 * IPv4-mapped IPv6 addresses ({@code ::ffff:a.b.c.d}): the IPv4-mapped form is not recognised as
 * loopback/site-local/link-local by the plain {@code isXAddress()} methods. An attacker can
 * bypass SSRF blocklists by supplying {@code http://[::ffff:127.0.0.1]/} instead of
 * {@code http://127.0.0.1/}.
 *
 * <h2>Canonical private ranges checked</h2>
 * <ul>
 *   <li>Loopback: {@code 127.0.0.0/8}, {@code ::1}</li>
 *   <li>Link-local: {@code 169.254.0.0/16}, {@code fe80::/10}</li>
 *   <li>Site-local / RFC1918: {@code 10.0.0.0/8}, {@code 172.16.0.0/12}, {@code 192.168.0.0/16}</li>
 *   <li>Any-local: {@code 0.0.0.0}, {@code ::}</li>
 *   <li>Multicast: {@code 224.0.0.0/4}, {@code ff00::/8}</li>
 *   <li>IPv4-mapped loopback: {@code ::ffff:127.x.x.x}</li>
 *   <li>IPv4-mapped link-local: {@code ::ffff:169.254.x.x}</li>
 *   <li>IPv4-mapped site-local: {@code ::ffff:10.x.x.x}, {@code ::ffff:172.16-31.x.x},
 *       {@code ::ffff:192.168.x.x}</li>
 *   <li>Cloud metadata: {@code 169.254.169.254}</li>
 *   <li>CGNAT: {@code 100.64.0.0/10} (RFC 6598)</li>
 *   <li>IPv6 ULA: {@code fc00::/7}</li>
 * </ul>
 *
 * <p>References: Sec-11/P1-10, Sec-24 (ArchUnit gate); OWASP SSRF Prevention Cheat Sheet;
 * NIST SP 800-53 SC-7; C3 — validate inputs; spring-input-validation-ssrf skill.
 */
public final class IpAddressClassifier {

    /** IPv4-mapped IPv6 prefix in lowercase hex: {@code 0000:0000:0000:0000:0000:ffff:}. */
    private static final String IPV4_MAPPED_PREFIX = "::ffff:";

    /** Alternative full-form IPv4-mapped prefix. */
    private static final String IPV4_MAPPED_FULL_PREFIX = "0:0:0:0:0:ffff:";

    private IpAddressClassifier() {
        // utility class — no instances
    }

    /**
     * Returns {@code true} if the given IP address string is a private, loopback, link-local,
     * site-local, or otherwise blocked address.
     *
     * <p>Handles all three address forms:
     * <ul>
     *   <li>Pure IPv4 (e.g. {@code 192.168.1.1})</li>
     *   <li>Pure IPv6 (e.g. {@code fe80::1})</li>
     *   <li>IPv4-mapped IPv6 (e.g. {@code ::ffff:192.168.1.1}) — this form was the original
     *       bug: {@code InetAddress.isSiteLocalAddress()} returns {@code false} for
     *       {@code ::ffff:192.168.1.1} on most JVMs.</li>
     * </ul>
     *
     * <p>If the address string cannot be resolved (malformed), the method returns {@code true}
     * (fail-secure: treat unresolvable addresses as blocked).
     *
     * @param ip the IP address string to check; {@code null} or blank is treated as private
     * @return {@code true} if the address is private/blocked/unresolvable
     */
    public static boolean isPrivate(final String ip) {
        if (ip == null || ip.isBlank()) {
            return true; // fail-secure: blank addresses are treated as blocked
        }

        // Check for IPv4-mapped IPv6 address by extracting the embedded IPv4 part
        // and checking that separately. This fixes the silent-failure on JVMs where
        // InetAddress.isSiteLocalAddress() returns false for ::ffff:192.168.x.x.
        String normalizedLower = ip.toLowerCase(java.util.Locale.ROOT).trim();
        if (normalizedLower.startsWith(IPV4_MAPPED_PREFIX)) {
            String embedded = ip.substring(IPV4_MAPPED_PREFIX.length()).trim();
            return isPrivate(embedded);
        }
        if (normalizedLower.startsWith(IPV4_MAPPED_FULL_PREFIX)) {
            // Remove the full-form prefix: 0:0:0:0:0:ffff:a.b.c.d
            String embedded = ip.substring(IPV4_MAPPED_FULL_PREFIX.length()).trim();
            // The embedded part may be in hex colon form (e.g. 7f00:0001 = 127.0.0.1)
            // Resolve it as IPv6 first; if it looks like a dotted decimal, treat as IPv4.
            if (embedded.contains(".")) {
                return isPrivate(embedded);
            }
            // Convert hex colon-pair to dotted decimal for IPv4 check
            String asIpv4 = hexColonToIpv4(embedded);
            if (asIpv4 != null) {
                return isPrivate(asIpv4);
            }
        }

        try {
            InetAddress addr = InetAddress.getByName(ip);
            return isBlockedInetAddress(addr);
        } catch (UnknownHostException e) {
            // Treat unresolvable addresses as blocked — fail-secure
            return true;
        }
    }

    /**
     * Returns {@code true} if the given resolved {@link InetAddress} is in a blocked range.
     *
     * <p>This is the canonical implementation used by {@link de.seism0saurus.glacier.share.application.DefaultSafeUrlValidator}
     * via delegation. It is also the implementation {@link #isPrivate(String)} delegates to after
     * resolving the string.
     *
     * @param addr a resolved {@link InetAddress}; must not be {@code null}
     * @return {@code true} if the address is loopback, link-local, site-local, any-local,
     *         multicast, CGNAT, or IPv6-ULA
     */
    public static boolean isBlockedInetAddress(final InetAddress addr) {
        // Standard JVM checks — these work correctly for pure IPv4 and pure IPv6.
        // IPv4-mapped IPv6 is handled by isPrivate(String) before reaching this method.
        if (addr.isLoopbackAddress()) return true;       // 127/8, ::1
        if (addr.isLinkLocalAddress()) return true;      // 169.254/16, fe80::/10
        if (addr.isSiteLocalAddress()) return true;      // 10/8, 172.16/12, 192.168/16
        if (addr.isAnyLocalAddress()) return true;       // 0.0.0.0, ::
        if (addr.isMulticastAddress()) return true;

        String hostAddr = addr.getHostAddress();

        // Cloud metadata endpoints (AWS, GCP, Azure, DO) — 169.254.169.254
        if (hostAddr.equals("169.254.169.254")) return true;

        // CGNAT shared address space RFC 6598: 100.64.0.0/10 = 100.64.0.0 - 100.127.255.255
        if (isCgnat(hostAddr)) return true;

        // IPv6 ULA (fc00::/7): fc00:: through fdff::
        String lower = hostAddr.toLowerCase(java.util.Locale.ROOT);
        if (lower.startsWith("fc") || lower.startsWith("fd")) return true;

        return false;
    }

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    /**
     * Returns {@code true} if the given IPv4 address string is in the CGNAT range
     * {@code 100.64.0.0/10} (first octet 100, second octet 64–127).
     */
    private static boolean isCgnat(final String hostAddr) {
        if (!hostAddr.contains(".")) return false;
        String[] parts = hostAddr.split("\\.", -1);
        if (parts.length < 2) return false;
        try {
            int first = Integer.parseInt(parts[0]);
            int second = Integer.parseInt(parts[1]);
            return first == 100 && second >= 64 && second <= 127;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /**
     * Converts an IPv4-mapped IPv6 hex-colon representation (e.g. {@code 7f00:0001})
     * to a dotted-decimal IPv4 string (e.g. {@code 127.0.0.1}).
     *
     * <p>Returns {@code null} if the input does not match the expected format.
     */
    private static String hexColonToIpv4(final String hexColon) {
        if (hexColon == null || !hexColon.contains(":")) return null;
        String[] parts = hexColon.split(":");
        if (parts.length != 2) return null;
        try {
            int hi = Integer.parseInt(parts[0], 16);
            int lo = Integer.parseInt(parts[1], 16);
            int b1 = (hi >> 8) & 0xFF;
            int b2 = hi & 0xFF;
            int b3 = (lo >> 8) & 0xFF;
            int b4 = lo & 0xFF;
            return b1 + "." + b2 + "." + b3 + "." + b4;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
