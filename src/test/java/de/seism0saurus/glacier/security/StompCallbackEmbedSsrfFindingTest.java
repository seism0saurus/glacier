package de.seism0saurus.glacier.security;

import de.seism0saurus.glacier.mastodon.DefaultSafeUrlValidator;
import de.seism0saurus.glacier.mastodon.SafeUrlValidator;
import de.seism0saurus.glacier.mastodon.StompCallback;
import de.seism0saurus.glacier.mastodon.SubscriptionManager;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.client.RestTemplate;
import social.bigbone.api.entity.Account;
import social.bigbone.api.entity.Status;
import social.bigbone.api.entity.streaming.MastodonApiEvent;
import social.bigbone.api.entity.streaming.ParsedStreamEvent;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pentest finding (CLOSED — re-enabled after fix, ADR-PT-01 / ADR-PT-02 / SR-PT-04).
 *
 * <p>{@link StompCallback} now injects a {@link SafeUrlValidator} that is called
 * BEFORE any outbound HEAD request is issued. The production implementation
 * ({@link DefaultSafeUrlValidator}) blocks loopback, link-local, RFC1918,
 * cloud-metadata, CGNAT, and non-HTTP(S) scheme URLs.</p>
 *
 * <p>This test was previously {@code @Disabled}. It is now re-enabled and verifies
 * the post-fix invariant: for every URL in the SSRF blocklist,
 * {@code restTemplate.headForHeaders} is NEVER called (SR-PT-04).</p>
 *
 * <p>Constructor adaptation note: the {@link StompCallback} constructor signature is
 * {@code (SubscriptionManager, SimpMessagingTemplate, RestTemplate, SafeUrlValidator,
 * String principal, String hashtag, String handle, String glacierDomain)}.
 * The {@code MessageCache} parameter referenced in the original finding draft was
 * from a different feature branch and is NOT part of this constructor.
 * The real {@link DefaultSafeUrlValidator} is used here (not a mock) — the whole
 * point of this regression test is that the production blocklist rejects these URLs.</p>
 *
 * <p>Severity of the original finding: Medium (blind SSRF, internal-network probe;
 * no data exfiltration because HEAD response body is dropped). Now resolved.</p>
 */
class StompCallbackEmbedSsrfFindingTest {

    @ParameterizedTest
    @ValueSource(strings = {
            // Loopback — would let an attacker enumerate localhost:* services
            "http://127.0.0.1:9090/status/1",
            // Link-local / cloud metadata (AWS/GCP/Azure IMDS)
            "http://169.254.169.254/latest/meta-data/",
            // RFC1918 — blind probe of internal network
            "http://10.0.0.5/internal/status/1",
            "http://192.168.1.1/router/status/1",
            "http://172.16.10.10/api/status/1",
            // Non-HTTP scheme — must be rejected by the scheme allowlist
            "file:///etc/passwd",
            "gopher://attacker.example.com:25/_status",
    })
    void onEvent_statusUrlInBlocklist_noEmbedRequestIsIssued(final String maliciousUrl) {
        // Setup: a remote Mastodon status whose url points at a forbidden destination.
        SubscriptionManager subscriptionManager = mock(SubscriptionManager.class);
        RestTemplate restTemplate = mock(RestTemplate.class);
        Status status = mock(Status.class);
        Account account = mock(Account.class);

        when(account.getDisplayName()).thenReturn("attacker@malicious.example");
        when(status.getId()).thenReturn("ssrf-1");
        when(status.getUrl()).thenReturn(maliciousUrl);
        when(status.getAccount()).thenReturn(account);

        // SR-PT-04: use the REAL DefaultSafeUrlValidator — the whole point of this
        // regression test is that the production blocklist rejects these URLs.
        // Note: "localhost" entries in @ValueSource may resolve via DNS depending
        // on the test environment; DefaultSafeUrlValidator blocks them via the
        // loopback/unresolvable-host check in isPrivateAddress().
        SafeUrlValidator validator = new DefaultSafeUrlValidator();

        // Constructor: (SubscriptionManager, SimpMessagingTemplate, RestTemplate,
        //               SafeUrlValidator, principal, hashtag, handle, glacierDomain)
        // SimpMessagingTemplate is null because the SSRF guard fires before any
        // message would be sent — the null will never be dereferenced.
        StompCallback callback = new StompCallback(
                subscriptionManager, null, restTemplate, validator,
                UUID.randomUUID().toString(), "hashtag",
                "glacier@example.com", "glacier.example.com");

        ParsedStreamEvent.StatusCreated created = new ParsedStreamEvent.StatusCreated(status);
        MastodonApiEvent.StreamEvent streamEvent = new MastodonApiEvent.StreamEvent(created, List.of());

        // Act
        callback.onEvent(streamEvent);

        // Post-fix invariant (SR-PT-04): SafeUrlValidator must reject the URL
        // BEFORE headForHeaders is ever called.
        verify(restTemplate, never()).headForHeaders(anyString());
    }
}
