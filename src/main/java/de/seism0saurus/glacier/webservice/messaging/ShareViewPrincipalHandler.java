package de.seism0saurus.glacier.webservice.messaging;

import de.seism0saurus.glacier.share.application.ShareLinkActivityRegistry;
import de.seism0saurus.glacier.share.application.ShareLinkService;
import de.seism0saurus.glacier.share.application.ShareLinkViewerCounter;
import de.seism0saurus.glacier.share.domain.ShareLink;
import de.seism0saurus.glacier.share.domain.ShareLinkCapPolicy;
import de.seism0saurus.glacier.share.domain.ShareLinkId;
import de.seism0saurus.glacier.webservice.security.SubscribeRateLimitInterceptor;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpSession;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.EventListener;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;
import org.springframework.web.socket.server.support.DefaultHandshakeHandler;

import java.net.URI;
import java.security.Principal;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;

import static de.seism0saurus.glacier.util.LogScrubber.hash8;

/**
 * Handshake handler for the viewer-only {@code /share-view-ws} STOMP endpoint.
 *
 * <p>Reads the {@code __Host-shareViewerId} cookie and uses it as the viewer identity.
 * Mints a new viewer ID if the cookie is absent, blank, too short, or missing the
 * mandatory {@value #SV_PREFIX} prefix.
 *
 * <p>Extracts the {@code shareLinkId} query parameter from the WebSocket upgrade URI
 * and binds it to the returned {@link ShareViewerPrincipal}. The interceptor
 * ({@link de.seism0saurus.glacier.share.web.ShareViewTopicAuthInterceptor}) uses this
 * bound link ID to enforce that the viewer may only subscribe to their share's topics.
 *
 * <p>Enforces the per-link viewer cap (SR-SHARE-05, OWASP API4 — Unrestricted Resource
 * Consumption): at handshake time, the counter for the bound share link is incremented
 * and checked against {@link ShareLinkCapPolicy#getMaxViewersPerLink()}. If the cap is
 * exceeded the counter is refunded and the handshake is rejected (fail-closed).
 * On session disconnect the counter is decremented so the slot is freed for new viewers.
 *
 * <p>Security controls (ADR-SHARE-04, ADR-SHARE-05 revised, SR-SHARE-05, SR-SHARE-06, SR-SHARE-07,
 * SR-RELAY-05, SR-RELAY-06, SR-RELAY-13):
 * <ul>
 *   <li>Returns {@link ShareViewerPrincipal} — typed principal that structurally cannot
 *       collide with {@link WallPrincipal} in cache or rate-limit bucket maps (ADR-SHARE-05).</li>
 *   <li>Only reads {@code __Host-shareViewerId} — ignores {@code wallId} even if present.</li>
 *   <li>{@code sv_} prefix on cookie VALUES retained as log/observability aid only;
 *       authorization is enforced by the type, not the prefix (ADR-SHARE-05 revised).</li>
 *   <li>Minimum token length {@value #MIN_VIEWER_ID_LENGTH} chars to prevent brute-force.</li>
 *   <li>New IDs generated via {@link SecureRandom} (256 bits entropy, URL-safe base64).</li>
 *   <li>Only {@link ShareViewerPrincipal} disconnects decrement the counter — {@link WallPrincipal}
 *       sessions are never counted (glacier-fallback-mode-discipline: namespace isolation).</li>
 *   <li>SR-RELAY-05: {@link ShareLinkService#resolve} is called BEFORE
 *       {@link ShareLinkViewerCounter#increment}, preventing counter exhaustion by attackers
 *       with revoked links (OWASP API4 — Unrestricted Resource Consumption).</li>
 *   <li>SR-RELAY-06 / SR-RELAY-13: {@link ShareLinkActivityRegistry#register} re-resolves under
 *       per-linkId lock; returns {@code false} on concurrent revocation → counter is refunded
 *       and handshake is rejected HTTP 403 (fail-closed, C1 — access control at every entry).</li>
 * </ul>
 */
public class ShareViewPrincipalHandler extends DefaultHandshakeHandler {

