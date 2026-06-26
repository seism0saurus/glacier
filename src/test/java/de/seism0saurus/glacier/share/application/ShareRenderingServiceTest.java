package de.seism0saurus.glacier.share.application;

import de.seism0saurus.glacier.share.domain.ShareLinkId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import social.bigbone.api.entity.MediaAttachment;
import social.bigbone.api.entity.Status;
import social.bigbone.api.entity.Account;

import social.bigbone.PrecisionDateTime;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Security corpus test for {@link ShareRenderingService}.
 *
 * <p>Verifies that a malicious {@link Status} with script injection in every field
 * produces a {@link ReadonlyTootView} where all output fields are either:
 * <ul>
 *   <li>plain text (HTML stripped by Jsoup)</li>
 *   <li>null (invalid URL rejected by SafeUrlValidator)</li>
 *   <li>a proxy URL (rewritten by ShareImageProxyUrlBuilder)</li>
 * </ul>
 *
 * <p>Zero {@code [innerHTML]} anywhere in the pipeline — Angular interpolation escaping
 * is the final defence layer (ADR-SHARE-03, OWASP A03).
 */
class ShareRenderingServiceTest {

    private ShareRenderingService service;
    private SafeUrlValidator mockUrlValidator;
    private ImageProxyUrlSigner mockProxyBuilder;

    private static final ShareLinkId SHARE_LINK_ID =
            ShareLinkId.fromUrlPath("sv_AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA");

    @BeforeEach
    void setUp() {
        JsoupTextExtractor extractor = new JsoupTextExtractor();
        mockUrlValidator = mock(SafeUrlValidator.class);
        mockProxyBuilder = mock(ImageProxyUrlSigner.class);

        // Default: valid URLs pass validation, proxy returns a rewritten URL
        when(mockUrlValidator.validate(anyString()))
                .thenReturn(Optional.of(URI.create("https://mastodon.social/image.png")));
        when(mockProxyBuilder.sign(anyString(), eq(SHARE_LINK_ID)))
                .thenReturn("https://share.example.com/rest/share/img-proxy?u=signed");

        service = new ShareRenderingService(extractor, mockUrlValidator, mockProxyBuilder);
    }

    @Test
    void scriptInContent_isStrippedToPlainText() {
        Status status = mockStatus("<script>alert('XSS')</script>Hello, world!");
        ReadonlyTootView view = service.renderForView(status, SHARE_LINK_ID);
        assertThat(view.textContent()).doesNotContain("<script>");
        assertThat(view.textContent()).doesNotContain("alert(");
        assertThat(view.textContent()).contains("Hello, world!");
    }

    @Test
    void createdAt_fromRealPrecisionDateTime_isConvertedNotStringParsed() {
        // Regression: getCreatedAt() returns a Bigbone PrecisionDateTime, whose toString()
        // is NOT an ISO-8601 instant (e.g. "ExactTime(instant=...)"). The previous
        // Instant.parse(getCreatedAt().toString()) threw DateTimeParseException on every
        // real status — which surfaced as share.relay.render_failed for the whole share
        // wall (the unit suite missed it because mockStatus never stubbed getCreatedAt).
        Instant ts = Instant.parse("2026-01-01T12:00:00Z");
        Status status = mockStatus("Hello");
        when(status.getCreatedAt())
                .thenReturn(new PrecisionDateTime.ValidPrecisionDateTime.ExactTime(ts));

        ReadonlyTootView view = service.renderForView(status, SHARE_LINK_ID);

        assertThat(view.createdAt()).isEqualTo(ts);
    }

    @Test
    void iframeInContent_isStrippedToPlainText() {
        Status status = mockStatus("<iframe src='https://evil.example.com'></iframe>Safe text");
        ReadonlyTootView view = service.renderForView(status, SHARE_LINK_ID);
        assertThat(view.textContent()).doesNotContain("<iframe");
        assertThat(view.textContent()).contains("Safe text");
    }

    @Test
    void javascriptSchemeInMentionUrl_isRejected() {
        when(mockUrlValidator.validate("javascript:alert(1)")).thenReturn(Optional.empty());
        Status status = mockStatusWithMention("attacker", "javascript:alert(1)");
        ReadonlyTootView view = service.renderForView(status, SHARE_LINK_ID);
        assertThat(view.mentions()).hasSize(1);
        assertThat(view.mentions().get(0).profileUrl()).isNull();
    }

