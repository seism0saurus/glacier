package de.seism0saurus.glacier.share.web;

import de.seism0saurus.glacier.share.application.SafeUrlValidator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.net.URI;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Security-gate unit tests for {@link ShareImageProxyService#fetch(String)}.
 *
 * <p>Focuses on the SSRF defence chain and caching decisions that gate every outbound image
 * fetch — the branches that decide whether a request is allowed out at all, exercised without
 * any network egress:
 * <ul>
 *   <li>{@link SafeUrlValidator} rejection (first-pass SSRF block)</li>
 *   <li>https-only scheme enforcement (defence-in-depth beyond the validator)</li>
 *   <li>negative-cache short-circuit (a blocked URL is not re-validated on the next call)</li>
 *   <li>second-pass DNS-pin block on the resolved address — the TOCTOU / DNS-rebinding guard —
 *       including the classic cloud-metadata IP (169.254.169.254) and loopback</li>
 * </ul>
 *
 * <p>The happy-path fetch (content-type allowlist, 1 MB cap) is intentionally not exercised here:
 * it requires a non-loopback HTTP server because the DNS-pin second pass blocks loopback, so it is
 * covered at the e2e / integration layer rather than in a unit test.
 */
@ExtendWith(MockitoExtension.class)
class ShareImageProxyServiceTest {

    @Mock
    private SafeUrlValidator validator;

    private ShareImageProxyService newService() {
        return new ShareImageProxyService(validator, 60);
    }

    @Test
    void validatorRejectsUrl_returnsEmpty_andNegativeCachesToAvoidRevalidation() {
        ShareImageProxyService svc = newService();
        String url = "https://evil.example.com/x.png";
        when(validator.validate(url)).thenReturn(Optional.empty());

        assertThat(svc.fetch(url)).isEmpty();
        // Second call must be served from the negative cache — the validator is consulted once.
        assertThat(svc.fetch(url)).isEmpty();
        verify(validator, times(1)).validate(url);
    }

    @Test
    void nonHttpsScheme_isRejected_evenIfValidatorReturnedAUri() {
        ShareImageProxyService svc = newService();
        // Defence-in-depth: even if the validator let an http URI through, the service rejects it.
        String url = "http://images.example.com/x.png";
        when(validator.validate(url)).thenReturn(Optional.of(URI.create(url)));

        assertThat(svc.fetch(url)).isEmpty();
    }

    @Test
    void httpsCloudMetadataIp_blockedBySecondPassDnsPin() {
        ShareImageProxyService svc = newService();
        // The classic SSRF target. Even if the first-pass validator were bypassed, resolving and
        // pinning 169.254.169.254 (link-local) must be blocked by the second-pass guard.
        String url = "https://169.254.169.254/latest/meta-data/";
        when(validator.validate(url)).thenReturn(Optional.of(URI.create(url)));

        assertThat(svc.fetch(url)).isEmpty();
    }

    @Test
    void httpsLoopbackIp_blockedBySecondPassDnsPin_andNegativeCached() {
        ShareImageProxyService svc = newService();
        String url = "https://127.0.0.1/avatar.png";
        when(validator.validate(url)).thenReturn(Optional.of(URI.create(url)));

        assertThat(svc.fetch(url)).isEmpty();
        // The empty result is negative-cached; a second call does not re-resolve/re-validate.
        assertThat(svc.fetch(url)).isEmpty();
        verify(validator, times(1)).validate(url);
    }
}
