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
import java.util.Set;

/**
 * Jakarta Validation constraint that guards {@code glacier.domain} against dangerous values.
 *
 * <p>Prohibited values (Sec-12/P1-11, OWASP A05:2021):
 * <ul>
 *   <li>Loopback addresses: {@code localhost}, {@code 127.0.0.1}, {@code ::1}</li>
 *   <li>IPv4 private ranges: {@code 10.*}, {@code 172.16–31.*}, {@code 192.168.*}</li>
 *   <li>Link-local: {@code 169.254.*}, {@code fe80::*}</li>
 *   <li>Any address literal: {@code 0.0.0.0}</li>
 *   <li>CRLF injection characters: {@code \r}, {@code \n} (header injection / log injection)</li>
 *   <li>Null bytes: {@code \0} (path truncation attacks)</li>
 *   <li>Tab characters: {@code \t}</li>
 * </ul>
 *
 * <p>This annotation is applied to the {@code domain} field in
 * {@link GlacierCoreProperties}. Combined with {@code @NotBlank}, the full startup
 * validation prevents silent misconfiguration where {@code glacier.domain} is set to an
 * internal address or a value that enables header/log injection.
 *
 * <p>References: Sec-12/P1-11; OWASP A05:2021 Security Misconfiguration;
 * ASVS V5.1.3 (L1) — no CRLF in header values; C3 — input validation at every boundary.
 */
