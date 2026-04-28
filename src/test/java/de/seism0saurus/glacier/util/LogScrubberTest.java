package de.seism0saurus.glacier.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link LogScrubber}.
 *
 * <p>Verifies the D-13 / SR-8 log-scrubbing contracts: sensitive values must never
 * reach the JSON encoder as raw strings — only irreversible, correlation-safe digests.</p>
 */
public class LogScrubberTest {

    // -------------------------------------------------------------------------
    // hash8
    // -------------------------------------------------------------------------

    /**
     * Arrange: a non-null value.
     * Act: hash it twice.
     * Assert: same input → same 8-character hex digest (deterministic).
     */
    @Test
    public void hash8_sameInput_returnsSameDigest() {
        String result1 = LogScrubber.hash8("hello");
        String result2 = LogScrubber.hash8("hello");
        assertThat(result1).isEqualTo(result2);
    }

    /**
     * Arrange: two different values.
     * Act: hash both.
     * Assert: different inputs → different digests (collision resistance for nearby strings).
     */
    @Test
    public void hash8_differentInputs_returnsDifferentDigests() {
        String result1 = LogScrubber.hash8("hello");
        String result2 = LogScrubber.hash8("world");
        assertThat(result1).isNotEqualTo(result2);
    }

    /**
     * Arrange: a non-null value.
     * Act: hash it.
     * Assert: result is exactly 8 lowercase hex characters.
     */
    @Test
    public void hash8_nonNullInput_returnsEightCharacterLowercaseHex() {
        String result = LogScrubber.hash8("test-value");
        assertThat(result).hasSize(8).matches("[0-9a-f]{8}");
    }

    /**
     * Arrange: null input.
     * Act: call hash8(null).
     * Assert: returns the literal string "null" rather than throwing NPE.
     */
    @Test
    public void hash8_nullInput_returnsLiteralNull() {
        String result = LogScrubber.hash8(null);
        assertThat(result).isEqualTo("null");
    }

    // -------------------------------------------------------------------------
    // hashtagLen
    // -------------------------------------------------------------------------

    /**
     * Arrange: a hashtag string of known length.
     * Act: call hashtagLen.
     * Assert: returns the character count.
     */
    @Test
    public void hashtagLen_normalHashtag_returnsLength() {
        assertThat(LogScrubber.hashtagLen("glacier")).isEqualTo(7);
    }

    /**
     * Arrange: null.
     * Act: call hashtagLen(null).
     * Assert: returns 0 rather than throwing NPE.
     */
    @Test
    public void hashtagLen_nullHashtag_returnsZero() {
        assertThat(LogScrubber.hashtagLen(null)).isEqualTo(0);
    }

    // -------------------------------------------------------------------------
    // urlHostHash — SR-PT-07
    // -------------------------------------------------------------------------

    /**
     * SR-PT-07 (part 1): same host + different paths → same hash.
     *
     * Arrange: two URLs with identical host and port, differing only in path.
     * Act: call urlHostHash for each.
     * Assert: both return the same 8-character digest.
     */
    @Test
    public void urlHostHash_sameHostDifferentPaths_returnsSameHash() {
        String url1 = "https://mastodon.social/@user/12345";
        String url2 = "https://mastodon.social/@other/99999";

        String hash1 = LogScrubber.urlHostHash(url1);
        String hash2 = LogScrubber.urlHostHash(url2);

        assertThat(hash1).isEqualTo(hash2);
    }

    /**
     * SR-PT-07 (part 2): different hosts → different hashes.
     *
     * Arrange: two URLs with different hosts.
     * Act: call urlHostHash for each.
     * Assert: the digests differ.
     */
    @Test
    public void urlHostHash_differentHosts_returnsDifferentHashes() {
        String url1 = "https://mastodon.social/@user/12345";
        String url2 = "https://fosstodon.org/@user/12345";

        String hash1 = LogScrubber.urlHostHash(url1);
        String hash2 = LogScrubber.urlHostHash(url2);

        assertThat(hash1).isNotEqualTo(hash2);
    }

    /**
     * Arrange: an https URL without an explicit port.
     * Act: call urlHostHash.
     * Assert: produces the same digest as the equivalent explicit-port-443 URL.
     */
    @Test
    public void urlHostHash_httpsWithoutExplicitPort_defaultsToPort443() {
        String withoutPort = "https://mastodon.social/about";
        String withPort443 = "https://mastodon.social:443/about";

        assertThat(LogScrubber.urlHostHash(withoutPort))
                .isEqualTo(LogScrubber.urlHostHash(withPort443));
    }

    /**
     * Arrange: an http URL without an explicit port.
     * Act: call urlHostHash.
     * Assert: produces the same digest as the equivalent explicit-port-80 URL.
     */
    @Test
    public void urlHostHash_httpWithoutExplicitPort_defaultsToPort80() {
        String withoutPort = "http://mastodon.social/about";
        String withPort80 = "http://mastodon.social:80/about";

        assertThat(LogScrubber.urlHostHash(withoutPort))
                .isEqualTo(LogScrubber.urlHostHash(withPort80));
    }

    /**
     * Arrange: null URL.
     * Act: call urlHostHash(null).
     * Assert: returns a deterministic "unparseable" hash rather than throwing NPE.
     */
    @Test
    public void urlHostHash_nullUrl_returnsUnparseableHash() {
        String result = LogScrubber.urlHostHash(null);
        assertThat(result).isEqualTo(LogScrubber.hash8("unparseable"));
    }

    /**
     * Arrange: blank URL.
     * Act: call urlHostHash(" ").
     * Assert: returns the "unparseable" hash.
     */
    @Test
    public void urlHostHash_blankUrl_returnsUnparseableHash() {
        String result = LogScrubber.urlHostHash("   ");
        assertThat(result).isEqualTo(LogScrubber.hash8("unparseable"));
    }

    /**
     * Arrange: a malformed URL that cannot be parsed.
     * Act: call urlHostHash.
     * Assert: returns the "unparseable" hash without throwing an exception.
     */
    @Test
    public void urlHostHash_malformedUrl_returnsUnparseableHash() {
        String result = LogScrubber.urlHostHash("not a url ://[invalid");
        assertThat(result).isEqualTo(LogScrubber.hash8("unparseable"));
    }

    /**
     * Arrange: a URL without a host component (e.g. file scheme).
     * Act: call urlHostHash.
     * Assert: returns the "unparseable" hash.
     */
    @Test
    public void urlHostHash_urlWithoutHost_returnsUnparseableHash() {
        String result = LogScrubber.urlHostHash("file:///local/path");
        // file URIs have a null or empty host
        assertThat(result).isEqualTo(LogScrubber.hash8("unparseable"));
    }

    /**
     * Arrange: result of urlHostHash.
     * Assert: always 8 lowercase hex characters.
     */
    @Test
    public void urlHostHash_validUrl_returnsEightCharacterHex() {
        String result = LogScrubber.urlHostHash("https://mastodon.social/@user/1");
        assertThat(result).hasSize(8).matches("[0-9a-f]{8}");
    }
}
