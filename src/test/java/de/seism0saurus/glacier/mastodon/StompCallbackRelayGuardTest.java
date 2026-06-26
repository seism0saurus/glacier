package de.seism0saurus.glacier.mastodon;

import de.seism0saurus.glacier.share.application.SafeUrlValidator;
import de.seism0saurus.glacier.share.application.ShareViewStompRelay;
import de.seism0saurus.glacier.webservice.cache.CacheEntry;
import de.seism0saurus.glacier.webservice.cache.EventType;
import de.seism0saurus.glacier.webservice.messaging.PrincipalKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.RestTemplate;
import social.bigbone.api.entity.Status;
import social.bigbone.api.entity.streaming.MastodonApiEvent;
import social.bigbone.api.entity.streaming.ParsedStreamEvent;

import java.net.URI;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Branch coverage for the two operational states {@link StompCallback} guards on but the render
 * tests never exercise:
 * <ul>
 *   <li><b>{@code shareViewStompRelay == null}</b> — the documented "no active share links"
 *       configuration (the relay collaborator is nullable). Every relay call site is guarded by
 *       a null check; this verifies the create and delete paths still write the cache and do not
 *       NPE when the relay is absent.</li>
 *   <li><b>{@code stored == null}</b> — the cache de-duplication / drop result of
 *       {@code MessageCache.recordThenPublish}. Every relay call is also gated on a non-null
 *       stored entry; this verifies no relay fan-out happens for a dropped/duplicate toot.</li>
 * </ul>
 *
 * <p>Construction mirrors {@link StompCallbackRenderTest}; the validator is permissive and the
 * embed HEAD returns {@code X-Frame-Options: ALLOWALL} so the SSRF + embed gates pass and the
 * relay/cache guards downstream are actually reached.
 */
class StompCallbackRelayGuardTest {

    private static final SafeUrlValidator PERMISSIVE_VALIDATOR =
            rawUrl -> Optional.of(URI.create(rawUrl));

    private SubscriptionManager subscriptionManager;
    private RestTemplate restTemplate;
    private de.seism0saurus.glacier.webservice.cache.MessageCache messageCache;
    private ShareViewStompRelay relay;
    private Status mockStatus;

    private final String principal = UUID.randomUUID().toString();
    private final String hashtag = "glacier";

    @BeforeEach
    void setUp() {
        subscriptionManager = mock(SubscriptionManager.class);
        restTemplate = mock(RestTemplate.class);
        messageCache = mock(de.seism0saurus.glacier.webservice.cache.MessageCache.class);
        relay = mock(ShareViewStompRelay.class);
        mockStatus = mock(Status.class);

        // Opt-in: the toot mentions the bot's short handle.
        Status.Mention botMention = mock(Status.Mention.class);
        when(botMention.getAcct()).thenReturn("glacier");
        when(mockStatus.getMentions()).thenReturn(List.of(botMention));
        when(mockStatus.getId()).thenReturn("status-1");
        when(mockStatus.getUrl()).thenReturn("https://mastodon.example.com/status-1");

        HttpHeaders allow = new HttpHeaders();
        allow.set("X-Frame-Options", "ALLOWALL");
        when(restTemplate.headForHeaders("https://mastodon.example.com/status-1/embed"))
                .thenReturn(allow);
    }

    private StompCallback callbackWith(final ShareViewStompRelay relayOrNull) {
        return new StompCallback(
                subscriptionManager, messageCache, relayOrNull, restTemplate,
                PERMISSIVE_VALIDATOR, principal, hashtag,
                MastodonShortHandle.parse("glacier@mastodon.example.com"), "glacier.example.com");
    }

    // --- shareViewStompRelay == null (no active share links) ---

    @Test
    void statusCreated_withNullRelay_writesCache_andDoesNotThrow() {
        when(messageCache.recordThenPublish(any(PrincipalKey.class), anyString(), any(CacheEntry.class)))
                .thenReturn(new CacheEntry(EventType.CREATED, "status-1",
                        "https://mastodon.example.com/status-1/embed", null, 1L));
        StompCallback callback = callbackWith(null);

        callback.onEvent(new MastodonApiEvent.StreamEvent(
                new ParsedStreamEvent.StatusCreated(mockStatus), List.of()));

        // The cache write still happens; the null-relay guards simply skip the fan-out.
        verify(messageCache).recordThenPublish(any(), any(), any());
    }

    @Test
    void statusDeleted_withNullRelay_writesCache_andDoesNotThrow() {
        when(messageCache.recordThenPublish(any(PrincipalKey.class), anyString(), any(CacheEntry.class)))
                .thenReturn(new CacheEntry(EventType.DELETED, "status-1", null, null, 2L));
        StompCallback callback = callbackWith(null);

        callback.onEvent(new MastodonApiEvent.StreamEvent(
                new ParsedStreamEvent.StatusDeleted("status-1"), List.of()));

        verify(messageCache).recordThenPublish(any(), any(), any());
    }

    // --- stored == null (cache de-dup / drop) ---

    @Test
    void statusCreated_whenCacheReturnsNull_doesNotRelay() {
        // recordThenPublish returning null models a de-duplicated / dropped toot.
        when(messageCache.recordThenPublish(any(PrincipalKey.class), anyString(), any(CacheEntry.class)))
                .thenReturn(null);
        StompCallback callback = callbackWith(relay);

        callback.onEvent(new MastodonApiEvent.StreamEvent(
                new ParsedStreamEvent.StatusCreated(mockStatus), List.of()));

        // No fan-out for a dropped entry — neither the Status overload nor the Object overload.
        verify(relay, never()).relayTootEvent(anyString(), anyString(), anyString(), any(Status.class));
        verify(relay, never()).relayTootEvent(anyString(), anyString(), anyString(), any(Object.class));
    }

    @Test
    void statusDeleted_whenCacheReturnsNull_doesNotRelay() {
        when(messageCache.recordThenPublish(any(PrincipalKey.class), anyString(), any(CacheEntry.class)))
                .thenReturn(null);
        StompCallback callback = callbackWith(relay);

        callback.onEvent(new MastodonApiEvent.StreamEvent(
                new ParsedStreamEvent.StatusDeleted("status-1"), List.of()));

        verify(relay, never()).relayTootEvent(anyString(), anyString(), anyString(), any(Object.class));
    }
}
