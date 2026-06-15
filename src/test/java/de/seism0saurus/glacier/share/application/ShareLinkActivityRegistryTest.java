package de.seism0saurus.glacier.share.application;

import de.seism0saurus.glacier.share.domain.ShareLink;
import de.seism0saurus.glacier.share.domain.ShareLinkId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ShareLinkActivityRegistry}.
 *
 * <p>No Spring context — collaborators are Mockito mocks. The registry itself is
 * instantiated directly so all lifecycle methods are testable in isolation.
 *
 * <p>Arrange / Act / Assert conventions:
 * <ul>
 *   <li><b>Arrange</b> — registry fresh per test; mock stubs configured per scenario.</li>
 *   <li><b>Act</b>    — single registry method call.</li>
 *   <li><b>Assert</b> — state outcome verified via {@link ShareLinkActivityRegistry#getActiveLinks}.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class ShareLinkActivityRegistryTest {

    private static final String SHARER_A = "sharer-wall-id-AAAAAAAAAAAAAAAAAAAAAAAAAAAA";
    private static final String SHARER_B = "sharer-wall-id-BBBBBBBBBBBBBBBBBBBBBBBBBBBB";
    private static final ShareLinkId LINK_1 =
            ShareLinkId.fromUrlPath("sv_AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA");
    private static final ShareLinkId LINK_2 =
            ShareLinkId.fromUrlPath("sv_BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB");

    @Mock
    private ShareLinkService shareLinkService;

    private ShareLinkActivityRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new ShareLinkActivityRegistry();
    }

    // -----------------------------------------------------------------------
    // register — happy path
    // -----------------------------------------------------------------------

    /**
     * When {@code resolve()} returns an active link, {@code register()} must return
     * {@code true} and the link must appear in {@code getActiveLinks()}.
     *
     * <p>Arrange: mock service resolves LINK_1 as active.
     * <p>Act:     register LINK_1.
     * <p>Assert:  returns true; getActiveLinks contains LINK_1.
     */
    @Test
    void register_returnsTrue_whenLinkIsActive() {
        ShareLink activeLink = shareLink(LINK_1, SHARER_A);
        when(shareLinkService.resolve(eq(LINK_1), any(Instant.class))).thenReturn(Optional.of(activeLink));

        boolean result = registry.register(SHARER_A, LINK_1, shareLinkService, Instant.now());

        assertThat(result).isTrue();
        assertThat(registry.getActiveLinks(SHARER_A)).contains(LINK_1);
    }

    // -----------------------------------------------------------------------
    // register — revoked link (TOCTOU: concurrent revocation wins)
    // -----------------------------------------------------------------------

    /**
     * When {@code resolve()} returns empty (concurrent revocation happened before register
     * acquired the lock), {@code register()} must return {@code false} and the link must
     * NOT appear in {@code getActiveLinks()}.
     *
     * <p>Arrange: mock service returns empty for LINK_1 (link was revoked).
     * <p>Act:     register LINK_1.
     * <p>Assert:  returns false; getActiveLinks is empty for sharer A.
     */
    @Test
    void register_returnsFalse_whenLinkIsRevoked() {
        when(shareLinkService.resolve(eq(LINK_1), any(Instant.class))).thenReturn(Optional.empty());

        boolean result = registry.register(SHARER_A, LINK_1, shareLinkService, Instant.now());

        assertThat(result).isFalse();
        assertThat(registry.getActiveLinks(SHARER_A)).isEmpty();
    }

    // -----------------------------------------------------------------------
    // unregister
    // -----------------------------------------------------------------------

    /**
     * Registering then unregistering a link must leave {@code getActiveLinks()} empty.
     *
     * <p>Arrange: register LINK_1 successfully.
     * <p>Act:     unregister LINK_1.
     * <p>Assert:  getActiveLinks is empty for sharer A.
     */
    @Test
    void unregister_removesLink() {
        ShareLink activeLink = shareLink(LINK_1, SHARER_A);
        when(shareLinkService.resolve(eq(LINK_1), any(Instant.class))).thenReturn(Optional.of(activeLink));
        registry.register(SHARER_A, LINK_1, shareLinkService, Instant.now());

        registry.unregister(SHARER_A, LINK_1);

        assertThat(registry.getActiveLinks(SHARER_A)).isEmpty();
    }

    // -----------------------------------------------------------------------
    // getActiveLinks — immutability guard (SR-RELAY-08)
    // -----------------------------------------------------------------------

    /**
     * {@code getActiveLinks()} must return an unmodifiable view — callers cannot
     * mutate the registry's internal set directly.
     *
     * <p>SR-RELAY-08: no bulk-mutation API must be reachable from outside the registry.
     *
     * <p>Arrange: register LINK_1.
     * <p>Act:     obtain the returned set and attempt {@code add(LINK_2)}.
     * <p>Assert:  {@link UnsupportedOperationException} is thrown.
     */
    @Test
    void getActiveLinks_returnsUnmodifiableSet() {
        ShareLink activeLink = shareLink(LINK_1, SHARER_A);
        when(shareLinkService.resolve(eq(LINK_1), any(Instant.class))).thenReturn(Optional.of(activeLink));
        registry.register(SHARER_A, LINK_1, shareLinkService, Instant.now());

        Set<ShareLinkId> activeLinks = registry.getActiveLinks(SHARER_A);

        assertThatThrownBy(() -> activeLinks.add(LINK_2))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    // -----------------------------------------------------------------------
    // clear — @PreDestroy / test isolation (SR-RELAY-21)
    // -----------------------------------------------------------------------

    /**
     * {@code clear()} must remove all entries from the registry.
     *
     * <p>SR-RELAY-21: the {@code @PreDestroy} method is required so Spring test contexts
     * do not leak state between integration test runs.
     *
     * <p>Arrange: register LINK_1.
     * <p>Act:     call clear().
     * <p>Assert:  getActiveLinks returns empty set.
     */
    @Test
    void clear_emptyRegistry() {
        ShareLink activeLink = shareLink(LINK_1, SHARER_A);
        when(shareLinkService.resolve(eq(LINK_1), any(Instant.class))).thenReturn(Optional.of(activeLink));
        registry.register(SHARER_A, LINK_1, shareLinkService, Instant.now());

        registry.clear();

        assertThat(registry.getActiveLinks(SHARER_A)).isEmpty();
    }

    // -----------------------------------------------------------------------
    // Isolation between different sharers
    // -----------------------------------------------------------------------

    /**
     * Entries for sharer A must not affect entries for sharer B and vice-versa.
     *
     * <p>Arrange: register LINK_1 for SHARER_A; register LINK_2 for SHARER_B.
     * <p>Act:     unregister LINK_1 from SHARER_A.
     * <p>Assert:  SHARER_A has no active links; SHARER_B still has LINK_2.
     */
    @Test
    void register_differentSharerId_isolatedEntries() {
        ShareLink link1 = shareLink(LINK_1, SHARER_A);
        ShareLink link2 = shareLink(LINK_2, SHARER_B);
        when(shareLinkService.resolve(eq(LINK_1), any(Instant.class))).thenReturn(Optional.of(link1));
        when(shareLinkService.resolve(eq(LINK_2), any(Instant.class))).thenReturn(Optional.of(link2));

        registry.register(SHARER_A, LINK_1, shareLinkService, Instant.now());
        registry.register(SHARER_B, LINK_2, shareLinkService, Instant.now());

        registry.unregister(SHARER_A, LINK_1);

        assertThat(registry.getActiveLinks(SHARER_A)).isEmpty();
        assertThat(registry.getActiveLinks(SHARER_B)).contains(LINK_2);
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static ShareLink shareLink(final ShareLinkId id, final String wallId) {
        return ShareLink.create(id, wallId, Instant.now(), Duration.ofDays(7));
    }
}
