package de.seism0saurus.glacier.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link LogScrubber}.
 *
 * <p>Security controls verified (D-13, SR-8):
 * <ul>
 *   <li>{@code hash8} produces 8-char lowercase hex, consistent, handles null/blank</li>
 *   <li>{@code containsRawUuid} detects UUID patterns</li>
 *   <li>{@code maskIp} masks last octet/group</li>
 *   <li>{@code hashtagLen} returns the character count</li>
 *   <li>{@code urlHostHash} hashes host:port for SSRF audit events (SR-PT-07)</li>
 *   <li>{@code FORBIDDEN_LOG_FIELDS} contains required sensitive key names</li>
 * </ul>
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

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "   ", "\t", "\n"})
    void hash8_blankInput_returnsBlankSentinel(String blank) {
        assertThat(LogScrubber.hash8(blank)).isEqualTo("blank");
    }

    /**
     * Pins the exact 8-hex-char output for a fixed known value.
     * SHA-256 of "my-wall-id" (UTF-8) first 8 hex chars: "70c8ebbb".
     */
    @Test
    void hash8_knownValue_returnsExpectedEightCharPrefix() {
        // SHA-256("my-wall-id") first 8 hex chars, verified: echo -n "my-wall-id" | sha256sum
        assertThat(LogScrubber.hash8("my-wall-id")).isEqualTo("70c8ebbb");
    }

    @Test
    void hash8_matchesLegacyTruncation_forKnownInputs() {
        assertThat(LogScrubber.hash8("550e8400-e29b-41d4-a716-446655440000"))
                .matches("[0-9a-f]{8}")
                .hasSize(8);
        assertThat(LogScrubber.hash8("6ba7b810-9dad-11d1-80b4-00c04fd430c8"))
                .matches("[0-9a-f]{8}")
                .hasSize(8);
        assertThat(LogScrubber.hash8("6ba7b811-9dad-11d1-80b4-00c04fd430c8"))
                .matches("[0-9a-f]{8}")
                .hasSize(8);

        String h1 = LogScrubber.hash8("550e8400-e29b-41d4-a716-446655440000");
        String h2 = LogScrubber.hash8("6ba7b810-9dad-11d1-80b4-00c04fd430c8");
        String h3 = LogScrubber.hash8("6ba7b811-9dad-11d1-80b4-00c04fd430c8");
        assertThat(h1).isNotEqualTo(h2);
        assertThat(h2).isNotEqualTo(h3);
        assertThat(h1).isNotEqualTo(h3);
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
    // hashtagLen — returns int
    // -------------------------------------------------------------------------

    @Test
    void hashtagLen_normal_returnsLength() {
        assertThat(LogScrubber.hashtagLen("java")).isEqualTo(4);
        assertThat(LogScrubber.hashtagLen("glacier")).isEqualTo(7);
    }

    @Test
    void hashtagLen_null_returnsZero() {
        assertThat(LogScrubber.hashtagLen(null)).isEqualTo(0);
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
    // urlHostHash — SR-PT-07
    // -------------------------------------------------------------------------

    /**
     * SR-PT-07 (part 1): same host + different paths → same hash.
     */
    @Test
    void urlHostHash_sameHostDifferentPaths_returnsSameHash() {
        String url1 = "https://mastodon.social/@user/12345";
        String url2 = "https://mastodon.social/@other/99999";

        assertThat(LogScrubber.urlHostHash(url1)).isEqualTo(LogScrubber.urlHostHash(url2));
    }

    /**
     * SR-PT-07 (part 2): different hosts → different hashes.
     */
    @Test
    void urlHostHash_differentHosts_returnsDifferentHashes() {
        String url1 = "https://mastodon.social/@user/12345";
        String url2 = "https://fosstodon.org/@user/12345";

        assertThat(LogScrubber.urlHostHash(url1)).isNotEqualTo(LogScrubber.urlHostHash(url2));
    }

    /**
     * https URL without explicit port defaults to 443.
     */
    @Test
    void urlHostHash_httpsWithoutExplicitPort_defaultsToPort443() {
        String withoutPort = "https://mastodon.social/about";
        String withPort443 = "https://mastodon.social:443/about";

        assertThat(LogScrubber.urlHostHash(withoutPort))
                .isEqualTo(LogScrubber.urlHostHash(withPort443));
    }

    /**
     * http URL without explicit port defaults to 80.
     */
    @Test
    void urlHostHash_httpWithoutExplicitPort_defaultsToPort80() {
        String withoutPort = "http://mastodon.social/about";
        String withPort80 = "http://mastodon.social:80/about";

        assertThat(LogScrubber.urlHostHash(withoutPort))
                .isEqualTo(LogScrubber.urlHostHash(withPort80));
    }

    /**
     * null URL returns the hash of the "unparseable" sentinel.
     */
    @Test
    void urlHostHash_nullUrl_returnsUnparseableHash() {
        assertThat(LogScrubber.urlHostHash(null))
                .isEqualTo(LogScrubber.hash8("unparseable"));
    }

    /**
     * Blank URL returns the "unparseable" hash.
     */
    @Test
    void urlHostHash_blankUrl_returnsUnparseableHash() {
        assertThat(LogScrubber.urlHostHash("   "))
                .isEqualTo(LogScrubber.hash8("unparseable"));
    }

    /**
     * Malformed URL returns the "unparseable" hash without throwing.
     */
    @Test
    void urlHostHash_malformedUrl_returnsUnparseableHash() {
        assertThat(LogScrubber.urlHostHash("not a url ://[invalid"))
                .isEqualTo(LogScrubber.hash8("unparseable"));
    }

    /**
     * URL without a host (e.g. file scheme) returns the "unparseable" hash.
     */
    @Test
    void urlHostHash_urlWithoutHost_returnsUnparseableHash() {
        assertThat(LogScrubber.urlHostHash("file:///local/path"))
                .isEqualTo(LogScrubber.hash8("unparseable"));
    }

    /**
     * Valid URL returns exactly 8 lowercase hex characters.
     */
    @Test
    void urlHostHash_validUrl_returnsEightCharacterHex() {
        assertThat(LogScrubber.urlHostHash("https://mastodon.social/@user/1"))
                .hasSize(8)
                .matches("[0-9a-f]{8}");
    }
}
