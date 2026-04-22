package de.seism0saurus.glacier.webservice;

import de.seism0saurus.glacier.util.LogScrubber;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Unit tests for {@link CookieBasedFallbackAuthGuard}.
 *
 * Security controls verified (SR-2, D-08, ADR-06):
 * - Null wallId → 401
 * - Blank wallId → 401
 * - wallId length < 32 → 401
 * - Valid wallId (length ≥ 32) → 200 with principal = raw wallId
 * - Principal is never null when authenticated
 * - hashWallId produces 8-char hex, handles null gracefully
 */
class CookieBasedFallbackAuthGuardTest {

    private CookieBasedFallbackAuthGuard guard;
    private HttpServletRequest request;

    @BeforeEach
    void setUp() {
        guard = new CookieBasedFallbackAuthGuard();
        request = mock(HttpServletRequest.class);
    }

    // -------------------------------------------------------------------------
    // Rejection cases (SR-2: wallId validation)
    // -------------------------------------------------------------------------

    @Test
    void authenticate_nullWallId_returnsUnauthenticated() {
        FallbackAuthGuard.AuthResult result = guard.authenticate(request, null);

        assertThat(result.authenticated()).isFalse();
        assertThat(result.principal()).isNull();
    }

    @Test
    void authenticate_blankWallId_returnsUnauthenticated() {
        FallbackAuthGuard.AuthResult result = guard.authenticate(request, "   ");

        assertThat(result.authenticated()).isFalse();
        assertThat(result.principal()).isNull();
    }

    @Test
    void authenticate_emptyWallId_returnsUnauthenticated() {
        FallbackAuthGuard.AuthResult result = guard.authenticate(request, "");

        assertThat(result.authenticated()).isFalse();
        assertThat(result.principal()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"short", "1234567890123456789012345678901"}) // 5 and 31 chars
    void authenticate_wallIdTooShort_returnsUnauthenticated(String shortId) {
        assertThat(shortId.length()).isLessThan(CookieBasedFallbackAuthGuard.MIN_WALL_ID_LENGTH);
        FallbackAuthGuard.AuthResult result = guard.authenticate(request, shortId);

        assertThat(result.authenticated()).isFalse();
        assertThat(result.principal()).isNull();
    }

    @Test
    void authenticate_wallIdExactlyAtMinLength_returnsAuthenticated() {
        // Exactly 32 chars — boundary condition
        String wallId = "12345678901234567890123456789012"; // 32 chars
        assertThat(wallId.length()).isEqualTo(CookieBasedFallbackAuthGuard.MIN_WALL_ID_LENGTH);
        FallbackAuthGuard.AuthResult result = guard.authenticate(request, wallId);

        assertThat(result.authenticated()).isTrue();
        assertThat(result.principal()).isEqualTo(wallId);
    }

    // -------------------------------------------------------------------------
    // Success cases
    // -------------------------------------------------------------------------

    @Test
    void authenticate_validUuidWallId_returnsAuthenticatedWithPrincipal() {
        String wallId = "550e8400-e29b-41d4-a716-446655440000"; // UUID format = 36 chars
        FallbackAuthGuard.AuthResult result = guard.authenticate(request, wallId);

        assertThat(result.authenticated()).isTrue();
        assertThat(result.principal()).isEqualTo(wallId);
    }

    @Test
    void authenticate_validWallId_principalEqualsRawWallId() {
        // Principal must be the raw wallId so MessageCache lookups succeed
        String wallId = "abcdef1234567890abcdef1234567890"; // 32 hex chars
        FallbackAuthGuard.AuthResult result = guard.authenticate(request, wallId);

        assertThat(result.principal()).isEqualTo(wallId);
    }

    // -------------------------------------------------------------------------
    // Log-hygiene hash contract (D-13, SR-8) — now delegated to LogScrubber.hash8
    //
    // FIX C: the private hashWallId method has been removed; all call sites
    // now use LogScrubber.hash8 directly.  These tests verify the canonical
    // contract through the public API to guard against future regressions.
    // -------------------------------------------------------------------------

    @Test
    void logScrubber_hash8_null_returnsNullString() {
        assertThat(LogScrubber.hash8(null)).isEqualTo("null");
    }

    @Test
    void logScrubber_hash8_validInput_returns8HexChars() {
        String hash = LogScrubber.hash8("any-wall-id");
        assertThat(hash).hasSize(8);
        assertThat(hash).matches("[0-9a-f]{8}");
    }

    @Test
    void logScrubber_hash8_sameInput_producesSameOutput() {
        String wallId = "deterministic-wall-id-1234567890";
        assertThat(LogScrubber.hash8(wallId)).isEqualTo(LogScrubber.hash8(wallId));
    }

    @Test
    void logScrubber_hash8_differentInputs_produceDifferentHashes() {
        String h1 = LogScrubber.hash8("wall-a-1234567890123456789012345");
        String h2 = LogScrubber.hash8("wall-b-1234567890123456789012345");
        assertThat(h1).isNotEqualTo(h2);
    }
}