    private static final Logger log = LoggerFactory.getLogger(ShareViewPrincipalHandler.class);
    private static final Logger AUDIT = LoggerFactory.getLogger("AUDIT");

    /** Cookie name for share viewer identity. Uses __Host- prefix in secure mode (OWASP A05). */
    public static final String COOKIE_NAME_SECURE = "__Host-shareViewerId";
    public static final String COOKIE_NAME_INSECURE = "shareViewerId";

    /**
     * Prefix on shareViewerId cookie VALUES — retained as log/observability aid.
     * Authorization is enforced by the {@link ShareViewerPrincipal} type, not this prefix.
     * (ADR-SHARE-05 revised)
     */
    public static final String SV_PREFIX = "sv_";

    /** Minimum total length of a valid shareViewerId (sv_ + 43 base64url chars = 46). */
    public static final int MIN_VIEWER_ID_LENGTH = 46;

    /** Token entropy: 32 bytes = 256 bits. */
    private static final int TOKEN_BYTES = 32;

    /**
     * Raw token value of the sentinel {@link ShareLinkId} returned when no valid
     * {@code shareLinkId} query parameter is present in the upgrade URI.
     * Used by {@link #isUnboundSentinel(ShareLinkId)} for fast equality check.
     */
    static final String UNBOUND_SENTINEL_TOKEN = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final Base64.Encoder BASE64_URL = Base64.getUrlEncoder().withoutPadding();

    private final boolean secureCookies;
    private final ShareLinkViewerCounter viewerCounter;
    private final ShareLinkCapPolicy capPolicy;

    /**
     * SR-RELAY-05: share-link service used to resolve a link ID before incrementing the counter.
     * Declared {@code @Lazy} to break the circular dependency:
     * {@code WebSocketConfiguration} → {@code ShareViewPrincipalHandler} →
     * {@code ShareLinkServiceImpl} → (possibly) {@code WebSocketConfiguration} via event publishing.
     * C1 — access-control check before any resource allocation.
     */
    private final ShareLinkService shareLinkService;

    /**
     * SR-RELAY-06 / SR-RELAY-07: routing table with per-linkId locks that closes the TOCTOU
     * window between the handshake's initial {@code resolve()} and the actual registration.
     */
    private final ShareLinkActivityRegistry registry;

    /**
     * Constructs the handler with viewer-cap enforcement and secure TOCTOU-resistant registration.
     *
     * @param secureCookies    whether to use the {@code __Host-} cookie prefix (production mode)
     * @param viewerCounter    per-link viewer session counter (SR-SHARE-05)
     * @param capPolicy        cap configuration; provides {@link ShareLinkCapPolicy#getMaxViewersPerLink()}
     * @param shareLinkService service for resolving links before counter increment (SR-RELAY-05);
     *                         must be {@code @Lazy} at the injection site to avoid circular deps
     * @param registry         routing table for registering active viewer sessions (SR-RELAY-06)
     */
    public ShareViewPrincipalHandler(
            final boolean secureCookies,
            final ShareLinkViewerCounter viewerCounter,
            final ShareLinkCapPolicy capPolicy,
            @Lazy final ShareLinkService shareLinkService,
            final ShareLinkActivityRegistry registry) {
        this.secureCookies = secureCookies;
        this.viewerCounter = viewerCounter;
        this.capPolicy = capPolicy;
        this.shareLinkService = shareLinkService;
        this.registry = registry;
    }

