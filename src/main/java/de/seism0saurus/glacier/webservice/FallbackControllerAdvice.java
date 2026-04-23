package de.seism0saurus.glacier.webservice;

import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.Map;

/**
 * Narrow-scoped {@link RestControllerAdvice} that translates exceptions thrown by
 * {@link FallbackController} into flat {@code {"error": "code"}} JSON bodies.
 *
 * <p>Security controls (SR-2, non-blocking fix #2, OWASP A05, T-11 stacktrace leakage):
 * <ul>
 *   <li>Scoped exclusively to {@link FallbackController} — does NOT suppress Spring's default
 *       error handling for other endpoints.</li>
 *   <li>Returns only a flat {@code {"error": "code"}} body — never a stacktrace, exception
 *       message, or any request-derived string (OWASP A05: Security Misconfiguration).</li>
 *   <li>Validation failures return {@code 400 {"error":"invalid_hashtag"}} or
 *       {@code 400 {"error":"invalid_cursor"}} depending on which parameter failed (SR-1).</li>
 *   <li>Missing required parameters return {@code 400 {"error":"invalid_hashtag"}} —
 *       same flat shape; no enumeration of parameter names.</li>
 *   <li>Hashtag value is NEVER echoed in the response body (OWASP A03 injection prevention).</li>
 * </ul>
 */
@RestControllerAdvice(assignableTypes = FallbackController.class)
public class FallbackControllerAdvice {

    private static final Logger LOGGER = LoggerFactory.getLogger(FallbackControllerAdvice.class);

    /**
     * Maps {@link ConstraintViolationException} (from {@code @Validated} + {@code @Pattern}
     * or {@code @Min}/{@code @Max}) to a structured 400 response.
     *
     * <p>The error code is determined by which parameter violated: {@code "hashtag"} →
     * {@code invalid_hashtag}; {@code "since"} → {@code invalid_cursor}; anything else →
     * {@code invalid_input}.  The raw value is never echoed (SR-1).
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Map<String, String>> handleConstraintViolation(
            final ConstraintViolationException ex) {

        LOGGER.debug("Input validation failed: {}", ex.getMessage());

        // Determine which parameter was the offender for a meaningful error code
        boolean isHashtagViolation = ex.getConstraintViolations().stream()
                .anyMatch(cv -> cv.getPropertyPath().toString().contains("hashtag"));
        boolean isSinceViolation = ex.getConstraintViolations().stream()
                .anyMatch(cv -> cv.getPropertyPath().toString().contains("since"));

        String code = isHashtagViolation ? "invalid_hashtag"
                : isSinceViolation ? "invalid_cursor"
                : "invalid_input";

        return ResponseEntity.badRequest().body(Map.of("error", code));
    }

    /**
     * Maps {@link MissingServletRequestParameterException} (missing required {@code ?hashtag=})
     * to {@code 400 {"error":"invalid_hashtag"}}.  Missing {@code since} is not an error
     * (it has {@code required=false}).
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Map<String, String>> handleMissingParam(
            final MissingServletRequestParameterException ex) {

        LOGGER.debug("Missing required parameter in fallback request");
        String code = "since".equals(ex.getParameterName()) ? "invalid_cursor" : "invalid_hashtag";
        return ResponseEntity.badRequest().body(Map.of("error", code));
    }

    /**
     * Maps {@link MethodArgumentTypeMismatchException} (e.g., {@code ?since=abc}) to
     * {@code 400 {"error":"invalid_cursor"}} when the offending parameter is {@code since},
     * or {@code 400 {"error":"invalid_hashtag"}} otherwise.
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, String>> handleTypeMismatch(
            final MethodArgumentTypeMismatchException ex) {

        LOGGER.debug("Type mismatch in fallback request parameter");
        String code = "since".equals(ex.getName()) ? "invalid_cursor" : "invalid_hashtag";
        return ResponseEntity.badRequest().body(Map.of("error", code));
    }
}
