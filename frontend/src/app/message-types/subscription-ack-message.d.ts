export interface SubscriptionAckMessage{
  hashtag: string;
  principal: string;
  subscribed: boolean;
  /** Present when subscribed=false; null on success. (D-12) */
  rejection?: {
    code: string;
    details?: Record<string, unknown>;
  };
}