    @Test
    void javascriptSchemeInAvatarUrl_isRejectedAndAvatarIsNull() {
        when(mockUrlValidator.validate("javascript:alert(1)")).thenReturn(Optional.empty());
        Status status = mockStatusWithAvatar("javascript:alert(1)");
        ReadonlyTootView view = service.renderForView(status, SHARE_LINK_ID);
        assertThat(view.authorAvatarProxyUrl()).isNull();
    }

    @Test
    void svgOnloadInMediaDescription_isStrippedToPlainText() {
        Status status = mockStatusWithMedia(
                "https://mastodon.social/image.png",
                "<svg onload=alert(1)>safe alt</svg>");
        ReadonlyTootView view = service.renderForView(status, SHARE_LINK_ID);
        assertThat(view.media()).isNotEmpty();
        String altText = view.media().get(0).altText();
        assertThat(altText).doesNotContain("<svg");
        assertThat(altText).doesNotContain("onload");
    }

    @Test
    void pollOptionWithScriptTitle_isStrippedToPlainText() {
        Status status = mockStatusWithPollOption("<script>alert(1)</script>Option A");
        ReadonlyTootView view = service.renderForView(status, SHARE_LINK_ID);
        assertThat(view.poll()).isPresent();
        String title = view.poll().get().options().get(0).title();
        assertThat(title).doesNotContain("<script>");
        assertThat(title).contains("Option A");
    }

    @Test
    void authorDisplayNameWithScript_isStrippedToPlainText() {
        Status status = mockStatusWithAuthorName("<script>steal()</script>Real Name");
        ReadonlyTootView view = service.renderForView(status, SHARE_LINK_ID);
        assertThat(view.authorDisplayName()).doesNotContain("<script>");
        assertThat(view.authorDisplayName()).contains("Real Name");
    }

    @Test
    void validAvatarUrl_isRewrittenToProxy() {
        when(mockUrlValidator.validate("https://mastodon.social/avatar.jpg"))
                .thenReturn(Optional.of(URI.create("https://mastodon.social/avatar.jpg")));
        when(mockProxyBuilder.sign("https://mastodon.social/avatar.jpg", SHARE_LINK_ID))
                .thenReturn("https://share.example.com/rest/share/img-proxy?u=signed-avatar");

        Status status = mockStatusWithAvatar("https://mastodon.social/avatar.jpg");
        ReadonlyTootView view = service.renderForView(status, SHARE_LINK_ID);

        assertThat(view.authorAvatarProxyUrl())
                .isEqualTo("https://share.example.com/rest/share/img-proxy?u=signed-avatar");
        // Must never be the original federation URL (SSRF/tracking prevention)
        assertThat(view.authorAvatarProxyUrl())
                .doesNotContain("mastodon.social/avatar.jpg");
    }

    @Test
    void bidiOverrideInContent_isStripped_andFlagSet() {
        // U+202E RIGHT-TO-LEFT OVERRIDE in content
        Status status = mockStatus("Hello ‮world");
        ReadonlyTootView view = service.renderForView(status, SHARE_LINK_ID);
        assertThat(view.textContent()).doesNotContain("‮");
        assertThat(view.bidiStripped()).isTrue();
    }

    @Test
    void shareViewerWallIdNeverInOutput() {
        // Structural: ReadonlyTootView has no sharerWallId field at all
        Status status = mockStatus("Normal toot");
        ReadonlyTootView view = service.renderForView(status, SHARE_LINK_ID);
        // Verify by reflection that no field name contains "wallid" or "sharer"
        for (var component : view.getClass().getRecordComponents()) {
            String lower = component.getName().toLowerCase();
            assertThat(lower).doesNotContain("wallid");
            assertThat(lower).doesNotContain("sharer");
        }
    }

    // -----------------------------------------------------------------------
    // Null / empty / edge-branch coverage (the uncovered ~half of renderForView)
    // -----------------------------------------------------------------------

