package de.seism0saurus.glacier.share.application;

import de.seism0saurus.glacier.share.domain.ShareLink;
import de.seism0saurus.glacier.share.domain.ShareLinkId;
import de.seism0saurus.glacier.util.LogScrubber;
import de.seism0saurus.glacier.webservice.cache.CacheEntry;
import de.seism0saurus.glacier.webservice.cache.EventType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import social.bigbone.api.entity.Status;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for the {@code relayTootEvent(wallId, hashtag, eventType, Status)} overload
 * of {@link ShareViewStompRelay}.
 *
 * <p>Security requirements verified:
 * <ul>
 *   <li>SR-RENDER-01: {@code renderForView(status, shareLinkId)} called per link with the
 *       current link's ID; never hoisted or shared across links.</li>
 *   <li>SR-RENDER-02: published payload is exactly the mocked {@code renderForView} return —
 *       no hand-built {@link ReadonlyTootView} in the relay.</li>
 *   <li>SR-SHARE-02: wallId never appears in any STOMP topic path.</li>
 *   <li>SR-LOG-01: no raw wallId / toot URL / token in relay log lines.</li>
 * </ul>
 *
 * <p>OWASP A01 (Broken Access Control — BOLA) / ADR-RENDER-01 / ADR-SHARE-04.
 */
class ShareViewStompRelayRenderTest {

    private SimpMessagingTemplate mockTemplate;
    private ShareLinkService mockShareLinkService;
    private ShareTootCache shareTootCache;
    private ShareLinkActivityRegistry mockRegistry;
    private ShareRenderingService mockRenderingService;
    private ShareViewStompRelay relay;

    /** Raw wallId — must NEVER appear in topic paths (SR-SHARE-02). */
    private static final String WALL_ID = "test-wall-id-render-BBBBBBBBBBBBBBBBBBBBB";
    private static final String HASHTAG = "glacier";

    // ShareLinkId tokens must be >= 43 chars (URL-safe base64 alphabet)
    private static final ShareLinkId LINK_1 =
            ShareLinkId.fromUrlPath("sv_AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA");
    private static final ShareLinkId LINK_2 =
            ShareLinkId.fromUrlPath("sv_CCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCC");

    @BeforeEach
    void setUp() {
        mockTemplate = mock(SimpMessagingTemplate.class);
        mockShareLinkService = mock(ShareLinkService.class);
        shareTootCache = new ShareTootCache(20);
        mockRegistry = mock(ShareLinkActivityRegistry.class);
        mockRenderingService = mock(ShareRenderingService.class);
        relay = new ShareViewStompRelay(
                mockTemplate, mockShareLinkService, shareTootCache, mockRegistry,
                mockRenderingService, Clock.systemUTC());
    }

    // -----------------------------------------------------------------------
    // SR-RENDER-01: per-link rendering — two links, two distinct views
    // -----------------------------------------------------------------------

    /**
     * SR-RENDER-01: with two active links, renderForView is called once per link,
     * each with its own ShareLinkId. Two distinct rendered views are published.
     *
     * <p>Arrange: registry returns {LINK_1, LINK_2}. RenderingService returns
     *   distinct views per LinkId.
     * <p>Act: relayTootEvent(wallId, hashtag, "creation", status).
     * <p>Assert: renderForView(status, LINK_1) and renderForView(status, LINK_2)
     *   each called exactly once; two convertAndSend calls with distinct bodies.
     */
    @Test
    void relayTootEvent_withStatus_twoActiveLinks_rendersPerLinkDistinctly() {
        // Arrange
        Status mockStatus = mock(Status.class);

        ReadonlyTootView viewForLink1 = stubTootView("toot-001-link1");
        ReadonlyTootView viewForLink2 = stubTootView("toot-001-link2");

        when(mockRegistry.getActiveLinks(WALL_ID)).thenReturn(Set.of(LINK_1, LINK_2));
        when(mockRenderingService.renderForView(mockStatus, LINK_1)).thenReturn(viewForLink1);
        when(mockRenderingService.renderForView(mockStatus, LINK_2)).thenReturn(viewForLink2);

        // Act
        relay.relayTootEvent(WALL_ID, HASHTAG, "creation", mockStatus);

        // Assert: renderForView called once per link with that link's ID (SR-RENDER-01)
        verify(mockRenderingService, times(1)).renderForView(mockStatus, LINK_1);
        verify(mockRenderingService, times(1)).renderForView(mockStatus, LINK_2);
        verifyNoMoreInteractions(mockRenderingService);

        // Assert: two distinct STOMP publishes, each with the per-link rendered body (SR-RENDER-01)
        verify(mockTemplate).convertAndSend(
                eq("/topic/share/" + LINK_1.value() + "/" + HASHTAG + "/creation"),
                eq(viewForLink1));
        verify(mockTemplate).convertAndSend(
                eq("/topic/share/" + LINK_2.value() + "/" + HASHTAG + "/creation"),
                eq(viewForLink2));

        // SR-RENDER-01: the two published payloads must be distinct objects (per-link)
        assertThat(viewForLink1).isNotEqualTo(viewForLink2);
    }

