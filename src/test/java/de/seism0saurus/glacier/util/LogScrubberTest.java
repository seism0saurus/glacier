package de.seism0saurus.glacier.util;

import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.Size;
import net.jqwik.api.constraints.StringLength;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

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
 *   <li>{@code safeEventName} allowlist-guards Mastodon streaming event names (ADR-F6-05, CWE-117)</li>
 *   <li>{@code xfoSummary} returns bounded numeric summary of X-Frame-Options list (TD-4 / ADR-TD4-01, CWE-117)</li>
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

    // --- Mutation-kill: maskIp boundary + sentinel (L132/L133/L134) ---

    /**
     * MUT-KILL L132 (ConditionalsBoundaryMutator on {@code lastDot > 0}):
     * an input whose only dot is at index 0 must NOT be treated as a maskable IPv4.
     *
     * <p>{@code ".foo"} → lastDot=0, lastColon=-1. With {@code > 0} the dot branch is skipped
     * and (no colon either) the method returns {@code "redacted"}. The boundary mutant
     * {@code >= 0} would instead return {@code "" + ".xxx"} = {@code ".xxx"}.
     */
    @Test
    void maskIp_dotAtIndexZero_returnsRedacted_notXxx() {
        assertThat(LogScrubber.maskIp(".foo")).isEqualTo("redacted");
    }

    /**
     * MUT-KILL L133 (ConditionalsBoundaryMutator on {@code lastColon > 0}):
     * an input whose only colon is at index 0 (and no dot) must NOT be treated as IPv6.
     *
     * <p>{@code ":foo"} → lastDot=-1, lastColon=0. With {@code > 0} the colon branch is skipped
     * and the method returns {@code "redacted"}. The boundary mutant {@code >= 0} would instead
     * return {@code "" + ":xxxx"} = {@code ":xxxx"}.
     */
    @Test
    void maskIp_colonAtIndexZero_returnsRedacted_notXxxx() {
        assertThat(LogScrubber.maskIp(":foo")).isEqualTo("redacted");
    }

    /**
     * MUT-KILL L134 (EmptyObjectReturnVals — {@code return ""} instead of {@code "redacted"}):
     * an input with neither dot nor colon must return the exact sentinel {@code "redacted"},
     * which is non-empty. The empty-return mutant produces {@code ""}.
     */
    @Test
    void maskIp_noDotNoColon_returnsExactRedactedSentinel() {
        assertThat(LogScrubber.maskIp("localhost")).isEqualTo("redacted");
        assertThat(LogScrubber.maskIp("localhost")).isNotEmpty();
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

    // --- Mutation-kill: urlHostHash conditionals (L176, L186) ---

    /**
     * MUT-KILL L176 (RemoveConditional_EQUAL_IF — force the early {@code return hash8("unparseable")}):
     * a valid URL must produce a host-derived hash that is NOT equal to the unparseable sentinel hash.
     *
     * <p>The pre-existing "returns eight hex chars" test does not kill this mutant because the
     * unparseable hash is also eight hex chars. Asserting inequality with {@code hash8("unparseable")}
     * is what distinguishes the real host hash from the fail-secure sentinel.
     */
    @Test
    void urlHostHash_validUrl_isNotTheUnparseableSentinelHash() {
        assertThat(LogScrubber.urlHostHash("https://mastodon.social/@user/1"))
                .isNotEqualTo(LogScrubber.hash8("unparseable"));
    }

    /**
     * MUT-KILL L186 (RemoveConditional_EQUAL_IF on {@code if (port == -1)} — force normalization
     * even when an explicit port is present): an explicit non-default port must produce a different
     * hash than the same host on its default port.
     *
     * <p>{@code https://host:8443/} carries explicit port 8443. The correct code hashes
     * {@code "host:8443"}; the mutant forces {@code port = 443} (https default) and hashes
     * {@code "host:443"} — so the explicit-port hash must differ from the default-port hash.
     */
    @Test
    void urlHostHash_explicitNonDefaultPort_differsFromDefaultPort() {
        String explicit = LogScrubber.urlHostHash("https://mastodon.social:8443/about");
        String defaultPort = LogScrubber.urlHostHash("https://mastodon.social/about"); // -> :443
        assertThat(explicit).isNotEqualTo(defaultPort);
    }

    /**
     * MUT-KILL L186 (complementary): the explicit-port hash must equal a deterministic
     * reconstruction {@code hash8("host:port")}, pinning that the explicit port (not the
     * normalized default) is what feeds the hash.
     */
    @Test
    void urlHostHash_explicitPort_matchesHostColonPortHash() {
        assertThat(LogScrubber.urlHostHash("https://mastodon.social:8443/about"))
                .isEqualTo(LogScrubber.hash8("mastodon.social:8443"));
    }

    /**
     * Complementary pin for the default-port normalization (guards L187 path):
     * an https URL without a port hashes {@code "host:443"}.
     */
    @Test
    void urlHostHash_httpsDefaultPort_matchesHostColon443Hash() {
        assertThat(LogScrubber.urlHostHash("https://mastodon.social/about"))
                .isEqualTo(LogScrubber.hash8("mastodon.social:443"));
    }

    // -------------------------------------------------------------------------
    // safeEventName — ADR-F6-05, CWE-117 log injection guard
    // -------------------------------------------------------------------------

    /**
     * Arrange: null input.
     * Act: call safeEventName(null).
     * Assert: returns the literal string "null" — consistent with other LogScrubber null sentinels.
     */
    @Test
    void safeEventName_null_returnsNullSentinel() {
        assertThat(LogScrubber.safeEventName(null)).isEqualTo("null");
    }

    /**
     * Arrange: empty string.
     * Act: call safeEventName("").
     * Assert: returns the literal string "blank".
     */
    @Test
    void safeEventName_emptyString_returnsBlankSentinel() {
        assertThat(LogScrubber.safeEventName("")).isEqualTo("blank");
    }

    /**
     * Arrange: whitespace-only string.
     * Act: call safeEventName("  ").
     * Assert: returns the literal string "blank".
     */
    @Test
    void safeEventName_whitespaceOnly_returnsBlankSentinel() {
        assertThat(LogScrubber.safeEventName("  ")).isEqualTo("blank");
    }

    /**
     * Arrange: each of the Mastodon 4.3 documented streaming event names.
     * Act: call safeEventName with each known value.
     * Assert: verbatim value is returned — allowlisted events pass through unchanged.
     */
    @Test
    void safeEventName_knownEvents_returnVerbatim() {
        assertThat(LogScrubber.safeEventName("update")).isEqualTo("update");
        assertThat(LogScrubber.safeEventName("status.update")).isEqualTo("status.update");
        assertThat(LogScrubber.safeEventName("delete")).isEqualTo("delete");
        assertThat(LogScrubber.safeEventName("status.delete")).isEqualTo("status.delete");
        assertThat(LogScrubber.safeEventName("filters_changed")).isEqualTo("filters_changed");
        assertThat(LogScrubber.safeEventName("announcement")).isEqualTo("announcement");
        assertThat(LogScrubber.safeEventName("announcement.reaction")).isEqualTo("announcement.reaction");
        assertThat(LogScrubber.safeEventName("announcement.delete")).isEqualTo("announcement.delete");
        assertThat(LogScrubber.safeEventName("encrypted_message")).isEqualTo("encrypted_message");
        assertThat(LogScrubber.safeEventName("notification")).isEqualTo("notification");
        assertThat(LogScrubber.safeEventName("conversation")).isEqualTo("conversation");
        assertThat(LogScrubber.safeEventName("notifications_merged")).isEqualTo("notifications_merged");
    }

    /**
     * Arrange: the Mastodon 4.3 streaming event name "notifications_merged" (F-6-INFO-1).
     * Act: call safeEventName("notifications_merged").
     * Assert: verbatim value returned — allowlist now covers Mastodon 4.3 events.
     *
     * <p>Red-anchor: removing "notifications_merged" from KNOWN_STREAM_EVENTS causes this
     * test to fail with "unknown(len=20)" instead of "notifications_merged".
     */
    @Test
    void safeEventName_notificationsMerged_isAllowlistedForMastodon43() {
        assertThat(LogScrubber.safeEventName("notifications_merged"))
                .isEqualTo("notifications_merged");
    }

    /**
     * Arrange: case/padding/control-char variants of "notifications_merged".
     * Act: call safeEventName with each variant.
     * Assert: all fall through to the bounded fallback — exact-equality only; no case folding,
     * no trimming, no Unicode normalization in the allowlist check.
     *
     * <p>Variants tested:
     * <ul>
     *   <li>{@code "Notifications_Merged"} — uppercase first chars</li>
     *   <li>{@code "notifications_merged "} — trailing space</li>
     *   <li>{@code "notifications_merged\r\n"} — CRLF suffix (CWE-117 variant)</li>
     *   <li>{@code "notifications_merged‮"} — RTL override Unicode (homoglyph variant)</li>
     * </ul>
     */
    @Test
    void safeEventName_notificationsMerged_caseAndPaddingVariants_returnFallback() {
        assertThat(LogScrubber.safeEventName("Notifications_Merged"))
                .startsWith("unknown(len=");
        assertThat(LogScrubber.safeEventName("notifications_merged "))
                .startsWith("unknown(len=");
        assertThat(LogScrubber.safeEventName("notifications_merged\r\n"))
                .startsWith("unknown(len=");
        assertThat(LogScrubber.safeEventName("notifications_merged‮"))
                .startsWith("unknown(len=");
    }

    /**
     * Arrange: an event name not in the Mastodon 4.x allowlist.
     * Act: call safeEventName("injected_event").
     * Assert: returns bounded fallback "unknown(len=14)" — the raw value never reaches the logger.
     */
    @Test
    void safeEventName_unknownEvent_returnsBoundedFallback() {
        assertThat(LogScrubber.safeEventName("injected_event")).isEqualTo("unknown(len=14)");
    }

    /**
     * Arrange: single-character unknown event.
     * Act: call safeEventName("x").
     * Assert: returns "unknown(len=1)" — bounded and controlled.
     */
    @Test
    void safeEventName_singleCharUnknownEvent_returnsBoundedFallback() {
        assertThat(LogScrubber.safeEventName("x")).isEqualTo("unknown(len=1)");
    }

    /**
     * Arrange: event name containing CRLF characters — classic CWE-117 log injection payload.
     * Act: call safeEventName with the injected value.
     * Assert: the result contains no newline or carriage return characters.
     */
    @Test
    void safeEventName_crlfInjectionAttempt_doesNotPassThrough() {
        String injected = "update\r\nFAKE_LOG_ENTRY";
        String result = LogScrubber.safeEventName(injected);
        assertThat(result).doesNotContain("\r");
        assertThat(result).doesNotContain("\n");
        assertThat(result).startsWith("unknown(len=");
    }

    /**
     * Arrange: event name containing a null byte — CWE-117 variant.
     * Act: call safeEventName with a string containing a null byte.
     * Assert: the result does not contain the null byte and is bounded.
     */
    @Test
    void safeEventName_nullByteInjectionAttempt_doesNotPassThrough() {
        String injected = "update malicious";
        String result = LogScrubber.safeEventName(injected);
        assertThat(result).doesNotContain(" ");
        assertThat(result).startsWith("unknown(len=");
    }

    // -------------------------------------------------------------------------
    // safeEventName — jqwik property-based fuzz test (SR-TEST-06)
    // -------------------------------------------------------------------------

    /**
     * Property: for any arbitrary string input, safeEventName must NEVER emit CRLF characters.
     *
     * <p>This is the CWE-117 log-injection guard — no attacker-controlled bytes from the
     * Mastodon streaming wire should be able to insert log-forged lines via CRLF sequences.
     *
     * <p>Arrange: arbitrary string up to 200 characters.
     * Act: call safeEventName(s).
     * Assert:
     * <ul>
     *   <li>Result contains no newline or carriage return characters.</li>
     *   <li>Result length is bounded: at most max(s.length(), 20) — "unknown(len=N)" is
     *       at most ~20 chars for inputs up to 999 chars; for larger inputs the
     *       bound is s.length() (only allowlisted values pass through, max ~21 chars).</li>
     * </ul>
     */
    @Property
    void safeEventName_neverContainsCrlfForAnyArbitraryInput(@ForAll @StringLength(max = 200) String s) {
        String result = LogScrubber.safeEventName(s);
        assertThat(result)
                .as("safeEventName must never emit CRLF for input of length %d", s.length())
                .doesNotContain("\n", "\r");
        // Result is bounded: the longest allowlisted value is "announcement.reaction" (21 chars)
        // and "unknown(len=N)" for N up to 200 is 17 chars — always less than or equal to
        // max(s.length(), 25) which accommodates the full fallback format
        assertThat(result.length())
                .as("safeEventName result length should be bounded")
                .isLessThanOrEqualTo(Math.max(s.length(), 25));
    }

    // -------------------------------------------------------------------------
    // xfoSummary — TD-4 / ADR-TD4-01, CWE-117 log injection guard (LST-T4-1..8)
    // -------------------------------------------------------------------------

    /**
     * LST-T4-1: null input returns the zero-values sentinel.
     *
     * <p>Arrange: null list.
     * Act: call {@link LogScrubber#xfoSummary(List)} with null.
     * Assert: returns {@code "xfo-values=0 xfo-totallen=0"} — same form as empty list.
     */
    @Test
    void xfoSummary_null_returnsZeroValues() {
        assertThat(LogScrubber.xfoSummary(null)).isEqualTo("xfo-values=0 xfo-totallen=0");
    }

    /**
     * LST-T4-2: empty list input returns the zero-values sentinel.
     *
     * <p>Arrange: {@code List.of()}.
     * Act: call {@link LogScrubber#xfoSummary(List)}.
     * Assert: returns {@code "xfo-values=0 xfo-totallen=0"}.
     */
    @Test
    void xfoSummary_emptyList_returnsZeroValues() {
        assertThat(LogScrubber.xfoSummary(List.of())).isEqualTo("xfo-values=0 xfo-totallen=0");
    }

    /**
     * LST-T4-3: single non-null value returns correct count and total length.
     *
     * <p>Arrange: {@code List.of("DENY")} (length 4).
     * Act: call {@link LogScrubber#xfoSummary(List)}.
     * Assert: returns {@code "xfo-values=1 xfo-totallen=4"};
     *         result does NOT contain the raw value {@code "DENY"}.
     */
    @Test
    void xfoSummary_singleValue_returnsCountAndLength() {
        String result = LogScrubber.xfoSummary(List.of("DENY"));
        assertThat(result).isEqualTo("xfo-values=1 xfo-totallen=4");
        // D-13/SR-8: raw value bytes must not appear in the summary
        assertThat(result).doesNotContain("DENY");
    }

    /**
     * LST-T4-4: two values return combined count and summed total length.
     *
     * <p>Arrange: {@code List.of("DENY", "SAMEORIGIN")} — lengths 4 + 10 = 14.
     * Act: call {@link LogScrubber#xfoSummary(List)}.
     * Assert: returns {@code "xfo-values=2 xfo-totallen=14"};
     *         result contains neither raw element.
     */
    @Test
    void xfoSummary_multipleValues_returnsCountAndTotalLength() {
        String result = LogScrubber.xfoSummary(List.of("DENY", "SAMEORIGIN"));
        assertThat(result).isEqualTo("xfo-values=2 xfo-totallen=14");
        assertThat(result).doesNotContain("DENY");
        assertThat(result).doesNotContain("SAMEORIGIN");
    }

    /**
     * LST-T4-5: a CRLF-injection payload does not pass through to the summary.
     *
     * <p>Arrange: list containing {@code "foo\r\nbar"} — a classic CWE-117 log-injection fragment.
     * Act: call {@link LogScrubber#xfoSummary(List)}.
     * Assert: result contains neither CR nor LF; result starts with {@code "xfo-values="}.
     */
    @Test
    void xfoSummary_crlfPayload_doesNotPassThrough() {
        String result = LogScrubber.xfoSummary(List.of("foo\r\nbar"));
        assertThat(result).doesNotContain("\r");
        assertThat(result).doesNotContain("\n");
        assertThat(result).startsWith("xfo-values=");
    }

    /**
     * LST-T4-6: an ANSI escape sequence in a value does not pass through.
     *
     * <p>Arrange: list containing {@code "x"} where x is a plain safe value (the ANSI guard
     * is structural — since only numeric counts are emitted, no escape characters can appear).
     * Act: call {@link LogScrubber#xfoSummary(List)}.
     * Assert: result contains no ESC character (U+001B); result starts with {@code "xfo-values="}.
     */
    @Test
    void xfoSummary_ansiPayload_doesNotPassThrough() {
        String result = LogScrubber.xfoSummary(List.of("x[31mRED[0m"));
        assertThat(result).doesNotContain("");
        assertThat(result).startsWith("xfo-values=");
    }

    /**
     * LST-T4-7: a null element is counted in xfo-values but contributes 0 to xfo-totallen.
     *
     * <p>Arrange: {@code Arrays.asList(null, "DENY")} — 2 slots, 1 null + 1 of length 4.
     * Act: call {@link LogScrubber#xfoSummary(List)}.
     * Assert: returns {@code "xfo-values=2 xfo-totallen=4"}; no NPE thrown.
     *
     * <p>Per ADR-TD4-01: null elements count toward {@code xfo-values} (slot count) and
     * contribute 0 to {@code xfo-totallen} (length).
     */
    @Test
    void xfoSummary_nullElement_countedZeroLength() {
        List<String> listWithNull = Arrays.asList(null, "DENY");
        String result = LogScrubber.xfoSummary(listWithNull);
        assertThat(result).isEqualTo("xfo-values=2 xfo-totallen=4");
    }

    /**
     * LST-T4-8: property-based fuzz test — for any list of strings, the summary must never
     * contain control bytes or Unicode line/paragraph separators.
     *
     * <p>Arrange: arbitrary list (up to 10 elements, each up to 200 characters).
     * Act: call {@link LogScrubber#xfoSummary(List)}.
     * Assert:
     * <ol>
     *   <li>Result starts with {@code "xfo-values="}.</li>
     *   <li>No codepoint in the result is below U+0020 (except space U+0020 is allowed because
     *       it appears in the format {@code "xfo-values=N xfo-totallen=M"} between the two fields).</li>
     *   <li>Result contains none of the Unicode line/paragraph control characters:
     *       U+2028, U+2029, U+202E, or U+FEFF.</li>
     * </ol>
     */
    @Property
    void xfoSummary_propertyTest_neverContainsControlBytes(
            @ForAll @Size(max = 10) List<@StringLength(max = 200) String> values) {
        String result = LogScrubber.xfoSummary(values);

        // Invariant 1: result always starts with the expected prefix
        assertThat(result)
                .as("xfoSummary result must always start with 'xfo-values='")
                .startsWith("xfo-values=");

        // Invariant 2: no codepoint below 0x20 (control characters, except space 0x20 is OK)
        result.codePoints().forEach(cp ->
                assertThat(cp)
                        .as("xfoSummary result must not contain control characters below 0x20 (codepoint: %d)", cp)
                        .isGreaterThanOrEqualTo(0x20));

        // Invariant 3: no Unicode line/paragraph separators or directional overrides
        assertThat(result).doesNotContain(" ");  // LINE SEPARATOR
        assertThat(result).doesNotContain(" ");  // PARAGRAPH SEPARATOR
        assertThat(result).doesNotContain("‮");  // RIGHT-TO-LEFT OVERRIDE
        assertThat(result).doesNotContain("﻿");  // BOM / ZERO-WIDTH NO-BREAK SPACE
    }

    // -------------------------------------------------------------------------
    // TD-5: xfoSummary long totalLen — CWE-117 / SR-TD5-03
    // LST-T5-1: overflow canary — logical sum > 2 GiB must produce a positive totallen
    // LST-T5-2: property-based invariants for the long-based implementation
    // -------------------------------------------------------------------------

    /**
     * LST-T5-1 — Overflow canary: a flyweight list whose logical total length exceeds
     * {@link Integer#MAX_VALUE} must not produce a negative {@code xfo-totallen} value.
     *
     * <p>SR-TD5-03: before the fix, {@code int totalLen} overflowed for lists whose
     * total character count exceeded 2^31-1, producing a negative value in the log output.
     * After the fix, {@code long totalLen} accumulates without overflow.
     *
     * <p>Arrange: {@code Collections.nCopies(2049, "x".repeat(1_048_576))} — a flyweight
     *             list with 2 049 logical elements, each of 1 MiB (1 048 576 chars).
     *             Total: 2 049 × 1 048 576 = 2 148 532 224 characters (> 2 GiB, which
     *             overflows {@code int} but fits in {@code long}).
     *             {@link Collections#nCopies} does not materialise the strings in memory,
     *             so this does not cause an OOM in the Surefire forked JVM.
     * Act:     call {@link LogScrubber#xfoSummary(List)}.
     * Assert (SR-TD5-03):
     * <ol>
     *   <li>Result starts with {@code "xfo-values=2049 xfo-totallen="}.</li>
     *   <li>The totallen value extracted from the result equals
     *       {@code 2_148_532_224L} (2049 × 1 048 576) — positive and correct.</li>
     *   <li>Full expected result is {@code "xfo-values=2049 xfo-totallen=2148532224"}.</li>
     * </ol>
     *
     * <p><strong>RED state</strong>: this test FAILS before the TD-5-C fix because
     * {@code int totalLen += value.length()} wraps to a negative value at overflow, producing
     * a negative {@code xfo-totallen=} in the output.
     */
    @Test
    void xfoSummary_longOverflowCanary_totallenIsPositiveAndCorrect() {
        // Arrange: flyweight list — nCopies does not allocate 2 GiB of strings
        int count = 2049;
        int elementLength = 1_048_576; // 1 MiB per element
        List<String> flyweightList = Collections.nCopies(count, "x".repeat(elementLength));
        long expectedTotalLen = (long) count * elementLength; // 2_148_532_224L

        // Act
        String result = LogScrubber.xfoSummary(flyweightList);

        // Assert (SR-TD5-03): format is intact
        assertThat(result)
                .as("SR-TD5-03: output must start with 'xfo-values=2049 xfo-totallen='")
                .startsWith("xfo-values=2049 xfo-totallen=");

        // Assert (SR-TD5-03): full output equals the expected long value (no int overflow)
        assertThat(result)
                .as("SR-TD5-03: full output must be 'xfo-values=2049 xfo-totallen=2148532224'")
                .isEqualTo("xfo-values=" + count + " xfo-totallen=" + expectedTotalLen);

        // Explicit check: totallen must be positive (int overflow would yield negative)
        String[] parts = result.split("xfo-totallen=");
        long actualTotalLen = Long.parseLong(parts[1]);
        assertThat(actualTotalLen)
                .as("SR-TD5-03: totallen must be positive — negative value signals int overflow")
                .isPositive()
                .isEqualTo(expectedTotalLen);
    }

    /**
     * LST-T5-2 — Property-based fuzz test: for any {@link List}{@code <String>} input,
     * {@code xfoSummary} must satisfy the same structural invariants as LST-T4-8 —
     * now verified against the {@code long}-based implementation.
     *
     * <p>SR-TD5-03: widening {@code int totalLen} to {@code long} must not break the
     * existing safety properties that prevent CWE-117 log injection.
     *
     * <p>Arrange: arbitrary list (up to 10 elements, each up to 200 characters).
     * Act:     call {@link LogScrubber#xfoSummary(List)}.
     * Assert:
     * <ol>
     *   <li>Result starts with {@code "xfo-values="}.</li>
     *   <li>Result contains {@code "xfo-totallen="}.</li>
     *   <li>No codepoint in the result is below U+0020 (space is allowed — it appears
     *       in {@code "xfo-values=N xfo-totallen=M"} as a field separator).</li>
     *   <li>Result contains none of: U+2028 (LINE SEPARATOR), U+2029 (PARAGRAPH SEPARATOR),
     *       U+202E (RIGHT-TO-LEFT OVERRIDE), U+FEFF (BOM/ZERO-WIDTH NO-BREAK SPACE).</li>
     * </ol>
     */
    // --- Mutation-kill: xfoSummary guard (L228) ---

    /**
     * MUT-KILL L228 (RemoveConditional_EQUAL_ELSE / NonVoidMethodCall on the null check):
     * {@code null} must return the exact sentinel and must NOT throw.
     *
     * <p>Removing the {@code values == null} half of the guard makes the surviving condition
     * {@code values.isEmpty()}, which NPEs on null. Pinning the exact return value while a null
     * is passed kills that mutation (the mutant throws instead of returning).
     */
    @Test
    void xfoSummary_null_returnsExactSentinelWithoutThrowing() {
        assertThat(LogScrubber.xfoSummary(null)).isEqualTo("xfo-values=0 xfo-totallen=0");
    }

    /**
     * Complementary pin for the loop path: a populated list must be summarised by the loop
     * (not the early sentinel), producing the exact computed count and total length.
     */
    @Test
    void xfoSummary_populatedList_isComputedByLoop_notSentinel() {
        String result = LogScrubber.xfoSummary(List.of("AB", "CDE"));
        assertThat(result).isEqualTo("xfo-values=2 xfo-totallen=5");
    }

    // --- Mutation-kill: forErrorMessage (L259/L260/L261) ---

    /**
     * MUT-KILL L259 (NegateConditionals / RemoveConditional on {@code value == null}):
     * {@code null} returns exactly {@code "[scrubbed null]"}.
     */
    @Test
    void forErrorMessage_null_returnsScrubbedNull() {
        assertThat(LogScrubber.forErrorMessage(null)).isEqualTo("[scrubbed null]");
    }

    /**
     * MUT-KILL L260 (removed {@code isBlank} / RemoveConditional on the blank check):
     * a blank (whitespace-only) value returns exactly {@code "[scrubbed blank]"} — not the
     * len/hash form. If the blank branch is removed, a whitespace string would fall through to
     * {@code "[scrubbed len=3 hash=...]"}, failing this exact-equality assertion.
     */
    @Test
    void forErrorMessage_blank_returnsScrubbedBlank() {
        assertThat(LogScrubber.forErrorMessage("   ")).isEqualTo("[scrubbed blank]");
        assertThat(LogScrubber.forErrorMessage("")).isEqualTo("[scrubbed blank]");
    }

    /**
     * MUT-KILL L261 (removed {@code length} call / removed {@code hash8} call):
     * a non-blank value returns the exact {@code "[scrubbed len=N hash=XXXXXXXX]"} form,
     * pinning BOTH the exact character length and the exact hash8 prefix.
     *
     * <p>"super-secret-token" has length 18; the hash term must equal {@code hash8(value)}.
     * Removing the {@code length()} call changes {@code len=18}; removing the {@code hash8}
     * call changes the {@code hash=} term — either mutation breaks this exact-equality.
     */
    @Test
    void forErrorMessage_nonBlank_returnsExactLenAndHash() {
        String value = "super-secret-token";
        assertThat(value).hasSize(18); // sanity: pins the expected len term
        assertThat(LogScrubber.forErrorMessage(value))
                .isEqualTo("[scrubbed len=18 hash=" + LogScrubber.hash8(value) + "]");
    }

    /**
     * MUT-KILL L261 (removed {@code length} call) — second anchor with a different length so the
     * {@code len=N} term cannot be coincidentally satisfied by a constant/inlined value.
     */
    @Test
    void forErrorMessage_differentLength_pinsExactLenTerm() {
        assertThat(LogScrubber.forErrorMessage("abc"))
                .isEqualTo("[scrubbed len=3 hash=" + LogScrubber.hash8("abc") + "]");
        // and the raw value must never appear verbatim (D-13/SR-8)
        assertThat(LogScrubber.forErrorMessage("super-secret-token"))
                .doesNotContain("super-secret-token");
    }

    @Property
    void xfoSummary_longBased_propertyTest_neverContainsControlBytes(
            @ForAll @Size(max = 10) List<@StringLength(max = 200) String> values) {
        String result = LogScrubber.xfoSummary(values);

        // Invariant 1: result always starts with the expected prefix
        assertThat(result)
                .as("xfoSummary (long) result must always start with 'xfo-values='")
                .startsWith("xfo-values=");

        // Invariant 2: result always contains the totallen field
        assertThat(result)
                .as("xfoSummary (long) result must always contain 'xfo-totallen='")
                .contains("xfo-totallen=");

        // Invariant 3: no codepoint below 0x20 (control characters; space 0x20 is OK)
        result.codePoints().forEach(cp ->
                assertThat(cp)
                        .as("xfoSummary (long) result must not contain control characters below 0x20 (codepoint: %d)", cp)
                        .isGreaterThanOrEqualTo(0x20));

        // Invariant 4: no Unicode line/paragraph separators or directional overrides
        assertThat(result).doesNotContain("\u2028");  // LINE SEPARATOR
        assertThat(result).doesNotContain("\u2029");  // PARAGRAPH SEPARATOR
        assertThat(result).doesNotContain("\u202e");  // RIGHT-TO-LEFT OVERRIDE
        assertThat(result).doesNotContain("\ufeff");  // BOM / ZERO-WIDTH NO-BREAK SPACE
    }

}