    @Test
    void minimalStatus_allFieldsNull_rendersSafelyWithDefaults() {
        // A bare mock: every getter returns null/false. Exercises the null-account ternaries,
        // the four null-collection early returns, null id → "", null content → "", the null
        // spoiler branch, and the createdAt fallback — i.e. the branches the populated
        // happy-path fixtures never reach. Must not throw.
        Status status = mock(Status.class);

        ReadonlyTootView view = service.renderForView(status, SHARE_LINK_ID);

        assertThat(view.id()).isEqualTo("");
        assertThat(view.authorDisplayName()).isNull();
        assertThat(view.authorAcct()).isNull();
        assertThat(view.authorProfileUrl()).isNull();
        assertThat(view.authorAvatarProxyUrl()).isNull();
        assertThat(view.textContent()).isEmpty();
        assertThat(view.spoilerText()).isNull();
        assertThat(view.sensitive()).isFalse();
        assertThat(view.createdAt()).isNotNull(); // fallback to "now"
        assertThat(view.mentions()).isEmpty();
        assertThat(view.hashtags()).isEmpty();
        assertThat(view.customEmojis()).isEmpty();
        assertThat(view.media()).isEmpty();
        assertThat(view.poll()).isEmpty();
    }

    @Test
    void oversizeContent_isTruncatedWithMarker() {
        // > MAX_TEXT_BYTES (8192) → JsoupTextExtractor truncates and appends "[…]", which
        // triggers the SR-SHARE-17 oversize AUDIT branch.
        Status status = mockStatus("a".repeat(10_000));
        ReadonlyTootView view = service.renderForView(status, SHARE_LINK_ID);
        assertThat(view.textContent()).endsWith("[…]");
    }

    @Test
    void spoilerText_present_isExtractedToPlainText() {
        Status status = mockStatus("body");
        when(status.getSpoilerText()).thenReturn("<b>Content warning</b>");
        ReadonlyTootView view = service.renderForView(status, SHARE_LINK_ID);
        assertThat(view.spoilerText()).isEqualTo("Content warning");
        assertThat(view.spoilerText()).doesNotContain("<b>");
    }

    @Test
    void blankSpoilerText_isNull() {
        Status status = mockStatus("body");
        when(status.getSpoilerText()).thenReturn("   ");
        ReadonlyTootView view = service.renderForView(status, SHARE_LINK_ID);
        assertThat(view.spoilerText()).isNull();
    }

    @Test
    void sensitiveFlag_true_isPropagated() {
        Status status = mockStatus("body");
        when(status.isSensitive()).thenReturn(true);
        ReadonlyTootView view = service.renderForView(status, SHARE_LINK_ID);
        assertThat(view.sensitive()).isTrue();
    }

    @Test
    void customEmojis_present_areMappedToProxiedRefs() {
        Status status = mockStatus("body");
        social.bigbone.api.entity.CustomEmoji emoji = mock(social.bigbone.api.entity.CustomEmoji.class);
        when(emoji.getShortcode()).thenReturn("party");
        when(emoji.getUrl()).thenReturn("https://mastodon.social/emoji/party.png");
        when(status.getEmojis()).thenReturn(List.of(emoji));

        ReadonlyTootView view = service.renderForView(status, SHARE_LINK_ID);

        assertThat(view.customEmojis()).hasSize(1);
        assertThat(view.customEmojis().get(0).shortcode()).isEqualTo("party");
        assertThat(view.customEmojis().get(0).proxyUrl()).isNotNull();
    }

    @Test
    void customEmoji_withInvalidUrl_isFilteredOut() {
        Status status = mockStatus("body");
        social.bigbone.api.entity.CustomEmoji emoji = mock(social.bigbone.api.entity.CustomEmoji.class);
        when(emoji.getShortcode()).thenReturn("evil");
        when(emoji.getUrl()).thenReturn("javascript:alert(1)");
        when(status.getEmojis()).thenReturn(List.of(emoji));
        when(mockUrlValidator.validate("javascript:alert(1)")).thenReturn(Optional.empty());

        ReadonlyTootView view = service.renderForView(status, SHARE_LINK_ID);

        assertThat(view.customEmojis()).isEmpty();
    }

    @Test
    void media_withInvalidUrl_isFilteredOut() {
        Status status = mockStatusWithMedia("javascript:alert(1)", "alt text");
        when(mockUrlValidator.validate("javascript:alert(1)")).thenReturn(Optional.empty());
        ReadonlyTootView view = service.renderForView(status, SHARE_LINK_ID);
        assertThat(view.media()).isEmpty();
    }