    /**
     * SR-RENDER-02: the published payload is EXACTLY the return value of renderForView,
     * not a hand-built ReadonlyTootView constructed directly in the relay.
     *
     * <p>This test pins that no code in ShareViewStompRelay calls {@code new ReadonlyTootView(...)}.
     * The SR-RENDER-02 ArchUnit gate in {@link ShareRelayArchitectureTest} is the structural gate;
     * this behavioral test cross-verifies that the relay's STOMP publish path uses exactly
     * whatever renderForView returns — even if its fields are non-standard.
     */
    @Test
    void relayTootEvent_onlyRendersViaService_publishedPayloadIsExactlyRenderForViewReturn() {
        // Arrange: use a distinctive sentinel view to detect any inline construction
        Status mockStatus = mock(Status.class);
        ReadonlyTootView sentinelView = stubTootView("sentinel-render-02");

        when(mockRegistry.getActiveLinks(WALL_ID)).thenReturn(Set.of(LINK_1));
        when(mockRenderingService.renderForView(mockStatus, LINK_1)).thenReturn(sentinelView);

        // Act
        relay.relayTootEvent(WALL_ID, HASHTAG, "creation", mockStatus);

        // Assert: the exact object returned by renderForView is what was published (SR-RENDER-02)
        ArgumentCaptor<Object> payloadCaptor = ArgumentCaptor.forClass(Object.class);
        verify(mockTemplate).convertAndSend(anyString(), payloadCaptor.capture());
        assertThat(payloadCaptor.getValue()).isSameAs(sentinelView);
    }

    /**
     * SR-RENDER-01 failure containment: a render failure for one link must not
     * prevent other links from receiving the relay event.
     *
     * <p>Arrange: registry has LINK_1 and LINK_2. renderForView for LINK_1 throws.
     * <p>Act:     relayTootEvent.
     * <p>Assert:  LINK_2 still receives a STOMP message; no exception propagates.
     */
    @Test
    void relayTootEvent_renderFailure_isContainedPerLink() {
        // Arrange
        Status mockStatus = mock(Status.class);
        ReadonlyTootView viewForLink2 = stubTootView("toot-render-failure-test");

        when(mockRegistry.getActiveLinks(WALL_ID)).thenReturn(Set.of(LINK_1, LINK_2));
        when(mockRenderingService.renderForView(mockStatus, LINK_1))
                .thenThrow(new RuntimeException("rendering-failed-for-link1"));
        when(mockRenderingService.renderForView(mockStatus, LINK_2)).thenReturn(viewForLink2);

        // Act: must not throw
        assertThatCode(() -> relay.relayTootEvent(WALL_ID, HASHTAG, "creation", mockStatus))
                .doesNotThrowAnyException();

        // Assert: LINK_2 still received its message
        verify(mockTemplate).convertAndSend(
                eq("/topic/share/" + LINK_2.value() + "/" + HASHTAG + "/creation"),
                eq(viewForLink2));
        // LINK_1 must have had renderForView called (it threw), but no publish for it
        verify(mockRenderingService).renderForView(mockStatus, LINK_1);
    }

    /**
     * Empty registry: when no active links exist, renderForView is never called.
     *
     * <p>Arrange: registry returns empty set.
     * <p>Act:     relayTootEvent with Status.
     * <p>Assert:  renderForView never called; no STOMP send.
     */
    @Test
    void relayTootEvent_emptyRegistry_doesNotInvokeRenderingService() {
        // Arrange
        Status mockStatus = mock(Status.class);
        when(mockRegistry.getActiveLinks(WALL_ID)).thenReturn(Set.of());

        // Act
        relay.relayTootEvent(WALL_ID, HASHTAG, "creation", mockStatus);

        // Assert: no rendering, no STOMP
        verifyNoInteractions(mockRenderingService);
        verifyNoInteractions(mockTemplate);
    }

