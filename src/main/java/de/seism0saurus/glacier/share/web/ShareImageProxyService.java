package de.seism0saurus.glacier.share.web;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import de.seism0saurus.glacier.share.application.DefaultSafeUrlValidator;
import de.seism0saurus.glacier.share.application.SafeUrlValidator;
import de.seism0saurus.glacier.util.LogScrubber;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.net.HttpURLConnection;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Fetches images via a hardened HTTP client and caches results.
 *
 * <p>Security controls (ADR-SHARE-07):
 * <ul>
 *   <li>{@link SafeUrlValidator} blocks RFC1918/loopback/CGNAT/cloud-metadata IPs (SSRF)</li>
 *   <li>Redirects disabled — prevents redirect-based SSRF bypass</li>
 *   <li>3 s connect / 5 s read timeout</li>
 *   <li>Only {@code https} URLs accepted (scheme enforced by validator)</li>
 *   <li>SVG rejected from content-type allowlist (XSS via SVG)</li>
 *   <li>1 MB bounded InputStream — throws on byte N+1 regardless of Content-Length</li>
 *   <li>Caffeine cache: 10 000 entries × 1 h positive + 60 s negative TTL</li>
 * </ul>
 */
@Service
public class ShareImageProxyService {

    private static final Logger log = LoggerFactory.getLogger(ShareImageProxyService.class);
    private static final Logger AUDIT = LoggerFactory.getLogger("AUDIT");

    /** Allowed image content types — SVG excluded (XSS via SVG). */
    private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of(
            "image/png", "image/jpeg", "image/gif", "image/webp"
    );

    /** 1 MB byte cap — prevents OOM via large upstream images. */
    private static final int MAX_BYTES = 1024 * 1024;

    private final SafeUrlValidator urlValidator;
    private final RestTemplate restTemplate;
    private final Cache<String, Optional<ProxyResult>> positiveCache;
    private final Cache<String, Optional<ProxyResult>> negativeCache;

    public record ProxyResult(byte[] body, String contentType) {}

    public ShareImageProxyService(
            final SafeUrlValidator urlValidator,
            @Value("${glacier.share.imgproxy.negativeCacheSeconds:60}") final int negativeCacheSeconds) {
        this.urlValidator = urlValidator;

        // Hardened RestTemplate: no redirects, short timeouts (ADR-SHARE-07)
        // SSRF Finding 4: setOutputStreaming(false) does NOT disable redirect following.
        // We override prepareConnection to call setInstanceFollowRedirects(false) which
        // is the correct HttpURLConnection API to disable redirect following.
        // OWASP A10 (SSRF): redirect-based SSRF bypass prevention.
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory() {
            @Override
            protected void prepareConnection(HttpURLConnection conn, String method)
                    throws java.io.IOException {
                // Disable redirect following BEFORE delegating to super, which may
                // configure the connection further. instanceFollowRedirects takes
                // precedence over the static followRedirects setting (JDK contract).
                conn.setInstanceFollowRedirects(false);
                super.prepareConnection(conn, method);
            }
        };
        factory.setConnectTimeout(3000);
        factory.setReadTimeout(5000);
        this.restTemplate = new RestTemplate(factory);

        // Positive cache: 10 000 entries × 1 h
        this.positiveCache = Caffeine.newBuilder()
                .maximumSize(10_000)
                .expireAfterWrite(Duration.ofHours(1))
                .build();

        // Negative cache: short TTL to avoid thundering herd on 5xx
        this.negativeCache = Caffeine.newBuilder()
                .maximumSize(10_000)
                .expireAfterWrite(Duration.ofSeconds(negativeCacheSeconds))
                .build();
    }

