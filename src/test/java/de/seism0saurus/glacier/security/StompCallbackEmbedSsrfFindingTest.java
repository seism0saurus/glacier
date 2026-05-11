package de.seism0saurus.glacier.security;

import de.seism0saurus.glacier.share.application.DefaultSafeUrlValidator;
import de.seism0saurus.glacier.share.application.SafeUrlValidator;
import de.seism0saurus.glacier.share.application.ShareViewStompRelay;
import de.seism0saurus.glacier.mastodon.MastodonShortHandle;
import de.seism0saurus.glacier.mastodon.StompCallback;
import de.seism0saurus.glacier.mastodon.SubscriptionManager;
import de.seism0saurus.glacier.webservice.cache.CacheEntry;
import de.seism0saurus.glacier.webservice.cache.EventType;
import de.seism0saurus.glacier.webservice.cache.MessageCache;
import de.seism0saurus.glacier.webservice.messaging.PrincipalKey;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.client.RestTemplate;
import social.bigbone.api.entity.Account;
import social.bigbone.api.entity.Status;
import social.bigbone.api.entity.streaming.MastodonApiEvent;
import social.bigbone.api.entity.streaming.ParsedStreamEvent;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
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
 * {@code restTemplate.headForHeaders} is NEVER called (SR-PT-04) and
 * {@code messageCache.recordThenPublish} is NEVER called (SR-PT-10).</p>
 *
 * <p>Constructor note (merged design): the {@link StompCallback} constructor signature is
 * {@code (SubscriptionManager, MessageCache, ShareViewStompRelay, RestTemplate, SafeUrlValidator,
 * String principal, String hashtag, String handle, String glacierDomain)}.
 * The {@code SimpMessagingTemplate} parameter has been removed — publishing is delegated to
 * {@link MessageCache} (D-03). The real {@link DefaultSafeUrlValidator} is used here (not a mock)
 * — the whole point of this regression test is that the production blocklist rejects these URLs.</p>
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
        MessageCache messageCache = mock(MessageCache.class);
        ShareViewStompRelay shareViewStompRelay = mock(ShareViewStompRelay.class);
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

        // Constructor (merged): (SubscriptionManager, MessageCache, ShareViewStompRelay,
        //                        RestTemplate, SafeUrlValidator, principal, hashtag, handle, glacierDomain)
        // MessageCache/ShareViewStompRelay are mocked because the SSRF guard fires before
        // any write would be attempted — they will never be dereferenced.
        StompCallback callback = new StompCallback(
                subscriptionManager, messageCache, shareViewStompRelay, restTemplate, validator,
                UUID.randomUUID().toString(), "hashtag",
                MastodonShortHandle.parse("glacier@example.com"), "glacier.example.com");

        ParsedStreamEvent.StatusCreated created = new ParsedStreamEvent.StatusCreated(status);
        MastodonApiEvent.StreamEvent streamEvent = new MastodonApiEvent.StreamEvent(created, List.of());

        // Act
        callback.onEvent(streamEvent);

        // Post-fix invariant (SR-PT-04): SafeUrlValidator must reject the URL
        // BEFORE headForHeaders is ever called.
        verify(restTemplate, never()).headForHeaders(anyString());

        // Post-fix invariant (SR-PT-10): SafeUrlValidator must reject the URL
        // BEFORE the cache is ever written.
        verify(messageCache, never()).recordThenPublish(any(PrincipalKey.class), anyString(), any(CacheEntry.class));
    }

    /**
     * Pentest finding regression — {@code StatusEdited} SSRF path (SR-PT-04 / SR-PT-05 / SR-PT-10).
     *
     * <p>{@code processStatusEditedEvent} does NOT issue a {@code HEAD} request; it validates the
     * toot URL via {@link SafeUrlValidator} and — if safe — builds a cache entry and publishes it
     * via {@link MessageCache#recordThenPublish}. The SSRF guard must therefore fire
     * BEFORE {@code recordThenPublish} is called, not before {@code headForHeaders}.</p>
     *
     * <p>This test uses the real {@link DefaultSafeUrlValidator} production blocklist (SR-PT-04)
     * and a mocked {@link MessageCache} so we can assert that no cache write occurs for any URL
     * in the blocklist (SR-PT-10).</p>
     *
     * <p>SSRF guard fires before CacheEntry write — SR-PT-10 (StatusEdited path):
     * because {@code processStatusEditedEvent} validates the URL before writing to the cache,
     * and validation rejects these URLs, no side effect can reach the wall or the cache.</p>
     */
    @ParameterizedTest
    @ValueSource(strings = {
            // Loopback — would let an attacker enumerate localhost:* services
            "http://127.0.0.1:9090/status/1",
            // Link-local / cloud metadata (AWS/GCP/Azure IMDS)
            "http://169.254.169.254/latest/meta-data/",
            // RFC1918 — blind probe of internal network
            "http://10.0.0.5/internal/status/1",
            "http://192.168.1.1/router/status/1",
            // Non-HTTP scheme — must be rejected by the scheme allowlist
            "file:///etc/passwd",
    })
    void onEvent_statusEdited_urlInBlocklist_noCacheWriteOccurs(final String maliciousUrl) {
        // Setup: a remote Mastodon status whose url points at a forbidden destination.
        SubscriptionManager subscriptionManager = mock(SubscriptionManager.class);
        RestTemplate restTemplate = mock(RestTemplate.class);
        // SR-PT-10: mock the cache so we can verify recordThenPublish is never invoked
        MessageCache messageCache = mock(MessageCache.class);
        ShareViewStompRelay shareViewStompRelay = mock(ShareViewStompRelay.class);
        Status status = mock(Status.class);
        Account account = mock(Account.class);

        when(account.getDisplayName()).thenReturn("attacker@malicious.example");
        when(status.getId()).thenReturn("ssrf-edited-1");
        when(status.getUrl()).thenReturn(maliciousUrl);
        when(status.getAccount()).thenReturn(account);

        // SR-PT-04: use the REAL DefaultSafeUrlValidator — the whole point of this
        // regression test is that the production blocklist rejects these URLs.
        SafeUrlValidator validator = new DefaultSafeUrlValidator();

        // Constructor (merged): (SubscriptionManager, MessageCache, ShareViewStompRelay,
        //                        RestTemplate, SafeUrlValidator, principal, hashtag, handle, glacierDomain)
        StompCallback callback = new StompCallback(
                subscriptionManager, messageCache, shareViewStompRelay, restTemplate, validator,
                UUID.randomUUID().toString(), "hashtag",
                MastodonShortHandle.parse("glacier@example.com"), "glacier.example.com");

        ParsedStreamEvent.StatusEdited edited = new ParsedStreamEvent.StatusEdited(status);
        MastodonApiEvent.StreamEvent streamEvent = new MastodonApiEvent.StreamEvent(edited, List.of());

        // Act
        callback.onEvent(streamEvent);

        // Post-fix invariant (SR-PT-10): SafeUrlValidator must reject the URL
        // BEFORE recordThenPublish is ever called — no cache write must occur.
        verify(messageCache, never()).recordThenPublish(any(PrincipalKey.class), anyString(), any(CacheEntry.class));

        // SR-PT-04 (complementary): confirm restTemplate.headForHeaders was also not called —
        // the StatusEdited path never calls it, this assertion documents that invariant.
        verify(restTemplate, never()).headForHeaders(anyString());
    }
}