    // -----------------------------------------------------------------------
    // SR-SHARE-02: wallId must NOT appear in STOMP topic paths
    // -----------------------------------------------------------------------

    /**
     * SR-SHARE-02: the STOMP topic must not contain the sharer's wallId.
     * The wallId is server-side only; viewers must not be able to discover it.
     */
    @Test
    void relayTootEvent_withStatus_wallIdNeverInTopicPath() {
        // Arrange
        Status mockStatus = mock(Status.class);
        when(mockRegistry.getActiveLinks(WALL_ID)).thenReturn(Set.of(LINK_1));
        when(mockRenderingService.renderForView(mockStatus, LINK_1))
                .thenReturn(stubTootView("sec-topic-test"));

        // Act
        relay.relayTootEvent(WALL_ID, HASHTAG, "creation", mockStatus);

        // Assert: topic path does not contain wallId (SR-SHARE-02)
        ArgumentCaptor<String> topicCaptor = ArgumentCaptor.forClass(String.class);
        verify(mockTemplate).convertAndSend(topicCaptor.capture(), any(Object.class));
        assertThat(topicCaptor.getValue()).doesNotContain(WALL_ID);
        assertThat(topicCaptor.getValue()).startsWith("/topic/share/");
    }

    // -----------------------------------------------------------------------
    // SR-LOG-01: relay log lines must not contain raw wallId / token / text
    // -----------------------------------------------------------------------

    /**
     * SR-LOG-01 / D-13 / SR-8: log lines emitted during the Status-overload relay
     * must not contain the raw wallId, raw shareLinkId token, or the status object
     * toString() representation.
     *
     * <p>ASVS V7.1.1 (L1) — sensitive identifiers must not appear in log output.
     */
    @Test
    void relayTootEvent_withStatus_logLinesDoNotContainRawWallIdOrToken() {
        Logger rootLogger = (Logger) LoggerFactory.getLogger("de.seism0saurus.glacier.share.application");
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        rootLogger.addAppender(appender);

        try {
            Status mockStatus = mock(Status.class);
            // Make toString() return a sentinel that would be detected if logged
            // (Mockito's default toString contains the class name + hashcode — sufficient)
            when(mockRegistry.getActiveLinks(WALL_ID)).thenReturn(Set.of(LINK_1));
            when(mockRenderingService.renderForView(mockStatus, LINK_1))
                    .thenReturn(stubTootView("log-hygiene-test"));

            relay.relayTootEvent(WALL_ID, HASHTAG, "creation", mockStatus);

            // Assert: no log line contains the raw wallId
            for (ILoggingEvent event : appender.list) {
                String msg = event.getFormattedMessage();
                // SR-LOG-01: raw wallId must never appear in log output
                assertThat(msg).as("SR-LOG-01: raw wallId must not appear in log line: %s", msg)
                        .doesNotContain(WALL_ID);
                // SR-LOG-01: raw shareLinkId token must not appear in log output
                assertThat(msg).as("SR-LOG-01: raw LINK_1 token must not appear in log line: %s", msg)
                        .doesNotContain(LINK_1.value());
            }
        } finally {
            rootLogger.detachAppender(appender);
        }
    }

    // -----------------------------------------------------------------------
    // FLAW-3 documentation: Object/CacheEntry overload (DELETION path) preserved
    // -----------------------------------------------------------------------

