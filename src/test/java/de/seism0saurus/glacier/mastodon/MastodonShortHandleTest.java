package de.seism0saurus.glacier.mastodon;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link MastodonShortHandle} value object.
 *
 * <p>Verifies the full validation contract of {@link MastodonShortHandle#parse(String)},
 * including the new rejection cases that extend the legacy {@code getShortHandle} behaviour:
 * multiple internal {@code @}, control characters, Unicode directional overrides, and
 * total-length cap.
 *
 * <p>Each test follows Arrange / Act / Assert structure as documented inline.
 *
 * <p>Security controls covered: SR-P3A-06 (parse rejection set is strict superset of
 * {@code getShortHandle}), SR-P3A-13 (control-character exclusion), ADR-P3A-2 (factory pattern).
 */
class MastodonShortHandleTest {

    // -------------------------------------------------------------------------
    // Happy-path parsing
    // -------------------------------------------------------------------------

    /**
     * Arrange: a valid handle without a leading {@code @}.
     * Act: parse.
     * Assert: all three record components are populated correctly with no leading {@code @}.
     */
    @Test
    void parse_validHandleNoLeadingAt_parsesCorrectly() {
        MastodonShortHandle handle = MastodonShortHandle.parse("glacier@instance.social");

        assertThat(handle.localPart()).isEqualTo("glacier");
        assertThat(handle.server()).isEqualTo("instance.social");
        assertThat(handle.full()).isEqualTo("glacier@instance.social");
    }

    /**
     * Arrange: a valid handle WITH a leading {@code @}.
     * Act: parse.
     * Assert: the leading {@code @} is stripped; the record equals the no-leading-at form.
     */
    @Test
    void parse_validHandleWithLeadingAt_stripsAt() {
        MastodonShortHandle withAt = MastodonShortHandle.parse("@glacier@instance.social");
        MastodonShortHandle withoutAt = MastodonShortHandle.parse("glacier@instance.social");

        assertThat(withAt.full()).isEqualTo("glacier@instance.social");
        assertThat(withAt).isEqualTo(withoutAt);
    }

    // -------------------------------------------------------------------------
    // Null / blank rejections
    // -------------------------------------------------------------------------

    /**
     * Arrange: null input.
     * Act: parse.
     * Assert: {@link NullPointerException} is thrown (not IAE) so callers can distinguish
     *         "missing" from "invalid" at startup.
     */
    @Test
    void parse_null_throwsNpe() {
        assertThrows(NullPointerException.class, () -> MastodonShortHandle.parse(null));
    }

    /**
     * Arrange: empty string.
     * Act: parse.
     * Assert: {@link IllegalArgumentException} is thrown.
     */
    @Test
    void parse_blank_throwsIae() {
        assertThrows(IllegalArgumentException.class, () -> MastodonShortHandle.parse(""));
    }

    /**
     * Arrange: whitespace-only string.
     * Act: parse.
     * Assert: {@link IllegalArgumentException} is thrown.
     */
    @Test
    void parse_whitespaceOnly_throwsIae() {
        assertThrows(IllegalArgumentException.class, () -> MastodonShortHandle.parse("   "));
    }

    // -------------------------------------------------------------------------
    // Structural rejections (no internal @)
    // -------------------------------------------------------------------------

    /**
     * Arrange: a string without any {@code @} separator.
     * Act: parse.
     * Assert: {@link IllegalArgumentException} is thrown — no server part present.
     */
    @Test
    void parse_noInternalAt_throwsIae() {
        assertThrows(IllegalArgumentException.class, () -> MastodonShortHandle.parse("glacierinstance"));
    }

    /**
     * Arrange: single {@code @} only.
     * Act: parse.
     * Assert: {@link IllegalArgumentException} is thrown — both parts are empty.
     */
    @Test
    void parse_singleAtOnly_throwsIae() {
        assertThrows(IllegalArgumentException.class, () -> MastodonShortHandle.parse("@"));
    }

    /**
     * Arrange: {@code "@instance.social"} — after stripping the optional leading {@code @},
     *          the local part becomes the empty string.
     * Act: parse.
     * Assert: {@link IllegalArgumentException} is thrown.
     */
    @Test
    void parse_emptyLocalPart_throwsIae() {
        assertThrows(IllegalArgumentException.class, () -> MastodonShortHandle.parse("@instance.social"));
    }

    /**
     * Arrange: {@code "@@instance.social"} — leading {@code @} stripped, then local part is empty.
     * Act: parse.
     * Assert: {@link IllegalArgumentException} is thrown.
     */
    @Test
    void parse_emptyLocalPartDoubleAt_throwsIae() {
        assertThrows(IllegalArgumentException.class, () -> MastodonShortHandle.parse("@@instance.social"));
    }

    /**
     * Arrange: {@code "glacier@one@two"} — three segments after splitting on {@code @}.
     *          The old {@code getShortHandle} silently truncated at the first {@code @};
     *          {@code parse()} must reject this (SR-P3A-06, strict superset of legacy behaviour).
     * Act: parse.
     * Assert: {@link IllegalArgumentException} is thrown.
     */
    @Test
    void parse_multipleInternalAt_throwsIae() {
        assertThrows(IllegalArgumentException.class, () -> MastodonShortHandle.parse("glacier@one@two"));
    }

    // -------------------------------------------------------------------------
    // Control-character rejections (NEW — SR-P3A-13)
    // -------------------------------------------------------------------------

    /**
     * Arrange: handle containing CR and LF in the local part.
     * Act: parse.
     * Assert: {@link IllegalArgumentException} is thrown — log-injection vector.
     */
    @Test
    void parse_crlfInLocalPart_throwsIae() {
        assertThrows(IllegalArgumentException.class, () -> MastodonShortHandle.parse("gla\r\ncier@instance"));
    }

    /**
     * Arrange: handle containing a horizontal tab.
     * Act: parse.
     * Assert: {@link IllegalArgumentException} is thrown.
     */
    @Test
    void parse_tabInHandle_throwsIae() {
        assertThrows(IllegalArgumentException.class, () -> MastodonShortHandle.parse("glacier\t@instance"));
    }

    /**
     * Arrange: handle containing U+202E RIGHT-TO-LEFT OVERRIDE (visual spoofing vector).
     * Act: parse.
     * Assert: {@link IllegalArgumentException} is thrown.
     */
    @Test
    void parse_rtlOverrideInHandle_throwsIae() {
        // U+202E = RIGHT-TO-LEFT OVERRIDE
        assertThrows(IllegalArgumentException.class, () -> MastodonShortHandle.parse("glac‮ier@instance"));
    }

    /**
     * Arrange: handle containing U+FEFF BYTE ORDER MARK.
     * Act: parse.
     * Assert: {@link IllegalArgumentException} is thrown.
     */
    @Test
    void parse_bomInHandle_throwsIae() {
        // U+FEFF = BYTE ORDER MARK / ZERO WIDTH NO-BREAK SPACE
        assertThrows(IllegalArgumentException.class, () -> MastodonShortHandle.parse("glacier﻿@instance"));
    }

    // -------------------------------------------------------------------------
    // Length cap (NEW — SR-P3A-13)
    // -------------------------------------------------------------------------

    /**
     * Arrange: a handle whose total length exceeds 254 characters.
     * Act: parse.
     * Assert: {@link IllegalArgumentException} is thrown.
     */
    @Test
    void parse_exceeds254Chars_throwsIae() {
        // local part 100 chars + "@" + server 200 chars = 301 chars total
        String tooLong = "a".repeat(100) + "@" + "b".repeat(200);
        assertThat(tooLong.length()).isGreaterThan(254);
        assertThrows(IllegalArgumentException.class, () -> MastodonShortHandle.parse(tooLong));
    }

    // -------------------------------------------------------------------------
    // equals / hashCode contract
    // -------------------------------------------------------------------------

    /**
     * Arrange: two {@link MastodonShortHandle} instances created from the same canonical handle.
     * Act: compare with {@code equals}.
     * Assert: they are equal (record equality by components).
     */
    @Test
    void equals_sameFullHandle_equal() {
        MastodonShortHandle a = MastodonShortHandle.parse("glacier@instance.social");
        MastodonShortHandle b = MastodonShortHandle.parse("glacier@instance.social");

        assertThat(a).isEqualTo(b);
    }

    /**
     * Arrange: two {@link MastodonShortHandle} instances created from the same canonical handle.
     * Act: compare hash codes.
     * Assert: they are equal (consistent with {@code equals} contract).
     */
    @Test
    void hashCode_sameFullHandle_equal() {
        MastodonShortHandle a = MastodonShortHandle.parse("glacier@instance.social");
        MastodonShortHandle b = MastodonShortHandle.parse("glacier@instance.social");

        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    // -------------------------------------------------------------------------
    // toString — must NOT expose server or full handle (D-13 / SR-8)
    // -------------------------------------------------------------------------

    /**
     * Arrange: a parsed handle.
     * Act: call {@code toString()}.
     * Assert: the server part does not appear in the output — prevents log leakage of server.
     */
    @Test
    void toString_doesNotContainServer() {
        MastodonShortHandle handle = MastodonShortHandle.parse("glacier@instance.social");

        assertThat(handle.toString()).doesNotContain("instance.social");
    }

    /**
     * Arrange: a parsed handle.
     * Act: call {@code toString()}.
     * Assert: the full handle string (with {@code @}) does not appear verbatim — prevents
     *         accidental log disclosure of the full identity.
     */
    @Test
    void toString_doesNotContainFull() {
        MastodonShortHandle handle = MastodonShortHandle.parse("glacier@instance.social");

        assertThat(handle.toString()).doesNotContain("glacier@instance.social");
    }
}
