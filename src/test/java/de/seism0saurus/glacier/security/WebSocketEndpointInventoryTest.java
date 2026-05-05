package de.seism0saurus.glacier.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.socket.server.support.WebSocketHandlerMapping;
import social.bigbone.MastodonClient;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * UT-sec-06b: WebSocket endpoint inventory test — API9:2023 gate.
 *
 * <p>Complements {@link EndpointInventoryTest} (which covers HTTP routes) by asserting
 * that every WebSocket upgrade path registered with Spring's {@link WebSocketHandlerMapping}
 * is explicitly present in the authoritative allowlist below. A rogue
 * {@code registry.addEndpoint("/admin-ws")} added to {@link
 * de.seism0saurus.glacier.webservice.messaging.WebSocketConfiguration} would fail this test.
 *
 * <p>Uses {@code webEnvironment=MOCK} so no real port is bound; the full Spring context
 * (including {@code WebSocketConfiguration}) is started so that handler mappings are populated.
 *
 * <p><b>Mode applicability</b>: live mode only — WS endpoints are not registered in
 * killswitch mode. Fallback mode: N/A (WebSocket broker inactive). Insecure mode: same endpoints.
 *
 * <p>OWASP: API9:2023 — Improper Inventory Management (WebSocket surface).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class WebSocketEndpointInventoryTest {

    /**
     * Authoritative allowlist of all WebSocket upgrade paths exposed by Glacier.
     *
     * <p>Per ADR-PT-API5-01 extension: any path registered in a {@link WebSocketHandlerMapping}
     * that is NOT in this set will fail the test. The set uses plain path strings (no method prefix).
     *
     * <ul>
     *   <li>{@code /websocket} — main wall STOMP endpoint (sharer/viewer, PrincipalHandler)</li>
     *   <li>{@code /share-view-ws} — read-only share-view STOMP endpoint (ShareViewPrincipalHandler,
     *       ADR-SHARE-04)</li>
     * </ul>
     */
    private static final Set<String> AUTHORITATIVE_WS_ALLOWLIST = Set.of(
            "/websocket",
            "/share-view-ws"
    );

    @MockitoBean
    @SuppressWarnings("unused")
    private MastodonClient mastodonClient;

    /**
     * All registered {@link WebSocketHandlerMapping} beans — Spring registers one per
     * STOMP endpoint batch configured in {@code WebMvcStompEndpointRegistry}.
     */
    @Autowired
    private java.util.List<WebSocketHandlerMapping> webSocketHandlerMappings;

    /**
     * UT-sec-06b: discover all WebSocket upgrade paths and verify they are in the allowlist.
     *
     * <p>Fails if:
     * <ul>
     *   <li>A new {@code registry.addEndpoint(…)} is added without being listed here.</li>
     *   <li>An allowlist entry no longer maps to any registered handler (stale entry).</li>
     * </ul>
     */
    @Test
    void webSocketRouteInventory_matchesAuthoritativeAllowlist() {
        Set<String> discovered = new HashSet<>();

        for (WebSocketHandlerMapping mapping : webSocketHandlerMappings) {
            mapping.getHandlerMap().forEach((path, handler) -> {
                // SockJS fallback paths contain sub-segments — only record the root upgrade path
                if (!path.contains("/**") && !path.contains("/{")) {
                    discovered.add(path);
                }
            });
        }

        Set<String> undocumented = new HashSet<>(discovered);
        undocumented.removeAll(AUTHORITATIVE_WS_ALLOWLIST);

        assertThat(undocumented)
                .as("UT-sec-06b (API9:2023): WebSocket paths NOT in authoritative allowlist. "
                        + "Add each new WS endpoint to AUTHORITATIVE_WS_ALLOWLIST and to "
                        + "OWASP_COVERAGE_MATRIX.md.\nUndocumented paths: %s", undocumented)
                .isEmpty();

        Set<String> stale = new HashSet<>(AUTHORITATIVE_WS_ALLOWLIST);
        stale.removeAll(discovered);

        assertThat(stale)
                .as("UT-sec-06b (API9:2023): allowlist entries NOT found as registered WS paths "
                        + "(stale allowlist). Remove paths that no longer exist.\nStale entries: %s", stale)
                .isEmpty();
    }
}
