package de.seism0saurus.glacier.webservice.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Resolves the originating client IP address from an incoming HTTP request,
 * honouring a configurable number of trusted reverse-proxy hops.
 *
 * <h2>Problem addressed (Sec-14/P1-12)</h2>
 * <p>Reading {@code X-Forwarded-For} directly without validating the hop count enables
 * XFF spoofing: a client can inject arbitrary IPs at the leftmost position of the header.
 * For example, with {@code X-Forwarded-For: 1.2.3.4, 10.0.0.1}, if only one proxy hop is
 * trusted, the real client IP is {@code 10.0.0.1} (the rightmost, added by the trusted
 * proxy), not {@code 1.2.3.4} (which the client injected).
 *
 * <h2>Configuration</h2>
 * <p>{@code glacier.proxy.trusted-hops} (default {@code 1}): the number of trusted reverse
 * proxies that prepend hops to {@code X-Forwarded-For}. This corresponds to the number of
 * hops from the rightmost entry to trust as proxy-added:
 * <ul>
 *   <li>0: no proxies trusted — always use {@code request.getRemoteAddr()}</li>
 *   <li>1 (default): one trusted proxy (e.g. Traefik) — use the rightmost XFF entry</li>
 *   <li>N: N trusted proxies in a chain — use the Nth from the right (0-indexed)</li>
 * </ul>
 *
 * <h2>Algorithm</h2>
 * <ol>
 *   <li>If {@code X-Forwarded-For} is absent or the header is blank, fall back to
 *       {@code request.getRemoteAddr()}.</li>
 *   <li>Split the header value by comma and trim each part.</li>
 *   <li>The rightmost N entries are added by trusted proxies; the entry at position
 *       {@code len - trustedHops - 1} (0-indexed from the left) is the real client IP.
 *       If {@code trustedHops ≥ len}, fall back to index 0 (leftmost entry).</li>
 *   <li>If the result is blank or unresolvable, fall back to
 *       {@code request.getRemoteAddr()}.</li>
 * </ol>
 *
 * <p>References: Sec-14/P1-12; spring-security-hardening skill (§forward-headers-strategy);
 * OWASP A05:2021 Security Misconfiguration; NIST SP 800-53 SC-7.
 */
@Component
public class ClientIpResolver {

    private static final String XFF_HEADER = "X-Forwarded-For";

    /**
     * Number of trusted reverse-proxy hops.
     * Only override from 1 if Glacier is behind a multi-hop proxy chain.
     */
    private final int trustedHops;

    /**
     * Constructs a {@code ClientIpResolver} with a configurable hop count.
     *
     * @param trustedHops {@code glacier.proxy.trusted-hops} — number of trusted proxy hops
     *                    (default 1). Set to 0 to always use the remote address directly.
     */
    public ClientIpResolver(
            @Value("${glacier.proxy.trusted-hops:1}") final int trustedHops) {
        if (trustedHops < 0) {
            throw new IllegalArgumentException(
                    "glacier.proxy.trusted-hops must be >= 0, got: " + trustedHops);
        }
        this.trustedHops = trustedHops;
    }

    /**
     * Returns the most plausible client IP address for the given request.
     *
     * <p>When {@code X-Forwarded-For} is present and {@code trustedHops >= 1}, extracts the
     * rightmost non-proxy entry from the header. Otherwise, returns {@code getRemoteAddr()}.
     *
     * <p>Never returns {@code null}: if all resolution attempts fail, {@code "unknown"} is
     * returned to avoid NPEs in rate-limit key computation.
     *
     * @param request the incoming servlet request
     * @return the resolved client IP address; never {@code null}
     */
    public String resolve(final HttpServletRequest request) {
        if (request == null) {
            return "unknown";
        }

        // trustedHops=0 means bypass XFF entirely — use the direct connection IP
        if (trustedHops == 0) {
            return safeRemoteAddr(request);
        }

        String xff = request.getHeader(XFF_HEADER);
        if (xff == null || xff.isBlank()) {
            // No XFF header — direct connection or proxy stripped the header
            return safeRemoteAddr(request);
        }

        // Split by comma and trim whitespace from each part
        String[] parts = xff.split(",", -1);
        // Remove blank entries
        java.util.List<String> hops = new java.util.ArrayList<>();
        for (String part : parts) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                hops.add(trimmed);
            }
        }

        if (hops.isEmpty()) {
            return safeRemoteAddr(request);
        }

        // The rightmost `trustedHops` entries were added by trusted proxies.
        // The client IP is at index: len - trustedHops - 1
        int clientIndex = hops.size() - trustedHops - 1;

        // Clamp to valid range
        if (clientIndex < 0) {
            // Fewer hops than expected — the leftmost entry is the best we have
            clientIndex = 0;
        }

        String resolved = hops.get(clientIndex);
        if (resolved.isBlank()) {
            return safeRemoteAddr(request);
        }

        // Strip IPv6 brackets if present (e.g. [::1] → ::1)
        if (resolved.startsWith("[") && resolved.endsWith("]")) {
            resolved = resolved.substring(1, resolved.length() - 1);
        }

        return resolved;
    }

    // -----------------------------------------------------------------------
    // Accessors (package-private for testing)
    // -----------------------------------------------------------------------

    /**
     * Returns the configured trusted hop count.
     * Package-private for use in tests.
     */
    int getTrustedHops() {
        return trustedHops;
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static String safeRemoteAddr(final HttpServletRequest request) {
        String addr = request.getRemoteAddr();
        return (addr != null && !addr.isBlank()) ? addr : "unknown";
    }
}