    @Override
    protected Principal determineUser(
            @NotNull ServerHttpRequest request,
            @NotNull WebSocketHandler wsHandler,
            @NotNull Map<String, Object> attributes) {

        // ---- Step 1: extract shareLinkId from the upgrade URI ----------------------
        // SR-RELAY-05: must happen first so sentinel short-circuits before any I/O.
        ShareLinkId boundShareLinkId = extractShareLinkId(request.getURI());

        // ---- Step 2: reject immediately if link is unbound (sentinel) ---------------
        // Returning null would prevent STOMP session setup, but we return a sentinel
        // principal here so that ShareViewTopicAuthInterceptor can enforce topic rules.
        // If the URI had no valid shareLinkId, skip resolve() entirely — no real link
        // can ever match the sentinel, so the resolve call would be wasted I/O.
        if (isUnboundSentinel(boundShareLinkId)) {
            AUDIT.info("viewer.handshake_rejected reason=missing_share_link_id");
            // Return the sentinel-bound principal; the topic interceptor will block SUBSCRIBE.
            // We do NOT call resolve() or increment() for an unbound sentinel.
            String sentinelViewerId = mintNewShareViewerId();
            return buildPrincipalFromRequest(request, attributes, sentinelViewerId, boundShareLinkId);
        }

        // ---- Step 3: resolve the share link (SR-RELAY-05) --------------------------
        // C1 — access control check applied BEFORE any resource allocation (counter increment).
        // Returning Optional.empty() means the link is expired, revoked, or unknown.
        Instant now = Instant.now();
        Optional<ShareLink> activeLinkOpt = shareLinkService.resolve(boundShareLinkId, now);
        if (activeLinkOpt.isEmpty()) {
            AUDIT.info("viewer.handshake_rejected reason=link_not_active shareId-hash={}",
                    boundShareLinkId.hash8());
            // Returning null causes DefaultHandshakeHandler to reject with HTTP 403 (fail-closed).
            return null;
        }
        String sharerWallId = activeLinkOpt.get().sharerWallId();

        // ---- Step 4: mint / read viewer ID from cookie -----------------------------
        String cookieName = secureCookies ? COOKIE_NAME_SECURE : COOKIE_NAME_INSECURE;
        String viewerId = resolveViewerId(request, attributes, cookieName);

        // ---- Steps 5-6: increment → cap-check → register (atomic slot commitment) ----
        // SEC-ACC-01 / SR-RELAY-13 (exception path): the try/catch/finally guarantees the counter
        // slot is always refunded if the handshake does not complete successfully, including
        // when registry.register() throws a DataAccessException (SQLite adapter, flapping DB).
        // Without this guard, a throwing register() would leak the slot permanently, driving
        // viewerCount → maxViewers and causing a self-inflicted DoS (OWASP API4).
        // C1 — access control at every entry point; fail-closed on all exit paths.
        int newCount = viewerCounter.increment(boundShareLinkId);
        boolean committed = false;
        try {
            // ---- Step 5: enforce per-link viewer cap (SR-SHARE-05 / OWASP API4) --------
            // Increment AFTER resolve — an attacker with a revoked link never reaches this line.
            int maxViewers = capPolicy.getMaxViewersPerLink();
            if (newCount > maxViewers) {
                AUDIT.info("viewer.handshake_rejected reason=cap_exceeded shareId-hash={} viewerId-hash={} cap={} count={}",
                        boundShareLinkId.hash8(), hash8(viewerId), maxViewers, newCount);
                // Returning null causes DefaultHandshakeHandler to reject the WebSocket upgrade
                // with HTTP 403, preventing the STOMP session from being established (fail-closed).
                return null;
            }

            // ---- Step 6: register in routing table under per-linkId lock (SR-RELAY-06) --
            // registry.register() re-resolves under lock to detect concurrent revocation that
            // arrived between Step 3 and now (TOCTOU mitigation — ADR-RELAY-03, SR-RELAY-07).
            boolean registered = registry.register(sharerWallId, boundShareLinkId, shareLinkService, now);
            if (!registered) {
                // SR-RELAY-13: concurrent revocation won the race — reject (refund via finally).
                AUDIT.info("viewer.handshake_rejected reason=concurrent_revoke shareId-hash={}",
                        boundShareLinkId.hash8());
                // Returning null causes DefaultHandshakeHandler to reject the WebSocket upgrade
                // with HTTP 403 (fail-closed, C1 — access control at every entry point).
                return null;
            }

            // ---- Step 7: commit — principal accepted ---------------------------------
            // ADR-SHARE-05 revised: return typed ShareViewerPrincipal bound to the share link.
            // The ShareViewTopicAuthInterceptor validates the shareLinkId is ACTIVE on SUBSCRIBE.
            committed = true;
            return new ShareViewerPrincipal(viewerId, boundShareLinkId);
        } catch (RuntimeException e) {
            // SEC-ACC-01: registry.register() threw (e.g. DataAccessException from SQLite).
            // Fail-closed: the handshake is rejected with HTTP 403 by returning null.
            // The slot is refunded in the finally block below.
            // Do NOT log the raw exception message to avoid leaking internal stack details
            // (glacier-structured-logging-logback: never leak internals in AUDIT events).
            AUDIT.info("viewer.handshake_rejected reason=registry_error shareId-hash={}",
                    boundShareLinkId.hash8());
            log.error("viewer.handshake registry.register() threw for shareId-hash={} — rejecting (fail-closed)",
                    boundShareLinkId.hash8(), e);
            return null;
        } finally {
            // SEC-ACC-01: refund the slot on ANY non-committed exit: cap exceeded, register
            // returns false, register throws (DataAccessException / any RuntimeException).
            // Only skipped when committed == true, i.e. the principal was successfully returned.
            if (!committed) {
                viewerCounter.decrement(boundShareLinkId);
            }
        }
    }

