package de.seism0saurus.glacier.webservice.messaging.messages;

/**
 * RejectionCode enumerates the machine-readable reasons why a subscription request
 * was declined by the server.
 *
 * <p>A {@link RejectionCode} is embedded in a {@link SubscriptionRejection} object
 * that is attached to a negative {@link SubscriptionAckMessage}. Clients may use
 * this value to display a localised error or to suppress retry logic for
 * permanently-invalid inputs.</p>
 */
public enum RejectionCode {

    /**
     * The hashtag supplied by the client failed format validation.
     *
     * <p>This code is returned when the hashtag is blank, exceeds the maximum
     * allowed length, or contains characters outside the permitted Unicode
     * letter/digit/underscore set defined in
     * {@link de.seism0saurus.glacier.webservice.messaging.HashtagFormat#PATTERN}.</p>
     */
    INVALID_HASHTAG
}