    /**
     * Fetches and validates an image from {@code url}.
     *
     * @param url a scheme-validated URL (https only)
     * @return the fetched bytes and content type, or empty on any security/error rejection
     */
    public Optional<ProxyResult> fetch(final String url) {
        // Check positive cache first
        Optional<ProxyResult> cached = positiveCache.getIfPresent(url);
        if (cached != null) return cached;

        // Check negative cache
        Optional<ProxyResult> negativeCached = negativeCache.getIfPresent(url);
        if (negativeCached != null) return Optional.empty();

        // SSRF guard: re-validate URL (already validated at signing time, but defence-in-depth)
        Optional<URI> safeUri = urlValidator.validate(url);
        if (safeUri.isEmpty()) {
            AUDIT.info("share.proxy.fetch_blocked reason=ssrf url-hash={}", LogScrubber.hash8(url));
            negativeCache.put(url, Optional.empty());
            return Optional.empty();
        }

        // Scheme must be https only (defence-in-depth beyond validator)
        if (!"https".equals(safeUri.get().getScheme())) {
            AUDIT.info("share.proxy.fetch_blocked reason=scheme url-hash={}", LogScrubber.hash8(url));
            negativeCache.put(url, Optional.empty());
            return Optional.empty();
        }

        try {
            Optional<ProxyResult> result = fetchInternal(safeUri.get());
            if (result.isPresent()) {
                positiveCache.put(url, result);
            } else {
                negativeCache.put(url, Optional.empty());
            }
            return result;
        } catch (Exception e) {
            AUDIT.info("share.proxy.fetch_blocked reason=error url-hash={} msg={}",
                    LogScrubber.hash8(url), e.getClass().getSimpleName());
            negativeCache.put(url, Optional.empty());
            return Optional.empty();
        }
    }

    private Optional<ProxyResult> fetchInternal(URI uri) throws IOException {
        // SSRF Finding 5: DNS pinning — resolve host once and pin to IP address.
        // This prevents TOCTOU DNS-rebinding attacks where a valid hostname resolves
        // to a public IP during validation but a private IP during the actual fetch.
        // OWASP SSRF Prevention Cheat Sheet §DNS Pinning, SR-SHARE-09.
        final URI requestUri;
        try {
            InetAddress addr = DefaultSafeUrlValidator.resolveAndPin(uri.getHost());

            // Second-pass blocklist check on pinned address (defence-in-depth)
            if (DefaultSafeUrlValidator.isBlockedAddress(addr)) {
                AUDIT.info("share.proxy.fetch_blocked reason=ssrf_pinned_second_pass url-hash={}",
                        LogScrubber.hash8(uri.toString()));
                return Optional.empty();
            }

            // Build URI targeting the numeric IP; preserve the original host as Host header
            int port = uri.getPort() < 0
                    ? ("https".equals(uri.getScheme()) ? 443 : 80)
                    : uri.getPort();
            requestUri = new URI(uri.getScheme(), null, addr.getHostAddress(),
                    port, uri.getPath(), uri.getQuery(), null);
        } catch (IllegalArgumentException e) {
            // resolveAndPin rejected the address (private/blocked)
            AUDIT.info("share.proxy.fetch_blocked reason=dns_pin_blocked url-hash={}",
                    LogScrubber.hash8(uri.toString()));
            return Optional.empty();
        } catch (java.net.UnknownHostException e) {
            AUDIT.info("share.proxy.fetch_blocked reason=dns_unresolvable url-hash={}",
                    LogScrubber.hash8(uri.toString()));
            return Optional.empty();
        } catch (java.net.URISyntaxException e) {
            AUDIT.info("share.proxy.fetch_blocked reason=uri_build_failed url-hash={}",
                    LogScrubber.hash8(uri.toString()));
            return Optional.empty();
        }

        // Execute GET with bounded InputStream; include original Host header so SNI / vhosts work
        return restTemplate.execute(requestUri, HttpMethod.GET,
                httpRequest -> httpRequest.getHeaders().set("Host", uri.getHost()),
                response -> {
            // Content-type check (SVG rejected)
            String contentType = response.getHeaders().getContentType() != null
                    ? response.getHeaders().getContentType().toString()
                    : "";
            // Strip parameters (e.g. "image/png; charset=...")
            String baseType = contentType.split(";")[0].trim().toLowerCase(Locale.ROOT);
            if (!ALLOWED_CONTENT_TYPES.contains(baseType)) {
                AUDIT.info("share.proxy.fetch_blocked reason=content_type ct={}", baseType);
                return Optional.empty();
            }

            // Bounded read: throw on byte MAX_BYTES+1
            InputStream is = response.getBody();
            byte[] buf = new byte[MAX_BYTES + 1];
            int totalRead = 0;
            int n;
            while ((n = is.read(buf, totalRead, buf.length - totalRead)) != -1) {
                totalRead += n;
                if (totalRead > MAX_BYTES) {
                    AUDIT.info("share.proxy.fetch_blocked reason=oversize url-hash=<redacted>");
                    return Optional.empty();
                }
            }

            byte[] body = new byte[totalRead];
            System.arraycopy(buf, 0, body, 0, totalRead);
            return Optional.of(new ProxyResult(body, baseType));
        });
    }
}
