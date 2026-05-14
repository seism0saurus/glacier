package de.seism0saurus.glacier.share.application;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for the creator-IP round-trip validation in {@link ShareLinkServiceImpl}.
 *
 * <p>SR-SQLITE-11 requires that the creator IP is validated via an {@code InetAddress}
 * round-trip before being persisted. This prevents cap-bypass attacks that submit
 * malformed {@code X-Forwarded-For} values (e.g., {@code "Hi-Im-Attacker-1"}) which
 * would not match any legitimately-created HMAC entry, so the IP cap would never
 * trigger for such strings.
 *
 * <h2>Security invariants tested</h2>
 * <ul>
 *   <li>Valid IPv4 and IPv6 literals are accepted.</li>
 *   <li>Hostnames, malformed strings, and empty strings are rejected.</li>
 *   <li>null is accepted — the creator IP field is nullable in the domain model.</li>
 *   <li>The exception message does NOT echo the invalid value (SR-SQLITE-01 / log hygiene).</li>
 * </ul>
 *
 * <p>References: SR-SQLITE-11; OWASP A03:2021 — Injection; CWE-20 — Improper Input Validation;
 * ASVS V5.1.3 (L1); C3 — validate all inputs at boundary.
 */
class ShareLinkServiceImplCreatorIpValidationTest {

    /**
     * Reflectively obtains the package-private {@code validateCreatorIp} method so this
     * unit test can exercise it in isolation without constructing a full Spring context
     * or mocking all collaborators.
     *
     * <p>The method is package-private (not private) so it is accessible from this test
     * class in the same package.
     */
    private void invokeValidate(final String ip) {
        // Delegate directly to the accessible package-private helper
        ShareLinkServiceImpl.validateCreatorIpForTest(ip);
    }

    // -------------------------------------------------------------------------
    // Positive cases — valid IP addresses must be accepted
    // -------------------------------------------------------------------------

    /**
     * SR-SQLITE-11: valid IPv4 literals must pass the round-trip check.
     */
    @ParameterizedTest(name = "valid IPv4 accepted: [{0}]")
    @ValueSource(strings = {
            "192.168.1.1",
            "10.0.0.1",
            "127.0.0.1",
            "0.0.0.0",
            "255.255.255.255",
    })
    void validIpv4_isAccepted(final String ip) {
        assertThatNoException()
                .as("Valid IPv4 %s must not throw", ip)
                .isThrownBy(() -> invokeValidate(ip));
    }

    /**
     * SR-SQLITE-11: valid IPv6 literals must pass the round-trip check.
     *
     * <p>IPv6 addresses are accepted if {@link java.net.InetAddress#getByName(String)} parses
     * them as an {@link java.net.Inet6Address}. The normalised form returned by
     * {@code getHostAddress()} may differ from the input (e.g., {@code "::1"} normalises to
     * {@code "0:0:0:0:0:0:0:1"}) — this is expected and accepted.
     *
     * <p>Note: IPv4-mapped IPv6 addresses ({@code ::ffff:x.x.x.x}) are intentionally excluded
     * because {@link java.net.InetAddress#getByName(String)} parses them as
     * {@code Inet4Address}, so they fall under IPv4 validation where the round-trip check
     * would fail (the input format does not survive the normalisation). Proxies that send
     * X-Forwarded-For headers use plain IPv4 or plain IPv6 literals in practice.
     */
    @ParameterizedTest(name = "valid IPv6 accepted: [{0}]")
    @ValueSource(strings = {
            "::1",
            "2001:db8::1",
            "fe80::1",
            "2001:0db8:0000:0000:0000:0000:0000:0001",
    })
    void validIpv6_isAccepted(final String ip) {
        assertThatNoException()
                .as("Valid IPv6 %s must not throw", ip)
                .isThrownBy(() -> invokeValidate(ip));
    }

