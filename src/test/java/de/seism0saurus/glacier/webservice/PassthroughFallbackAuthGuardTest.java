package de.seism0saurus.glacier.webservice;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Behavioural tests for {@link PassthroughFallbackAuthGuard#authenticate}.
 *
 * <p>{@link PassthroughFallbackAuthGuardProfileTest} only covers the {@code @Profile("test")}
 * bean wiring; the actual auth decision — reject a missing/blank wallId, accept a present one as
 * the principal — was previously unexercised. The request argument is ignored by this guard
 * (it trusts the cookie value passed by the caller), so {@code null} is passed for it.
 */
class PassthroughFallbackAuthGuardTest {

    private final PassthroughFallbackAuthGuard guard = new PassthroughFallbackAuthGuard();

    @Test
    void nullWallId_isNotAuthenticated() {
        FallbackAuthGuard.AuthResult result = guard.authenticate(null, null);
        assertThat(result.authenticated()).isFalse();
        assertThat(result.principal()).isNull();
    }

    @Test
    void blankWallId_isNotAuthenticated() {
        FallbackAuthGuard.AuthResult result = guard.authenticate(null, "   ");
        assertThat(result.authenticated()).isFalse();
        assertThat(result.principal()).isNull();
    }

    @Test
    void presentWallId_isAuthenticatedWithThatPrincipal() {
        FallbackAuthGuard.AuthResult result = guard.authenticate(null, "wall-abc-123");
        assertThat(result.authenticated()).isTrue();
        assertThat(result.principal()).isEqualTo("wall-abc-123");
    }
}
