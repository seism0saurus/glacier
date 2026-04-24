package de.seism0saurus.glacier.webservice.cache;

import de.seism0saurus.glacier.webservice.messaging.PrincipalKey;
import de.seism0saurus.glacier.webservice.messaging.PrincipalKind;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageHeaders;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;
import social.bigbone.MastodonClient;

import java.security.Principal;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration test verifying that the {@link de.seism0saurus.glacier.webservice.messaging.SubscriptionListener}
 * 5-minute disconnect timer evicts the cache for a disconnected principal (ADR-05, FIX B).
 *
 * <p>This is the regression guard for the memory-reclamation path described in D-11:
 * eviction-on-disconnect is the <em>sole</em> memory-reclamation path for dormant
 * principals under the 10 000-principal cap.  A silent regression here would
 * accumulate dormant principals and trigger the DoS cap prematurely.
 *
 * <p>Uses a full {@link SpringBootTest} context so that the real
 * {@code SubscriptionListener} → {@code SubscriptionManager} → {@code MessageCache}
 * chain is exercised end-to-end, including the virtual-thread timer.
 *
 * <p>{@code glacier.timeouts.client_reconnect=500} (ms) allows the timer to fire in
 * under 1 second, keeping the test fast while still exercising the real timer logic.
 *
 * <p>Asserting cache state:
 * <ul>
 *   <li>Before disconnect: {@link MessageCache#isProvisioned} returns {@code true}.</li>
 *   <li>After 500 ms timer fires: {@link MessageCache#isProvisioned} returns {@code false}.</li>
 *   <li>After eviction: {@link MessageCache#snapshot} throws
 *       {@link UnknownSubscriptionException} (HTTP 400 {@code unknown_subscription}).</li>
 * </ul>
 *
 * <p>The test drives the lifecycle via {@link ApplicationEventPublisher}:
 * <ol>
 *   <li>Provision the cache entry directly (bypassing STOMP — not under test here).</li>
 *   <li>Publish a {@link SessionDisconnectEvent} for the known principal.</li>
 *   <li>Wait up to 5 s for Awaitility to observe cache eviction.</li>
 * </ol>
 *
 * <p>Asserting via HTTP ({@code /rest/messages}) is not needed here — the cache
 * state check directly confirms eviction.  An HTTP assertion is already covered
 * by {@code FallbackControllerIT}.
 */
@SpringBootTest
@TestPropertySource(properties = {
        // Override the 5-minute timer to 500 ms so the test finishes in < 1 second
        "glacier.timeouts.client_reconnect=500"
})
class MessageCacheDisconnectTimerIT {

    private static final String PRINCIPAL = "timer-eviction-test-principal";
    private static final String HASHTAG = "timertest";

    /** Convenience factory: wraps a wallId string in a WALL PrincipalKey. */
    private static PrincipalKey wall(String wallId) {
        return new PrincipalKey(PrincipalKind.WALL, wallId);
    }

    @Autowired
    private MessageCache messageCache;

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    /**
     * MastodonClient is a required bean but cannot connect to a real Mastodon instance
     * in this IT.  Mocking it prevents application context startup failure.
     */
    @SuppressWarnings("unused")
    @MockitoBean
    private MastodonClient mastodonClient;

    /**
     * Verifies that a {@link SessionDisconnectEvent} triggers the 500-ms timer which,
     * when it fires without an intervening reconnect, evicts the principal's cache entries.
     *
     * <p>Arrange: provision {@code (PRINCIPAL, HASHTAG)} in the cache.
     * <p>Act: publish a disconnect event for {@code PRINCIPAL}; wait 5 s for eviction.
     * <p>Assert: {@link MessageCache#isProvisioned} is {@code false};
     *   {@link MessageCache#snapshot} throws {@link UnknownSubscriptionException}.
     */
    @Test
    void sessionDisconnect_afterReconnectTimeout_evictsCacheForPrincipal() {
        // Arrange: provision the cache so there is something to evict
        messageCache.provisionHashtag(wall(PRINCIPAL), HASHTAG);
        assertThat(messageCache.isProvisioned(wall(PRINCIPAL), HASHTAG)).isTrue();

        // Act: simulate STOMP session disconnect by publishing the Spring WebSocket event
        // that SubscriptionListener listens to.  Use a real Principal so the event is
        // not discarded by the null-principal guard in SubscriptionListener.
        Principal principal = () -> PRINCIPAL;
        Message<byte[]> disconnectMessage = buildDisconnectMessage(PRINCIPAL);
        SessionDisconnectEvent disconnectEvent = new SessionDisconnectEvent(
                this, disconnectMessage, "session-timer-test", null, principal);
        eventPublisher.publishEvent(disconnectEvent);

        // Assert: Awaitility polls until the timer fires and eviction completes.
        // Upper bound 5 s gives the 500-ms timer ample time even under GC pressure.
        Awaitility.await("cache eviction after 500-ms disconnect timer")
                .atMost(Duration.ofSeconds(5))
                .pollInterval(Duration.ofMillis(100))
                .untilAsserted(() ->
                        assertThat(messageCache.isProvisioned(wall(PRINCIPAL), HASHTAG)).isFalse()
                );

        // Also verify that the HTTP snapshot path throws (end-to-end contract D-01/ADR-05)
        assertThatThrownBy(() -> messageCache.snapshot(wall(PRINCIPAL), HASHTAG, null))
                .isInstanceOf(UnknownSubscriptionException.class)
                .hasMessageContaining(HASHTAG);
    }

    /**
     * Verifies that a reconnect within the 500-ms window cancels the timer,
     * leaving the cache intact.
     *
     * <p>This is the "grace window" contract from ADR-05: during the reconnect window
     * the cache stays populated so a fallback-mode client can continue polling.
     */
    @Test
    void sessionDisconnect_thenReconnectWithinTimeout_cacheRemainsProvisioned() throws InterruptedException {
        // Arrange
        String gracePrincipal = "grace-window-principal";
        messageCache.provisionHashtag(wall(gracePrincipal), HASHTAG);
        assertThat(messageCache.isProvisioned(wall(gracePrincipal), HASHTAG)).isTrue();

        // Act: disconnect, then reconnect almost immediately (before the 500-ms timer fires)
        Principal principal = () -> gracePrincipal;
        Message<byte[]> disconnectMessage = buildDisconnectMessage(gracePrincipal);
        SessionDisconnectEvent disconnectEvent = new SessionDisconnectEvent(
                this, disconnectMessage, "session-grace-test", null, principal);
        eventPublisher.publishEvent(disconnectEvent);

        // Reconnect within ~100 ms — before the 500 ms timer fires
        Thread.sleep(100);
        publishReconnectEvent(gracePrincipal);

        // Wait 1 s to confirm the timer was cancelled (if it had fired, cache would be empty)
        Thread.sleep(700);
        assertThat(messageCache.isProvisioned(wall(gracePrincipal), HASHTAG)).isTrue();

        // Cleanup to avoid leaking state across tests
        messageCache.evictPrincipal(wall(gracePrincipal));
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Builds a minimal STOMP DISCONNECT message suitable for wrapping in a
     * {@link SessionDisconnectEvent}. Only the session-id header is required.
     */
    private static Message<byte[]> buildDisconnectMessage(final String sessionId) {
        SimpMessageHeaderAccessor accessor = SimpMessageHeaderAccessor.create();
        accessor.setSessionId(sessionId);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    /**
     * Publishes a {@link org.springframework.web.socket.messaging.SessionConnectedEvent}
     * for the given principal, simulating a successful STOMP reconnect.
     *
     * <p>The event carries the same principal name as the disconnect event so that
     * {@link de.seism0saurus.glacier.webservice.messaging.SubscriptionListener}
     * cancels the pending eviction timer.
     */
    private void publishReconnectEvent(final String principalName) {
        Principal principal = () -> principalName;
        SimpMessageHeaderAccessor accessor = SimpMessageHeaderAccessor.create();
        accessor.setSessionId("session-reconnect-" + principalName);
        Message<byte[]> connectMessage = MessageBuilder.createMessage(
                new byte[0], accessor.getMessageHeaders());
        org.springframework.web.socket.messaging.SessionConnectedEvent connectedEvent =
                new org.springframework.web.socket.messaging.SessionConnectedEvent(
                        this, connectMessage, principal);
        eventPublisher.publishEvent(connectedEvent);
    }
}
