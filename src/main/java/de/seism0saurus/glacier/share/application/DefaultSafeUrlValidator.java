package de.seism0saurus.glacier.share.application;

import de.seism0saurus.glacier.util.IpAddressClassifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Locale;
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

    /**
     * Whether dev mode is active ({@code glacier.devmode}). Gated to the {@code dev}/{@code test}
     * profiles by {@code StartupSanityChecker}; never {@code true} in production.
     */
    private final boolean devMode;

    /**
     * The configured Mastodon instance host ({@code mastodon.instance}), lower-cased, or empty
     * when unset. In dev mode this single host is exempted from the private-IP SSRF block so the
     * local/e2e fixture (an internal hostname resolving to an RFC1918 address) can be embedded.
     * Production never reaches the exemption because {@link #devMode} is {@code false}.
     */
    private final String devInstanceHost;

    /**
     * Production-strict constructor (no dev exemption). Used by tests that assert the
     * default SSRF behaviour and by any wiring that does not need the dev allowance.
     */
    public DefaultSafeUrlValidator() {
        this(false, "");
    }

    /**
     * Spring constructor. Binds {@code glacier.devmode} and {@code mastodon.instance} so that,
     * in dev mode only, the configured instance host is exempt from the private-IP blocklist
     * (see {@link #devInstanceHost}). All other SSRF guards remain in force in every mode.
     *
     * @param devMode          value of {@code glacier.devmode} (default {@code false})
     * @param mastodonInstance value of {@code mastodon.instance} (the bare instance host)
     */
    @Autowired
    public DefaultSafeUrlValidator(
            @Value("${glacier.devmode:false}") final boolean devMode,
            @Value("${mastodon.instance:}") final String mastodonInstance) {
        this.devMode = devMode;
        this.devInstanceHost = mastodonInstance == null
                ? ""
                : mastodonInstance.trim().toLowerCase(Locale.ROOT);
    }

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
        // C3/CWE-178: Locale.ROOT ensures SSRF scheme allowlist comparison is locale-independent.
        // URI.getScheme() may return the scheme in original case (e.g. "HTTP" for "HTTP://...").
        // Using Locale.ROOT prevents Turkish locale's dotless-i from corrupting scheme folding.
        String scheme = uri.getScheme();
        if (scheme == null || !ALLOWED_SCHEMES.contains(scheme.toLowerCase(Locale.ROOT))) {
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
        // Resolve once and pin the IP to prevent DNS rebinding attacks.
        // Dev-only exemption: in dev mode the single configured mastodon.instance host is
        // allowed even when it resolves to a private address (the local/e2e fixture runs on
        // an internal hostname). devMode is false in production, so the block stays strict there.
        final boolean devInstanceExempt =
                devMode && !devInstanceHost.isEmpty() && host.toLowerCase(Locale.ROOT).equals(devInstanceHost);
        try {
            InetAddress[] addresses = InetAddress.getAllByName(host);
            for (InetAddress addr : addresses) {
                if (isBlockedAddress(addr)) {
                    if (devInstanceExempt) {
                        AUDIT.info("stomp.embed.dev_instance_exempt host={}", obfuscateHost(host));
                        continue;
                    }
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
     * Resolves the given hostname to an {@link InetAddress} and verifies it is not in
     * a blocked range (SSRF prevention).
     *
     * <p>This is the DNS-pinning helper used by {@code ShareImageProxyService.fetchInternal}
     * to prevent TOCTOU DNS-rebinding attacks. The validated address is used as the actual
     * request target so the JVM never performs a second DNS lookup for the same URL.
     *
     * <p>References: OWASP SSRF Prevention Cheat Sheet §DNS Pinning,
     * {@code spring-input-validation-ssrf} skill, SR-SHARE-09, SSRF Finding 5.
     *
     * @param host the hostname to resolve and check
     * @return a safe, non-private {@link InetAddress} for the host
     * @throws java.net.UnknownHostException if the host cannot be resolved
     * @throws IllegalArgumentException      if the resolved IP is in a blocked range
     */
    public static InetAddress resolveAndPin(final String host)
            throws java.net.UnknownHostException {
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("Host must not be blank");
        }
        InetAddress[] addresses = InetAddress.getAllByName(host);
        if (addresses == null || addresses.length == 0) {
            throw new java.net.UnknownHostException("No addresses for host: " + host);
        }
        // Use the first resolved address — check blocklist on each
        for (InetAddress addr : addresses) {
            if (isBlockedAddress(addr)) {
                throw new IllegalArgumentException(
                        "SSRF-blocked resolved address for host: " + obfuscateHost(host));
            }
        }
        return addresses[0];
    }

    /**
     * Returns true if the given IP address is in a blocked range.
     *
     * <p>Delegates to {@link IpAddressClassifier#isBlockedInetAddress(InetAddress)} which is
     * the sole authorised caller of {@code InetAddress.isXAddress()} classification methods
     * in the Glacier codebase. This delegation also handles IPv4-mapped IPv6 addresses
     * ({@code ::ffff:a.b.c.d}) correctly — see Sec-11/Sec-24.
     *
     * <p>References: {@code spring-input-validation-ssrf} skill SSRF section,
     * NIST SP 800-53 SC-7, Sec-11/P1-10, Sec-24 (ArchUnit gate).
     */
    public static boolean isBlockedAddress(final InetAddress addr) {
        // Sec-11/Sec-24: delegate to IpAddressClassifier — the sole authorised consumer
        // of InetAddress.isXAddress() methods. This handles IPv4-mapped IPv6 correctly.
        return IpAddressClassifier.isBlockedInetAddress(addr);
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
