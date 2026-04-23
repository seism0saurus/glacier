package de.seism0saurus.glacier.share.application;

import de.seism0saurus.glacier.share.domain.ShareLink;
import de.seism0saurus.glacier.share.domain.ShareLinkId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Map;

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
 * <p>Security: SR-SHARE-02, SR-SHARE-06, ADR-SHARE-04.
 */
class ShareViewStompRelayTest {

    private SimpMessagingTemplate mockTemplate;
    private ShareLinkService mockShareLinkService;
    private ShareViewStompRelay relay;

    private static final String WALL_ID = "test-wall-id";
    private static final String HASHTAG = "testhashtag";
    private static final ShareLinkId LINK_ID =
            new ShareLinkId("sv_AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA");
    private static final ShareLinkId LINK_ID_2 =
            new ShareLinkId("sv_BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB");

    @BeforeEach
    void setUp() {
        mockTemplate = mock(SimpMessagingTemplate.class);
        mockShareLinkService = mock(ShareLinkService.class);
        relay = new ShareViewStompRelay(mockTemplate, mockShareLinkService);
    }

    // -----------------------------------------------------------------------
    // Core relay behavior
    // -----------------------------------------------------------------------

    @Test
    void relayTootEvent_publishesToShareTopic() {
        ShareLink activeLink = shareLink(LINK_ID, WALL_ID);
        when(mockShareLinkService.listBySharer(eq(WALL_ID), any(Instant.class)))
                .thenReturn(List.of(activeLink));

        Object payload = Map.of("id", "toot-1");
        relay.relayTootEvent(WALL_ID, HASHTAG, "creation", payload);

        String expectedTopic = "/topic/share/" + LINK_ID.getValue() + "/" + HASHTAG + "/creation";
        verify(mockTemplate).convertAndSend(eq(expectedTopic), eq(payload));
    }

    @Test
    void relayTootEvent_multipleActiveLinks_publishesToAll() {
        ShareLink link1 = shareLink(LINK_ID, WALL_ID);
        ShareLink link2 = shareLink(LINK_ID_2, WALL_ID);
        when(mockShareLinkService.listBySharer(eq(WALL_ID), any(Instant.class)))
                .thenReturn(List.of(link1, link2));

        Object payload = Map.of("id", "toot-2");
        relay.relayTootEvent(WALL_ID, HASHTAG, "modification", payload);

        verify(mockTemplate).convertAndSend(
                eq("/topic/share/" + LINK_ID.getValue() + "/" + HASHTAG + "/modification"),
                eq(payload));
        verify(mockTemplate).convertAndSend(
                eq("/topic/share/" + LINK_ID_2.getValue() + "/" + HASHTAG + "/modification"),
                eq(payload));
    }

    @Test
    void relayTootEvent_noActiveLinks_noPublish() {
        when(mockShareLinkService.listBySharer(eq(WALL_ID), any(Instant.class)))
                .thenReturn(List.of());

        relay.relayTootEvent(WALL_ID, HASHTAG, "creation", Map.of());

        verifyNoInteractions(mockTemplate);
    }

    // -----------------------------------------------------------------------
    // Security: wallId must NOT appear in viewer-facing topics (SR-SHARE-02)
    // -----------------------------------------------------------------------

    @Test
    void relayTootEvent_wallIdNeverInTopicPath() {
        ShareLink link = shareLink(LINK_ID, WALL_ID);
        when(mockShareLinkService.listBySharer(eq(WALL_ID), any(Instant.class)))
                .thenReturn(List.of(link));

        relay.relayTootEvent(WALL_ID, HASHTAG, "creation", Map.of());

        @SuppressWarnings("unchecked")
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

        String expectedControl = "/topic/share/" + LINK_ID.getValue() + "/control";
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
        when(mockShareLinkService.listBySharer(any(), any())).thenReturn(List.of());
        assertThatCode(() -> relay.relayTootEvent(WALL_ID, "", "creation", Map.of()))
                .doesNotThrowAnyException();
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static ShareLink shareLink(ShareLinkId id, String wallId) {
        Instant now = Instant.now();
        return new ShareLink(id, wallId, now, now.plusSeconds(7 * 24 * 3600));
    }
}
