package de.seism0saurus.glacier.webservice.messaging;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import de.seism0saurus.glacier.share.domain.ShareLinkId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.support.MessageBuilder;

import java.security.Principal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Unit tests for {@link WallTopicAuthInterceptor}.
 *
 * <p>Security requirement: OWASP API1 BOLA — a WallPrincipal must only be able to
 * subscribe to the /topic/hashtags/{ownWallId}/... destination that matches their
 * principal name. Cross-principal subscriptions are rejected with a null return
 * (drops the message) and an AUDIT event is emitted.
 *
 * <p>D-13/SR-8 requirement: the AUDIT log event must NEVER contain raw wallId UUIDs —
 * only {@link de.seism0saurus.glacier.util.LogScrubber#hash8} fingerprints.
 *
 * @see ADR-TEST-01, SR-TEST-21
 */
class WallTopicAuthInterceptorTest {

    private WallTopicAuthInterceptor interceptor;
    private MessageChannel channel;
    private ListAppender<ILoggingEvent> auditAppender;

    @BeforeEach
    void setUp() {
        interceptor = new WallTopicAuthInterceptor();
        channel = mock(MessageChannel.class);
        auditAppender = attachAuditAppender();
    }

    @AfterEach
    void tearDown() {
        detachAuditAppender(auditAppender);
    }

    // ---------------------------------------------------------------------------
    // Happy path: own wallId in destination
    // ---------------------------------------------------------------------------

    @Test
    void wallPrincipalCanSubscribeToOwnHashtagTopic() {
        // ARRANGE
        String wallId = "a1b2c3d4-e5f6-7890-abcd-ef1234567890";
        WallPrincipal principal = new WallPrincipal(wallId);
        Message<?> msg = buildSubscribeMessage("/topic/hashtags/" + wallId + "/tag/creation", principal);

        // ACT
        Message<?> result = interceptor.preSend(msg, channel);

        // ASSERT — message is passed through unchanged (not dropped)
        assertThat(result).isNotNull();
        // No audit event for allowed subscribe
        assertThat(auditAppender.list).isEmpty();
    }

    // ---------------------------------------------------------------------------
    // Rejection: different wallId in destination (BOLA attack path)
    // ---------------------------------------------------------------------------

    @Test
    void wallPrincipalCannotSubscribeToOtherWallIdTopic() {
        // ARRANGE
        String ownWallId   = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
        String otherWallId = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
        WallPrincipal principal = new WallPrincipal(ownWallId);
        Message<?> msg = buildSubscribeMessage("/topic/hashtags/" + otherWallId + "/tag/creation", principal);

        // ACT
        Message<?> result = interceptor.preSend(msg, channel);

        // ASSERT — message is dropped (null = reject)
        assertThat(result).isNull();
    }

    // ---------------------------------------------------------------------------
    // Null principal is rejected
    // ---------------------------------------------------------------------------

    @Test
    void nullPrincipalIsRejected() {
        // ARRANGE
        Message<?> msg = buildSubscribeMessage("/topic/hashtags/some-id/tag/creation", null);

        // ACT
        Message<?> result = interceptor.preSend(msg, channel);

        // ASSERT
        assertThat(result).isNull();
    }

    // ---------------------------------------------------------------------------
    // ShareViewerPrincipal is NOT checked by this interceptor — passed through
    // ---------------------------------------------------------------------------

    @Test
    void viewerPrincipalIsPassedThrough() {
        // ARRANGE — a ShareViewerPrincipal with a random share-link ID should not
        // be evaluated by WallTopicAuthInterceptor; that is ShareViewTopicAuthInterceptor's job.
        // ShareViewerPrincipal requires viewerId + boundShareLinkId (ShareLinkId must be ≥43 chars).
        String shareToken = "sv_" + "A".repeat(40); // 43 chars total — valid ShareLinkId
        ShareLinkId linkId = ShareLinkId.fromUrlPath(shareToken);
        ShareViewerPrincipal viewer = new ShareViewerPrincipal("sv_viewer-99999", linkId);
        Message<?> msg = buildSubscribeMessage("/topic/hashtags/some-wall-id/tag/creation", viewer);

        // ACT
        Message<?> result = interceptor.preSend(msg, channel);

        // ASSERT — passed through, not intercepted by this class
        assertThat(result).isNotNull();
    }

    // ---------------------------------------------------------------------------
    // Malformed destination (no wallId segment) is rejected
    // ---------------------------------------------------------------------------

    @Test
    void malformedDestinationIsRejected() {
        // ARRANGE — destination has the /topic/hashtags/ prefix but no wallId
        WallPrincipal principal = new WallPrincipal("any-wall-id");
        Message<?> msg = buildSubscribeMessage("/topic/hashtags/", principal);

        // ACT
        Message<?> result = interceptor.preSend(msg, channel);

        // ASSERT — malformed destination rejected
        assertThat(result).isNull();
    }

    // ---------------------------------------------------------------------------
    // Non-hashtags topic passes through without inspection
    // ---------------------------------------------------------------------------

    @Test
    void nonHashtagsTopicIsPassedThrough() {
        // ARRANGE — this interceptor only guards /topic/hashtags/...
        WallPrincipal principal = new WallPrincipal("my-wall-id");
        Message<?> msg = buildSubscribeMessage("/topic/other/my-wall-id/something", principal);

        // ACT
        Message<?> result = interceptor.preSend(msg, channel);

        // ASSERT — passed through unchanged
        assertThat(result).isNotNull();
    }

    // ---------------------------------------------------------------------------
    // Audit event on cross-principal rejection — with D-13/SR-8 no-raw-UUID check
    // ---------------------------------------------------------------------------

    @Test
    void auditEventEmittedOnRejection() {
        // ARRANGE
        String ownWallId   = "cccccccc-cccc-cccc-cccc-cccccccccccc";
        String otherWallId = "dddddddd-dddd-dddd-dddd-dddddddddddd";
        WallPrincipal principal = new WallPrincipal(ownWallId);
        Message<?> msg = buildSubscribeMessage("/topic/hashtags/" + otherWallId + "/tag/creation", principal);

        // ACT
        interceptor.preSend(msg, channel);

        // ASSERT — AUDIT logger received exactly one event mentioning cross-principal rejection
        assertThat(auditAppender.list).hasSize(1);
        ILoggingEvent event = auditAppender.list.get(0);
        assertThat(event.getFormattedMessage()).contains("cross_principal");

        // D-13/SR-8: the victim's raw wallId UUID MUST NOT appear in the audit log message.
        // LogScrubber.hash8 is used — the raw UUID must never leak.
        assertThat(event.getFormattedMessage()).doesNotContain(otherWallId);

        // The attacker's own wallId also must not appear verbatim (belt-and-suspenders).
        assertThat(event.getFormattedMessage()).doesNotContain(ownWallId);

        // SR-TEST-08, D-13: AUDIT log must use the canonical structured field names
        // so downstream log-processing pipelines (SIEM, alerting) can parse them reliably.
        assertThat(event.getFormattedMessage())
                .as("AUDIT log must use canonical principal-hash8= field name (SR-TEST-08, D-13)")
                .contains("principal-hash8=");
        assertThat(event.getFormattedMessage())
                .as("AUDIT log must use canonical destination-wallId-hash8= field name (SR-TEST-08, D-13)")
                .contains("destination-wallId-hash8=");
    }

    // ---------------------------------------------------------------------------
    // Non-SUBSCRIBE frames (e.g. SEND) are not inspected
    // ---------------------------------------------------------------------------

    @Test
    void nonSubscribeFrameIsPassedThrough() {
        // ARRANGE — SEND frames go through /glacier/subscription, not /topic; but even
        // if destination matches, only SUBSCRIBE type should be inspected.
        WallPrincipal principal = new WallPrincipal("my-wall-id");
        Message<?> msg = buildSendMessage("/topic/hashtags/other-id/tag/creation", principal);

        // ACT
        Message<?> result = interceptor.preSend(msg, channel);

        // ASSERT
        assertThat(result).isNotNull();
    }

    // ============================================================================
    // Helpers
    // ============================================================================

    private Message<?> buildSubscribeMessage(String destination, Principal principal) {
        SimpMessageHeaderAccessor accessor = SimpMessageHeaderAccessor.create(SimpMessageType.SUBSCRIBE);
        accessor.setDestination(destination);
        if (principal != null) {
            accessor.setUser(principal);
        }
        accessor.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    private Message<?> buildSendMessage(String destination, Principal principal) {
        SimpMessageHeaderAccessor accessor = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
        accessor.setDestination(destination);
        if (principal != null) {
            accessor.setUser(principal);
        }
        accessor.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    private ListAppender<ILoggingEvent> attachAuditAppender() {
        ch.qos.logback.classic.Logger auditLogger =
                (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger("AUDIT");
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        auditLogger.addAppender(appender);
        return appender;
    }

    private void detachAuditAppender(ListAppender<ILoggingEvent> appender) {
        ch.qos.logback.classic.Logger auditLogger =
                (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger("AUDIT");
        auditLogger.detachAppender(appender);
    }
}
