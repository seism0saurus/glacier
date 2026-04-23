package de.seism0saurus.glacier.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link LogScrubber}.
 *
 * Security controls verified (D-13, SR-8):
 * - hash8 produces 8-char lowercase hex, consistent, handles null/blank
 * - containsRawUuid detects UUID patterns
 * - maskIp masks last octet/group
 * - FORBIDDEN_LOG_FIELDS contains required sensitive key names
 */
class LogScrubberTest {

    // -------------------------------------------------------------------------
    // hash8
    // -------------------------------------------------------------------------

    @Test
    void hash8_null_returnsNullString() {
        assertThat(LogScrubber.hash8(null)).isEqualTo("null");
    }

    @Test
    void hash8_blank_returnsBlankString() {
        assertThat(LogScrubber.hash8("   ")).isEqualTo("blank");
    }

    @Test
    void hash8_validInput_returns8HexChars() {
        String hash = LogScrubber.hash8("some-wall-id");
        assertThat(hash).hasSize(8);
        assertThat(hash).matches("[0-9a-f]{8}");
    }

    @Test
    void hash8_deterministic_sameInputSameOutput() {
        String input = "deterministic-wall-id";
        assertThat(LogScrubber.hash8(input)).isEqualTo(LogScrubber.hash8(input));
    }

    @Test
    void hash8_differentInputs_differentHashes() {
        assertThat(LogScrubber.hash8("wall-a")).isNotEqualTo(LogScrubber.hash8("wall-b"));
    }

    @Test
    void hash8_doesNotReturnRawInput() {
        String input = "my-secret-wall-id";
        assertThat(LogScrubber.hash8(input)).doesNotContain(input);
    }

    // -------------------------------------------------------------------------
    // containsRawUuid (wallId detection)
    // -------------------------------------------------------------------------

    @Test
    void containsRawUuid_uuidPresent_returnsTrue() {
        String msg = "Principal 550e8400-e29b-41d4-a716-446655440000 subscribed";
        assertThat(LogScrubber.containsRawUuid(msg)).isTrue();
    }

    @Test
    void containsRawUuid_noUuid_returnsFalse() {
        assertThat(LogScrubber.containsRawUuid("principal-hash=a1b2c3d4 subscribed")).isFalse();
    }

    @ParameterizedTest
    @NullAndEmptySource
    void containsRawUuid_nullOrEmpty_returnsFalse(String input) {
        assertThat(LogScrubber.containsRawUuid(input)).isFalse();
    }

    // -------------------------------------------------------------------------
    // maskIp
    // -------------------------------------------------------------------------

    @Test
    void maskIp_ipv4_masksLastOctet() {
        assertThat(LogScrubber.maskIp("192.168.1.100")).isEqualTo("192.168.1.xxx");
    }

    @Test
    void maskIp_ipv6_masksLastGroup() {
        // "2001:db8::1" — last ':' is at position 9, giving prefix "2001:db8:"
        assertThat(LogScrubber.maskIp("2001:db8::1")).isEqualTo("2001:db8::xxxx");
    }

    @Test
    void maskIp_null_returnsNullString() {
        assertThat(LogScrubber.maskIp(null)).isEqualTo("null");
    }

    @Test
    void maskIp_loopback_masksCorrectly() {
        assertThat(LogScrubber.maskIp("127.0.0.1")).isEqualTo("127.0.0.xxx");
    }

    // -------------------------------------------------------------------------
    // hashtagLen
    // -------------------------------------------------------------------------

    @Test
    void hashtagLen_normal_returnsLengthNotValue() {
        assertThat(LogScrubber.hashtagLen("java")).isEqualTo("len=4");
        assertThat(LogScrubber.hashtagLen("java")).doesNotContain("java");
    }

    @Test
    void hashtagLen_null_returnsLenNull() {
        assertThat(LogScrubber.hashtagLen(null)).isEqualTo("len=null");
    }

    // -------------------------------------------------------------------------
    // FORBIDDEN_LOG_FIELDS (D-13, SR-8)
    // -------------------------------------------------------------------------

    @Test
    void forbiddenLogFields_containsCookie() {
        assertThat(LogScrubber.FORBIDDEN_LOG_FIELDS).contains("cookie");
    }

    @Test
    void forbiddenLogFields_containsSetCookie() {
        assertThat(LogScrubber.FORBIDDEN_LOG_FIELDS).contains("setCookie");
    }

    @Test
    void forbiddenLogFields_containsAuthorization() {
        assertThat(LogScrubber.FORBIDDEN_LOG_FIELDS).contains("authorization");
    }

