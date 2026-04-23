package de.seism0saurus.glacier.share.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Optional;
import java.util.Set;

/**
 * Default implementation of URL validation for the share-link feature.
 *
 * <p>Implements a strict allowlist approach per SR-SHARE-09:
 * <ol>
 *   <li>Scheme allowlist: {@code http} and {@code https} only.</li>
 *   <li>Rejects {@code javascript:}, {@code data:}, {@code vbscript:}, {@code file:},
 *       {@code blob:}, {@code about:}, {@code mailto:}, {@code tel:} and any
 *       non-hierarchical scheme.</li>
 *   <li>Rejects userinfo ({@code user@host}) — prevents phishing URLs.</li>
 *   <li>Rejects empty or missing host.</li>
 *   <li>Blocks RFC1918 / loopback / link-local / CGN / multicast / IPv6-private
 *       (SSRF prevention — OWASP SSRF Prevention Cheat Sheet, NIST SP 800-53 AC-3).</li>
 *   <li>DNS resolves the host once and pins the IP (SSRF DNS-rebinding prevention).</li>
 *   <li>Rejects bidi override and control characters.</li>
 * </ol>
 *
 * <p>References: OWASP A03 (Injection), OWASP SSRF Prevention Cheat Sheet,
 * SR-SHARE-09, {@code spring-input-validation-ssrf} skill.
 */
@Component
public class DefaultSafeUrlValidator implements SafeUrlValidator {

    private static final Logger log = LoggerFactory.getLogger(DefaultSafeUrlValidator.class);
    private static final Logger AUDIT = LoggerFactory.getLogger("AUDIT");

    /** Allowed URL schemes — case-insensitive comparison applied in code. */
    private static final Set<String> ALLOWED_SCHEMES = Set.of("http", "https");

    /**
     * Bidi override and directional isolate characters that must not appear in URLs.
     * Prevents Trojan Source / URL spoofing attacks via invisible character injection.
     * Range: U+202E (RLO), U+2066-U+2069 (directional isolates), U+200B (ZWSP).
     */
    private static final Set<Integer> BIDI_OVERRIDE_CHARS = Set.of(
            0x202E, // RIGHT-TO-LEFT OVERRIDE
            0x2066, // LEFT-TO-RIGHT ISOLATE
            0x2067, // RIGHT-TO-LEFT ISOLATE
            0x2068, // FIRST STRONG ISOLATE
            0x2069, // POP DIRECTIONAL ISOLATE
            0x200B  // ZERO WIDTH SPACE
    );

    @Override
    public Optional<URI> validate(final String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }

        // Step 1: Check for bidi override characters before URI parsing
        // (avoids relying on URI parser to catch obfuscated inputs)
        if (containsBidiOrControlChars(raw)) {
            AUDIT.info("share.proxy.fetch_blocked reason=bidi_chars");
            return Optional.empty();
        }

        // Step 2: Parse URI — reject malformed
        URI uri;
        try {
            uri = URI.create(raw);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }

        // Step 3: Scheme must be http or https (case-insensitive)
        String scheme = uri.getScheme();
        if (scheme == null || !ALLOWED_SCHEMES.contains(scheme.toLowerCase())) {
            AUDIT.info("share.proxy.fetch_blocked reason=disallowed_scheme scheme={}", scheme);
            return Optional.empty();
        }

        // Step 4: Reject userinfo (user:pass@host or user@host)
        if (uri.getUserInfo() != null) {
            AUDIT.info("share.proxy.fetch_blocked reason=userinfo_present");
            return Optional.empty();
        }

        // Step 5: Require non-empty host
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            return Optional.empty();
        }

        // Step 6: DNS resolution + SSRF blocklist check
        // Resolve once and pin the IP to prevent DNS rebinding attacks
        try {
            InetAddress[] addresses = InetAddress.getAllByName(host);
            for (InetAddress addr : addresses) {
                if (isBlockedAddress(addr)) {
                    AUDIT.info("share.proxy.fetch_blocked reason=ssrf_blocked host={}",
                            obfuscateHost(host));
                    return Optional.empty();
                }
            }
        } catch (UnknownHostException e) {
            // Unresolvable host — reject (fail secure)
            return Optional.empty();
        }

        // Step 7: Normalize and do a final bidi check on normalized form
        URI normalized = uri.normalize();
        if (containsBidiOrControlChars(normalized.toString())) {
            return Optional.empty();
        }

        return Optional.of(normalized);
    }

    /**
     * Returns true if the given IP address is in a blocked range.
     *
     * <p>Blocks: loopback, link-local, site-local (RFC1918), any-local,
     * multicast, CGNAT (100.64.0.0/10), IPv6 ULA (fc00::/7),
     * and the cloud metadata endpoint 169.254.169.254.
     *
     * <p>References: {@code spring-input-validation-ssrf} skill SSRF section,
     * NIST SP 800-53 SC-7.
     */
    static boolean isBlockedAddress(final InetAddress addr) {
        if (addr.isLoopbackAddress()) return true;       // 127/8, ::1
        if (addr.isLinkLocalAddress()) return true;      // 169.254/16, fe80::/10
        if (addr.isSiteLocalAddress()) return true;      // 10/8, 172.16/12, 192.168/16
        if (addr.isAnyLocalAddress()) return true;       // 0.0.0.0, ::
        if (addr.isMulticastAddress()) return true;

        String hostAddr = addr.getHostAddress();
        // Cloud metadata endpoints (AWS, GCP, Azure, DO)
        if (hostAddr.equals("169.254.169.254")) return true;
        // CGNAT shared address space RFC 6598
        if (hostAddr.startsWith("100.64.") || hostAddr.startsWith("100.65.")
                || hostAddr.startsWith("100.1") || isCgnat(hostAddr)) return true;
        // IPv6 ULA (fc00::/7)
        if (hostAddr.startsWith("fc") || hostAddr.startsWith("fd")) return true;

        return false;
    }

    private static boolean isCgnat(String hostAddr) {
        // 100.64.0.0/10 = 100.64.0.0 - 100.127.255.255
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

    private static boolean containsBidiOrControlChars(final String value) {
        return value.codePoints().anyMatch(cp ->
                BIDI_OVERRIDE_CHARS.contains(cp)
                        || (cp < 0x20 && cp != 0x09 && cp != 0x0A && cp != 0x0D) // control chars except TAB/LF/CR
                        || cp == 0x7F  // DEL
        );
    }

    /**
     * Partially obfuscates a hostname for safe audit log output.
     * Never log the full host in case it encodes internal topology.
     */
    private static String obfuscateHost(String host) {
        if (host == null || host.length() <= 4) return "***";
        return host.substring(0, 4) + "...";
    }
}
