package de.seism0saurus.glacier.share.application;

import java.net.URI;
import java.util.Optional;

/**
 * SafeUrlValidator is the SSRF-prevention contract for URLs that Glacier fetches on behalf of clients.
 *
 * <p>Before issuing any outbound HTTP request — specifically the {@code HEAD} call on a toot's
 * {@code /embed} endpoint in {@link de.seism0saurus.glacier.mastodon.StompCallback} — the raw URL
 * string from a Mastodon event must pass this validator. Implementations are responsible for
 * enforcing an allowlist of safe schemes and blocking resolution of private, link-local, and
 * loopback addresses (SSRF guard).</p>
 *
 * <p>A return value of {@link Optional#empty()} signals that the URL is blocked. The caller must
 * not proceed with the HTTP request and must emit an audit log event
 * ({@code stomp.embed.ssrf_blocked}) instead.</p>
 *
 * <p>A return value of {@link Optional#of(URI)} signals that the URL is safe to use for the
 * outbound request. The returned {@link URI} is the canonical, validated form of the input.</p>
 *
 * <p>Callers must treat the result as definitive — they must not re-parse or re-validate the
 * original string after receiving a non-empty {@link Optional}.</p>
 */
@FunctionalInterface
public interface SafeUrlValidator {

    /**
     * Validates the given raw URL string and returns a safe {@link URI} if it is permitted.
     *
     * @param rawUrl the raw URL string extracted from a Mastodon streaming event; may be {@code null}
     * @return {@link Optional#of(URI)} with the validated URI when the URL is safe,
     *         or {@link Optional#empty()} when the URL is blocked by the SSRF guard
     */
    Optional<URI> validate(String rawUrl);
}