    /**
     * SR-SQLITE-11: null IP must be accepted — the creator IP is nullable in the domain model
     * (anonymous requests from proxies that strip the forwarded-for header).
     */
    @Test
    void nullIp_isAccepted() {
        assertThatNoException()
                .as("null creator IP must not throw (nullable field)")
                .isThrownBy(() -> invokeValidate(null));
    }

    /**
     * SR-SQLITE-11: blank string IP must be accepted — treated same as null (no IP provided).
     */
    @ParameterizedTest(name = "blank IP accepted: [{arguments}]")
    @ValueSource(strings = {"", "   "})
    void blankIp_isAccepted(final String ip) {
        assertThatNoException()
                .as("Blank creator IP '%s' must not throw (treated as absent)", ip)
                .isThrownBy(() -> invokeValidate(ip));
    }

    // -------------------------------------------------------------------------
    // Negative cases — malformed / non-IP strings must be rejected
    // -------------------------------------------------------------------------

    /**
     * SR-SQLITE-11 / cap-bypass: hostnames must be rejected.
     *
     * <p>A hostile client could send {@code X-Forwarded-For: evil.example.com} — the
     * HMAC would produce a unique value that never matches any legitimate IP's HMAC, so
     * the IP cap would never trigger.
     */
    @ParameterizedTest(name = "hostname rejected: [{0}]")
    @ValueSource(strings = {
            "evil.example.com",
            "localhost",
            "host.internal",
    })
    void hostname_isRejected(final String ip) {
        assertThatThrownBy(() -> invokeValidate(ip))
                .as("Hostname %s must be rejected by IP round-trip validation (SR-SQLITE-11)", ip)
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * SR-SQLITE-11 / cap-bypass: malformed strings must be rejected.
     *
     * <p>These are the class of inputs that motivate SR-SQLITE-11:
     * {@code "Hi-Im-Attacker-1"}, {@code "not-an-ip"}, etc. would produce a unique HMAC
     * on every submission, bypassing the per-IP cap entirely.
     */
    @ParameterizedTest(name = "malformed IP rejected: [{0}]")
    @ValueSource(strings = {
            "Hi-Im-Attacker-1",
            "not-an-ip",
            "999.999.999.999",
            "1.2.3.4.5",
            "1.2.3",
            "1.2.3.4/24",
            "a:b:c:d:e:f:g",
    })
    void malformedIp_isRejected(final String ip) {
        assertThatThrownBy(() -> invokeValidate(ip))
                .as("Malformed IP '%s' must be rejected by round-trip validation (SR-SQLITE-11)", ip)
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * SR-SQLITE-11: a very long string must be rejected (DoS / OOM guard on
     * {@code InetAddress.getByName} with a huge input).
     */
    @Test
    void veryLongString_isRejected() {
        String longInput = "a".repeat(300);
        assertThatThrownBy(() -> invokeValidate(longInput))
                .as("Very long string must be rejected (SR-SQLITE-11)")
                .isInstanceOf(IllegalArgumentException.class);
    }

    // -------------------------------------------------------------------------
    // Security invariant: exception message must NOT echo the invalid value
    // -------------------------------------------------------------------------

    /**
     * SR-SQLITE-01 / log-hygiene: the exception message thrown by {@code validateCreatorIp}
     * must never contain the invalid input value — preventing injection into logs or
     * error responses that may be observed by the attacker.
     *
     * <p>References: SR-SQLITE-01; D-13 log hygiene; CWE-117 — Improper Output Neutralisation.
     */
    @ParameterizedTest(name = "exception message does not echo input: [{0}]")
    @ValueSource(strings = {
            "Hi-Im-Attacker-CANARY",
            "evil.example.com",
            "not-an-ip",
    })
    void exceptionMessage_doesNotEchoInvalidInput(final String ip) {
        assertThatThrownBy(() -> invokeValidate(ip))
                .isInstanceOf(IllegalArgumentException.class)
                .extracting(Throwable::getMessage)
                .asString()
                .as("Exception message must NOT echo the invalid input (SR-SQLITE-01 / log hygiene)")
                .doesNotContain(ip);
    }
}
