package de.seism0saurus.glacier.share.application;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URI;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exhaustive XSS / SSRF corpus test for {@link DefaultSafeUrlValidator}.
 *
 * <p>Security requirement: SR-SHARE-09 (safe URL gating before proxy fetch / anchor emission).
 * References: OWASP A03 (Injection), OWASP SSRF Prevention Cheat Sheet,
 * BSI TSS-WEB section 5.2 (content injection).
 */
class DefaultSafeUrlValidatorCorpusTest {

    private final DefaultSafeUrlValidator validator = new DefaultSafeUrlValidator();

    // -----------------------------------------------------------------------
    // BLOCKED — dangerous schemes
    // -----------------------------------------------------------------------

    @ParameterizedTest(name = "blocked dangerous scheme: {0}")
    @ValueSource(strings = {
            "javascript:alert(1)",
            "JAVASCRIPT:alert(1)",
            "JavaScript:alert(1)",
            "\tjavascript:alert(1)",
            " javascript:alert(1)",
            "javascript://%0aalert(1)",
            "javascript://comment%0aalert(1)",
            "data:text/html,<script>alert(1)</script>",
            "data:text/html;base64,PHNjcmlwdD5hbGVydCgxKTwvc2NyaXB0Pg==",
            "vbscript:msgbox(1)",
            "file:///etc/passwd",
            "file://localhost/etc/passwd",
            "blob:https://evil.com/uuid",
            "about:blank",
            "mailto:user@example.com",
            "tel:+1234567890",
            "ftp://evil.com/file",
            "gopher://evil.com/",
            "jar://evil.com/file.jar!/",
            "ldap://ldap.evil.com/",
    })
    void blocksScheme(String url) {
        Optional<URI> result = validator.validate(url);
        assertThat(result).as("should block: %s", url).isEmpty();
    }

    // -----------------------------------------------------------------------
    // BLOCKED — SSRF: RFC1918 / loopback / link-local / CGNAT / multicast
    // -----------------------------------------------------------------------

    @ParameterizedTest(name = "blocked private/internal host: {0}")
    @ValueSource(strings = {
            "http://127.0.0.1/",
            "http://127.0.0.1:8080/internal/actuator",
            "http://localhost/",
            "https://localhost/",
            "http://0.0.0.0/",
            "http://10.0.0.1/secret",
            "http://10.255.255.255/",
            "http://172.16.0.1/",
            "http://172.31.255.255/",
            "http://192.168.1.1/",
            "http://192.168.255.255/",
            "http://169.254.169.254/",
            "http://169.254.169.254/latest/meta-data/",
            "http://100.64.0.1/",
            "http://100.127.255.255/",
            "http://[::1]/",
            "http://[fc00::1]/",
            "http://[fd00::1]/",
            "http://[fe80::1]/",
    })
    void blocksPrivateAndInternalHost(String url) {
        Optional<URI> result = validator.validate(url);
        assertThat(result).as("should block SSRF: %s", url).isEmpty();
    }

    // -----------------------------------------------------------------------
    // BLOCKED — userinfo (phishing / credential exposure)
    // -----------------------------------------------------------------------

    @ParameterizedTest(name = "blocked userinfo: {0}")
    @ValueSource(strings = {
            "http://user:pass@evil.com/",
            "https://attacker@legitimate.example.com/",
            "http://user@host/",
    })
    void blocksUserinfo(String url) {
        Optional<URI> result = validator.validate(url);
        assertThat(result).as("should block userinfo: %s", url).isEmpty();
    }

    // -----------------------------------------------------------------------
    // BLOCKED — bidi override / control characters
    // -----------------------------------------------------------------------

    @ParameterizedTest(name = "blocked bidi/control chars: {0}")
    @ValueSource(strings = {
            "https://evil.com/‮path",
            "https://evil.com/⁦path",
            "https://evil.com/⁧path",
            "https://evil.com/⁨path",
            "https://evil.com/⁩path",
            "https://evil.com/​path",
    })
    void blocksBidiAndControlChars(String url) {
        Optional<URI> result = validator.validate(url);
        assertThat(result).as("should block bidi: %s", url).isEmpty();
    }

    // -----------------------------------------------------------------------
    // BLOCKED — null / blank / malformed
    // -----------------------------------------------------------------------

    @Test
    void blocksNull() {
        assertThat(validator.validate(null)).isEmpty();
    }

    @Test
    void blocksBlank() {
        assertThat(validator.validate("")).isEmpty();
        assertThat(validator.validate("   ")).isEmpty();
    }

    @ParameterizedTest(name = "blocked malformed: {0}")
    @ValueSource(strings = {
            "not-a-url",
            "://missing-scheme",
            "http://",
    })
    void blocksMalformed(String url) {
        Optional<URI> result = validator.validate(url);
        assertThat(result).as("should block malformed: %s", url).isEmpty();
    }

    // -----------------------------------------------------------------------
    // BLOCKED — encoded / obfuscated javascript:
    // -----------------------------------------------------------------------

    @ParameterizedTest(name = "blocked obfuscated: {0}")
    @ValueSource(strings = {
            // URL-encoded colon
            "javascript%3Aalert(1)",
            // Unicode escapes for 'j' 'a' 'v' 'a' 's' 'c' 'r' 'i' 'p' 't'
            "javascript:alert(1)",
    })
    void blocksObfuscatedJavascript(String url) {
        Optional<URI> result = validator.validate(url);
        assertThat(result).as("should block obfuscated: %s", url).isEmpty();
    }

    // -----------------------------------------------------------------------
    // ALLOWED — valid public URLs
    // -----------------------------------------------------------------------

    @ParameterizedTest(name = "allowed valid URL: {0}")
    @ValueSource(strings = {
            "https://mastodon.social/@user/123",
            "https://fosstodon.org/users/testuser/statuses/123",
            "http://mastodon.social/@user/123",
            "https://mastodon.social/path?query=value&other=thing",
            "https://mastodon.social/path#fragment",
    })
    void allowsValidPublicUrls(String url) {
        Optional<URI> result = validator.validate(url);
        assertThat(result).as("should allow: %s", url).isPresent();
    }

    @Test
    void allowsIpv6PublicAddress() {
        // Public IPv6, not fc/fd/fe80
        Optional<URI> result = validator.validate("https://[2001:db8::1]/path");
        assertThat(result).isPresent();
    }

    @Test
    void returnsNormalizedUri() {
        Optional<URI> result = validator.validate("HTTPS://EXAMPLE.COM/path");
        assertThat(result).isPresent();
    }
}