    /**
     * Extracts and validates the viewer ID from the request cookie, minting a new one if absent or invalid.
     * Populates STOMP session and remote-address attributes needed by rate-limit interceptors.
     */
    private String resolveViewerId(
            final ServerHttpRequest request,
            final Map<String, Object> attributes,
            final String cookieName) {
        String viewerId = null;

        if (request instanceof ServletServerHttpRequest servletRequest) {
            HttpSession session = servletRequest.getServletRequest().getSession();
            attributes.put(PrincipalHandler.SESSION_ID, session.getId());
            // F-1 / OWASP API6:2023 / SR-WS-02 / SR-WS-03: populate REMOTE_ADDR so
            // SubscribeRateLimitInterceptor can key buckets on (IP + principal).
            // Without this, per-IP isolation is inert and every bucket key starts with "unknown:".
            attributes.put(SubscribeRateLimitInterceptor.REMOTE_ADDR,
                    servletRequest.getServletRequest().getRemoteAddr());

            Cookie[] cookies = servletRequest.getServletRequest().getCookies();
            if (cookies != null) {
                Optional<Cookie> viewerCookieOpt = Arrays.stream(cookies)
                        .filter(c -> cookieName.equals(c.getName()))
                        .findFirst();
                if (viewerCookieOpt.isPresent()) {
                    String raw = viewerCookieOpt.get().getValue();
                    if (isValidShareViewerId(raw)) {
                        viewerId = raw;
                    } else {
                        String reason = raw == null ? "null"
                                : raw.isBlank() ? "blank"
                                : !raw.startsWith(SV_PREFIX) ? "missing_sv_prefix"
                                : "too_short(" + raw.length() + ")";
                        AUDIT.info("viewer.handshake_rejected reason={} viewerId-hash={}", reason, hash8(raw));
                    }
                }
            }
        }

        if (viewerId == null) {
            viewerId = mintNewShareViewerId();
            AUDIT.info("viewer.handshake new_viewer_id viewerId-hash={}", hash8(viewerId));
        }

        return viewerId;
    }

    /**
     * Helper for the sentinel path: populates request attributes and returns a typed principal
     * without calling resolve or incrementing the counter.
     */
    private ShareViewerPrincipal buildPrincipalFromRequest(
            final ServerHttpRequest request,
            final Map<String, Object> attributes,
            final String viewerId,
            final ShareLinkId boundShareLinkId) {
        if (request instanceof ServletServerHttpRequest servletRequest) {
            HttpSession session = servletRequest.getServletRequest().getSession();
            attributes.put(PrincipalHandler.SESSION_ID, session.getId());
            attributes.put(SubscribeRateLimitInterceptor.REMOTE_ADDR,
                    servletRequest.getServletRequest().getRemoteAddr());
        }
        return new ShareViewerPrincipal(viewerId, boundShareLinkId);
    }

