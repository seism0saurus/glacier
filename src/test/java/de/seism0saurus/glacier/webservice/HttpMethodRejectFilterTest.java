package de.seism0saurus.glacier.webservice;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link HttpMethodRejectFilter}.
 *
 * <p>Verifies that TRACE and TRACK methods are rejected with 405 and that
 * all other methods are passed through to the filter chain.
 *
 * <p>Security reference: C8 — Browser Security; ASVS V14.5.1 (L1); WSTG-CONF-06.
 */
@ExtendWith(MockitoExtension.class)
class HttpMethodRejectFilterTest {

    @Mock
    private HttpServletRequest request;

    @Mock
    private HttpServletResponse response;

    @Mock
    private FilterChain chain;

    @InjectMocks
    private HttpMethodRejectFilter filter;

    // -------------------------------------------------------------------------
    // TRACE and TRACK must be rejected
    // -------------------------------------------------------------------------

    @ParameterizedTest(name = "{0} method must be rejected with 405")
    @ValueSource(strings = {"TRACE", "TRACK", "trace", "track", "Trace", "Track"})
    void rejectedMethod_setsStatus405AndDoesNotContinueChain(String method) throws Exception {
        when(request.getMethod()).thenReturn(method);

        filter.doFilter(request, response, chain);

        verify(response).setStatus(HttpServletResponse.SC_METHOD_NOT_ALLOWED);
        // Must NOT call chain.doFilter — the request must be terminated here
        verify(chain, never()).doFilter(request, response);
    }

    @ParameterizedTest(name = "{0} method must be rejected with Allow header")
    @ValueSource(strings = {"TRACE", "TRACK"})
    void rejectedMethod_setsAllowHeader(String method) throws Exception {
        when(request.getMethod()).thenReturn(method);

        filter.doFilter(request, response, chain);

        verify(response).setHeader(eq("Allow"), anyString());
    }

    // -------------------------------------------------------------------------
    // Permitted methods must be passed through
    // -------------------------------------------------------------------------

    @ParameterizedTest(name = "{0} method must pass through filter chain")
    @ValueSource(strings = {"GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS", "HEAD"})
    void permittedMethod_callsFilterChain(String method) throws Exception {
        when(request.getMethod()).thenReturn(method);

        filter.doFilter(request, response, chain);

        // Must continue the filter chain
        verify(chain).doFilter(request, response);
        // Must NOT set 405 status
        verify(response, never()).setStatus(HttpServletResponse.SC_METHOD_NOT_ALLOWED);
    }

    // -------------------------------------------------------------------------
    // Non-HTTP servlet request must pass through (defensive)
    // -------------------------------------------------------------------------

    @Test
    void nonHttpServletRequest_passesThrough() throws Exception {
        // Non-HttpServletRequest should just be passed through without any HTTP-specific processing
        jakarta.servlet.ServletRequest nonHttpRequest = mock(jakarta.servlet.ServletRequest.class);
        jakarta.servlet.ServletResponse nonHttpResponse = mock(jakarta.servlet.ServletResponse.class);

        filter.doFilter(nonHttpRequest, nonHttpResponse, chain);

        verify(chain).doFilter(nonHttpRequest, nonHttpResponse);
    }
}
