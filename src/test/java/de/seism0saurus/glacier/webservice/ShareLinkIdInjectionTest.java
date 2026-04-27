package de.seism0saurus.glacier.webservice;

import de.seism0saurus.glacier.share.domain.ShareLinkId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Input injection tests for share-link identifiers.
 *
 * <p>Security requirement SR-TEST-17 (OWASP A03 — Injection):
 * {@link ShareLinkId#fromUrlPath(String)} must reject all malicious or malformed
 * input patterns before they can reach the application layer:
 * <ul>
 *   <li>Path traversal segments (e.g. {@code ../../../})</li>
 *   <li>Characters outside the URL-safe Base64 alphabet ({@code A-Z a-z 0-9 - _})</li>
 *   <li>Strings shorter than the 43-character minimum (insufficient entropy)</li>
 *   <li>Null and blank strings</li>
 * </ul>
 *
 * <p>Valid IDs are URL-safe Base64url strings of at least 43 characters
 * (≥ 256 bits of entropy). The controller routes malformed IDs to 404
 * (anti-enumeration) via the {@link IllegalArgumentException} thrown here.
 *
 * @see ShareLinkId
 * @see de.seism0saurus.glacier.share.web.ShareLinkController
 */
class ShareLinkIdInjectionTest {

    // A valid 43-char URL-safe Base64url token (minimum entropy requirement)
    private static final String VALID_TOKEN = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"; // 43 As

    // ---------------------------------------------------------------------------
    // Rejection: path traversal (contains '/' or '.')
    // ---------------------------------------------------------------------------

    @ParameterizedTest(name = "pathTraversal [{0}] rejected")
    @ValueSource(strings = {
            "../../../etc/passwd",
            "../../secret",
            "foo/../bar",
            "foo/bar",
            "/absolute/path"
    })
    void pathTraversalSegments_areRejected(String id) {
        // ShareLinkId only accepts [A-Za-z0-9_-] — '/' and '.' are not in the alphabet
        assertThatThrownBy(() -> ShareLinkId.fromUrlPath(id))
                .as("Path traversal input [%s] must be rejected by ShareLinkId.fromUrlPath()", id)
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---------------------------------------------------------------------------
    // Rejection: string too short (below 43-char minimum entropy requirement)
    // ---------------------------------------------------------------------------

    @ParameterizedTest(name = "shortId [{0}] rejected")
    @ValueSource(strings = {
            "../../../etc/passwd",
            "abc123",
            "short",
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"  // 41 chars — just below minimum
    })
    void shortIds_areRejected(String id) {
        // Note: some of these also fail the alphabet check — both are valid rejection reasons
        assertThatThrownBy(() -> ShareLinkId.fromUrlPath(id))
                .as("Too-short input [%s] must be rejected (below 43-char minimum)", id)
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---------------------------------------------------------------------------
    // Rejection: NUL byte and control characters (not in URL-safe Base64 alphabet)
    // ---------------------------------------------------------------------------

    @Test
    void nulByte_isRejected() {
        // NUL byte is not in [A-Za-z0-9_-]
        String idWithNul = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" + '\0';
        assertThatThrownBy(() -> ShareLinkId.fromUrlPath(idWithNul))
                .as("NUL byte in share-link ID must be rejected")
                .isInstanceOf(IllegalArgumentException.class);
    }

    static Stream<String> controlCharInputs() {
        // Build 43-char strings containing control characters — invalid alphabet
        String prefix = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"; // 43 chars
        return Stream.of(
                prefix.substring(0, 42) + (char) 0x01,  // SOH embedded at end
                prefix.substring(0, 42) + '\n',           // LF
                prefix.substring(0, 42) + '\r',           // CR
                prefix.substring(0, 42) + '\t',           // TAB
                prefix.substring(0, 42) + (char) 0x7F    // DEL
        );
    }

    @ParameterizedTest(name = "controlChar in [{0}] rejected")
    @MethodSource("controlCharInputs")
    void asciiControlCharacters_areRejected(String id) {
        assertThatThrownBy(() -> ShareLinkId.fromUrlPath(id))
                .as("ASCII control character must be rejected (not in URL-safe Base64 alphabet)")
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---------------------------------------------------------------------------
    // Rejection: SQL and shell injection characters (not in URL-safe Base64 alphabet)
    // ---------------------------------------------------------------------------

    @ParameterizedTest(name = "injectionPattern [{0}] rejected")
    @ValueSource(strings = {
            "; rm -rf /",
            "$(whoami)",
            "`id`",
            "' OR '1'='1",
            "\" OR \"1\"=\"1",
            "& echo hello"
    })
    void injectionPatterns_areRejected(String id) {
        assertThatThrownBy(() -> ShareLinkId.fromUrlPath(id))
                .as("Injection pattern [%s] must be rejected", id)
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---------------------------------------------------------------------------
    // Rejection: null and blank
    // ---------------------------------------------------------------------------

    @Test
    void nullId_isRejected() {
        assertThatThrownBy(() -> ShareLinkId.fromUrlPath(null))
                .as("null share-link ID must be rejected")
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void emptyId_isRejected() {
        assertThatThrownBy(() -> ShareLinkId.fromUrlPath(""))
                .as("empty share-link ID must be rejected")
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void blankId_isRejected() {
        assertThatThrownBy(() -> ShareLinkId.fromUrlPath("   "))
                .as("blank share-link ID must be rejected")
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---------------------------------------------------------------------------
    // Acceptance: valid URL-safe Base64url IDs of sufficient length
    // ---------------------------------------------------------------------------

    @Test
    void validToken_43Chars_isAccepted() {
        // 43 URL-safe Base64url chars = 256 bits of entropy — the minimum
        ShareLinkId id = ShareLinkId.fromUrlPath(VALID_TOKEN);
        assertThat(id).isNotNull();
        assertThat(id.value()).isEqualTo(VALID_TOKEN);
    }

    @Test
    void validToken_longerThan43Chars_isAccepted() {
        // Longer tokens are also valid (e.g., 64-char tokens)
        String longToken = "A".repeat(64);
        ShareLinkId id = ShareLinkId.fromUrlPath(longToken);
        assertThat(id).isNotNull();
        assertThat(id.value()).isEqualTo(longToken);
    }

    @Test
    void validToken_withHyphensAndUnderscores_isAccepted() {
        // Hyphens and underscores are valid in URL-safe Base64url (RFC 4648 §5)
        String tokenWithSpecials = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA".substring(0, 41)
                + "-_"; // 43 chars total
        ShareLinkId id = ShareLinkId.fromUrlPath(tokenWithSpecials);
        assertThat(id).isNotNull();
    }
}
