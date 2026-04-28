package de.seism0saurus.glacier;

import org.springframework.context.annotation.Configuration;

import java.security.Security;

/**
 * SecurityBootstrap applies JVM-wide security hardening at application startup.
 *
 * <p>This {@link Configuration} class uses a {@code static} initialiser block so that
 * the JVM security properties are set before any {@link java.net.InetAddress} lookup
 * can occur. The class itself has no Spring bean methods — its sole effect is the
 * side-effecting {@code static} block.</p>
 *
 * <h2>DNS Cache TTL (SR-PT-09)</h2>
 * <p>By default the JVM caches successful DNS lookups indefinitely (or for a very
 * long period set by the security policy). A long cache TTL creates a TOCTOU
 * window in SSRF guards: an attacker can pre-resolve a domain to a benign address,
 * pass the allow-list check, then rotate DNS to point at an internal host before
 * the embed fetch fires.</p>
 *
 * <p>This class sets:</p>
 * <ul>
 *   <li>{@code networkaddress.cache.ttl = 30} — successful lookups are cached for
 *       30 seconds, limiting the TOCTOU window while preserving acceptable
 *       performance for most connection patterns.</li>
 *   <li>{@code networkaddress.cache.negative.ttl = 10} — failed lookups are
 *       cached for 10 seconds, preventing amplification of NXDOMAIN errors
 *       while ensuring rapid recovery when a hostname becomes resolvable.</li>
 * </ul>
 *
 * @see java.security.Security#setProperty(String, String)
 */
@Configuration
public class SecurityBootstrap {

    static {
        Security.setProperty("networkaddress.cache.ttl", "30");
        Security.setProperty("networkaddress.cache.negative.ttl", "10");
    }
}
