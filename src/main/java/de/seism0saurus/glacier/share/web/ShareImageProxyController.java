package de.seism0saurus.glacier.share.web;

import de.seism0saurus.glacier.util.LogScrubber;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Server-side image proxy for the share wall.
 *
 * <p>Endpoint: {@code GET /rest/share/img-proxy?u={signed-token}}
 *
 * <p>The signed token is produced exclusively by {@link ShareImageProxyUrlBuilder#sign}.
 * Only {@link de.seism0saurus.glacier.share.application.ShareRenderingService} calls the
 * signing path — enforced by architecture, not the JVM (the HMAC provides the runtime
 * enforcement: any forged URL is rejected with 401).
 *
 * <p>Security controls (ADR-SHARE-07):
 * <ul>
 *   <li>HMAC-SHA256 signature validation before any outbound fetch (SR-SHARE-15)</li>
 *   <li>Expiry timestamp embedded in signed payload — expired tokens return 401</li>
 *   <li>{@link ShareImageProxyService} enforces SSRF blocklist, content-type allowlist,
 *       1 MB byte cap, redirect-disabled client, Caffeine negative cache</li>
 *   <li>Response headers: CORP, nosniff, CSP default-src none, Content-Disposition inline,
 *       Cache-Control immutable (OWASP A05: security headers)</li>
 * </ul>
 */
@RestController
public class ShareImageProxyController {

    private static final Logger AUDIT = LoggerFactory.getLogger("AUDIT");
    private static final Logger log = LoggerFactory.getLogger(ShareImageProxyController.class);

    private final ShareImageProxyUrlBuilder urlBuilder;
    private final ShareImageProxyService proxyService;

    public ShareImageProxyController(
            final ShareImageProxyUrlBuilder urlBuilder,
            final ShareImageProxyService proxyService) {
        this.urlBuilder = urlBuilder;
        this.proxyService = proxyService;
    }

    /**
     * Proxy endpoint.
     *
     * @param signedToken the URL-encoded signed token ({@code u} parameter)
     * @return the proxied image with hardened response headers, or 401/502/503
     */
    @GetMapping("/rest/share/img-proxy")
    public ResponseEntity<byte[]> proxyImage(
            @RequestParam(name = "u", required = false) String signedToken,
            HttpServletRequest request) {

        if (signedToken == null || signedToken.isBlank()) {
            return ResponseEntity.badRequest().build();
        }

        // URL-decode the token (it was encoded on the way in)
        String decodedToken = URLDecoder.decode(signedToken, StandardCharsets.UTF_8);

        // HMAC signature + expiry verification (fail-closed)
        Optional<String> originalUrl = urlBuilder.verify(decodedToken);
        if (originalUrl.isEmpty()) {
            AUDIT.info("share.proxy.rejected reason=invalid_signature remoteIp={}",
                    LogScrubber.maskIp(request.getRemoteAddr()));
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        // Fetch via hardened client (SSRF blocklist + CT allowlist + size cap)
        Optional<ShareImageProxyService.ProxyResult> result = proxyService.fetch(originalUrl.get());
        if (result.isEmpty()) {
            // Upstream rejected or SSRF blocked — 502 Bad Gateway
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).build();
        }

        ShareImageProxyService.ProxyResult proxyResult = result.get();

        return ResponseEntity.ok()
                // CORP: prevent cross-origin image leakage (ADR-SHARE-07)
                .header("Cross-Origin-Resource-Policy", "same-origin")
                // Prevent MIME sniffing (OWASP A05)
                .header("X-Content-Type-Options", "nosniff")
                // CSP: proxy response carries no scripts
                .header("Content-Security-Policy", "default-src 'none'")
                // Force download context for corrupted CTs (defence-in-depth)
                .header("Content-Disposition", "inline")
                // Long-lived immutable cache (proxy URL is HMAC-signed with expiry)
                .cacheControl(CacheControl.maxAge(3600, TimeUnit.SECONDS).cachePublic().immutable())
                .contentType(org.springframework.http.MediaType.parseMediaType(proxyResult.contentType()))
                .body(proxyResult.body());
    }
}
