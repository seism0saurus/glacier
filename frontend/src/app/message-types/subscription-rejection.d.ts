/**
 * Matches the backend SubscriptionRejection DTO (D-12).
 * Carried inside SubscriptionAckMessage.rejection when the server
 * cannot fulfil a subscription request.
 */
export interface SubscriptionRejection {
  /** Machine-readable rejection code (see RejectionCode enum on the backend). */
  code: string;
  /**
   * Optional key/value metadata about the rejection.
   * For CAP_EXCEEDED this includes `{ limit: number }`.
   * MUST NOT contain raw wallId, raw hashtag, or client IP per D-12.
   */
  details?: Record<string, unknown>;
}
