package de.seism0saurus.glacier.webservice.messaging.messages;

/**
 * Structured reason codes carried in a negative {@link SubscriptionAckMessage}.
 *
 * <p>The frontend uses {@code rejection.code} for branching (D-12):
 * <ul>
 *   <li>{@code CAP_EXCEEDED}   — per-principal hashtag cap or global principal cap reached (D-11)</li>
 *   <li>{@code INVALID_HASHTAG} — the supplied hashtag failed format validation</li>
 *   <li>{@code INTERNAL_ERROR}  — an unexpected server-side failure; the client should not retry immediately</li>
 * </ul>
 */
public enum RejectionCode {

    /** The subscription was refused because a memory-DoS cap was reached (D-11). */
    CAP_EXCEEDED,

    /** The supplied hashtag did not pass format validation. */
    INVALID_HASHTAG,

    /** An unexpected internal error prevented the subscription. */
    INTERNAL_ERROR
}
