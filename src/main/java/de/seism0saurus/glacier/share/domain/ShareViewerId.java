package de.seism0saurus.glacier.share.domain;

import de.seism0saurus.glacier.util.LogScrubber;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Value object representing the opaque, URL-safe viewer-session identifier.
 *
 * <p>Mirrors {@link ShareLinkId} structurally but is a DISTINCT Java type so the compiler
 * prevents accidental substitution of a viewer ID where a share link ID is expected (and
 * vice versa).  This separation is mandated by ADR-SHARE-05.
 *
 * <p>Validation rules are identical to {@link ShareLinkId}:
 * <ul>
 *   <li>Minimum 43 characters (256-bit entropy floor).</li>
 *   <li>Only URL-safe base64url characters ({@code A-Z a-z 0-9 - _}).</li>
 * </ul>
 *
 * <p>The {@code shareViewerId} cookie is minted by
 * {@link de.seism0saurus.glacier.share.infrastructure.SecureRandomTokenGenerator} and is
 * scoped to {@code Path=/share} — it never reaches the main {@code /rest/*} endpoints.
 *
 * @see ShareLinkId
 */
public final class ShareViewerId {

    /** Minimum token length — same invariant as {@link ShareLinkId}. */
    static final int MIN_LENGTH = 43;

    private static final Pattern URL_SAFE_BASE64 = Pattern.compile("^[A-Za-z0-9_-]+$");

    private final String token;

    /**
     * Package-private constructor — use
     * {@link de.seism0saurus.glacier.share.infrastructure.SecureRandomTokenGenerator} for
     * production code.
     *
     * @param token a URL-safe base64url string of at least {@value MIN_LENGTH} characters
     * @throws IllegalArgumentException if validation fails
     */
    ShareViewerId(final String token) {
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("ShareViewerId token must not be null or blank");
        }
        if (token.length() < MIN_LENGTH) {
            throw new IllegalArgumentException(
                    "ShareViewerId token length " + token.length() + " is below the minimum of " + MIN_LENGTH);
        }
        if (!URL_SAFE_BASE64.matcher(token).matches()) {
            throw new IllegalArgumentException(
                    "ShareViewerId token contains characters outside the URL-safe base64url alphabet");
        }
        this.token = token;
    }

    /**
     * Parses a cookie value or URL path segment as a {@link ShareViewerId}, performing full
     * validation.  Use this factory method in web-layer code.
     *
     * @param raw the string to parse; must satisfy the base64url + length invariants
     * @return a validated {@link ShareViewerId}
     * @throws IllegalArgumentException if {@code raw} fails validation
     */
    public static ShareViewerId fromUrlPath(final String raw) {
        return new ShareViewerId(raw);
    }

    /**
     * Returns the raw URL-safe base64url token string.
     *
     * @return the raw token; never null or blank
     */
    public String value() {
        return token;
    }

    /**
     * Returns the first 8 hex characters of SHA-256({@code token}) for safe log output.
     *
     * <p>The raw {@code shareViewerId} is never logged (D-13, SR-8).
     *
     * @return an 8-character lowercase hex fingerprint
     */
    public String hash8() {
        return LogScrubber.hash8(token);
    }

    @Override
    public boolean equals(final Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof ShareViewerId other)) return false;
        return Objects.equals(token, other.token);
    }

    @Override
    public int hashCode() {
        return Objects.hash(token);
    }

    @Override
    public String toString() {
        return token;
    }
}
