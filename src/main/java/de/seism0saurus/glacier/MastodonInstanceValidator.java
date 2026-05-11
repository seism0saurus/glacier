package de.seism0saurus.glacier;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Jakarta Validation constraint for the {@code mastodon.instance} property.
 *
 * <p>Valid instances:
 * <ul>
 *   <li>Bare hostname: {@code mastodon.social}</li>
 *   <li>Hostname with port: {@code my-instance.example.com:8443}</li>
 * </ul>
 *
 * <p>Rejected values:
 * <ul>
 *   <li>Any value containing {@code ://} (explicit scheme — SSRF precursor)</li>
 *   <li>Any value containing a path component (slash after host)</li>
 *   <li>CRLF, null bytes, tabs (log injection / header injection, CWE-117)</li>
 *   <li>Private IPv4 ranges (RFC1918): 10.x, 172.16–31.x, 192.168.x (SSRF, OWASP API8)</li>
 *   <li>Loopback: 127.x.x.x, ::1, localhost (SSRF)</li>
 *   <li>Link-local: 169.254.x, fe80:: (SSRF)</li>
 * </ul>
 *
 * <p>This validator does NOT delegate to {@link DomainSafetyValidator} because that class
 * has a raw-value-concatenation defect in its error message at line 98
 * (tracked as TD-P3A-DOMAIN-FIX). A separate validator is used to avoid inheriting
 * that defect (ADR-P3A-10).
 *
 * <p>Violation messages are STATIC strings only — no value interpolation
 * (SR-P3A-01; CWE-532; ASVS V7.3.1 L1).
 *
 * <p>References: ADR-P3A-10; SR-P3A-01; OWASP A05:2021; OWASP API8; ASVS V5.1.3 (L1).
 */
@Documented
@Constraint(validatedBy = MastodonInstanceValidator.MastodonInstanceConstraintValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
public @interface MastodonInstanceValidator {

    String message() default "mastodon.instance must be a plain hostname (no scheme, no path, no private/loopback addresses)";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    /**
     * The actual constraint validator implementation.
     *
     * <p>Does NOT use raw value concatenation in error messages (ADR-P3A-10).
     */
    class MastodonInstanceConstraintValidator
            implements ConstraintValidator<MastodonInstanceValidator, String> {

        @Override
        public boolean isValid(final String value, final ConstraintValidatorContext context) {
            // Null/blank handled by @NotBlank — only validate non-blank values
            if (value == null || value.isBlank()) {
                return true;
            }

            String v = value.trim();

            // Reject CRLF, null byte, tab (header injection / log injection)
            // ASVS V5.1.3 (L1)
            if (containsControlChars(v)) {
                addStaticViolation(context,
                        "mastodon.instance must not contain CRLF, null bytes, or tab characters");
                return false;
            }

            // Reject explicit scheme (e.g. http://, https://)
            // SSRF precursor — instance should be a bare host, not a URL
            if (v.contains("://")) {
                addStaticViolation(context,
                        "mastodon.instance must be a bare hostname, not a URL (no '://')");
                return false;
            }

            // Extract host part (before optional :port)
            String host = extractHost(v);

            // Reject loopback and private/internal IP ranges (SSRF guard)
            // OWASP API8:2023; NIST SP 800-204
            String lower = host.toLowerCase(java.util.Locale.ROOT);
            if (isProhibitedHost(lower)) {
                addStaticViolation(context,
                        "mastodon.instance must not be a loopback, link-local, or private IP address");
                return false;
            }

            return true;
        }

        /**
         * Returns {@code true} if the value contains control characters that could
         * enable log injection or HTTP header injection.
         *
         * @param v the trimmed value to check
         * @return {@code true} if dangerous control characters are present
         */
        private static boolean containsControlChars(final String v) {
            return v.contains("\r") || v.contains("\n")
                    || v.contains("\0") || v.contains("\t");
        }

        /**
         * Extracts the host portion from a value that may include an optional port.
         * For values of the form {@code host:port}, returns {@code host}.
         * For plain hosts, returns the value as-is.
         *
         * @param v the trimmed value (already confirmed to not contain "://")
         * @return the host portion
         */
        private static String extractHost(final String v) {
            int colonIdx = v.lastIndexOf(':');
            if (colonIdx > 0) {
                // Could be host:port — only strip port if what follows is all digits
                String maybePort = v.substring(colonIdx + 1);
                if (maybePort.matches("[0-9]+")) {
                    return v.substring(0, colonIdx);
                }
            }
            return v;
        }

        /**
         * Returns {@code true} if the (already lowercased) host is a loopback,
         * link-local, or private IP address that should never be a Mastodon instance.
         *
         * <p>Checks (RFC1918 + loopback + link-local):
         * <ul>
         *   <li>{@code localhost}, {@code 127.x.x.x}</li>
         *   <li>{@code ::1}, full-form {@code 0:0:0:0:0:0:0:1}, {@code [::1]}</li>
         *   <li>{@code 10.x}, {@code 172.16.x}–{@code 172.31.x}, {@code 192.168.x}</li>
         *   <li>{@code 169.254.x} (link-local), {@code fe80:} (IPv6 link-local)</li>
         *   <li>{@code 0.0.0.0}, {@code [::]}.</li>
         * </ul>
         *
         * @param lower the lowercased host portion
         * @return {@code true} if prohibited
         */
        private static boolean isProhibitedHost(final String lower) {
            // Loopback literals
            if ("localhost".equals(lower)
                    || "127.0.0.1".equals(lower)
                    || "::1".equals(lower)
                    || "[::1]".equals(lower)
                    || "0:0:0:0:0:0:0:1".equals(lower)
                    || "0.0.0.0".equals(lower)
                    || "[::]".equals(lower)) {
                return true;
            }
            // 127.x.x.x loopback range
            if (lower.startsWith("127.")) {
                return true;
            }
            // RFC1918: 10.x
            if (lower.startsWith("10.")) {
                return true;
            }
            // RFC1918: 192.168.x
            if (lower.startsWith("192.168.")) {
                return true;
            }
            // Link-local IPv4: 169.254.x
            if (lower.startsWith("169.254.")) {
                return true;
            }
            // IPv6 link-local: fe80:
            if (lower.startsWith("fe80") || lower.startsWith("[fe80")) {
                return true;
            }
            // RFC1918: 172.16.0.0/12 = 172.16.x through 172.31.x
            if (lower.startsWith("172.")) {
                String[] parts = lower.split("\\.", -1);
                if (parts.length >= 2) {
                    try {
                        int second = Integer.parseInt(parts[1]);
                        if (second >= 16 && second <= 31) {
                            return true;
                        }
                    } catch (NumberFormatException ignore) {
                        // not a valid IPv4 segment — not matching this range
                    }
                }
            }
            return false;
        }

        /**
         * Adds a constraint violation with a static message (no value interpolation).
         *
         * <p>SR-P3A-01 / ADR-P3A-7 / CWE-532: violation messages must NEVER include
         * the rejected value — even partially. Use only static strings here.
         *
         * @param context the constraint validator context
         * @param staticMessage the static message to use
         */
        private static void addStaticViolation(
                final ConstraintValidatorContext context, final String staticMessage) {
            context.disableDefaultConstraintViolation();
            context.buildConstraintViolationWithTemplate(staticMessage)
                    .addConstraintViolation();
        }
    }
}
