package de.seism0saurus.glacier.share.application;

import de.seism0saurus.glacier.share.domain.ShareLinkId;
import de.seism0saurus.glacier.webservice.cache.MessageCache;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for the {@code relayTootEvent} method of {@link ShareViewStompRelay}.
 *
 * <p>Verifies that the relay reads active links from {@link ShareLinkActivityRegistry}
 * — not from the deprecated {@link ShareLinkService#listBySharer} — and publishes
 * STOMP messages to all active link topics.
 *
 * <p>Security: SR-SHARE-02 (wallId never in topic path), SR-RELAY-09 (no-viewers audit warn),
 * SR-RELAY-20 (30-second debounce).
 */
class ShareViewStompRelayRelayTest {

    private SimpMessagingTemplate mockTemplate;
    private ShareLinkService mockShareLinkService;
    private MessageCache mockMessageCache;
    private ShareLinkActivityRegistry mockRegistry;
    private ShareViewStompRelay relay;

    private static final String WALL_ID = "test-wall-id-relay-AAAAAAAAAAAAAAAAAAAAA";
    private static final String HASHTAG = "testhashtag";
    private static final ShareLinkId LINK_1 =
            ShareLinkId.fromUrlPath("sv_AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA");
    private static final ShareLinkId LINK_2 =
            ShareLinkId.fromUrlPath("sv_BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB");

    @BeforeEach
    void setUp() {
        mockTemplate = mock(SimpMessagingTemplate.class);
        mockShareLinkService = mock(ShareLinkService.class);
        mockMessageCache = mock(MessageCache.class);
        mockRegistry = mock(ShareLinkActivityRegistry.class);
        relay = new ShareViewStompRelay(mockTemplate, mockShareLinkService, mockMessageCache, mockRegistry);
    }

    // -----------------------------------------------------------------------
    // Core routing from registry
    // -----------------------------------------------------------------------

    /**
     * When the registry has two active links for the sharer, both receive the toot payload.
     *
     * <p>Arrange: registry returns {LINK_1, LINK_2} for WALL_ID.
     * <p>Act:     relayTootEvent(WALL_ID, "hashtag", "creation", payload).
     * <p>Assert:  STOMP template receives convertAndSend for both topic paths.
     */
    @Test
    void relayTootEvent_publishesToAllActiveLinks() {
        when(mockRegistry.getActiveLinks(WALL_ID)).thenReturn(Set.of(LINK_1, LINK_2));

        Object payload = Map.of("id", "toot-relay-1");
        relay.relayTootEvent(WALL_ID, HASHTAG, "creation", payload);

        verify(mockTemplate).convertAndSend(
                eq("/topic/share/" + LINK_1.value() + "/" + HASHTAG + "/creation"),
                eq(payload));
        verify(mockTemplate).convertAndSend(
                eq("/topic/share/" + LINK_2.value() + "/" + HASHTAG + "/creation"),
                eq(payload));
    }

    /**
     * When the registry is empty for the sharer, no STOMP message is sent.
     *
     * <p>Arrange: registry returns empty set.
     * <p>Act:     relayTootEvent.
     * <p>Assert:  no convertAndSend calls.
     */
    @Test
    void relayTootEvent_emptyRegistry_noStomp() {
        when(mockRegistry.getActiveLinks(WALL_ID)).thenReturn(Set.of());

        relay.relayTootEvent(WALL_ID, HASHTAG, "creation", Map.of());

        verifyNoInteractions(mockTemplate);
    }

    /**
     * When the registry is empty, an AUDIT warn is emitted — but the second call within
     * 30 seconds must NOT emit another warn (30-second debounce, SR-RELAY-09 / SR-RELAY-20).
     *
     * <p>We verify the debounce indirectly: calling relayTootEvent twice in rapid succession
     * must not cause two separate STOMP deliveries, and the no-viewers path must not
     * throw or cause duplicate side effects beyond what can be asserted here.
     * (The actual AUDIT logger output is checked by log-capture tests only; here we ensure
     * no exception is thrown and no STOMP template call happens.)
     *
     * <p>Arrange: registry returns empty set for two consecutive calls.
     * <p>Act:     call relayTootEvent twice.
     * <p>Assert:  no exceptions; no STOMP interactions.
     */
    @Test
    void relayTootEvent_emptyRegistry_auditWarnWithDebounce_noException() {
        when(mockRegistry.getActiveLinks(WALL_ID)).thenReturn(Set.of());

        assertThatCode(() -> {
            relay.relayTootEvent(WALL_ID, HASHTAG, "creation", Map.of());
            relay.relayTootEvent(WALL_ID, HASHTAG, "creation", Map.of());
        }).doesNotThrowAnyException();

        verifyNoInteractions(mockTemplate);
    }

    /**
     * A null wallId must not throw and must produce no STOMP output.
     *
     * <p>Arrange: wallId is null.
     * <p>Act:     relayTootEvent(null, ...).
     * <p>Assert:  no exception; no interactions on template or registry.
     */
    @Test
    void relayTootEvent_nullWallId_noOp() {
        assertThatCode(() -> relay.relayTootEvent(null, HASHTAG, "creation", Map.of()))
                .doesNotThrowAnyException();
        verifyNoInteractions(mockTemplate);
        verifyNoInteractions(mockRegistry);
    }

    // -----------------------------------------------------------------------
    // Security: wallId must NOT appear in viewer-facing topics (SR-SHARE-02)
    // -----------------------------------------------------------------------

    /**
     * The topic path must never contain the sharer's wallId.
     *
     * <p>SR-SHARE-02: the sharer's wallId is server-side only and must never reach any
     * viewer-facing topic destination string.
     *
     * <p>Arrange: registry has LINK_1 for WALL_ID.
     * <p>Act:     relayTootEvent.
     * <p>Assert:  captured topic path does not contain WALL_ID.
     */
    @Test
    void relayTootEvent_wallIdNeverInTopicPath() {
        when(mockRegistry.getActiveLinks(WALL_ID)).thenReturn(Set.of(LINK_1));

        relay.relayTootEvent(WALL_ID, HASHTAG, "creation", Map.of());

        var topicCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(mockTemplate).convertAndSend(topicCaptor.capture(), any(Object.class));

        assertThat(topicCaptor.getValue()).doesNotContain(WALL_ID);
        assertThat(topicCaptor.getValue()).startsWith("/topic/share/");
    }
}
