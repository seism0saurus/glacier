package de.seism0saurus.glacier.share.application;

import de.seism0saurus.glacier.share.domain.ShareLinkId;
import de.seism0saurus.glacier.util.LogScrubber;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import social.bigbone.api.entity.Status;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Renders a Bigbone {@link Status} into a {@link ReadonlyTootView} DTO
 * safe for delivery to Angular's interpolation-only rendering pipeline.
 *
 * <p>Security chain (ADR-SHARE-03, ADR-SHARE-07):
 * <ol>
 *   <li>Text content is extracted via {@link JsoupTextExtractor} — text nodes only,
 *       no attribute values, no raw HTML.</li>
 *   <li>All URLs are validated via {@link SafeUrlValidator}; invalid URLs are replaced
 *       by {@code null} and the field is omitted from the DTO.</li>
 *   <li>Avatar, emoji, and media URLs are rewritten to the image proxy via
 *       {@link ShareImageProxyUrlBuilder} — viewers never load images directly
 *       from federation instances (SSRF/tracking pixel prevention).</li>
 *   <li>Bidi override characters are stripped; {@link ReadonlyTootView#bidiStripped()}
 *       is set if any were found (UI surfaces an affordance).</li>
 *   <li>The sharer's wallId is NEVER included in the output — it is not a field
 *       on {@link Status} anyway, but documented here for auditability (SR-SHARE-02).</li>
 * </ol>
 *
 * <p>OWASP A03: structural XSS defence — Angular interpolation escaping + server-side
 * Jsoup extraction is strictly more restrictive than DOMPurify allowlists.
 */
@Service
public class ShareRenderingService {

    private static final Logger log = LoggerFactory.getLogger(ShareRenderingService.class);
    private static final Logger AUDIT = LoggerFactory.getLogger("AUDIT");

    private static final String OVERSIZE_PLACEHOLDER = "[…]";

    private final JsoupTextExtractor textExtractor;
    private final SafeUrlValidator urlValidator;
    private final ImageProxyUrlSigner proxyUrlSigner;

    public ShareRenderingService(
            final JsoupTextExtractor textExtractor,
            final SafeUrlValidator urlValidator,
            final ImageProxyUrlSigner proxyUrlSigner) {
        this.textExtractor = textExtractor;
        this.urlValidator = urlValidator;
        this.proxyUrlSigner = proxyUrlSigner;
    }

    /**
     * Renders a Bigbone {@link Status} into a viewer-safe {@link ReadonlyTootView}.
     *
     * <p>The {@code shareLinkId} is used for HMAC signing of proxy URLs so that
     * each share link's proxy URLs are scoped and cannot be reused across links.
     *
     * @param status      the source toot from Bigbone
     * @param shareLinkId the active share link scope
     * @return a safe DTO for Angular interpolation
     */
    public ReadonlyTootView renderForView(final Status status, final ShareLinkId shareLinkId) {
        // Extract body text via Jsoup (text nodes only — ADR-SHARE-03)
        // extractWithMeta() returns ExtractionResult with both text() and bidiStripped() metadata
        String rawContent = status.getContent();
        JsoupTextExtractor.ExtractionResult extracted =
                textExtractor.extractWithMeta(rawContent != null ? rawContent : "");

        // Log oversize events (SR-SHARE-17)
        if (extracted.text().endsWith(OVERSIZE_PLACEHOLDER)) {
            AUDIT.info("share.render.oversize statusId-hash={} shareLinkId-hash={}",
                    LogScrubber.hash8(safeId(status)),
                    LogScrubber.hash8(shareLinkId.value()));
        }

        // Validate author profile URL (scheme-check)
        String authorProfileUrl = validateUrl(status.getAccount() != null
                ? status.getAccount().getUrl() : null);

        // Rewrite avatar to proxy (ADR-SHARE-07)
        String authorAvatarProxyUrl = rewriteToProxy(
                status.getAccount() != null ? status.getAccount().getAvatar() : null,
                shareLinkId);

        // Author display name — text only (names can contain script tags in rare cases)
        String authorDisplayName = status.getAccount() != null
                ? safeTextOnly(status.getAccount().getDisplayName())
                : null;

        String authorAcct = status.getAccount() != null
                ? status.getAccount().getAcct()
                : null;

        // Spoiler text — also text-only extraction; extract() returns String directly
        String spoilerText = status.getSpoilerText() != null && !status.getSpoilerText().isBlank()
                ? textExtractor.extract(status.getSpoilerText())
                : null;

        // Custom emojis — rewrite src to proxy
        List<ReadonlyTootView.EmojiRef> emojis = buildEmojis(status, shareLinkId);

        // Media attachments — rewrite to proxy, clean alt text
        List<ReadonlyTootView.MediaRef> media = buildMedia(status, shareLinkId);

        // Mentions — validate profile URLs
        List<ReadonlyTootView.MentionRef> mentions = buildMentions(status);

        // Hashtags — safe search URL
        List<ReadonlyTootView.HashtagRef> hashtags = buildHashtags(status);

        // Poll
        Optional<ReadonlyTootView.PollView> poll = buildPoll(status);

        Instant createdAt = status.getCreatedAt() != null
                ? Instant.parse(status.getCreatedAt().toString())
                : Instant.now();

        return new ReadonlyTootView(
                safeId(status),
                authorDisplayName,
                authorAcct,
                authorProfileUrl,
                authorAvatarProxyUrl,
                createdAt,
                extracted.text(),
                spoilerText,
                Boolean.TRUE.equals(status.isSensitive()),
                extracted.bidiStripped(),
                status.getLanguage(),
                List.of(), // links extracted from mentions/content by frontend from structured fields
                mentions,
                hashtags,
                emojis,
                media,
                poll
        );
    }

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    private String safeId(Status status) {
        return status.getId() != null ? status.getId() : "";
    }

    /** Extract only text from a field that might contain HTML tags. */
    private String safeTextOnly(String raw) {
        if (raw == null || raw.isBlank()) return raw;
        // extract() already returns plain String; extractWithMeta() returns ExtractionResult
        return textExtractor.extract(raw);
    }

    /** Validate URL — returns null if invalid (SSRF + scheme check). */
    private String validateUrl(String raw) {
        if (raw == null || raw.isBlank()) return null;
        return urlValidator.validate(raw).map(uri -> uri.toString()).orElse(null);
    }

    /** Rewrite an image URL to the image proxy. Returns null if URL is invalid. */
    private String rewriteToProxy(String rawUrl, ShareLinkId shareLinkId) {
        if (rawUrl == null || rawUrl.isBlank()) return null;
        return urlValidator.validate(rawUrl)
                .map(uri -> proxyUrlSigner.sign(uri.toString(), shareLinkId))
                .orElse(null);
    }

    private List<ReadonlyTootView.EmojiRef> buildEmojis(Status status, ShareLinkId shareLinkId) {
        if (status.getEmojis() == null) return List.of();
        return status.getEmojis().stream()
                .map(e -> new ReadonlyTootView.EmojiRef(
                        e.getShortcode(),
                        rewriteToProxy(e.getUrl(), shareLinkId)
                ))
                .filter(e -> e.proxyUrl() != null)
                .toList();
    }

    private List<ReadonlyTootView.MediaRef> buildMedia(Status status, ShareLinkId shareLinkId) {
        if (status.getMediaAttachments() == null) return List.of();
        return status.getMediaAttachments().stream()
                .map(a -> {
                    String altText = safeTextOnly(a.getDescription());
                    String proxy = rewriteToProxy(a.getUrl(), shareLinkId);
                    return new ReadonlyTootView.MediaRef(proxy, altText, a.getType().toString());
                })
                .filter(m -> m.proxyUrl() != null)
                .toList();
    }

    private List<ReadonlyTootView.MentionRef> buildMentions(Status status) {
        if (status.getMentions() == null) return List.of();
        return status.getMentions().stream()
                .map(m -> new ReadonlyTootView.MentionRef(m.getAcct(), validateUrl(m.getUrl())))
                .toList();
    }

    private List<ReadonlyTootView.HashtagRef> buildHashtags(Status status) {
        if (status.getTags() == null) return List.of();
        return status.getTags().stream()
                .map(t -> new ReadonlyTootView.HashtagRef(t.getName(), null)) // no external URL
                .toList();
    }

    @SuppressWarnings("unchecked")
    private Optional<ReadonlyTootView.PollView> buildPoll(Status status) {
        if (status.getPoll() == null) return Optional.empty();
        var poll = status.getPoll();

        // Bigbone Poll is a Kotlin data class; getOptions() raw type due to Kotlin-Java generic erasure
        // getExpired()/getMultiple() are the Java accessor names for Kotlin val properties
        // votesCount is Kotlin Long (non-nullable) → Java primitive long; cast to int (bounded)
        List<social.bigbone.api.entity.Poll.Option> rawOptions =
                (List<social.bigbone.api.entity.Poll.Option>) (List<?>) (poll.getOptions() != null
                        ? poll.getOptions() : java.util.Collections.emptyList());

        List<ReadonlyTootView.PollOption> options = rawOptions.stream()
                .map(o -> new ReadonlyTootView.PollOption(
                        safeTextOnly(o.getTitle()),
                        // getVotesCount() returns primitive int on Poll.Option (Kotlin Int)
                        (int) Math.min(o.getVotesCount(), Integer.MAX_VALUE)))
                .toList();

        return Optional.of(new ReadonlyTootView.PollView(
                poll.getId(),
                // Kotlin val expired: Boolean → Java accessor is getExpired() not isExpired()
                poll.getExpired(),
                // Kotlin val multiple: Boolean → Java accessor is getMultiple() not isMultiple()
                poll.getMultiple(),
                // Kotlin val votesCount: Long → Java primitive long → cast to int (bounded)
                (int) Math.min(poll.getVotesCount(), Integer.MAX_VALUE),
                options
        ));
    }
}
