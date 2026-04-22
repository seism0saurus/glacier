package de.seism0saurus.glacier.webservice.cache;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link FallbackSecurityHeadersFilter}.
 *
 * Security controls verified (D-08, ADR-06, SR-5):
 * - All five security headers are set on /rest/messages
 * - Filter does NOT add headers to other paths
 * - FilterChain is always called (filter never swallows requests)
 */
class FallbackSecurityHeadersFilterTest {

    private FallbackSecurityHeadersFilter filter;
    private HttpServletRequest request;
    private HttpServletResponse response;
    private FilterChain chain;

    @BeforeEach
    void setUp() throws Exception {
        filter = new FallbackSecurityHeadersFilter();
        request = mock(HttpServletRequest.class);
        response = mock(HttpServletResponse.class);
        chain = mock(FilterChain.class);
    }

    // -------------------------------------------------------------------------
    // /rest/messages — all headers must be set (SR-5, D-08)
    // -------------------------------------------------------------------------

    @Test
    void doFilter_restMessages_setsCacheControlNoStore() throws Exception {
        when(request.getRequestURI()).thenReturn("/rest/messages");

        filter.doFilter(request, response, chain);

        verify(response).setHeader("Cache-Control", "no-store");
    }

    @Test
    void doFilter_restMessages_setsPragmaNoCache() throws Exception {
        when(request.getRequestURI()).thenReturn("/rest/messages");

        filter.doFilter(request, response, chain);

        verify(response).setHeader("Pragma", "no-cache");
    }

    @Test
    void doFilter_restMessages_setsVaryCookie() throws Exception {
        when(request.getRequestURI()).thenReturn("/rest/messages");

        filter.doFilter(request, response, chain);

        verify(response).setHeader("Vary", "Cookie");
    }

    @Test
    void doFilter_restMessages_setsXContentTypeOptionsNoSniff() throws Exception {
        when(request.getRequestURI()).thenReturn("/rest/messages");

        filter.doFilter(request, response, chain);

        verify(response).setHeader("X-Content-Type-Options", "nosniff");
    }

    @Test
    void doFilter_restMessages_setsContentSecurityPolicyDefaultSrcNone() throws Exception {
        when(request.getRequestURI()).thenReturn("/rest/messages");

        filter.doFilter(request, response, chain);

        verify(response).setHeader("Content-Security-Policy", "default-src 'none'");
    }

    @Test
    void doFilter_restMessages_chainIsAlwaysCalled() throws Exception {
        when(request.getRequestURI()).thenReturn("/rest/messages");

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
    }

    // -------------------------------------------------------------------------
    // Other paths — filter must NOT add headers (ADR-06 path-guard)
    // -------------------------------------------------------------------------

    @Test
    void doFilter_restWallId_doesNotAddSecurityHeaders() throws Exception {
        when(request.getRequestURI()).thenReturn("/rest/wall-id");

        filter.doFilter(request, response, chain);

        // No security headers should be set on this path
        verify(response, never()).setHeader(eq("Cache-Control"), anyString());
        verify(response, never()).setHeader(eq("Content-Security-Policy"), anyString());
    }

    @Test
    void doFilter_otherRestPath_doesNotAddSecurityHeaders() throws Exception {
        when(request.getRequestURI()).thenReturn("/rest/mastodon-handle");

        filter.doFilter(request, response, chain);

        verify(response, never()).setHeader(eq("Cache-Control"), anyString());
    }

    @Test
    void doFilter_rootPath_doesNotAddSecurityHeaders() throws Exception {
        when(request.getRequestURI()).thenReturn("/");

        filter.doFilter(request, response, chain);

        verify(response, never()).setHeader(eq("Cache-Control"), anyString());
    }

    @Test
    void doFilter_otherRestPath_chainIsStillCalled() throws Exception {
        when(request.getRequestURI()).thenReturn("/rest/operator");

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
    }

    // -------------------------------------------------------------------------
    // Non-HttpServletRequest — filter must handle gracefully
    // -------------------------------------------------------------------------

    @Test
    void doFilter_nonHttpRequest_chainIsCalledWithoutException() throws Exception {
        // ServletRequest that is NOT an HttpServletRequest
        jakarta.servlet.ServletRequest plainRequest = mock(jakarta.servlet.ServletRequest.class);

        filter.doFilter(plainRequest, response, chain);

        verify(chain).doFilter(plainRequest, response);
        // No headers should have been set on the response
        verifyNoInteractions(response);
    }

    @Test
    void doFilter_restMessages_withQueryString_setsHeaders() throws Exception {
        // URI without query string (getRequestURI() never includes query string)
        when(request.getRequestURI()).thenReturn("/rest/messages");

        filter.doFilter(request, response, chain);

        verify(response).setHeader("Cache-Control", "no-store");
        verify(response).setHeader("Vary", "Cookie");
    }

    // -------------------------------------------------------------------------
    // Partial path match check (path-guard precision)
    // -------------------------------------------------------------------------

    @Test
    void doFilter_restMessagesPrefix_doesNotAddHeaders() throws Exception {
        // /rest/messages-extra should NOT match (exact path guard)
        when(request.getRequestURI()).thenReturn("/rest/messages-extra");

        filter.doFilter(request, response, chain);

        verify(response, never()).setHeader(eq("Cache-Control"), anyString());
    }

    @Test
    void doFilter_restMessagesSuffix_doesNotAddHeaders() throws Exception {
        when(request.getRequestURI()).thenReturn("/other/rest/messages");

        filter.doFilter(request, response, chain);

        verify(response, never()).setHeader(eq("Cache-Control"), anyString());
    }
}
