package de.seism0saurus.glacier.share.web;

import de.seism0saurus.glacier.GlacierCookieProperties;
import de.seism0saurus.glacier.share.domain.ShareLinkId;
import org.junit.jupiter.api.Test;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PITest mutation-kill tests for {@link ShareImageProxyUrlBuilder}.
 *
 * <p>Each test pins an exact, observable value or boundary so the corresponding surviving
 * mutant would change behaviour and fail the test. The mutants targeted (by source line) are
 * documented per-test. These complement the property-based fuzz suite
 * ({@code ImageProxyUrlBuilderVerifyFuzzTest}) with deterministic equality/boundary assertions.
 *
 * <p>Security context: ADR-SHARE-07 (HMAC signing), OWASP A02 (Cryptographic Failures).
 */
class ShareImageProxyUrlBuilderMutationKillTest {

    /** 32-char dev secret so the validator accepts it in dev mode (secureCookies=false). */
    private static final String DEV_SECRET = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";

    /** Distinctive domain so the {@code glacierDomain} assignment is observable in output. */
    private static final String DOMAIN = "unit-domain.example.test";

    private static final String VALID_URL = "https://mastodon.social/media/1234.jpg";

    /** 43-char URL-safe base64url token → satisfies ShareLinkId.MIN_LENGTH. */
    private static final String DUMMY_SHARE_LINK_TOKEN =
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";

    /** Token validity window in seconds — must match the production constant exactly (7 days). */
    private static final long EXPECTED_VALIDITY_SECONDS = 7L * 24 * 3600; // 604800

    private static GlacierCookieProperties cookieProps(boolean secure) {
        GlacierCookieProperties props = new GlacierCookieProperties();
        props.setSecure(secure);
        return props;
    }

    private ShareImageProxyUrlBuilder builder() {
        ImageProxyHmacSecretValidator validator =
                new ImageProxyHmacSecretValidator(DEV_SECRET, cookieProps(false));
        return new ShareImageProxyUrlBuilder(validator, DOMAIN);
    }

