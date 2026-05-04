package de.seism0saurus.glacier.share.web;

import de.seism0saurus.glacier.share.domain.ShareLinkId;
import de.seism0saurus.glacier.util.LogScrubber;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.StringLength;
import net.jqwik.api.statistics.Statistics;

import java.util.Arrays;
import java.util.Base64;
import java.util.Optional;
import java.util.Random;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.stream.Stream;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
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
                    "verify() must never throw for input (length=" + s.length() + "): " + e.getClass().getSimpleName(), e);
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
     * <p>ADR-FUZZ-01: the mutation index is derived from {@link Random#nextInt(int)} bounded
     * to {@code token.length()} at runtime. This removes the original Bug A (over-wide
     * {@code @Provide} range [0, 299]) that silently skipped ~44 % of tries via an OOB return.
     *
     * <p>ADR-FUZZ-03: the {@link #macDecodesIdentically} guard is restricted to mutations in
     * the MAC suffix ({@code mutationIndex > dotIdx}). For payload-prefix mutations the MAC
     * is byte-identical by definition (the MAC was not touched), so calling the guard there
     * would falsely skip every payload-prefix position — the original Bug B.
     *
     * <p>ADR-FUZZ-05: {@code Statistics.coverage} gates enforce that at least 80 % of tries
     * produce a real assertion (ASSERTED) and at least 50 % exercise the payload-prefix region
     * (ASSERTED_PAYLOAD). A future regression that re-introduces a skip guard broad enough to
     * collapse the asserting-tries ratio will fail the build here rather than shipping silently.
     *
     * <p>ADR-FUZZ-02: assertion messages reference only {@code mutationIndex},
     * {@code token.length()}, and {@code LogScrubber.hash8(token)} — never raw token bytes.
     *
     * <p>Security: OWASP A02 — a broken HMAC check would allow token forgery, enabling
     * SSRF attacks through the image proxy.
     *
     * @param random jqwik-provided {@link Random} instance used to derive a bounded,
     *               shrinkable mutation index from the actual token length at runtime
     */
    @Property(tries = 500)
    void anyMutationOfValidTokenReturnsEmpty(@ForAll Random random) {
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

        // ADR-FUZZ-01: derive mutation index from the actual token length.
        // random.nextInt(token.length()) is always in [0, token.length()) — OOB is structurally
        // impossible. An out-of-range result here would indicate a test-infrastructure bug.
        int mutationIndex = random.nextInt(token.length());
        if (mutationIndex < 0 || mutationIndex >= token.length()) {
            throw new AssertionError(
                    "test bug: mutationIndex " + mutationIndex + " out of range; "
                    + "token-hash=" + LogScrubber.hash8(token)
                    + " length=" + token.length());
        }

        // Locate the dot separator so we can restrict the MAC-skip guard to the MAC suffix
        int dotIdx = token.lastIndexOf('.');

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

        // ADR-FUZZ-03: restrict the base64url padding-bit no-op skip to MAC-suffix mutations.
        // A 32-byte HMAC encodes to 43 base64url chars; the last char uses only 4 of its 6 bits.
        // Flipping a padding bit produces a different char that Java's lenient Base64 decoder maps
        // to the same bytes, so verify() legitimately returns non-empty. That edge case can only
        // occur in the MAC suffix — applying the skip to payload-prefix mutations is a false skip
        // because the MAC is byte-identical there by construction (not by padding-bit coincidence).
        if (mutationIndex > dotIdx && macDecodesIdentically(token, mutatedToken)) {
            Statistics.label("mutationOutcome").collect("SKIPPED_BASE64_PADDING_NOOP");
            return;
        }

        Optional<String> result;
        try {
            result = builder.verify(mutatedToken);
        } catch (Exception e) {
            // Throwing is acceptable — it means the token was rejected (possibly with parse error).
            // Classify by region so the coverage gate counts this as an asserted outcome.
            Statistics.label("mutationOutcome").collect(classifyMutationRegion(mutationIndex, dotIdx));
            Statistics.label("mutationOutcome").coverage(c -> {
                c.checkPattern("ASSERTED.*").percentage(p -> p >= 80.0);
                c.check("ASSERTED_PAYLOAD").percentage(p -> p >= 50.0);
            });
            return;
        }

        assertThat(result)
                .as("Mutated token at index %d (of %d, token-hash=%s) must not verify",
                        mutationIndex, token.length(), LogScrubber.hash8(token))
                .isEmpty();

        // Classify the mutation region for the coverage gate (ADR-FUZZ-05).
        Statistics.label("mutationOutcome").collect(classifyMutationRegion(mutationIndex, dotIdx));

        // ADR-FUZZ-05: hard coverage gates — fail the property if skip rate is too high.
        // "ASSERTED" means any of the three asserting outcomes; at least 80 % of tries must
        // produce a real assertion. "ASSERTED_PAYLOAD" must account for at least 50 % because
        // the payload prefix (URL, expiry, share-link ID) is the load-bearing HMAC region.
        Statistics.label("mutationOutcome").coverage(c -> {
            c.checkPattern("ASSERTED.*").percentage(p -> p >= 80.0);
            c.check("ASSERTED_PAYLOAD").percentage(p -> p >= 50.0);
        });
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
        assertThat(signedUrl)
                .as("sign(%s) must not return null", url)
                .isNotNull();

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
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Classifies a mutation index into one of the four outcome labels used by the
     * {@code Statistics.label("mutationOutcome")} coverage gate.
     *
     * <p>The signed token has the structure {@code base64url(payload) . base64url(mac)}.
     * Three disjoint regions exist:
     * <ul>
     *   <li>{@code ASSERTED_PAYLOAD} — index is in the base64url-encoded payload prefix
     *       ([0, dotIdx)). This is the load-bearing HMAC-authenticated region (URL, expiry,
     *       share-link ID).</li>
     *   <li>{@code ASSERTED_DOT} — index is the dot separator itself ({@code dotIdx}).
     *       Mutating the separator removes the structural boundary, causing a parse failure.</li>
     *   <li>{@code ASSERTED_MAC} — index is in the base64url-encoded MAC suffix
     *       ({@code dotIdx+1} onwards). Only mutations that do not flip non-significant
     *       padding bits reach this label (padding-bit flips are SKIPPED before this method).</li>
     * </ul>
     *
     * @param mutationIndex the character position that was mutated
     * @param dotIdx        the position of the last {@code '.'} in the token
     * @return one of {@code "ASSERTED_PAYLOAD"}, {@code "ASSERTED_DOT"}, {@code "ASSERTED_MAC"}
     */
    private static String classifyMutationRegion(int mutationIndex, int dotIdx) {
        if (mutationIndex < dotIdx) return "ASSERTED_PAYLOAD";
        if (mutationIndex == dotIdx) return "ASSERTED_DOT";
        return "ASSERTED_MAC";
    }

    /**
     * Returns {@code true} if both tokens' MAC portions (the suffix after the last {@code .})
     * decode to the same bytes via the URL-safe Base64 decoder.
     *
     * <p>This detects mutations that only flip non-significant base64url padding bits:
     * HMAC-SHA256 produces 32 bytes, which encodes to 43 base64url chars. The 43rd char
     * uses only 4 of its 6 bits; the remaining 2 are padding zeros. Java's lenient
     * {@link Base64#getUrlDecoder()} ignores those bits, so two chars that differ only in
     * padding bits decode to identical bytes. A mutation in those bits is semantically a
     * no-op and must be skipped rather than treated as a test failure.
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
            byte[] macA = Base64.getUrlDecoder().decode(a.substring(dotA + 1));
            byte[] macB = Base64.getUrlDecoder().decode(b.substring(dotB + 1));
            return Arrays.equals(macA, macB);
        } catch (IllegalArgumentException e) {
            return false; // malformed base64url → definitely different; proceed with assertion
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

    // -------------------------------------------------------------------------
    // Deterministic boundary tests (SR-FUZZ-FIX-05 / SR-FUZZ-FIX-07 / SR-FUZZ-FIX-08)
    // -------------------------------------------------------------------------

    /**
     * SR-FUZZ-FIX-08 / T-FUZZ-FIX-08: a properly HMAC-signed token whose {@code expiresAt}
     * timestamp is one second in the past must be rejected by {@link ShareImageProxyUrlBuilder#verify}.
     *
     * <p>The package-private {@link ShareImageProxyUrlBuilder#hmacSha256} is used directly
     * so the forged token carries a real HMAC and the test reaches the expiry branch —
     * not the HMAC-reject branch (ADR-FUZZ-04).
     */
    @Test
    void expiredTokenReturnsEmpty() throws Exception {
        ShareImageProxyUrlBuilder builder = buildBuilder();

        long expiredAt = Instant.now().getEpochSecond() - 1;
        String payload = VALID_URL + "|" + expiredAt + "|" + DUMMY_SHARE_LINK_TOKEN;

        byte[] macBytes = ShareImageProxyUrlBuilder.hmacSha256(
                DEV_SECRET.getBytes(StandardCharsets.UTF_8), payload);

        String encodedPayload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        String encodedMac = Base64.getUrlEncoder().withoutPadding().encodeToString(macBytes);
        String forgedExpiredToken = encodedPayload + "." + encodedMac;

        Optional<String> result = builder.verify(forgedExpiredToken);
        assertThat(result)
                .as("verify() must reject an expired token even when the HMAC is valid"
                        + " (SR-FUZZ-FIX-08 / CWE-613 / ASVS V13.2.4 L1)")
                .isEmpty();
    }

    /**
     * SR-FUZZ-FIX-05: flipping the dot separator character in a valid signed token must
     * cause {@link ShareImageProxyUrlBuilder#verify} to return empty (OWASP A02, CWE-345).
     */
    @Test
    void dotSeparatorMutationReturnsEmpty() {
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

        int dotIdx = token.lastIndexOf('.');
        assertThat(dotIdx)
                .as("Signed token must contain a dot separator between payload and MAC")
                .isGreaterThanOrEqualTo(0);

        String mutatedToken = token.substring(0, dotIdx) + ',' + token.substring(dotIdx + 1);

        Optional<String> result;
        try {
            result = builder.verify(mutatedToken);
        } catch (Exception e) {
            return; // exception is also an acceptable rejection (SR-FUZZ-FIX-05)
        }

        assertThat(result)
                .as("verify() must reject a token whose dot separator has been mutated"
                        + " (SR-FUZZ-FIX-05 / OWASP A02 / CWE-345)")
                .isEmpty();
    }

    /**
     * SR-FUZZ-FIX-07: six boundary cases covering all non-HMAC rejection branches inside
     * {@link ShareImageProxyUrlBuilder#verify}. Rows 5 and 6 use the package-private
     * {@code hmacSha256} to produce a genuine HMAC so the test reaches the parsing branch
     * under test (ADR-FUZZ-04 / SR-FUZZ-FIX-07; OWASP A02, ASVS V2.9.1 L1).
     */
    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("nonMacBranchRows")
    void nonMacBranchesReturnEmpty(final String description, final String tokenInput) {
        ShareImageProxyUrlBuilder builder = buildBuilder();

        Optional<String> result;
        try {
            result = builder.verify(tokenInput);
        } catch (Exception e) {
            return; // exception is also acceptable — the token was rejected
        }

        assertThat(result)
                .as("verify() must return empty for boundary case: %s (SR-FUZZ-FIX-07)", description)
                .isEmpty();
    }

    static Stream<Arguments> nonMacBranchRows() {
        byte[] keyBytes = DEV_SECRET.getBytes(StandardCharsets.UTF_8);
        String singlePipeToken = forgeSignedToken(keyBytes, "onlyone");
        String nonNumericToken = forgeSignedToken(keyBytes, "https://example.com|NOTANUMBER|sharelink-id");

        return Stream.of(
                Arguments.of("null token", null),
                Arguments.of("empty token", ""),
                Arguments.of("no dot separator", "abcdefghijk"),
                Arguments.of("invalid base64 payload",
                        "!!!.AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"),
                Arguments.of("payload with single pipe", singlePipeToken),
                Arguments.of("non-numeric expiresAt", nonNumericToken),
                Arguments.of("invalid base64 in MAC suffix", "AAAAAAAAAAA.!!!")
        );
    }

    private static String forgeSignedToken(final byte[] keyBytes, final String rawPayload) {
        try {
            byte[] macBytes = ShareImageProxyUrlBuilder.hmacSha256(keyBytes, rawPayload);
            String encodedPayload = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(rawPayload.getBytes(StandardCharsets.UTF_8));
            String encodedMac = Base64.getUrlEncoder().withoutPadding().encodeToString(macBytes);
            return encodedPayload + "." + encodedMac;
        } catch (Exception e) {
            throw new RuntimeException("forgeSignedToken failed for payload: " + rawPayload, e);
        }
    }

    // -------------------------------------------------------------------------
    // SR-FUZZ-FIX-12: visibility gate — hmacSha256 must remain package-private
    // -------------------------------------------------------------------------

    /**
     * SR-FUZZ-FIX-12 (modifier gate): asserts that
     * {@link ShareImageProxyUrlBuilder#hmacSha256(byte[], String)} is package-private,
     * enforcing ADR-FUZZ-04. The call-graph gate is covered by
     * {@link #hmacSha256IsNotCalledByProductionClasses()}.
     */
    @Test
    void hmacSha256IsPackagePrivateForTestingOnly() throws NoSuchMethodException {
        Method method = ShareImageProxyUrlBuilder.class
                .getDeclaredMethod("hmacSha256", byte[].class, String.class);
        int modifiers = method.getModifiers();

        assertThat(Modifier.isPublic(modifiers))
                .as("hmacSha256 must NOT be public — ADR-FUZZ-04 requires package-private"
                        + " (SR-FUZZ-FIX-12 / OWASP A05)")
                .isFalse();
        assertThat(Modifier.isProtected(modifiers))
                .as("hmacSha256 must NOT be protected — ADR-FUZZ-04 requires package-private"
                        + " (SR-FUZZ-FIX-12 / OWASP A05)")
                .isFalse();
        assertThat(Modifier.isPrivate(modifiers))
                .as("hmacSha256 must NOT be private — must be package-private for test access"
                        + " (SR-FUZZ-FIX-12 / ADR-FUZZ-04)")
                .isFalse();
    }

    /**
     * SR-FUZZ-FIX-12 (call-graph gate): verifies no production class in
     * {@code de.seism0saurus.glacier.share.web} calls
     * {@link ShareImageProxyUrlBuilder#hmacSha256(byte[], String)}.
     *
     * <p>Uses ArchUnit bytecode analysis so the rule fires even if a future caller is added
     * in a different compilation unit (ADR-FUZZ-04 / OWASP A05).
     */
    @Test
    void hmacSha256IsNotCalledByProductionClasses() {
        JavaClasses productionClasses = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("de.seism0saurus.glacier.share.web");

        ArchRule rule = noClasses()
                .that().doNotHaveSimpleName("ShareImageProxyUrlBuilder")
                .should().callMethod(ShareImageProxyUrlBuilder.class, "hmacSha256",
                        byte[].class, String.class)
                .because("hmacSha256 is package-private FOR TESTING ONLY — ADR-FUZZ-04 / SR-FUZZ-FIX-12");

        rule.check(productionClasses);
    }
}
