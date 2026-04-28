package de.seism0saurus.glacier.share.domain;

/**
 * Lifecycle state of a {@link ShareLink}.
 *
 * <p>State transitions:
 * <pre>
 *   ACTIVE ──(TTL elapsed)──► EXPIRED   (terminal)
 *   ACTIVE ──(revoke())──────► REVOKED   (terminal)
 * </pre>
 *
 * <p>{@code EXPIRED} and {@code REVOKED} are both terminal — there is no re-activation path.
 * The {@code status(now)} method on {@link ShareLink} derives the state dynamically so there
 * is no stored enum field to keep in sync.
 */
public enum ShareLinkStatus {

    /**
     * The link is within its TTL and has not been revoked.
     * Viewers may access the wall through this link.
     */
    ACTIVE,

    /**
     * The TTL ({@code expiresAt}) has elapsed.  The link is no longer usable.
     * Terminal: cannot transition back to {@code ACTIVE}.
     */
    EXPIRED,

    /**
     * The sharer explicitly revoked the link before its TTL elapsed.  Terminal.
     * {@code REVOKED} takes precedence over {@code EXPIRED} when both conditions are true.
     */
    REVOKED
}
