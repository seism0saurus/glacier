package de.seism0saurus.glacier.mastodon;

/**
 * Value object representing a validated Mastodon account handle.
 *
 * <p>A Mastodon handle has the form {@code localPart@server} (optionally prefixed with a
 * leading {@code @}). This record stores the three components of the canonical form:
 * <ul>
 *   <li>{@link #full} — {@code "localPart@server"} without a leading {@code @}</li>
 *   <li>{@link #localPart} — the account name on the server (before the {@code @})</li>
 *   <li>{@link #server} — the server host (after the {@code @})</li>
 * </ul>
 *
 * <p>Immutable value object (record). All three components are always non-null and non-blank
 * when a valid instance is created — the compact constructor enforces this invariant.
 *
 * <p>Usage: obtain an instance via the static factory {@link #parse(String)}, never via
 * the canonical record constructor directly. The factory and the compact constructor share
 * identical validation logic.
 *
 * <p>Security:
 * <ul>
 *   <li>{@link #toString()} returns only {@code "MastodonShortHandle[localPart=...]"} —
 *       never the {@link #full} or {@link #server} fields — to prevent accidental leakage
 *       via log statements (D-13 / SR-8).</li>
 *   <li>Control characters, Unicode directional overrides, and handles over 254 characters
 *       are rejected to prevent log injection (CWE-117) and header injection (CWE-113).</li>
 * </ul>
 *
 * <p>Bounded context: Mastodon provisioning layer.
 * ADR references: ADR-P3A-2 (factory pattern), SR-P3A-06 (parse rejection superset),
 * SR-P3A-13 (control-character exclusion).
 *
 * @param full       the canonical form without a leading {@code @} (e.g. {@code "glacier@instance.social"})
 * @param localPart  the local account name (e.g. {@code "glacier"})
 * @param server     the server hostname (e.g. {@code "instance.social"})
 */
public record MastodonShortHandle(String full, String localPart, String server) {

    /**
     * Maximum total length of a Mastodon handle in characters.
     *
     * <p>254 follows the email address length limit (RFC 5321 §4.5.3.1.3), which most
     * Mastodon implementations honour. Handles beyond this length are almost certainly
     * malformed or adversarial input.
     */
    private static final int MAX_HANDLE_LENGTH = 254;

    /**
     * Compact constructor: validates all three components on construction.
     *
     * <p>Because records cannot have private canonical constructors, the compact constructor
     * IS the validation gate. It validates the already-parsed components, so callers that
     * bypass {@link #parse(String)} and supply components directly still receive validated
     * instances (fail-fast invariant).
     *
     * @throws IllegalArgumentException if any component is null, blank, or the full handle
     *                                  exceeds {@value #MAX_HANDLE_LENGTH} characters
     */
    public MastodonShortHandle {
        if (full == null || full.isBlank()) {
            throw new IllegalArgumentException("MastodonShortHandle: full must not be null or blank");
        }
        if (localPart == null || localPart.isBlank()) {
            throw new IllegalArgumentException("MastodonShortHandle: localPart must not be null or blank");
        }
        if (server == null || server.isBlank()) {
            throw new IllegalArgumentException("MastodonShortHandle: server must not be null or blank");
        }
        if (full.length() > MAX_HANDLE_LENGTH) {
            throw new IllegalArgumentException(
                    "MastodonShortHandle: handle exceeds maximum length of " + MAX_HANDLE_LENGTH + " characters");
        }
        rejectControlCharacters(full);
    }

