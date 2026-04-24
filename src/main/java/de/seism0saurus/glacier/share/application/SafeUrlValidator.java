package de.seism0saurus.glacier.share.application;

import java.net.URI;
import java.util.Optional;

/**
 * Domain service for URL validation.
 *
 * <p>Returns a validated {@link URI} only for safe, public HTTP/HTTPS URLs.
 * Callers must treat an empty result as "URL not allowed" without disclosing the
 * reason to external clients (fail-secure).
 *
 * <p>References: SR-SHARE-09, ADR-SHARE-03, {@code spring-input-validation-ssrf} skill.
 */
public interface SafeUrlValidator {

    /**
     * Validates the given raw URL string.
     *
     * <p>Validates:
     * <ul>
     *   <li>Scheme is {@code http} or {@code https} (never {@code javascript:}, {@code data:}, etc.)</li>
     *   <li>Host is non-empty and resolves to a public IP (blocks RFC1918, loopback, cloud-metadata)</li>
     *   <li>No userinfo ({@code user@host} phishing prevention)</li>
     *   <li>No bidi override or control characters</li>
     * </ul>
     *
     * @param raw the URL string to validate; may be {@code null}
     * @return the validated and normalized {@link URI}, or empty if the URL is unsafe/invalid
     */
    Optional<URI> validate(String raw);
}
