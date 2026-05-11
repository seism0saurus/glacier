package de.seism0saurus.glacier.mastodon;

import de.seism0saurus.glacier.util.LogScrubber;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Pure-function policy checker for iframe-embed safety.
 *
 * <p>Encapsulates the logic that decides whether a remote Mastodon instance's
 * toot embed page may be loaded inside a Glacier iframe, based on the HTTP
 * response headers returned by a {@code HEAD} request to the embed endpoint.
 *
 * <p>The decision follows the standard header-precedence rules:
 * <ol>
 *   <li>If a {@code Content-Security-Policy} header containing a {@code frame-ancestors}
 *       directive is present, that directive is authoritative (overrides X-Frame-Options).
 *       The toot is embeddable only if the {@code frame-ancestors} list includes the
 *       Glacier domain, {@code https://glacier-domain}, {@code https:}, {@code http:},
 *       or a wildcard origin.</li>
 *   <li>If no {@code frame-ancestors} is present, {@code X-Frame-Options} is consulted:
 *       <ul>
 *         <li>{@code DENY} or {@code SAMEORIGIN} → forbidden.</li>
 *         <li>{@code ALLOWALL} → permitted.</li>
 *         <li>absent (null) → permitted (browser default).</li>
 *         <li>any other value → forbidden (unknown/invalid, log-safe warning emitted).</li>
 *       </ul>
 *   </li>
 * </ol>
 *
 * <p>This class is {@code final} with a private constructor — all interaction is via the
 * static factory method {@link #isEmbeddable}. Instantiation is intentionally prevented
 * (SR-FUZZ-09).
 *
 * <p>Security notes:
 * <ul>
 *   <li>D-13/SR-8: raw header values are never logged. The {@link LogScrubber#xfoSummary}
 *       guard in the unknown-XFO branch is the CWE-117 log-injection defence (TD-4 /
 *       ADR-TD4-01). Any modification to the logging call in that branch must preserve
 *       this guard verbatim.</li>
 *   <li>SR-FUZZ-01/ADR-FUZZ-05: parameters are {@code List<String>} (not {@code String})
 *       to preserve per-value semantics required by RFC-7034 CSP list semantics and
 *       {@link LogScrubber#xfoSummary(List)}.</li>
 * </ul>
 *
 * @see StompCallback
 */
public final class IframeEmbedPolicy {

    /**
     * Logger for this class.
     * Structured JSON log output is configured in {@code src/main/resources/logback.xml}.
     */
    private static final Logger LOGGER = LoggerFactory.getLogger(IframeEmbedPolicy.class);

    /**
     * AUDIT logger for security-relevant events.
     *
     * <p>SR-PQ-10R2 / glacier-structured-logging-logback skill: security-header-bypass
     * attempts (domain-mismatch blocks) must be routed to the AUDIT channel so that
     * SOC/SIEM log aggregators filtering on logger name {@code "AUDIT"} capture all
     * embed-rejection events. The class LOGGER retains its own WARN for operator alerting.
     */
    private static final Logger AUDIT = LoggerFactory.getLogger("AUDIT");

    /**
     * Private constructor — this class is a non-instantiable pure-function holder.
     * All interaction is via {@link #isEmbeddable}.
     */
    private IframeEmbedPolicy() {
    }

    /**
     * Determines whether a toot embed URL may be loaded in a Glacier iframe.
     *
     * <p>Implements the header-precedence logic extracted verbatim from
     * {@link StompCallback#isLoadable}:
     * <ol>
     *   <li>CSP {@code frame-ancestors} directive (if present) takes precedence over XFO.</li>
     *   <li>If no {@code frame-ancestors}: XFO controls; absent XFO defaults to allowed.</li>
     * </ol>
     *
     * <p>D-13/SR-8/CWE-117 (TD-4 / ADR-TD4-01): in the unknown-XFO branch the raw
     * {@code xFrameOptions} list is passed through {@link LogScrubber#xfoSummary} before
     * reaching the log encoder — never logged verbatim.
     *
     * @param xFrameOptions the values of the {@code X-Frame-Options} header from the HEAD
     *                      response; {@code null} means the header was absent; an empty list
     *                      means the header was present but held no values.
     * @param csp           the values of the {@code Content-Security-Policy} header from the
     *                      HEAD response; {@code null} or empty means the header was absent.
     *                      Only the first element is evaluated (HTTP standard: first CSP wins).
     * @param domain        the Glacier deployment domain (e.g. {@code "glacier.example.com"})
     *                      used to check for an explicit domain match in {@code frame-ancestors}.
     * @return {@code true} if the embed is permitted; {@code false} otherwise.
     */
    public static boolean isEmbeddable(
            @Nullable final List<String> xFrameOptions,
            @Nullable final List<String> csp,
            final String domain) {

        // SR-PQ-12 / C5 — fail-closed guard: blank domain means we cannot determine a safe
        // allowlist entry. Pattern.quote("") would produce a trivially-matching literal that
        // accepts any frame-ancestors host, silently opening an iframe-embedding bypass.
        // Return false immediately so misconfigured deployments are denied, not opened.
        if (domain == null || domain.isBlank()) {
            LOGGER.warn("Rejecting embed check: configured domain is blank — failing closed");
            return false;
        }

        boolean xFrameExplicitlyNotAllowed = false;
        boolean xFrameExplicitlyAllowed = false;
        boolean xFrameDefaultAllowed = true;
        boolean frameAncestorsExists = false;
        boolean frameAncestorsContainsServerOrWildcard = false;

        if (csp != null && !csp.isEmpty()) {
            // According to http standard, only the first Content-Security Policy is valid. So we take the first element of the Header list.
            // C3/CWE-178: Locale.ROOT ensures protocol-token comparison is locale-independent.
            // Turkish locale's dotless-i would corrupt 'i'→'İ' in domain names under toUpperCase().
            frameAncestorsExists = csp.getFirst().toUpperCase(Locale.ROOT).contains("FRAME-ANCESTORS");
            if (frameAncestorsExists) {
                frameAncestorsContainsServerOrWildcard = Stream.of(csp.getFirst().split(";"))
                        .filter(policy -> policy.toUpperCase(Locale.ROOT).contains("FRAME-ANCESTORS"))
                        .map(String::trim)
                        // This is not perfect, but if the site of the toot does not explicitly allow glacier, or all http(s) sites as ancestors, we will most likely not be able to load it.
                        // So this regex should match either *, http(s):, http(s)://* with or without ports or the glacier domain with or without leading http(s) and with or without ports.
                        // SR-PQ-07 / CWE-625 / ADR-PQ-06: Pattern.quote() escapes the operator-controlled
                        // domain so that dots and other metacharacters are matched literally, not as
                        // regex wildcards. Without this, a lookalike host (e.g. "glacierXevents") would
                        // bypass the check because '.' in "GLACIER.EVENTS" matches any character.
                        .anyMatch(policy -> policy.toUpperCase(Locale.ROOT).matches(
                                "FRAME-ANCESTORS (\\S+ )*((HTTPS?:(//)?)|((HTTPS?://)?\\*(:((\\*)|80|443))?)|((HTTPS?://)?"
                                        + Pattern.quote(domain.toUpperCase(Locale.ROOT))
                                        + "(:((\\*)|80|443))?))( \\S+)*")
                        );
            }
        }
        if (xFrameOptions != null) {
            xFrameDefaultAllowed = false;

            xFrameExplicitlyNotAllowed = xFrameOptions.stream()
                    .anyMatch(option -> option.equalsIgnoreCase("DENY") || option.equalsIgnoreCase("SAMEORIGIN"));

            xFrameExplicitlyAllowed = xFrameOptions.stream()
                    .anyMatch(option -> option.equalsIgnoreCase("ALLOWALL"));
        }

        if (frameAncestorsExists) {
            if (frameAncestorsContainsServerOrWildcard) {
                LOGGER.info("FRAME-ANCESTORS header exists and this server or a wildcard is allowed");
            } else {
                // SR-PQ-10R2 / CWE-117 / D-13/SR-8: static scrubbed message for SOC/SIEM tooling.
                // Do NOT log the raw domain or any peer-controlled header value here.
                // Class LOGGER retains WARN for operator alerting; AUDIT channel required for SIEM filtering.
                LOGGER.warn("iframe.embed.rejected reason=domain_mismatch");
                AUDIT.info("iframe.embed.rejected reason=domain_mismatch");
            }
            return frameAncestorsContainsServerOrWildcard;
        } else if (xFrameDefaultAllowed) {
            LOGGER.info("FRAME-ANCESTORS header does not exists. X-Frame-Options is default allowed");
            return true;
        } else {
            if (xFrameExplicitlyNotAllowed) {
                LOGGER.warn("FRAME-ANCESTORS header does not exists. X-Frame-Options explicitly not allowed");
                return false;
            } else if (xFrameExplicitlyAllowed) {
                LOGGER.info("FRAME-ANCESTORS header does not exists. X-Frame-Options explicitly allowed");
                return true;
            } else {
                // D-13/SR-8/CWE-117 (TD-4 / ADR-TD4-01): xFrameOptions is a peer-controlled List<String>
                // from the remote Mastodon instance HEAD response. Only the bounded summary is logged.
                LOGGER.warn("FRAME-ANCESTORS header does not exists. X-Frame-Options has unknown or invalid value — {}",
                        LogScrubber.xfoSummary(xFrameOptions));
                return false;
            }
        }
    }
}
