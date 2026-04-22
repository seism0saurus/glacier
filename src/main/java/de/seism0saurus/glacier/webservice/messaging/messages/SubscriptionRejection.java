package de.seism0saurus.glacier.webservice.messaging.messages;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * Carries structured rejection information in a negative {@link SubscriptionAckMessage}.
 *
 * <p>The {@code details} map contains operational scalars only — never the raw
 * {@code wallId}, raw hashtag, or client IP (D-12 hygiene requirement):
 * <ul>
 *   <li>For {@link RejectionCode#CAP_EXCEEDED}: {@code {"limit": <int>}} where
 *       {@code limit} is the configured maximum that was hit.</li>
 * </ul>
 *
 * <p>The frontend reads {@code rejection.code} for branching and
 * {@code rejection.details.limit} to localise the CAP_REACHED snackbar copy (D-12).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SubscriptionRejection {

    /** The machine-readable reason code. */
    private RejectionCode code;

    /**
     * Optional key/value context about the rejection.
     *
     * <p>Must contain only operational scalars; must not contain raw {@code wallId},
     * raw hashtag, or IP address.
     */
    private Map<String, Object> details;
}