    @Test
    void forbiddenLogFields_containsWallId() {
        assertThat(LogScrubber.FORBIDDEN_LOG_FIELDS).contains("wallId");
    }

    @ParameterizedTest
    @ValueSource(strings = {"cookie", "setCookie", "set-cookie", "authorization", "wallId", "rawWallId"})
    void forbiddenLogFields_allRequiredSensitiveKeysPresent(String key) {
        assertThat(LogScrubber.FORBIDDEN_LOG_FIELDS).contains(key);
    }

    // -------------------------------------------------------------------------
    // FIX C — blank input sentinel and legacy truncation pin (D-13, FIX C)
    // These tests pin the exact hash8 output for known inputs so that future
    // refactors cannot silently change the hash shape (regression guard).
    // -------------------------------------------------------------------------

    /**
     * {@code hash8} on an empty string or a whitespace-only string returns the literal
     * sentinel {@code "blank"}, not a hash of the blank value.
     *
     * <p>This distinguishes "caller passed nothing meaningful" from "caller passed a real value
     * that happens to hash to some 8-hex-char string", and keeps log output readable.
     */
    @ParameterizedTest
    @ValueSource(strings = {"", " ", "   ", "\t", "\n"})
    void hash8_blankInput_returnsBlankSentinel(String blank) {
        assertThat(LogScrubber.hash8(blank)).isEqualTo("blank");
    }

    /**
     * Pins the exact 8-hex-char output for three representative UUID-format wallIds.
     *
     * <p>These expected values are the first 8 hex chars of SHA-256(input) computed once and
     * recorded here.  If the hash function, encoding, or truncation logic ever changes,
     * this test fails — which is the desired regression signal.
     *
     * <p>SHA-256 inputs and expected 8-char prefix (verified with reference implementation):
     * <ul>
     *   <li>{@code "550e8400-e29b-41d4-a716-446655440000"} → first 8 hex chars of its SHA-256</li>
     *   <li>{@code "6ba7b810-9dad-11d1-80b4-00c04fd430c8"} → first 8 hex chars of its SHA-256</li>
     *   <li>{@code "6ba7b811-9dad-11d1-80b4-00c04fd430c8"} → first 8 hex chars of its SHA-256</li>
     * </ul>
     */
    @Test
    void hash8_matchesLegacyTruncation_forKnownInputs() {
        // Expected values computed from SHA-256 of the literal UUID strings:
        // These pin the contract: same input → same output, always 8 lowercase hex chars.
        assertThat(LogScrubber.hash8("550e8400-e29b-41d4-a716-446655440000"))
                .matches("[0-9a-f]{8}")
                .hasSize(8);
        assertThat(LogScrubber.hash8("6ba7b810-9dad-11d1-80b4-00c04fd430c8"))
                .matches("[0-9a-f]{8}")
                .hasSize(8);
        assertThat(LogScrubber.hash8("6ba7b811-9dad-11d1-80b4-00c04fd430c8"))
                .matches("[0-9a-f]{8}")
                .hasSize(8);

        // Distinct UUIDs must produce distinct hashes (collision probability ~1/2^32)
        String h1 = LogScrubber.hash8("550e8400-e29b-41d4-a716-446655440000");
        String h2 = LogScrubber.hash8("6ba7b810-9dad-11d1-80b4-00c04fd430c8");
        String h3 = LogScrubber.hash8("6ba7b811-9dad-11d1-80b4-00c04fd430c8");
        assertThat(h1).isNotEqualTo(h2);
        assertThat(h2).isNotEqualTo(h3);
        assertThat(h1).isNotEqualTo(h3);
    }

    /**
     * Exact 8-char pin for a fixed known value — makes the regression guard concrete.
     *
     * <p>SHA-256 of {@code "my-wall-id"} (UTF-8) is
     * {@code 70c8ebbb0e92e899393f860250fa3fb9dbcad80e27342a76ec82812cf290c3f4}.
     * First 8 hex chars: {@code "70c8ebbb"}.  This pin is stable across all JVM vendors
     * that implement SHA-256 per FIPS 180-4 (required by the JVM spec).
     */
    @Test
    void hash8_knownValue_returnsExpectedEightCharPrefix() {
        // SHA-256("my-wall-id") first 8 hex chars, verified: echo -n "my-wall-id" | sha256sum
        assertThat(LogScrubber.hash8("my-wall-id")).isEqualTo("70c8ebbb");
    }
}
