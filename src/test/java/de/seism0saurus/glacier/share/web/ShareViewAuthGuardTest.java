package de.seism0saurus.glacier.share.web;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Unit tests for {@link ShareViewAuthGuard}.
 *
 * <p>Security requirements: SR-SHARE-06 (viewer auth only via shareViewerId cookie),
 * ADR-SHARE-05 (sv_ prefix required).
 */
@ExtendWith(MockitoExtension.class)
class ShareViewAuthGuardTest {

    private ShareViewAuthGuard guard;

    @Mock
    private HttpServletRequest request;

    @BeforeEach
    void setUp() {
        guard = new ShareViewAuthGuard();
    }

    @Test
    void acceptsValidShareViewerIdWithSvPrefix() {
        String viewerId = "sv_" + "A".repeat(43);
        ShareViewAuthGuard.AuthResult result = guard.authenticate(request, viewerId);
        assertThat(result.authenticated()).isTrue();
        assertThat(result.principal()).isEqualTo(viewerId);
    }

    @Test
    void rejectsNullViewerId() {
        ShareViewAuthGuard.AuthResult result = guard.authenticate(request, null);
        assertThat(result.authenticated()).isFalse();
        assertThat(result.principal()).isNull();
    }

    @Test
    void rejectsBlankViewerId() {
        ShareViewAuthGuard.AuthResult result = guard.authenticate(request, "");
        assertThat(result.authenticated()).isFalse();
    }

    @Test
    void rejectsViewerIdWithoutSvPrefix() {
        // wallId-style UUID must be rejected on share endpoint
        ShareViewAuthGuard.AuthResult result = guard.authenticate(request, "a1b2c3d4-e5f6-7890-abcd-ef1234567890");
        assertThat(result.authenticated()).isFalse();
    }

    @Test
    void rejectsShortViewerId() {
        // sv_ + only 5 chars
        ShareViewAuthGuard.AuthResult result = guard.authenticate(request, "sv_short");
        assertThat(result.authenticated()).isFalse();
    }

    @Test
    void rejectsViewerIdWithSvPrefixButTooShort() {
        // sv_ + 20 chars — must be at least 43 chars after prefix
        ShareViewAuthGuard.AuthResult result = guard.authenticate(request, "sv_" + "x".repeat(20));
        assertThat(result.authenticated()).isFalse();
    }
}
