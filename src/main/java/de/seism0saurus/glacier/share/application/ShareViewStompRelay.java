package de.seism0saurus.glacier.share.application;

import de.seism0saurus.glacier.share.domain.ShareLinkId;
import de.seism0saurus.glacier.util.LogScrubber;
import de.seism0saurus.glacier.webservice.cache.CacheEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import social.bigbone.api.entity.Status;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Relays sharer STOMP toot events to viewer-scoped share topics, and handles the
 * share-link lifecycle via Spring {@link EventListener} methods.
 *
 * <p>The sharer's wall receives toots on:
 * {@code /topic/hashtags/{wallId}/{hashtag}/{creation|modification|deletion}}
 *
 * <p>This relay re-publishes the same payload to all active share links for the
 * sharer's wallId, using viewer-safe topics:
 * {@code /topic/share/{shareLinkId}/{hashtag}/{eventType}}
 *
 * <p>Revocation is pushed as a control message:
 * {@code /topic/share/{shareLinkId}/control} with payload {@code {type:"revoked"}}.
 * Viewers that receive this must disconnect within the SLA window (ADR-SHARE-08).
 *
 * <h2>Routing table</h2>
 * <p>Active share-link IDs are tracked in {@link ShareLinkActivityRegistry}, which is
 * populated by {@link #onActivate(ShareLinkActivatedEvent)} and drained by
 * {@link #onRevoke(ShareLinkRevokedEvent)}. The deprecated
 * {@link ShareLinkService#listBySharer} method is no longer called here (ADR-RELAY-01).
 *
 * <h2>Security invariants</h2>
 * <ul>
 *   <li>The sharer's {@code wallId} NEVER appears in any viewer-facing topic path (SR-SHARE-02).</li>
 *   <li>Topics are scoped by {@code shareLinkId}, not by wallId, so viewers cannot
 *       discover other viewer sessions or the sharer's identity.</li>
 *   <li>The relay only publishes to ACTIVE links as recorded in {@link ShareLinkActivityRegistry}.</li>
 *   <li>Event listeners are synchronous — {@code @Async} is forbidden in this package
 *       (ARCH-RELAY-01; ARCH-RELAY-05; SR-RELAY-01).</li>
 *   <li>{@code onRevoke} calls {@code registry.unregister()} + {@code pushRevocation()}
 *       atomically under the per-linkId lock inside {@link ShareLinkActivityRegistry}
 *       (SR-RELAY-02; SR-RELAY-07).</li>
 * </ul>
 *
 * <p>ADR-SHARE-04, ADR-RELAY-01, SR-SHARE-02, SR-SHARE-06, OWASP API1 (BOLA).
 */
@Service
public class ShareViewStompRelay {

    private static final Logger log = LoggerFactory.getLogger(ShareViewStompRelay.class);
    private static final Logger AUDIT = LoggerFactory.getLogger("AUDIT");

    /** Viewer-facing STOMP topic prefix; wallId is NEVER part of this path (SR-SHARE-02). */
    private static final String SHARE_TOPIC_PREFIX = "/topic/share/";

    /**
     * Minimum interval between {@code share.relay.no_viewers} AUDIT.warn entries for the
     * same sharerWallId (SR-RELAY-20).
     */
    private static final Duration NO_VIEWERS_DEBOUNCE = Duration.ofSeconds(30);

    private final SimpMessagingTemplate messagingTemplate;
    private final ShareLinkService shareLinkService;
    private final ShareTootCache shareTootCache;
    private final ShareLinkActivityRegistry registry;

    /**
     * Rendering service for converting a Bigbone {@link Status} into a viewer-safe
     * {@link ReadonlyTootView}. Called inside the {@code getActiveLinks} loop so that
     * each share link receives a proxy-URL-HMAC-scoped view (SR-RENDER-01, ADR-RENDER-01).
     *
     * <p>May be {@code null} when constructed by tests that do not exercise the
     * typed-Status relay path (backward-compatible — the Object overload does not use it).
     */
    private final ShareRenderingService shareRenderingService;

    /**
     * Clock used by {@link #emitNoViewersWarnWithDebounce} for debounce timing.
     * Injected so tests can substitute a {@link Clock#fixed} for deterministic assertion
     * (ACC-03 behavioral debounce test, SR-RELAY-20).
     */
    private final Clock clock;

    /**
     * Debounce state for the {@code share.relay.no_viewers} AUDIT.warn (SR-RELAY-09, SR-RELAY-20).
     * Maps sharerWallId to the instant of the last emitted warn. Grows with distinct
     * sharerWallId values seen (Open Risk R3 — negligible at Glacier scale).
     */
    private final ConcurrentHashMap<String, Instant> noViewersLastWarnAt = new ConcurrentHashMap<>();

    /**
     * Spring-managed constructor.
     *
     * @param messagingTemplate    STOMP messaging template (lazy to break circular dep chain)
     * @param shareLinkService     share link service (lazy to break circular dep chain)
     * @param shareTootCache       per-(sharerWallId, hashtag) Status cache backing the share view's
     *                             initial render + fallback polling (FLAW-3)
     * @param registry             active share link routing table
     * @param shareRenderingService rendering service for typed-Status relay (ADR-RENDER-01,
     *                             SR-RENDER-01); injected to perform per-link rendering inside
     *                             the {@code getActiveLinks} loop
     * @param clock                clock for debounce timing; injected so tests can freeze time
     *                             (ACC-03 behavioral assertion, SR-RELAY-20)
     */
    public ShareViewStompRelay(
            // @Lazy on SimpMessagingTemplate and ShareLinkService breaks the mutual circular
            // dependency chain:
            //   shareViewStompRelay → SimpMessagingTemplate → WebSocketConfig →
            //     ShareViewTopicAuthInterceptor → shareLinkServiceImpl → applicationEvents → shareViewStompRelay
            @Lazy final SimpMessagingTemplate messagingTemplate,
            @Lazy final ShareLinkService shareLinkService,
            final ShareTootCache shareTootCache,
            final ShareLinkActivityRegistry registry,
            final ShareRenderingService shareRenderingService,
            final Clock clock) {
        this.messagingTemplate = messagingTemplate;
        this.shareLinkService = shareLinkService;
        this.shareTootCache = shareTootCache;
        this.registry = registry;
        this.shareRenderingService = shareRenderingService;
        this.clock = clock;
    }

    // -------------------------------------------------------------------------
    // EventListener — lifecycle events from ShareLinkServiceImpl
    // -------------------------------------------------------------------------

    /**
     * Registers the newly created share link in the routing table.
     *
     * <p>Called synchronously by the Spring event bus when {@link ShareLinkServiceImpl#create}
     * publishes a {@link ShareLinkActivatedEvent} after persisting the link. The link is
     * re-resolved under the per-linkId lock inside {@link ShareLinkActivityRegistry#register}
     * to guard against a concurrent revocation that may have arrived between the emission
     * of this event and its processing (ADR-RELAY-03, SR-RELAY-06).
     *
     * <p>SR-RELAY-14: AUDIT.info emitted with hash-sanitised IDs.
     *
     * @param event the activation event carrying sharerWallId and shareLinkId
     */
    @EventListener
    public void onActivate(final ShareLinkActivatedEvent event) {
        registry.register(event.sharerWallId(), event.shareLinkId(), shareLinkService, Instant.now());
        AUDIT.info("share.link.activated shareId-hash8={} wallId-hash8={}",
                event.shareLinkId().hash8(), LogScrubber.hash8(event.sharerWallId()));
    }

    /**
     * Removes the revoked share link from the routing table and pushes a revocation
     * control message to all connected viewers.
     *
     * <p>Called synchronously by the Spring event bus when {@link ShareLinkServiceImpl#revoke}
     * publishes a {@link ShareLinkRevokedEvent}. The {@code unregister()} call acquires the
     * per-linkId lock inside the registry, and the AUDIT log entry and {@code pushRevocation()}
     * call follow inside the same execution — ensuring atomicity from the caller's perspective
     * (SR-RELAY-02, SR-RELAY-07, SR-RELAY-22).
     *
     * <p>SR-RELAY-15: AUDIT.info emitted after {@code unregister()} and before
     * {@code pushRevocation()} (audit ordering correctness).
     *
     * @param event the revocation event carrying sharerWallId and shareLinkId
     */
    @EventListener
    public void onRevoke(final ShareLinkRevokedEvent event) {
        // unregister acquires the per-linkId lock internally (SR-RELAY-07)
        registry.unregister(event.sharerWallId(), event.shareLinkId());
        // SR-RELAY-22: AUDIT.info after unregister(), before pushRevocation()
        AUDIT.info("share.link.relay_revoked shareId-hash8={} wallId-hash8={}",
                event.shareLinkId().hash8(), LogScrubber.hash8(event.sharerWallId()));
        pushRevocation(event.shareLinkId());
    }

    // -------------------------------------------------------------------------
    // Relay
    // -------------------------------------------------------------------------

    /**
     * Re-publishes a toot event payload to all active share link topics for the given wall.
     *
     * <p>Called after a toot is published to the sharer's own topic tree.
     * The wallId is used only server-side to look up share links via the registry;
     * it never reaches any topic destination string (SR-SHARE-02).
     *
     * <p>When no active links are registered for the sharer and the 30-second debounce
     * window has elapsed, a single {@code AUDIT.warn("share.relay.no_viewers ...")} is
     * emitted (SR-RELAY-09, SR-RELAY-20).
     *
     * @param wallId    the sharer's wallId (server-side use only; never in topic path)
     * @param hashtag   the hashtag that produced the event
     * @param eventType one of {@code creation}, {@code modification}, {@code deletion}
     * @param payload   the original toot message payload
     */
    public void relayTootEvent(
            final String wallId,
            final String hashtag,
            final String eventType,
            final Object payload) {

        if (wallId == null || wallId.isBlank()) {
            // Defensive: null wallId means no share links to relay to
            return;
        }

        // FLAW-3: keep the Status cache consistent with deletions. The deletion path relays a
        // CacheEntry (no Status); drop the matching cached toot so it disappears from initial
        // render + fallback polling. Done before the active-viewers check so the cache stays
        // correct even with no viewers currently connected. ("deletion" = StompEventType.DELETION.)
        if ("deletion".equals(eventType) && payload instanceof CacheEntry ce) {
            shareTootCache.remove(wallId, hashtag, ce.statusId());
        }

        Set<ShareLinkId> activeLinks = registry.getActiveLinks(wallId);

        if (activeLinks.isEmpty()) {
            emitNoViewersWarnWithDebounce(wallId);
            return;
        }

        for (ShareLinkId shareLinkId : activeLinks) {
            // Topic path: /topic/share/{shareLinkId}/{hashtag}/{eventType}
            // wallId is DELIBERATELY absent from this path (SR-SHARE-02)
            String topic = SHARE_TOPIC_PREFIX + shareLinkId.value()
                    + "/" + hashtag
                    + "/" + eventType;
            try {
                messagingTemplate.convertAndSend(topic, payload);
                log.debug("share.relay.published shareId-hash={} hashtag={} event={}",
                        LogScrubber.hash8(shareLinkId.value()), hashtag, eventType);
            } catch (Exception e) {
                log.warn("share.relay.publish_failed shareId-hash={} reason={}",
                        LogScrubber.hash8(shareLinkId.value()), e.getMessage());
            }
        }
    }

    /**
     * Re-publishes a typed Bigbone {@link Status} to all active share link topics as a
     * viewer-safe {@link ReadonlyTootView}, rendered per link via {@link ShareRenderingService}.
     *
     * <p>Called on the typed {@code StatusCreated} and {@code StatusEdited} paths in
     * {@link de.seism0saurus.glacier.mastodon.StompCallback} where a full Bigbone
     * {@link Status} is in scope (ADR-RENDER-01).
     *
     * <p>SR-RENDER-01: {@link ShareRenderingService#renderForView(Status, ShareLinkId)} is called
     * INSIDE the per-link loop with the current link's {@link ShareLinkId}. The view is never
     * hoisted before the loop or shared across links — each link's image proxy URLs are HMAC-signed
     * with that link's ID, so a view rendered for LINK_A is NOT valid for LINK_B.
     *
     * <p>SR-RENDER-01 failure containment: a render failure for one link is caught per iteration
     * and logged at WARN level; other links continue to receive their relay event.
     *
     * <p>SR-RENDER-02: every published {@link ReadonlyTootView} originates from
     * {@link ShareRenderingService#renderForView} — the relay never constructs a view directly.
     *
     * <p>SR-SHARE-02: the wallId is server-side only; it never appears in any topic path.
     *
     * <p>FLAW-2 (resolved — Option B2): The dockerized Mastodon hashtag WebSocket stream delivers
     * events as {@link de.seism0saurus.glacier.webservice.messaging.messages.GenericMessageContent}
     * ({@code GenericMessage}), NOT as typed {@link social.bigbone.api.entity.streaming.MastodonApiEvent.StreamEvent}.
     * The generic path (used by the real streaming subscription via {@code streaming().hashtag()})
     * now parses the raw payload JSON into a Bigbone {@link Status} using
     * {@code StompCallback#LENIENT_JSON} (kotlinx.serialization, ignoreUnknownKeys=true, isLenient=true)
     * and calls this Status-overload relay for full per-link {@link ReadonlyTootView} rendering.
     * Both the generic path (live dockerized Mastodon) and the typed path (SSE-capable instances)
     * now use this overload, ensuring consistent rendering across streaming backends.
     * See ADR-RENDER-01 / SR-FLAW2-01 (B2 resolution).
     *
     * @param wallId    the sharer's wallId (server-side use only; never in topic path — SR-SHARE-02)
     * @param hashtag   the hashtag that produced the event
     * @param eventType one of {@code creation} or {@code modification}
     * @param status    the typed Bigbone {@link Status} for per-link rendering (ADR-RENDER-01)
     */
    public void relayTootEvent(
            final String wallId,
            final String hashtag,
            final String eventType,
            final Status status) {

        if (wallId == null || wallId.isBlank()) {
            // Defensive: null wallId means no share links to relay to
            return;
        }

        // FLAW-3: cache the Status for the share view's initial render (catalog) and fallback
        // polling, BEFORE the active-viewers check — so a viewer who opens the link later (or polls
        // in fallback) still sees recent history. The Status is link-agnostic; per-link rendering
        // happens at serve time in getRecentMessages().
        shareTootCache.record(wallId, hashtag, status);

        Set<ShareLinkId> activeLinks = registry.getActiveLinks(wallId);

        if (activeLinks.isEmpty()) {
            emitNoViewersWarnWithDebounce(wallId);
            return;
        }

        for (ShareLinkId shareLinkId : activeLinks) {
            // Topic path: /topic/share/{shareLinkId}/{hashtag}/{eventType}
            // wallId is DELIBERATELY absent from this path (SR-SHARE-02)
            String topic = SHARE_TOPIC_PREFIX + shareLinkId.value()
                    + "/" + hashtag
                    + "/" + eventType;
            try {
                // SR-RENDER-01: render INSIDE the loop with THIS iteration's shareLinkId —
                // never hoist or cache the view across links; HMAC signing is per-link.
                // SR-RENDER-02: the rendered view is the ONLY thing published — never hand-built.
                ReadonlyTootView view = shareRenderingService.renderForView(status, shareLinkId);
                messagingTemplate.convertAndSend(topic, view);
                log.debug("share.relay.published shareId-hash={} hashtag={} event={}",
                        LogScrubber.hash8(shareLinkId.value()), hashtag, eventType);
            } catch (Exception e) {
                // SR-RENDER-01 failure containment: one link's render failure does not block
                // other links. Log at WARN with hashed IDs only (SR-LOG-01 / D-13 / SR-8).
                log.warn("share.relay.render_failed shareId-hash={} reason={}",
                        LogScrubber.hash8(shareLinkId.value()), e.getClass().getSimpleName());
            }
        }
    }

    // -------------------------------------------------------------------------
    // Fallback polling support
    // -------------------------------------------------------------------------

    /**
     * Returns recent messages for the given share link and hashtag from the in-memory ring buffer.
     *
     * <p>This method provides the backing data for the fallback polling endpoint
     * {@code GET /rest/share/{id}/messages?hashtag=...&since=...}. It looks up the
     * sharer's wallId from the active share link, then queries the message cache
     * for events newer than {@code since}.
     *
     * <p>The sharer's wallId is used server-side to query the cache; it is NEVER
     * returned to callers (SR-SHARE-02). The {@link ShareLinkService#resolve} method
     * is the only remaining use of {@code shareLinkService} in this class (SR-RELAY-10).
     *
     * @param shareLinkId the active share link ID
     * @param hashtag     the hashtag to fetch events for
     * @param since       sequence cursor (events with sequence &gt; since are returned);
     *                    {@code null} returns all cached events
     * @param now         the current time (for active-link resolution)
     * @return list of {@link CacheEntry} objects, empty if none found or link not active
     */
    public List<ReadonlyTootView> getRecentMessages(
            final ShareLinkId shareLinkId,
            final String hashtag,
            final Long since,
            final Instant now) {

        try {
            // Resolve the active share link to find the sharer's wallId (server-side only)
            Optional<de.seism0saurus.glacier.share.domain.ShareLink> matchingLink =
                    shareLinkService.resolve(shareLinkId, now);
            if (matchingLink.isEmpty()) {
                return List.of();
            }

            // FLAW-3: render the cached Statuses for THIS link. The sharerWallId is used only here,
            // server-side, to key the cache; it is never returned (SR-SHARE-02). `since` is not used
            // for server-side filtering — the viewer de-duplicates by status id and the bound keeps
            // the payload small (see ShareTootCache#recent).
            String sharerWallId = matchingLink.get().sharerWallId();
            List<Status> cached = shareTootCache.recent(sharerWallId, hashtag);

            List<ReadonlyTootView> views = new java.util.ArrayList<>(cached.size());
            for (Status status : cached) {
                try {
                    // SR-RENDER-01: render per-link (image proxy URLs are HMAC-signed per link).
                    views.add(shareRenderingService.renderForView(status, shareLinkId));
                } catch (Exception e) {
                    // One toot's render failure must not drop the rest of the history.
                    log.warn("share.relay.history_render_failed shareId-hash={} hashtag={} reason={}",
                            LogScrubber.hash8(shareLinkId.value()), hashtag, e.getClass().getSimpleName());
                }
            }
            return List.copyOf(views);
        } catch (Exception e) {
            log.warn("share.relay.getRecentMessages_failed shareId-hash={} hashtag={} reason={}",
                    LogScrubber.hash8(shareLinkId.value()), hashtag, e.getMessage());
            return List.of();
        }
    }

    // -------------------------------------------------------------------------
    // Revocation push
    // -------------------------------------------------------------------------

    /**
     * Pushes a revocation control message to the viewer topic for the given share link.
     *
     * <p>Viewers that receive this message MUST disconnect within the 1-second SLA window.
     * The Angular client should treat this as a terminal state and redirect to an expiry page.
     *
     * <p>Topic: {@code /topic/share/{shareLinkId}/control}
     * Payload: {@code {"type":"revoked"}}
     *
     * @param shareLinkId the revoked share link (not-null)
     */
    public void pushRevocation(final ShareLinkId shareLinkId) {
        String topic = SHARE_TOPIC_PREFIX + shareLinkId.value() + "/control";
        Map<String, String> controlPayload = Map.of("type", "revoked");

        try {
            messagingTemplate.convertAndSend(topic, controlPayload);
            AUDIT.info("share.revoke.pushed shareId-hash={}", LogScrubber.hash8(shareLinkId.value()));
        } catch (Exception e) {
            log.warn("share.revoke.push_failed shareId-hash={} reason={}",
                    LogScrubber.hash8(shareLinkId.value()), e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Emits {@code AUDIT.warn("share.relay.no_viewers ...")} at most once per
     * {@link #NO_VIEWERS_DEBOUNCE} window per sharerWallId (SR-RELAY-09, SR-RELAY-20).
     *
     * <p>The debounce prevents log flooding when a sharer has no active viewers for
     * an extended period. A warn is emitted on the first call for a given wallId, and
     * again only after the debounce window elapses.
     *
     * @param wallId the sharer's wallId (logged as hash8 — never raw)
     */
    private void emitNoViewersWarnWithDebounce(final String wallId) {
        // ACC-03: use injected clock so tests can freeze time and prove the 30-second suppression
        // window behaviorally (SR-RELAY-20).
        Instant now = clock.instant();
        Instant lastWarn = noViewersLastWarnAt.get(wallId);
        if (lastWarn == null || Duration.between(lastWarn, now).compareTo(NO_VIEWERS_DEBOUNCE) >= 0) {
            noViewersLastWarnAt.put(wallId, now);
            AUDIT.warn("share.relay.no_viewers wallId-hash8={}", LogScrubber.hash8(wallId));
        }
    }
}