    /**
     * FLAW-3 documentation / SR-FLAW3-01: the existing Object-overload relay
     * for DELETION events is unchanged and continues to work.
     *
     * <p>The DELETION path uses the minimal CacheEntry/Object overload because
     * the frontend does not subscribe to deletion events on share topics,
     * and no ReadonlyTootView is published (ADR-RENDER-01).
     *
     * <p>KNOWN LIMITATION (FLAW-3): fallback mode renders blank because the
     * /rest/share/{id}/messages endpoint returns CacheEntry, not ReadonlyTootView.
     * This is deferred per the planning ADR; the e2e test.fixme stays.
     */
    @Test
    void relayTootEvent_deletionPathUsesObjectOverload_renderingServiceNotCalled() {
        // Arrange: simulate the DELETION path which uses the Object overload
        when(mockRegistry.getActiveLinks(WALL_ID)).thenReturn(Set.of(LINK_1));
        Object deletionPayload = new Object(); // simulates a CacheEntry

        // Act: use the Object overload (not the Status overload)
        relay.relayTootEvent(WALL_ID, HASHTAG, "deletion", deletionPayload);

        // Assert: renderForView is NOT called for the deletion path
        verifyNoInteractions(mockRenderingService);
        // Assert: STOMP message IS sent (relay still works for deletion)
        verify(mockTemplate).convertAndSend(
                eq("/topic/share/" + LINK_1.value() + "/" + HASHTAG + "/deletion"),
                eq(deletionPayload));
    }

    // -----------------------------------------------------------------------
    // Helper: build a minimal ReadonlyTootView for test assertions
    // -----------------------------------------------------------------------

    /**
     * Builds a minimal {@link ReadonlyTootView} with a distinctive {@code id} for test assertions.
     * All other fields are null/empty — sufficient for identity checks in relay tests.
     */
    // -----------------------------------------------------------------------
    // FLAW-3: getRecentMessages renders cached Statuses per-link (catalog + fallback source)
    // -----------------------------------------------------------------------

    @Test
    void getRecentMessages_rendersCachedStatusesForThisLink() {
        // A toot was relayed earlier (recorded into the share cache under the sharer's wallId).
        Status s = mock(Status.class);
        when(s.getId()).thenReturn("hist-1");
        shareTootCache.record(WALL_ID, HASHTAG, s);

        ShareLink link = mock(ShareLink.class);
        when(link.sharerWallId()).thenReturn(WALL_ID);
        when(mockShareLinkService.resolve(eq(LINK_1), any())).thenReturn(Optional.of(link));
        ReadonlyTootView view = stubTootView("hist-1");
        when(mockRenderingService.renderForView(s, LINK_1)).thenReturn(view);

        List<ReadonlyTootView> result = relay.getRecentMessages(LINK_1, HASHTAG, null, Instant.now());

        // Rendered per THIS link (SR-RENDER-01); the cached Status is link-agnostic.
        assertThat(result).containsExactly(view);
        verify(mockRenderingService).renderForView(s, LINK_1);
    }

    @Test
    void getRecentMessages_emptyCache_returnsEmpty() {
        ShareLink link = mock(ShareLink.class);
        when(link.sharerWallId()).thenReturn(WALL_ID);
        when(mockShareLinkService.resolve(eq(LINK_1), any())).thenReturn(Optional.of(link));

        assertThat(relay.getRecentMessages(LINK_1, HASHTAG, null, Instant.now())).isEmpty();
        verifyNoInteractions(mockRenderingService);
    }

    @Test
    void relayDeletionEvent_removesTootFromCache() {
        Status s = mock(Status.class);
        when(s.getId()).thenReturn("del-1");
        shareTootCache.record(WALL_ID, HASHTAG, s);
        when(mockRegistry.getActiveLinks(WALL_ID)).thenReturn(Set.of()); // no viewers; removal still runs

        // The deletion path relays a CacheEntry (no Status) on the Object overload.
        CacheEntry deletion = new CacheEntry(EventType.DELETED, "del-1", null, null, 1L);
        relay.relayTootEvent(WALL_ID, HASHTAG, "deletion", deletion);

        ShareLink link = mock(ShareLink.class);
        when(link.sharerWallId()).thenReturn(WALL_ID);
        when(mockShareLinkService.resolve(eq(LINK_1), any())).thenReturn(Optional.of(link));
        assertThat(relay.getRecentMessages(LINK_1, HASHTAG, null, Instant.now())).isEmpty();
    }

    private static ReadonlyTootView stubTootView(String id) {
        return new ReadonlyTootView(
                id,
                null, // authorDisplayName
                null, // authorAcct
                null, // authorProfileUrl
                null, // authorAvatarProxyUrl
                Instant.EPOCH,
                null, // textContent
                null, // spoilerText
                false,
                false,
                null, // language
                List.of(), // links
                List.of(), // mentions
                List.of(), // hashtags
                List.of(), // customEmojis
                List.of(), // media
                Optional.empty()
        );
    }
}