    /**
     * Parses a raw Mastodon handle string into a validated {@link MastodonShortHandle}.
     *
     * <p>Accepts both {@code "localPart@server"} and {@code "@localPart@server"} forms.
     * The optional leading {@code @} is stripped; the canonical form stored in {@link #full}
     * never contains a leading {@code @}.
     *
     * <p>Validation rules (SR-P3A-06 / SR-P3A-13):
     * <ul>
     *   <li>{@code null} &rarr; {@link NullPointerException}</li>
     *   <li>blank / whitespace-only &rarr; {@link IllegalArgumentException}</li>
     *   <li>no internal {@code @} after stripping optional leading {@code @} &rarr;
     *       {@link IllegalArgumentException}</li>
     *   <li>multiple internal {@code @} &rarr; {@link IllegalArgumentException}
     *       (old {@code getShortHandle} silently truncated; {@code parse()} rejects)</li>
     *   <li>empty local part &rarr; {@link IllegalArgumentException}</li>
     *   <li>control characters ({@code \r}, {@code \n}, {@code \t}, {@code \0}) &rarr;
     *       {@link IllegalArgumentException}</li>
     *   <li>Unicode directional / invisible characters (U+202E, U+200B, U+FEFF, U+2028,
     *       U+2029) &rarr; {@link IllegalArgumentException}</li>
     *   <li>total length &gt; 254 &rarr; {@link IllegalArgumentException}</li>
     * </ul>
     *
     * @param input the raw handle string; must not be {@code null}
     * @return a validated {@link MastodonShortHandle}
     * @throws NullPointerException     if {@code input} is {@code null}
     * @throws IllegalArgumentException if {@code input} fails any validation rule
     */
    public static MastodonShortHandle parse(final String input) {
        // null -> NullPointerException (distinguishes "missing" from "invalid")
        if (input == null) {
            throw new NullPointerException("mastodon handle must not be null");
        }
        // blank / whitespace-only
        if (input.isBlank()) {
            throw new IllegalArgumentException("mastodon handle must not be blank");
        }
        // Total length cap — checked before any other parsing to avoid O(n) operations on hostile input
        if (input.length() > MAX_HANDLE_LENGTH) {
            throw new IllegalArgumentException(
                    "mastodon handle exceeds maximum length of " + MAX_HANDLE_LENGTH + " characters");
        }
        // Control characters and Unicode injection vectors (log/header injection guard)
        rejectControlCharacters(input);

        // Strip optional leading @
        String normalized = input.startsWith("@") ? input.substring(1) : input;

        // After stripping the leading @, the remainder must contain exactly one @
        if (!normalized.contains("@")) {
            throw new IllegalArgumentException(
                    "mastodon handle does not contain an '@' separator — both local part and server are required");
        }
        // Multiple internal @ separators -> ambiguous; reject (strict superset of legacy behaviour)
        long atCount = normalized.chars().filter(ch -> ch == '@').count();
        if (atCount != 1) {
            throw new IllegalArgumentException(
                    "mastodon handle must contain exactly one '@' separator (found " + atCount + ")");
        }

        int atIndex = normalized.indexOf('@');
        String localPart = normalized.substring(0, atIndex);
        String serverPart = normalized.substring(atIndex + 1);

        if (localPart.isBlank()) {
            throw new IllegalArgumentException("mastodon handle has an empty local part");
        }
        if (serverPart.isBlank()) {
            throw new IllegalArgumentException("mastodon handle has an empty server part");
        }

        // full = canonical form without leading @
        return new MastodonShortHandle(normalized, localPart, serverPart);
    }

    /**
     * Rejects control characters and Unicode injection vectors.
     *
     * <p>Checked characters (SR-P3A-13):
     * <ul>
     *   <li>U+000D CR ({@code \r}), U+000A LF ({@code \n}) — log line / header injection
     *       (CWE-117 / CWE-113)</li>
     *   <li>U+0009 HT ({@code \t}) — log field separator injection</li>
     *   <li>U+0000 NUL ({@code \0}) — null-byte injection</li>
     *   <li>U+202E RIGHT-TO-LEFT OVERRIDE — visual spoofing</li>
     *   <li>U+200B ZERO WIDTH SPACE — invisible character injection</li>
     *   <li>U+FEFF BOM / ZERO WIDTH NO-BREAK SPACE — invisible character injection</li>
     *   <li>U+2028 LINE SEPARATOR — log line injection</li>
     *   <li>U+2029 PARAGRAPH SEPARATOR — log paragraph injection</li>
     * </ul>
     *
     * @param value the string to inspect
     * @throws IllegalArgumentException if any prohibited character is found
     */
    private static void rejectControlCharacters(final String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\r'              // U+000D CARRIAGE RETURN
                    || c == '\n'       // U+000A LINE FEED
                    || c == '\t'       // U+0009 HORIZONTAL TAB
                    || c == '\0'       // U+0000 NULL
                    || c == '‮'   // RIGHT-TO-LEFT OVERRIDE
                    || c == '​'   // ZERO WIDTH SPACE
                    || c == '﻿'   // BOM / ZERO WIDTH NO-BREAK SPACE
                    || c == ' '   // LINE SEPARATOR
                    || c == ' '   // PARAGRAPH SEPARATOR
            ) {
                throw new IllegalArgumentException(
                        "mastodon handle contains a prohibited control or Unicode direction character at index " + i);
            }
        }
    }

    /**
     * Returns a log-safe string representation that does NOT expose the server or full handle.
     *
     * <p>Overrides the default record {@code toString()} (which would include all components)
     * to prevent accidental leakage of the server hostname via SLF4J parameter placeholders
     * or exception message concatenation (D-13 / SR-8).
     *
     * @return {@code "MastodonShortHandle[localPart=<localPart>]"}
     */
    @Override
    public String toString() {
        return "MastodonShortHandle[localPart=" + localPart + "]";
    }
}