    private static String extractToken(final String signedUrl) {
        int uIdx = signedUrl.indexOf("?u=");
        if (uIdx < 0) return null;
        return URLDecoder.decode(signedUrl.substring(uIdx + 3), StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------------
    // L46 — MemberVariableMutator: "Removed assignment to member variable glacierDomain"
    // If the assignment is dropped, glacierDomain stays null and the URL host changes.
    // ------------------------------------------------------------------------

    @Test
    void signedUrl_embedsConfiguredGlacierDomain() {
        String signedUrl = builder().sign(VALID_URL, ShareLinkId.fromUrlPath(DUMMY_SHARE_LINK_TOKEN));

        assertThat(signedUrl)
                .as("L46: signed URL host must derive from the configured glacierDomain")
                .isNotNull()
                .startsWith("https://share." + DOMAIN + "/rest/share/img-proxy?u=");
    }

    // ------------------------------------------------------------------------
    // L58 — removed isBlank() / RemoveConditional EQUAL_ELSE/EQUAL_IF
    // sign() must reject null/empty/blank with null, and accept a valid URL with non-null.
    // ------------------------------------------------------------------------

    @Test
    void sign_nullUrl_returnsNull() {
        assertThat(builder().sign(null, ShareLinkId.fromUrlPath(DUMMY_SHARE_LINK_TOKEN)))
                .as("L58: sign(null) must return null")
                .isNull();
    }

    @Test
    void sign_emptyUrl_returnsNull() {
        assertThat(builder().sign("", ShareLinkId.fromUrlPath(DUMMY_SHARE_LINK_TOKEN)))
                .as("L58: sign(\"\") must return null (isBlank branch)")
                .isNull();
    }

    @Test
    void sign_blankUrl_returnsNull() {
        assertThat(builder().sign("   ", ShareLinkId.fromUrlPath(DUMMY_SHARE_LINK_TOKEN)))
                .as("L58: sign(\"   \") must return null (isBlank branch — kills removed isBlank)")
                .isNull();
    }

    @Test
    void sign_validUrl_returnsNonNull() {
        assertThat(builder().sign(VALID_URL, ShareLinkId.fromUrlPath(DUMMY_SHARE_LINK_TOKEN)))
                .as("L58: a valid URL must NOT be rejected (kills RemoveConditional that always-returns-null)")
                .isNotNull();
    }

    // ------------------------------------------------------------------------
    // L60 — InlineConstant: "Substituted 604800 with 604801"
    // Decode the base64url payload, parse expiresAt, and pin the offset to exactly 604800.
    // Bounding now before/after the call makes 604801 detectable: with the mutant,
    // (expiresAt - nowAfter) == 604801 > 604800, failing the upper-bound assertion.
    // ------------------------------------------------------------------------

    @Test
    void signedPayload_expiresAtIsNowPlusExactlySevenDays() {
        long nowBefore = Instant.now().getEpochSecond();
        String signedUrl = builder().sign(VALID_URL, ShareLinkId.fromUrlPath(DUMMY_SHARE_LINK_TOKEN));
        long nowAfter = Instant.now().getEpochSecond();

        String token = extractToken(signedUrl);
        assertThat(token).isNotNull();

        String encodedPayload = token.substring(0, token.lastIndexOf('.'));
        String payload = new String(Base64.getUrlDecoder().decode(encodedPayload), StandardCharsets.UTF_8);
        String[] parts = payload.split("\\|", 3);
        long expiresAt = Long.parseLong(parts[1]);

        // Lower bound: expiresAt >= nowBefore + 604800.
        // Upper bound: expiresAt <= nowAfter + 604800.
        // The mutant (604801) yields expiresAt == now + 604801, breaking the upper bound
        // unless a full wall-clock second elapsed between nowBefore and nowAfter (negligible).
        assertThat(expiresAt)
                .as("L60: expiresAt must be now + exactly %d seconds (kills 604800->604801)",
                        EXPECTED_VALIDITY_SECONDS)
                .isGreaterThanOrEqualTo(nowBefore + EXPECTED_VALIDITY_SECONDS)
                .isLessThanOrEqualTo(nowAfter + EXPECTED_VALIDITY_SECONDS);
    }

    // ------------------------------------------------------------------------
    // L73 — NakedReceiver: Base64 encoder .withoutPadding()
    // If withoutPadding() is stripped, the encoder emits '=' padding. The token must have none.
    // ------------------------------------------------------------------------

    @Test
    void signedToken_hasNoBase64Padding() {
        String signedUrl = builder().sign(VALID_URL, ShareLinkId.fromUrlPath(DUMMY_SHARE_LINK_TOKEN));
        String token = extractToken(signedUrl);

        assertThat(token)
                .as("L73: signed token must be base64url WITHOUT '=' padding")
                .isNotNull()
                .doesNotContain("=");
    }

    // ------------------------------------------------------------------------
    // L90 — removed isBlank() / RemoveConditional on verify(signedToken)
    // verify(null)/verify("")/verify("  ") must all be empty.
    // ------------------------------------------------------------------------

    @Test
    void verify_null_returnsEmpty() {
        assertThat(builder().verify(null))
                .as("L90: verify(null) must be empty")
                .isEmpty();
    }

    @Test
    void verify_empty_returnsEmpty() {
        assertThat(builder().verify(""))
                .as("L90: verify(\"\") must be empty")
                .isEmpty();
    }

    @Test
    void verify_blank_returnsEmpty() {
        assertThat(builder().verify("   "))
                .as("L90: verify(\"   \") must be empty (kills removed isBlank)")
                .isEmpty();
    }

    // ------------------------------------------------------------------------
    // L93 — ConditionalsBoundary on (dotIdx < 0): a token with no '.' must be empty.
    // ------------------------------------------------------------------------

    @Test
    void verify_tokenWithoutDot_returnsEmpty() {
        assertThat(builder().verify("noDotSeparatorHerePresent12345"))
                .as("L93: token without a '.' separator must be empty (dotIdx < 0 guard)")
                .isEmpty();
    }

    // ------------------------------------------------------------------------
    // L125/L126 — payload split & parts.length guard, and L135 expiry-compare boundary.
    // Construct genuinely HMAC-signed tokens (so HMAC passes) with malformed part counts
    // and an expired timestamp to pin these guards exactly.
    // ------------------------------------------------------------------------

    /** Forges a token with a REAL HMAC over rawPayload so verify() reaches the parse/expiry branch. */
    private static String forge(final String rawPayload) throws Exception {
        byte[] mac = ShareImageProxyUrlBuilder.hmacSha256(
                DEV_SECRET.getBytes(StandardCharsets.UTF_8), rawPayload);
        String encodedPayload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(rawPayload.getBytes(StandardCharsets.UTF_8));
        String encodedMac = Base64.getUrlEncoder().withoutPadding().encodeToString(mac);
        return encodedPayload + "." + encodedMac;
    }

    @Test
    void verify_payloadWithNoPipe_returnsEmpty() throws Exception {
        // split("\\|", 3) yields parts.length == 1 → must be rejected (parts.length < 2).
        String token = forge("onlyOnePartNoPipe");
        assertThat(builder().verify(token))
                .as("L126: payload with zero '|' (parts.length==1) must be empty")
                .isEmpty();
    }

    @Test
    void verify_payloadWithSinglePipe_recoversFirstPart() throws Exception {
        // parts.length == 2 is the accepted boundary (parts.length < 2 is false here).
        // parts[1] must be a parseable, future expiry so we reach the success return of parts[0].
        long future = Instant.now().getEpochSecond() + EXPECTED_VALIDITY_SECONDS;
        String token = forge("https://example.com/img.png|" + future);
        assertThat(builder().verify(token))
                .as("L126: parts.length==2 with a future expiry is the accepted boundary")
                .contains("https://example.com/img.png");
    }

    @Test
    void verify_expiredToken_returnsEmpty() throws Exception {
        // L135: now > expiresAt → expired → empty. One second in the past.
        long expired = Instant.now().getEpochSecond() - 1;
        String token = forge(VALID_URL + "|" + expired + "|" + DUMMY_SHARE_LINK_TOKEN);
        assertThat(builder().verify(token))
                .as("L135: a token whose expiresAt is in the past must be empty (CWE-613)")
                .isEmpty();
    }

    @Test
    void verify_nonExpiredToken_succeeds() throws Exception {
        // L135 boundary, other side: expiresAt comfortably in the future → recovered.
        long future = Instant.now().getEpochSecond() + EXPECTED_VALIDITY_SECONDS;
        String token = forge(VALID_URL + "|" + future + "|" + DUMMY_SHARE_LINK_TOKEN);
        Optional<String> result = builder().verify(token);
        assertThat(result)
                .as("L135: a future-dated, validly signed token must verify and recover the URL")
                .contains(VALID_URL);
    }
}
