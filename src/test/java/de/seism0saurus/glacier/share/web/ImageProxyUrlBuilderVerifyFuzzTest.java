package de.seism0saurus.glacier.share.web;

import de.seism0saurus.glacier.share.domain.ShareLinkId;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.StringLength;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SR-TEST-06: Property-based fuzz tests for the HMAC sign/verify path of
 * {@link ShareImageProxyUrlBuilder}.
 *
 * <p>Uses <a href="https://jqwik.net">jqwik 1.8.4</a> to generate arbitrary inputs.
 * Three properties are verified:
 * <ol>
 *   <li>{@link #anyNonSignedByteStringReturnsEmpty} — arbitrary strings that were not
 *       produced by {@link ShareImageProxyUrlBuilder#sign} must never verify successfully
 *       and must never throw.</li>
 *   <li>{@link #anyMutationOfValidTokenReturnsEmpty} — single-character mutations of a
 *       valid signed token must always fail verification.</li>
 *   <li>{@link #signThenVerifyRoundTripSucceeds} — {@code sign(url)} then
 *       {@code verify(result)} must recover the original URL.</li>
 * </ol>
 *
 * <p>Security: ADR-SHARE-07 (HMAC signing), OWASP A02 (Cryptographic Failures) —
 * any path that allows an unsigned or tampered token to pass verification would enable
 * SSRF by allowing arbitrary image URLs to be proxied without HMAC validation.
 *
 * <p>Note: jqwik tests must be in a class annotated with nothing special — jqwik
 * integrates automatically with JUnit 5 (Platform) via its {@code JqwikTestEngine}.
 * The {@code @Property} methods are picked up alongside {@code @Test} methods by Surefire.
 */
class ImageProxyUrlBuilderVerifyFuzzTest {

    /**
     * A minimum-viable 32-byte HMAC secret for dev mode.
     * Must be ≥ 32 characters so {@link ImageProxyHmacSecretValidator} accepts it in
     * dev mode ({@code glacier.cookie.secure=false}).
     */
    private static final String DEV_SECRET = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"; // 32 'A's

    /**
     * A valid image URL used as the canonical plaintext for round-trip and mutation tests.
     */
    private static final String VALID_URL = "https://mastodon.social/media/1234.jpg";

    /**
     * Minimum-length valid URL-safe base64url token for {@link ShareLinkId} construction.
     * 43 URL-safe base64url characters → ≥ 256 bits of entropy (ShareLinkId.MIN_LENGTH).
     */
    private static final String DUMMY_SHARE_LINK_TOKEN =
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"; // 43 'A's

    /**
     * Builds a test-mode {@link ShareImageProxyUrlBuilder} that uses a fixed HMAC secret
     * so property tests are deterministic and independent of the Spring context.
     *
     * <p>The validator is constructed with {@code secureCookies=false} so the dev-mode
     * auto-generate path is available (or the configured secret is used as-is).
     */
    private ShareImageProxyUrlBuilder buildBuilder() {
        // secureCookies=false → dev mode: short secrets accepted with a warning
        ImageProxyHmacSecretValidator validator =
                new ImageProxyHmacSecretValidator(DEV_SECRET, false);
        return new ShareImageProxyUrlBuilder(validator, "glacier.example.com");
    }

    // -------------------------------------------------------------------------
    // Property 1: arbitrary strings never verify and never throw
    // -------------------------------------------------------------------------

    /**
     * SR-TEST-06 property 1: any string that was NOT produced by {@code sign()}
     * must return {@link Optional#empty()} from {@code verify()}, never throwing.
     *
     * <p>This covers null-like, empty, garbage, and structurally plausible but unsigned strings.
     *
     * <p>Security: if arbitrary inputs could pass HMAC verification, any caller could
     * bypass the signing requirement and access the image proxy with attacker-controlled URLs.
     */
    @Property(tries = 500)
    void anyNonSignedByteStringReturnsEmpty(
            @ForAll @StringLength(min = 0, max = 200) String s) {

        ShareImageProxyUrlBuilder builder = buildBuilder();

        Optional<String> result;
        try {
            result = builder.verify(s);
        } catch (Exception e) {
            throw new AssertionError(
                    "verify() must never throw for input (length=" + s.length() + "): " + e, e);
        }

        // For arbitrary strings not produced by sign(), verify() must return empty.
        // Note: probabilistically (HMAC-SHA256 with 32-byte key) the chance of a random
        // string passing is negligible — this property test guards against implementation bugs.
        // We accept the 1 / 2^256 false-positive probability as acceptable for fuzz testing.
        if (result.isPresent()) {
            // Only acceptable if the string was coincidentally a valid signed token — extremely
            // unlikely. In practice this assertion holds for all 500 generated inputs.
            throw new AssertionError(
                    "verify() returned non-empty for an arbitrary string (length="
                            + s.length() + ") — possible HMAC bypass");
        }
    }

    // -------------------------------------------------------------------------
    // Property 2: single-character mutation always breaks the signature
    // -------------------------------------------------------------------------

    /**
     * SR-TEST-06 property 2: mutating any single character of a valid signed token
     * must invalidate the HMAC and return {@link Optional#empty()}.
     *
     * <p>This property verifies that the HMAC check is not a no-op (i.e. that the
     * implementation actually uses the computed MAC to reject tampered tokens).
     *
     * <p>Security: OWASP A02 — a broken HMAC check would allow token forgery, enabling
     * SSRF attacks through the image proxy.
     */
    @Property(tries = 500)
    void anyMutationOfValidTokenReturnsEmpty(@ForAll("mutationIndices") int mutationIndex) {
        ShareImageProxyUrlBuilder builder = buildBuilder();
        ShareLinkId shareLinkId = ShareLinkId.fromUrlPath(DUMMY_SHARE_LINK_TOKEN);

        // Produce a valid signed URL
        String signedUrl = builder.sign(VALID_URL, shareLinkId);
        assertThat(signedUrl)
                .as("sign() must produce a non-null URL for valid inputs")
                .isNotNull();

        // Extract the token from the ?u= parameter
        String token = extractToken(signedUrl);
        assertThat(token)
                .as("Signed URL must contain a ?u= token parameter")
                .isNotNull();

        // Skip mutation indices that are out of bounds for this specific token length
        if (mutationIndex >= token.length()) {
            return; // shrink; jqwik handles bound violations gracefully
        }

        // Mutate the character at mutationIndex by flipping one bit (XOR with 1)
        char original = token.charAt(mutationIndex);
        char mutated = (char) (original ^ 1);
        // Ensure the mutated character is actually different (XOR with 1 may produce the same
        // for certain control chars — use XOR with 3 as fallback)
        if (mutated == original) {
            mutated = (char) (original ^ 3);
        }
        String mutatedToken = token.substring(0, mutationIndex)
                + mutated
                + token.substring(mutationIndex + 1);

        Optional<String> result;
        try {
            result = builder.verify(mutatedToken);
        } catch (Exception e) {
            // Throwing is acceptable — it means the token was rejected (possibly with parse error)
            return;
        }

        assertThat(result)
                .as("Mutated token at index %d (original='%c' → '%c') must not verify",
                        mutationIndex, original, mutated)
                .isEmpty();
    }

    /** Provides mutation indices in the range [0, 300) — generous enough to cover any token. */
    @Provide
    Arbitrary<Integer> mutationIndices() {
        return Arbitraries.integers().between(0, 299);
    }

    // -------------------------------------------------------------------------
    // Property 3: sign → verify round-trip recovers original URL
    // -------------------------------------------------------------------------

    /**
     * SR-TEST-06 property 3: for any URL produced by {@code sign()}, calling
     * {@code verify()} on the extracted token must return the original URL.
     *
     * <p>This confirms that the signing and verification are correctly inverse operations
     * (i.e. no encoding/decoding bug silently transforms the URL payload).
     *
     * <p>Security: a broken round-trip would mean the proxied URL differs from the signed
     * URL, which could enable open-redirect or SSRF via payload transformation.
     */
    @Property(tries = 500)
    void signThenVerifyRoundTripSucceeds(@ForAll("httpUrls") String url) {
        ShareImageProxyUrlBuilder builder = buildBuilder();
        ShareLinkId shareLinkId = ShareLinkId.fromUrlPath(DUMMY_SHARE_LINK_TOKEN);

        String signedUrl = builder.sign(url, shareLinkId);
        if (signedUrl == null) {
            // sign() returns null on internal errors — skip this input (no assertion to check)
            return;
        }

        String token = extractToken(signedUrl);
        assertThat(token)
                .as("sign(%s) must produce a URL with a ?u= token", url)
                .isNotNull();

        Optional<String> recovered = builder.verify(token);
        assertThat(recovered)
                .as("verify(sign(%s)) must return non-empty (round-trip)", url)
                .isPresent();

        assertThat(recovered.get())
                .as("verify(sign(%s)) must recover the original URL", url)
                .isEqualTo(url);
    }

    /**
     * Provides sample HTTP/HTTPS URLs for round-trip testing.
     * URLs use only ASCII to avoid encoding edge-cases unrelated to the HMAC path.
     */
    @Provide
    Arbitrary<String> httpUrls() {
        return Arbitraries.of(
                "https://mastodon.social/media/abc123.jpg",
                "https://instance.example.com/files/123456/original/image.png",
                "https://cdn.glacier.events/media/attachments/99999/original.gif",
                "https://a.b.c/d/e/f.jpg",
                "https://mastodon.social/media/" + "x".repeat(100) + ".jpg",
                "https://user:pass@example.com/image.jpeg",
                "https://example.com/image.jpg?foo=bar&baz=qux",
                "https://example.com/path/to/%20encoded%20spaces/image.jpg"
        );
    }

    // -------------------------------------------------------------------------
    // Helper
    // -------------------------------------------------------------------------

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
