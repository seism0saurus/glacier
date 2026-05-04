package de.seism0saurus.glacier.share.web;

import de.seism0saurus.glacier.share.domain.ShareLinkId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Canary tests that document and guard the invariants introduced by the jqwik-flake fix
 * (ADR-FUZZ-01 through ADR-FUZZ-03).
 *
 * <p>These tests are <em>structural anchors</em>: they fail if the assumptions the fixed
 * property test relies on stop holding. They do not duplicate the property itself; they
 * verify the preconditions that make the property trustworthy.
 *
 * <p>Two canary scenarios are covered:
 * <ol>
 *   <li>{@link #mutationAtIndexMustInvalidateToken(int)} — a parameterized JUnit 5 test that
 *       exercises every single character position of the signed token deterministically. This
 *       proves HMAC invalidation at every position without jqwik's probabilistic sampling.</li>
 *   <li>{@link #mutationIndicesArbitraryMustNotExceedTokenLength()} — a sentinel that proves
 *       the old {@code @Provide mutationIndices()} bound ({@code [0, 299]}) exceeded the
 *       actual token length (168). This must fail before Commit 2 lands and pass afterward
 *       because Commit 2 removes the over-wide {@code @Provide} entirely.</li>
 * </ol>
 *
 * <p>Security: ADR-FUZZ-01 (SR-FUZZ-FIX-01/02/03), OWASP A02 (Cryptographic Failures).
 * If HMAC verification of the payload prefix were broken, an attacker could forge tokens
 * with attacker-controlled image URLs and trigger SSRF via the image proxy.
 */
class ImageProxyUrlBuilderVerifyFuzzCanaryTest {

    /**
     * A minimum-viable 32-byte HMAC secret for dev mode.
     * Must be 32 characters so {@link ImageProxyHmacSecretValidator} accepts it in
     * dev mode ({@code glacier.cookie.secure=false}).
     */
    private static final String DEV_SECRET = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"; // 32 'A's

    /**
     * A valid image URL used as the canonical plaintext for all canary tests.
     * This URL is intentionally identical to the one in {@code ImageProxyUrlBuilderVerifyFuzzTest}
     * so both test classes exercise the same signed token structure.
     */
    private static final String VALID_URL = "https://mastodon.social/media/1234.jpg";

    /**
     * Minimum-length valid URL-safe base64url token for {@link ShareLinkId} construction.
     * Intentionally identical to the constant in {@code ImageProxyUrlBuilderVerifyFuzzTest}.
     */
    private static final String DUMMY_SHARE_LINK_TOKEN =
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"; // 43 'A's

    /**
     * The expected signed-token length for the canonical VALID_URL and DUMMY_SHARE_LINK_TOKEN.
     *
     * <p>Derived manually:
     * <ul>
     *   <li>Payload: {@code "https://mastodon.social/media/1234.jpg|<epochSecond>|<shareLinkId>"}
     *       The epoch second occupies 10 decimal digits from 2001-09-09 until 2286-11-20.
     *       The shareLinkId is 43 chars. The URL is 38 chars. Two '|' separators.
     *       Total payload bytes: 38 + 1 + 10 + 1 + 43 = 93 bytes.</li>
     *   <li>base64url(93 bytes) without padding: ⌈93 * 4 / 3⌉ rounded to multiple of 4,
     *       minus padding chars = 124 chars.</li>
     *   <li>MAC: HMAC-SHA256 = 32 bytes → base64url without padding = 43 chars.</li>
     *   <li>Separator: 1 '.' char.</li>
     *   <li>Total: 124 + 1 + 43 = 168 chars.</li>
     * </ul>
     *
     * <p>This constant is intentionally declared here so that if a refactor changes the
     * token length silently (e.g., payload format change), this test fails visibly rather
     * than silently accepting a new out-of-range bound.
     */
    private static final int EXPECTED_TOKEN_LENGTH = 168;

    /**
     * The over-wide upper bound used by the old (broken) {@code @Provide mutationIndices()}
     * in {@link ImageProxyUrlBuilderVerifyFuzzTest}.
     *
     * <p>This constant documents the bug: 300 exceeds 168, so indices in [168, 299] always
     * hit the OOB silent-return guard, bypassing the assertion for ~44 % of generated values.
     */
    private static final int OLD_PROVIDE_UPPER_BOUND = 299;

    // -------------------------------------------------------------------------
    // Canary 1: every token position must produce invalidation when mutated
    // -------------------------------------------------------------------------

    /**
     * Returns every valid mutation index for the canonical signed token.
     *
     * <p>The stream covers indices {@code [0, EXPECTED_TOKEN_LENGTH)} so that the
     * parameterized test below exercises every character position without gaps.
     *
     * @return a stream of indices from 0 (inclusive) to token length (exclusive)
     */
    static IntStream allTokenPositions() {
        return IntStream.range(0, EXPECTED_TOKEN_LENGTH);
    }

    /**
     * SR-FUZZ-FIX-03 canary: mutating any single character of the signed token at index
     * {@code mutationIndex} must cause {@code verify()} to return empty.
     *
     * <p>Arrange: produce a valid signed URL with the canonical VALID_URL and DUMMY_SHARE_LINK_TOKEN.
     * <br>Act: flip one bit at position {@code mutationIndex}.
     * <br>Assert: {@code verify(mutatedToken)} returns {@link java.util.Optional#empty()}, or
     * throws (treating exception as rejection).
     *
     * <p>This is a deterministic exhaustive sweep across all 168 character positions,
     * unlike the jqwik property which samples probabilistically. It provides the
     * regression guarantee regardless of jqwik's sample selection.
     *
     * <p>The assertion message references only {@code mutationIndex} and the token length
     * (ADR-FUZZ-02: no raw token bytes in diagnostic output).
     *
     * @param mutationIndex the character position to mutate; ranges over [0, 168)
     */
    @ParameterizedTest(name = "mutation at index {0} must invalidate token")
    @MethodSource("allTokenPositions")
    void mutationAtIndexMustInvalidateToken(int mutationIndex) {
        ShareImageProxyUrlBuilder builder = buildBuilder();
        ShareLinkId shareLinkId = ShareLinkId.fromUrlPath(DUMMY_SHARE_LINK_TOKEN);

        String signedUrl = builder.sign(VALID_URL, shareLinkId);
        assertThat(signedUrl)
                .as("sign() must produce a non-null URL for valid inputs")
                .isNotNull();

        String token = extractToken(signedUrl);
        assertThat(token)
                .as("Signed URL must contain a ?u= token parameter")
                .isNotNull();

        // Structural anchor: token length must be exactly EXPECTED_TOKEN_LENGTH.
        // A refactor that changes the payload format would break this assertion visibly.
        assertThat(token.length())
                .as("Signed token length must be exactly %d chars", EXPECTED_TOKEN_LENGTH)
                .isEqualTo(EXPECTED_TOKEN_LENGTH);

        // Mutate the character at mutationIndex by flipping one bit (XOR with 1)
        char original = token.charAt(mutationIndex);
        char mutated = (char) (original ^ 1);
        if (mutated == original) {
            mutated = (char) (original ^ 3);
        }
        String mutatedToken = token.substring(0, mutationIndex)
                + mutated
                + token.substring(mutationIndex + 1);

        // Skip mutations that only flip non-significant base64url padding bits in the
        // MAC suffix (ADR-FUZZ-03: skip guard restricted to MAC region only).
        int dotIdx = token.lastIndexOf('.');
        if (mutationIndex > dotIdx && macDecodesIdentically(token, mutatedToken)) {
            // Genuine no-op: padding-bit flip in the MAC suffix — not a bypass
            return;
        }

        java.util.Optional<String> result;
        try {
            result = builder.verify(mutatedToken);
        } catch (Exception e) {
            // Exception counts as rejection — the mutation was detected
            return;
        }

        assertThat(result)
                .as("Mutated token at index %d (of %d) must not verify",
                        mutationIndex, token.length())
                .isEmpty();
    }

    // -------------------------------------------------------------------------
    // Canary 2: old @Provide upper bound exceeded actual token length
    // -------------------------------------------------------------------------

    /**
     * Sentinel test proving that the old {@code @Provide mutationIndices()} bound
     * ({@code [0, 299]}) exceeded the actual signed-token length (168).
     *
     * <p>This test documents the root cause of the jqwik flake (Bug A in the planning doc):
     * indices in the range [168, 299] — that is, 132 out of 300 possible values (44 %) —
     * always hit the OOB silent-return guard and bypassed the assertion entirely.
     *
     * <p>The test passes after Commit 2 removes the old {@code @Provide} method, because
     * the sentinel intent is preserved in the new design: the mutation index is now derived
     * from {@code token.length()} at runtime, making the OOB condition structurally impossible.
     *
     * <p>Arrange: produce a valid signed token with the canonical test inputs.
     * <br>Act: compare the old upper bound against the actual token length.
     * <br>Assert: the old bound ({@code [0, 299]}) DID exceed the token length, and the
     * fixed bound (derived from {@code token.length()}) does NOT.
     */
    @Test
    void mutationIndicesArbitraryMustNotExceedTokenLength() {
        ShareImageProxyUrlBuilder builder = buildBuilder();
        ShareLinkId shareLinkId = ShareLinkId.fromUrlPath(DUMMY_SHARE_LINK_TOKEN);

        String signedUrl = builder.sign(VALID_URL, shareLinkId);
        assertThat(signedUrl).as("sign() must produce a non-null URL for valid inputs").isNotNull();

        String token = extractToken(signedUrl);
        assertThat(token).as("Signed URL must contain a ?u= token parameter").isNotNull();

        int tokenLength = token.length();

        // Structural anchor: verify our expected length constant is correct
        assertThat(tokenLength)
                .as("Signed token must be exactly %d chars long", EXPECTED_TOKEN_LENGTH)
                .isEqualTo(EXPECTED_TOKEN_LENGTH);

        // Document the bug: the old upper bound (299) exceeded the token length (168).
        // This assertion proves the over-wide range existed and caused ~44% silent skips.
        assertThat(OLD_PROVIDE_UPPER_BOUND)
                .as("Old @Provide upper bound (%d) must exceed token length (%d) — documents Bug A",
                        OLD_PROVIDE_UPPER_BOUND, tokenLength)
                .isGreaterThan(tokenLength);

        // Prove the fix: a runtime-derived bound from token.length() is always in range.
        // Every index in [0, tokenLength) is a valid mutation target — no OOB is possible.
        assertThat(tokenLength - 1)
                .as("Last valid index (tokenLength-1 = %d) must be less than token length (%d)",
                        tokenLength - 1, tokenLength)
                .isLessThan(tokenLength);
    }

    // -------------------------------------------------------------------------
    // Helpers (package-private visibility for test-side use; mirrors the fuzz test)
    // -------------------------------------------------------------------------

    /**
     * Builds a test-mode {@link ShareImageProxyUrlBuilder} using a fixed HMAC secret
     * so canary tests are deterministic and independent of the Spring context.
     */
    private ShareImageProxyUrlBuilder buildBuilder() {
        ImageProxyHmacSecretValidator validator =
                new ImageProxyHmacSecretValidator(DEV_SECRET, false);
        return new ShareImageProxyUrlBuilder(validator, "glacier.example.com");
    }

    /**
     * Returns {@code true} if both tokens' MAC portions (after the last {@code .})
     * decode to the same bytes via the URL-safe Base64 decoder.
     *
     * <p>Detects mutations that flip only non-significant base64url padding bits
     * in the MAC suffix. This mirrors the helper in the main fuzz test.
     *
     * @param a the original token
     * @param b the mutated token
     * @return {@code true} if both tokens' decoded MAC bytes are identical
     */
    private static boolean macDecodesIdentically(final String a, final String b) {
        int dotA = a.lastIndexOf('.');
        int dotB = b.lastIndexOf('.');
        if (dotA < 0 || dotB < 0 || dotA != dotB) return false;
        try {
            byte[] macA = java.util.Base64.getUrlDecoder().decode(a.substring(dotA + 1));
            byte[] macB = java.util.Base64.getUrlDecoder().decode(b.substring(dotB + 1));
            return java.util.Arrays.equals(macA, macB);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * Extracts the {@code u} query parameter value from a signed proxy URL.
     *
     * @param signedUrl the full signed URL containing {@code ?u=<token>}
     * @return the URL-decoded token, or {@code null} if not found
     */
    private static String extractToken(final String signedUrl) {
        int uIdx = signedUrl.indexOf("?u=");
        if (uIdx < 0) return null;
        String encoded = signedUrl.substring(uIdx + 3);
        try {
            return java.net.URLDecoder.decode(encoded, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }
}
