package de.seism0saurus.glacier.webservice;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link FallbackControllerAdvice}.
 *
 * Security controls verified (SR-2, non-blocking fix #2, OWASP A05, T-11):
 * - ConstraintViolationException on hashtag → 400 {error:invalid_hashtag}
 * - ConstraintViolationException on since → 400 {error:invalid_cursor}
 * - MissingServletRequestParameterException → 400 {error:invalid_hashtag}
 * - MethodArgumentTypeMismatchException on since → 400 {error:invalid_cursor}
 * - Response body is always flat Map<String,String> — no stacktrace, no raw input
 */
class FallbackControllerAdviceTest {

    private FallbackControllerAdvice advice;

    @BeforeEach
    void setUp() {
        advice = new FallbackControllerAdvice();
    }

    // -------------------------------------------------------------------------
    // ConstraintViolationException — hashtag violation (SR-1)
    // -------------------------------------------------------------------------

    @Test
    void handleConstraintViolation_hashtagViolation_returns400InvalidHashtag() {
        ConstraintViolationException ex = buildConstraintViolation("getMessages.hashtag");

        ResponseEntity<Map<String, String>> result = advice.handleConstraintViolation(ex);

        assertThat(result.getStatusCode().value()).isEqualTo(400);
        assertThat(result.getBody()).containsEntry("error", "invalid_hashtag");
    }

    @Test
    void handleConstraintViolation_sinceViolation_returns400InvalidCursor() {
        ConstraintViolationException ex = buildConstraintViolation("getMessages.since");

        ResponseEntity<Map<String, String>> result = advice.handleConstraintViolation(ex);

        assertThat(result.getStatusCode().value()).isEqualTo(400);
        assertThat(result.getBody()).containsEntry("error", "invalid_cursor");
    }

    @Test
    void handleConstraintViolation_unknownParam_returns400InvalidInput() {
        ConstraintViolationException ex = buildConstraintViolation("getMessages.other");

        ResponseEntity<Map<String, String>> result = advice.handleConstraintViolation(ex);

        assertThat(result.getStatusCode().value()).isEqualTo(400);
        assertThat(result.getBody()).containsEntry("error", "invalid_input");
    }

    @Test
    void handleConstraintViolation_responseBodyContainsOnlyErrorKey() {
        ConstraintViolationException ex = buildConstraintViolation("getMessages.hashtag");

        ResponseEntity<Map<String, String>> result = advice.handleConstraintViolation(ex);

        // Flat map with exactly one key — no stacktrace or raw input
        assertThat(result.getBody()).hasSize(1);
        assertThat(result.getBody()).containsKey("error");
    }

    @Test
    void handleConstraintViolation_errorBodyDoesNotEchoHashtagValue() {
        // SR-1: hashtag value must never appear in response
        ConstraintViolationException ex = buildConstraintViolation("getMessages.hashtag");

        ResponseEntity<Map<String, String>> result = advice.handleConstraintViolation(ex);

        assertThat(result.getBody().values()).noneMatch(v -> v.contains("hashtag") && !v.equals("invalid_hashtag"));
    }

    // -------------------------------------------------------------------------
    // MissingServletRequestParameterException (SR-1)
    // -------------------------------------------------------------------------

    @Test
    void handleMissingParam_hashtagMissing_returns400InvalidHashtag() {
        MissingServletRequestParameterException ex =
                new MissingServletRequestParameterException("hashtag", "String");

        ResponseEntity<Map<String, String>> result = advice.handleMissingParam(ex);

        assertThat(result.getStatusCode().value()).isEqualTo(400);
        assertThat(result.getBody()).containsEntry("error", "invalid_hashtag");
    }

    @Test
    void handleMissingParam_sinceMissing_returns400InvalidCursor() {
        MissingServletRequestParameterException ex =
                new MissingServletRequestParameterException("since", "Long");

        ResponseEntity<Map<String, String>> result = advice.handleMissingParam(ex);

        assertThat(result.getStatusCode().value()).isEqualTo(400);
        assertThat(result.getBody()).containsEntry("error", "invalid_cursor");
    }

    // -------------------------------------------------------------------------
    // MethodArgumentTypeMismatchException (SR-1)
    // -------------------------------------------------------------------------

    @Test
    void handleTypeMismatch_since_returns400InvalidCursor() throws Exception {
        MethodArgumentTypeMismatchException ex = mock(MethodArgumentTypeMismatchException.class);
        when(ex.getName()).thenReturn("since");

        ResponseEntity<Map<String, String>> result = advice.handleTypeMismatch(ex);

        assertThat(result.getStatusCode().value()).isEqualTo(400);
        assertThat(result.getBody()).containsEntry("error", "invalid_cursor");
    }

    @Test
    void handleTypeMismatch_otherParam_returns400InvalidHashtag() throws Exception {
        MethodArgumentTypeMismatchException ex = mock(MethodArgumentTypeMismatchException.class);
        when(ex.getName()).thenReturn("hashtag");

        ResponseEntity<Map<String, String>> result = advice.handleTypeMismatch(ex);

        assertThat(result.getStatusCode().value()).isEqualTo(400);
        assertThat(result.getBody()).containsEntry("error", "invalid_hashtag");
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private static ConstraintViolationException buildConstraintViolation(String propertyPath) {
        ConstraintViolation<Object> violation = mock(ConstraintViolation.class);
        Path path = mock(Path.class);
        when(path.toString()).thenReturn(propertyPath);
        when(violation.getPropertyPath()).thenReturn(path);
        return new ConstraintViolationException(Set.of(violation));
    }
}
