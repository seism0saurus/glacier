package de.seism0saurus.glacier.share.application;

import de.seism0saurus.glacier.share.domain.ShareLinkId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for the {@code @EventListener} methods of {@link ShareViewStompRelay}.
 *
 * <p>Verifies that:
 * <ul>
 *   <li>{@code onActivate} registers the link in the registry and emits AUDIT.info.</li>
 *   <li>{@code onRevoke} calls {@code registry.unregister()} and then
 *       {@code pushRevocation()} in the correct order (SR-RELAY-22).</li>
 * </ul>
 *
 * <p>Security: SR-RELAY-14, SR-RELAY-15, SR-RELAY-22, ARCH-RELAY-02b.
 */
class ShareViewStompRelayEventTest {

    private SimpMessagingTemplate mockTemplate;
    private ShareLinkService mockShareLinkService;
    private ShareTootCache shareTootCache;
    private ShareLinkActivityRegistry mockRegistry;
    private ShareViewStompRelay relay;

    private static final String SHARER_WALL_ID = "sharer-wall-id-event-AAAAAAAAAAAAAAAAAAA";
    private static final ShareLinkId LINK_1 =
            ShareLinkId.fromUrlPath("sv_AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA");

    @BeforeEach
    void setUp() {
        mockTemplate = mock(SimpMessagingTemplate.class);
        mockShareLinkService = mock(ShareLinkService.class);
        shareTootCache = new ShareTootCache(20);
        mockRegistry = mock(ShareLinkActivityRegistry.class);
        // ShareRenderingService is null here: EventListener tests do not exercise the render path.
        relay = new ShareViewStompRelay(mockTemplate, mockShareLinkService, shareTootCache, mockRegistry,
                null, Clock.systemUTC());
    }

    // -----------------------------------------------------------------------
    // onActivate
    // -----------------------------------------------------------------------

    /**
     * Receiving a {@link ShareLinkActivatedEvent} must call {@code registry.register()}.
     *
     * <p>SR-RELAY-14: the AUDIT.info is emitted by the relay; we verify the registry
     * interaction here and trust the AUDIT logger is wired correctly via the real
     * logback configuration in integration tests.
     *
     * <p>Arrange: stub resolve so register() would return true.
     * <p>Act:     fire onActivate with a valid event.
     * <p>Assert:  registry.register() called with correct sharerWallId and shareLinkId.
     */
    @Test
    void onActivate_registersInRegistry() {
        when(mockRegistry.register(eq(SHARER_WALL_ID), eq(LINK_1), any(ShareLinkService.class), any(Instant.class)))
                .thenReturn(true);

        relay.onActivate(new ShareLinkActivatedEvent(SHARER_WALL_ID, LINK_1));

        verify(mockRegistry).register(eq(SHARER_WALL_ID), eq(LINK_1), any(ShareLinkService.class), any(Instant.class));
    }

    // -----------------------------------------------------------------------
    // onRevoke
    // -----------------------------------------------------------------------

    /**
     * Receiving a {@link ShareLinkRevokedEvent} must call both {@code registry.unregister()}
     * and {@code pushRevocation()}.
     *
     * <p>SR-RELAY-15: the relay must unregister the link and push the revocation control
     * message so viewers disconnect within the SLA window.
     *
     * <p>Arrange: standard mocks.
     * <p>Act:     fire onRevoke with a valid event.
     * <p>Assert:  registry.unregister() called; pushRevocation topic received by template.
     */
    @Test
    void onRevoke_unregistersAndPushesRevocation() {
        relay.onRevoke(new ShareLinkRevokedEvent(SHARER_WALL_ID, LINK_1));

        verify(mockRegistry).unregister(SHARER_WALL_ID, LINK_1);
        // pushRevocation sends to /topic/share/{id}/control
        String expectedControlTopic = "/topic/share/" + LINK_1.value() + "/control";
        verify(mockTemplate).convertAndSend(eq(expectedControlTopic), any(Object.class));
    }

    /**
     * {@code registry.unregister()} must be called BEFORE {@code pushRevocation()}
     * (SR-RELAY-22: audit ordering correctness — no new deliveries can arrive after revoke).
     *
     * <p>Arrange: standard mocks.
     * <p>Act:     fire onRevoke.
     * <p>Assert:  Mockito InOrder confirms unregister precedes convertAndSend (push).
     */
    @Test
    void onRevoke_auditLoggedBeforePushRevocation_orderingGuarantee() {
        InOrder inOrder = inOrder(mockRegistry, mockTemplate);

        relay.onRevoke(new ShareLinkRevokedEvent(SHARER_WALL_ID, LINK_1));

        inOrder.verify(mockRegistry).unregister(SHARER_WALL_ID, LINK_1);
        inOrder.verify(mockTemplate).convertAndSend(any(String.class), any(Object.class));
    }
}
