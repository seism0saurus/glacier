package de.seism0saurus.glacier.share.application;

import de.seism0saurus.glacier.share.domain.ShareLinkId;
import de.seism0saurus.glacier.webservice.cache.MessageCache;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import social.bigbone.api.entity.Status;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link ShareViewStompRelay}.
 *
 * <p>Verifies that toot events are re-published to viewer share topics,
 * revocation control messages are delivered, and the wallId never leaks
 * into viewer-facing topic paths (SR-SHARE-02).
 *
 * <p>After the ADR-RELAY-01 migration, routing uses {@link ShareLinkActivityRegistry}
 * instead of the deprecated {@link ShareLinkService#listBySharer}.
 *
 * <p>Security: SR-SHARE-02, SR-SHARE-06, ADR-SHARE-04, ADR-RELAY-01.
 */
class ShareViewStompRelayTest {

    private SimpMessagingTemplate mockTemplate;
    private ShareLinkService mockShareLinkService;
    private MessageCache mockMessageCache;
    private ShareLinkActivityRegistry mockRegistry;
    private ShareViewStompRelay relay;

    private static final String WALL_ID = "test-wall-id-AAAAAAAAAAAAAAAAAAAAAAAAAAAA";
    private static final String HASHTAG = "testhashtag";
    private static final ShareLinkId LINK_ID =
            ShareLinkId.fromUrlPath("sv_AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA");
    private static final ShareLinkId LINK_ID_2 =
            ShareLinkId.fromUrlPath("sv_BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB");

    @BeforeEach
    void setUp() {
        mockTemplate = mock(SimpMessagingTemplate.class);
        mockShareLinkService = mock(ShareLinkService.class);
        mockMessageCache = mock(MessageCache.class);
        mockRegistry = mock(ShareLinkActivityRegistry.class);
        // ShareRenderingService is null here: these tests exercise the Object/CacheEntry overload
        // only (relay-to-topic routing); the Status overload is tested in ShareViewStompRelayRenderTest.
        relay = new ShareViewStompRelay(mockTemplate, mockShareLinkService, mockMessageCache, mockRegistry,
                null, Clock.systemUTC());
    }

    // -----------------------------------------------------------------------
    // Core relay behavior
    // -----------------------------------------------------------------------

    @Test
    void relayTootEvent_publishesToShareTopic() {
        when(mockRegistry.getActiveLinks(WALL_ID)).thenReturn(Set.of(LINK_ID));

        Object payload = Map.of("id", "toot-1");
        relay.relayTootEvent(WALL_ID, HASHTAG, "creation", payload);

        String expectedTopic = "/topic/share/" + LINK_ID.value() + "/" + HASHTAG + "/creation";
        verify(mockTemplate).convertAndSend(eq(expectedTopic), eq(payload));
    }

    @Test
    void relayTootEvent_multipleActiveLinks_publishesToAll() {
        when(mockRegistry.getActiveLinks(WALL_ID)).thenReturn(Set.of(LINK_ID, LINK_ID_2));

        Object payload = Map.of("id", "toot-2");
        relay.relayTootEvent(WALL_ID, HASHTAG, "modification", payload);

        verify(mockTemplate).convertAndSend(
                eq("/topic/share/" + LINK_ID.value() + "/" + HASHTAG + "/modification"),
                eq(payload));
        verify(mockTemplate).convertAndSend(
                eq("/topic/share/" + LINK_ID_2.value() + "/" + HASHTAG + "/modification"),
                eq(payload));
    }

    @Test
    void relayTootEvent_noActiveLinks_noPublish() {
        when(mockRegistry.getActiveLinks(WALL_ID)).thenReturn(Set.of());

        relay.relayTootEvent(WALL_ID, HASHTAG, "creation", Map.of());

        verifyNoInteractions(mockTemplate);
    }

    // -----------------------------------------------------------------------
    // Security: wallId must NOT appear in viewer-facing topics (SR-SHARE-02)
    // -----------------------------------------------------------------------

    @Test
    void relayTootEvent_wallIdNeverInTopicPath() {
        when(mockRegistry.getActiveLinks(WALL_ID)).thenReturn(Set.of(LINK_ID));

        relay.relayTootEvent(WALL_ID, HASHTAG, "creation", Map.of());

        ArgumentCaptor<String> topicCaptor = ArgumentCaptor.forClass(String.class);
        verify(mockTemplate).convertAndSend(topicCaptor.capture(), any(Object.class));

        String topic = topicCaptor.getValue();
        assertThat(topic).doesNotContain(WALL_ID);
        assertThat(topic).startsWith("/topic/share/");
    }

    // -----------------------------------------------------------------------
    // Revocation push (1-second disconnect SLA)
    // -----------------------------------------------------------------------

    @Test
    void pushRevocation_publishesControlMessage() {
        relay.pushRevocation(LINK_ID);

        String expectedControl = "/topic/share/" + LINK_ID.value() + "/control";
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> payloadCaptor =
                (ArgumentCaptor<Map<String, String>>) (ArgumentCaptor<?>) ArgumentCaptor.forClass(Map.class);
        verify(mockTemplate).convertAndSend(eq(expectedControl), payloadCaptor.capture());

        Map<String, String> payload = payloadCaptor.getValue();
        assertThat(payload).containsEntry("type", "revoked");
    }

    @Test
    void pushRevocation_controlTopicNeverContainsWallId() {
        relay.pushRevocation(LINK_ID);

        ArgumentCaptor<String> topicCaptor = ArgumentCaptor.forClass(String.class);
        verify(mockTemplate).convertAndSend(topicCaptor.capture(), any(Object.class));

        // Control topic path must not leak any wallId (wallId is not in ShareLinkId anyway,
        // but explicitly assert path structure)
        String topic = topicCaptor.getValue();
        assertThat(topic).startsWith("/topic/share/");
        assertThat(topic).endsWith("/control");
    }

    // -----------------------------------------------------------------------
    // Defensive: null/blank wallId or hashtag
    // -----------------------------------------------------------------------

    @Test
    void relayTootEvent_nullWallId_doesNotThrow() {
        assertThatCode(() -> relay.relayTootEvent(null, HASHTAG, "creation", Map.of()))
                .doesNotThrowAnyException();
        verifyNoInteractions(mockTemplate);
    }

    @Test
    void relayTootEvent_blankHashtag_doesNotThrow() {
        when(mockRegistry.getActiveLinks(any())).thenReturn(Set.of());
        assertThatCode(() -> relay.relayTootEvent(WALL_ID, "", "creation", Map.of()))
                .doesNotThrowAnyException();
    }

    // -----------------------------------------------------------------------
    // Defensive wallId guards on both overloads + getRecentMessages empty path
    // -----------------------------------------------------------------------

    @Test
    void relayTootEvent_statusOverload_nullWallId_doesNotThrowOrPublish() {
        // The Status overload's null-wallId guard returns before touching the (null) render
        // service, so no NPE and no fan-out.
        assertThatCode(() -> relay.relayTootEvent(null, HASHTAG, "creation", (Status) null))
                .doesNotThrowAnyException();
        verifyNoInteractions(mockTemplate);
    }

    @Test
    void relayTootEvent_statusOverload_blankWallId_doesNotThrow() {
        assertThatCode(() -> relay.relayTootEvent("  ", HASHTAG, "creation", (Status) null))
                .doesNotThrowAnyException();
    }

    @Test
    void relayTootEvent_objectOverload_blankWallId_doesNotThrow() {
        assertThatCode(() -> relay.relayTootEvent("  ", HASHTAG, "creation", Map.of()))
                .doesNotThrowAnyException();
    }

    @Test
    void getRecentMessages_linkNotActive_returnsEmptyList() {
        // resolve() empty means the link is unknown/expired/revoked → no catalog leaked.
        when(mockShareLinkService.resolve(eq(LINK_ID), any())).thenReturn(Optional.empty());

        var result = relay.getRecentMessages(LINK_ID, HASHTAG, null, Instant.now());

        assertThat(result).isEmpty();
    }

}