    /**
     * Decrements the viewer counter for a share link when a viewer STOMP session ends.
     *
     * <p>glacier-fallback-mode-discipline: only {@link ShareViewerPrincipal} disconnects
     * trigger a decrement — {@link WallPrincipal} sessions are fully isolated and never
     * tracked by this counter.
     *
     * @param event the Spring WebSocket disconnect event
     */
    @EventListener
    public void onDisconnect(final SessionDisconnectEvent event) {
        Principal user = event.getUser();
        if (!(user instanceof ShareViewerPrincipal viewerPrincipal)) {
            // WallPrincipal or null — not our concern; namespace isolation preserved.
            return;
        }
        ShareLinkId shareLinkId = viewerPrincipal.boundShareLinkId();
        viewerCounter.decrement(shareLinkId);
        AUDIT.info("viewer.disconnect viewerId-hash={} shareId-hash={}",
                hash8(viewerPrincipal.getName()), shareLinkId.hash8());
    }

    /**
     * Validates a shareViewerId value.
     *
     * @return true if the value is non-null, starts with {@value #SV_PREFIX},
     *         is at least {@value #MIN_VIEWER_ID_LENGTH} chars long, and
     *         contains only URL-safe base64 characters after the prefix.
     */
    public static boolean isValidShareViewerId(final String value) {
        if (value == null || value.isBlank()) return false;
        if (!value.startsWith(SV_PREFIX)) return false;
        if (value.length() < MIN_VIEWER_ID_LENGTH) return false;
        String token = value.substring(SV_PREFIX.length());
        return token.matches("[A-Za-z0-9_-]+");
    }

    /** Generates a new secure shareViewerId: {@value #SV_PREFIX} + 256-bit random base64url. */
    public static String mintNewShareViewerId() {
        byte[] bytes = new byte[TOKEN_BYTES];
        SECURE_RANDOM.nextBytes(bytes);
        return SV_PREFIX + BASE64_URL.encodeToString(bytes);
    }

    /** Returns the appropriate cookie name based on transport security mode. */
    public String cookieName() {
        return secureCookies ? COOKIE_NAME_SECURE : COOKIE_NAME_INSECURE;
    }

    /**
     * Extracts the {@code shareLinkId} query parameter from the WebSocket upgrade URI and
     * constructs a {@link ShareLinkId}. Returns a sentinel "unbound" share link ID if the
     * parameter is absent or invalid — the interceptor will reject SUBSCRIBE attempts for
     * an unbound viewer.
     */
    private static ShareLinkId extractShareLinkId(final URI uri) {
        if (uri == null) return unboundShareLinkId();
        String query = uri.getQuery();
        if (query == null || query.isBlank()) return unboundShareLinkId();
        for (String param : query.split("&")) {
            if (param.startsWith("shareLinkId=")) {
                String raw = param.substring("shareLinkId=".length());
                try {
                    return ShareLinkId.fromUrlPath(raw);
                } catch (IllegalArgumentException e) {
                    // Invalid format — return unbound; interceptor will reject SUBSCRIBE
                    return unboundShareLinkId();
                }
            }
        }
        return unboundShareLinkId();
    }

    /**
     * Returns {@code true} if the given {@link ShareLinkId} is the unbound sentinel value —
     * indicating that no valid {@code shareLinkId} query parameter was present in the upgrade URI.
     *
     * <p>Used by {@link #determineUser} to skip the {@code shareLinkService.resolve()} call
     * for sentinel-bound principals (no real link can match the sentinel, so resolve would be
     * wasted I/O and the result is guaranteed to be empty).
     *
     * @param id the share-link ID to test; must not be null
     * @return {@code true} iff the ID is the unbound sentinel
     */
    private static boolean isUnboundSentinel(final ShareLinkId id) {
        return UNBOUND_SENTINEL_TOKEN.equals(id.value());
    }

    /**
     * Sentinel share link ID used when no valid {@code shareLinkId} is present in the
     * upgrade request. The interceptor rejects any SUBSCRIBE from a principal bound to
     * this value because it cannot match any real share link.
     */
    private static ShareLinkId unboundShareLinkId() {
        // A well-formed but semantically empty ID that no real link can match.
        // 43 URL-safe base64 chars of zeros — cannot be a real share link.
        return ShareLinkId.fromUrlPath(UNBOUND_SENTINEL_TOKEN);
    }
}