@Documented
@Constraint(validatedBy = DomainSafetyValidator.DomainSafetyConstraintValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
public @interface DomainSafetyValidator {

    String message() default "glacier.domain must not be a loopback/internal address and must not "
            + "contain CRLF, null bytes, or tab characters (Sec-12, OWASP A05:2021)";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    /**
     * The actual constraint validator implementation.
     *
     * <h2>Constraint-violation message policy (ADR-P3B-4)</h2>
     * <p>All {@code buildConstraintViolationWithTemplate()} calls in this class use
     * <strong>static string literals only</strong> — no raw domain value is ever concatenated
     * into a violation template. This is a defense-in-depth measure (CWE-532):
     * <ul>
     *   <li>{@code GlacierBindHandler.ScrubbingBindHandler} already drops the entire violation
     *       chain for the {@code @ConfigurationProperties} bind path. However, programmatic
     *       {@code validator.validate(bean)} calls, REST {@code @Valid} body binding
     *       ({@code MethodArgumentNotValidException}), AOP method validation, JMX attribute
     *       validation, and AOT introspection bypass that handler entirely.</li>
     *   <li>Making every violation message a static literal closes the Jakarta EL injection
     *       class entirely (EL template evaluation never receives the raw value), is trivially
     *       auditable by grep, and is enforced structurally by
     *       {@code ConstraintViolationMessageStaticOnlyStructureTest} (A4).</li>
     * </ul>
     *
     * <h2>Forward reference</h2>
     * <p><strong>TD-P3B-DOMAIN-UNICODE</strong> will add codepoint-class checks for
     * U+202E (RIGHT-TO-LEFT OVERRIDE), U+200B (ZERO-WIDTH SPACE), U+FEFF (BOM),
     * U+2028 (LINE SEPARATOR), and U+2029 (PARAGRAPH SEPARATOR) — these are out of scope
     * for Bundle B, which targets the log-leak primitive only.
     *
     * <p>References: ADR-P3B-4; SR-P3B-01; CWE-532; ASVS V7.3.1 (L1);
     * OWASP A05:2021; C3 (input validation); C5 (secure defaults).
     */
    class DomainSafetyConstraintValidator
            implements ConstraintValidator<DomainSafetyValidator, String> {

        /**
         * Prohibited literal domain values (case-insensitive).
         * These are the most common loopback/any-address values that could misconfigure
         * CORS, CSP frame-ancestors, and share-link URL generation to point at local services.
         */
        private static final Set<String> PROHIBITED_LITERALS = Set.of(
                "localhost",
                "127.0.0.1",
                "::1",
                "0.0.0.0",
                "[::]",
                "0:0:0:0:0:0:0:1"  // full-form ::1
        );

        @Override
        public boolean isValid(final String domain, final ConstraintValidatorContext context) {
            // null/blank is handled by @NotBlank — we only validate non-blank values here
            if (domain == null || domain.isBlank()) {
                return true; // let @NotBlank handle this case
            }

            String trimmed = domain.trim();

            // Check for CRLF injection, null bytes, and tab characters
            // These enable HTTP header injection and log injection attacks
            // ASVS V5.1.3 (L1) — no CRLF in header values
            if (trimmed.contains("\r") || trimmed.contains("\n")
                    || trimmed.contains("\0") || trimmed.contains("\t")) {
                context.disableDefaultConstraintViolation();
                context.buildConstraintViolationWithTemplate(
                        "glacier.domain must not contain CRLF characters, null bytes, or tabs "
                        + "(header injection / log injection risk) — Sec-12, ASVS V5.1.3")
                        .addConstraintViolation();
                return false;
            }

            // Check prohibited literal values (case-insensitive)
            String lower = trimmed.toLowerCase(java.util.Locale.ROOT);
            if (PROHIBITED_LITERALS.contains(lower)) {
                context.disableDefaultConstraintViolation();
                // ADR-P3B-4 (defense-in-depth, CWE-532): static literal only — no raw value echo.
                // GlacierBindHandler already scrubs the violation chain for the @ConfigurationProperties
                // bind path, but programmatic validator.validate(bean) calls, REST @Valid binding,
                // and AOP method validation bypass that handler. The message must be safe on its own.
                context.buildConstraintViolationWithTemplate(
                        "glacier.domain must not be a loopback or any-address literal "
                        + "(CORS/CSP misconfiguration risk) — Sec-12, OWASP A05:2021")
                        .addConstraintViolation();
                return false;
            }

            // Check for IPv4 loopback range (127.x.x.x)
            if (lower.startsWith("127.")) {
                context.disableDefaultConstraintViolation();
                context.buildConstraintViolationWithTemplate(
                        "glacier.domain must not be a loopback address (127.x.x.x) — Sec-12")
                        .addConstraintViolation();
                return false;
            }

            // Check for RFC1918 private IP ranges that would misconfigure CORS/CSP
            if (isPrivateIpv4Range(lower)) {
                context.disableDefaultConstraintViolation();
                context.buildConstraintViolationWithTemplate(
                        "glacier.domain must not be a private IP address range — Sec-12")
                        .addConstraintViolation();
                return false;
            }

            // Check for IPv6 loopback prefixes that are not caught by the literal check
            if (lower.startsWith("fe80") || lower.startsWith("[fe80")) {
                context.disableDefaultConstraintViolation();
                context.buildConstraintViolationWithTemplate(
                        "glacier.domain must not be an IPv6 link-local address — Sec-12")
                        .addConstraintViolation();
                return false;
            }

            return true;
        }

        /**
         * Returns {@code true} if the (already lowercased) domain looks like an RFC1918 or
         * link-local IPv4 address.
         *
         * <p>Checks: {@code 192.168.*}, {@code 172.16.*}–{@code 172.31.*}, {@code 10.*},
         * {@code 169.254.*}.
         */
        private static boolean isPrivateIpv4Range(final String lower) {
            if (lower.startsWith("192.168.")) return true;
            if (lower.startsWith("10.")) return true;
            if (lower.startsWith("169.254.")) return true;
            // 172.16.0.0/12 = 172.16.x.x through 172.31.x.x
            if (lower.startsWith("172.")) {
                String[] parts = lower.split("\\.", -1);
                if (parts.length >= 2) {
                    try {
                        int second = Integer.parseInt(parts[1]);
                        if (second >= 16 && second <= 31) return true;
                    } catch (NumberFormatException ignore) {
                        // not a valid IPv4 segment, not matching this range
                    }
                }
            }
            return false;
        }
    }
}