    @Test
    void hashtags_present_areMappedWithNullSearchUrl() {
        // Hashtags never carry an external URL (searchUrl always null) — anti-tracking.
        Status status = mockStatus("body");
        social.bigbone.api.entity.Tag tag = mock(social.bigbone.api.entity.Tag.class);
        when(tag.getName()).thenReturn("glacier");
        when(status.getTags()).thenReturn(List.of(tag));

        ReadonlyTootView view = service.renderForView(status, SHARE_LINK_ID);

        assertThat(view.hashtags()).hasSize(1);
        assertThat(view.hashtags().get(0).tag()).isEqualTo("glacier");
        assertThat(view.hashtags().get(0).searchUrl()).isNull();
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private Status mockStatus(String content) {
        Status status = mock(Status.class);
        Account account = mock(Account.class);
        when(account.getDisplayName()).thenReturn("Test User");
        when(account.getAcct()).thenReturn("test@mastodon.social");
        when(account.getUrl()).thenReturn("https://mastodon.social/@test");
        when(account.getAvatar()).thenReturn("https://mastodon.social/avatar.png");
        when(status.getAccount()).thenReturn(account);
        when(status.getId()).thenReturn("123456");
        when(status.getContent()).thenReturn(content);
        when(status.getSpoilerText()).thenReturn(null);
        when(status.isSensitive()).thenReturn(false);
        when(status.getLanguage()).thenReturn("en");
        when(status.getEmojis()).thenReturn(List.of());
        when(status.getMediaAttachments()).thenReturn(List.of());
        when(status.getMentions()).thenReturn(List.of());
        when(status.getTags()).thenReturn(List.of());
        when(status.getPoll()).thenReturn(null);
        return status;
    }

    private Status mockStatusWithMention(String acct, String url) {
        Status status = mockStatus("content");
        social.bigbone.api.entity.Status.Mention mention = mock(social.bigbone.api.entity.Status.Mention.class);
        when(mention.getAcct()).thenReturn(acct);
        when(mention.getUrl()).thenReturn(url);
        when(status.getMentions()).thenReturn(List.of(mention));
        return status;
    }

    private Status mockStatusWithAvatar(String avatarUrl) {
        Status status = mockStatus("content");
        Account account = mock(Account.class);
        when(account.getDisplayName()).thenReturn("Test User");
        when(account.getAcct()).thenReturn("test@mastodon.social");
        when(account.getUrl()).thenReturn("https://mastodon.social/@test");
        when(account.getAvatar()).thenReturn(avatarUrl);
        when(status.getAccount()).thenReturn(account);
        return status;
    }

    private Status mockStatusWithMedia(String mediaUrl, String altText) {
        Status status = mockStatus("content");
        MediaAttachment media = mock(MediaAttachment.class);
        when(media.getUrl()).thenReturn(mediaUrl);
        when(media.getDescription()).thenReturn(altText);
        when(media.getType()).thenReturn(MediaAttachment.MediaType.IMAGE);
        when(status.getMediaAttachments()).thenReturn(List.of(media));
        return status;
    }

    private Status mockStatusWithPollOption(String title) {
        Status status = mockStatus("content");
        social.bigbone.api.entity.Poll poll = mock(social.bigbone.api.entity.Poll.class);
        social.bigbone.api.entity.Poll.Option option = mock(social.bigbone.api.entity.Poll.Option.class);
        when(option.getTitle()).thenReturn(title);
        // Bigbone Poll.Option votesCount is Kotlin Long → Java primitive long
        when(option.getVotesCount()).thenReturn(0L);
        when(poll.getId()).thenReturn("poll-123");
        // Bigbone Poll Kotlin val expired/multiple → Java getExpired()/getMultiple()
        when(poll.getExpired()).thenReturn(false);
        when(poll.getMultiple()).thenReturn(false);
        // votesCount is Kotlin Long (non-nullable) → Java primitive long
        when(poll.getVotesCount()).thenReturn(0L);
        when(poll.getOptions()).thenReturn(List.of(option));
        when(status.getPoll()).thenReturn(poll);
        return status;
    }

    private Status mockStatusWithAuthorName(String displayName) {
        Status status = mockStatus("content");
        Account account = mock(Account.class);
        when(account.getDisplayName()).thenReturn(displayName);
        when(account.getAcct()).thenReturn("test@mastodon.social");
        when(account.getUrl()).thenReturn("https://mastodon.social/@test");
        when(account.getAvatar()).thenReturn("https://mastodon.social/avatar.png");
        when(status.getAccount()).thenReturn(account);
        return status;
    }
}
