package de.seism0saurus.glacier.share.domain;

import java.time.Instant;

/**
 * Read-model projection of a {@link ShareLink} for management list views.
 *
 * <p>This projection intentionally omits the raw share-link token and the raw {@code ShareLinkId}.
 * The token is shown exactly once — at creation time — and is never retrievable from the
 * management list (Stripe / GitHub / AWS bearer-token UX pattern; ADR-SQLITE-05).
 *
 * <p>The {@code idHash8} field carries the first 8 hex characters of SHA-256(token) for
 * log correlation and UI display purposes only. It is NOT a cryptographic identifier and
 * must not be used as a lookup key.
 *
 * <p>Security contract (SR-SQLITE-20): this record must never gain a field named
 * {@code id}, {@code token}, {@code secret}, {@code url}, or {@code readonlyUrl}
 * (case-insensitive). An ArchUnit rule in {@code ShareLinkPersistenceArchitectureTest}
 * enforces this invariant at build time.
 *
 * <h3>Null safety</h3>
 * <ul>
 *   <li>{@code revokedAt} is null when the link has not been revoked.</li>
 *   <li>All other fields are non-null.</li>
 * </ul>
 *
 * <p>References: ADR-SQLITE-05; SR-SQLITE-20; GDPR Art. 25 (Data Protection by Design);
 * OWASP A02:2021 (Cryptographic Failures).
 *
 * @param idHash8      first 8 hex chars of SHA-256(token) — safe for display and log correlation
 * @param createdAt    the instant the link was created; never null
 * @param expiresAt    the instant the link expires; never null
 * @param revokedAt    the instant the link was revoked; null if not revoked
 * @param status       the derived link status at the {@code now} instant used for the query
 * @param sharerWallId the wallId of the sharer who owns this link; never null
 */
public record ShareLinkSummary(
        String idHash8,
        Instant createdAt,
        Instant expiresAt,
        Instant revokedAt,
        ShareLinkStatus status,
        String sharerWallId) {
}
