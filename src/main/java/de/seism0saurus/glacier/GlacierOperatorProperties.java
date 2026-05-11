package de.seism0saurus.glacier;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/**
 * Startup-validated {@code @ConfigurationProperties} bean for Glacier operator legal-notice fields.
 *
 * <p>Applying {@code @Validated} here means Spring Boot's
 * {@code ConfigurationPropertiesBindingPostProcessor} runs Jakarta Validation during
 * context startup — invalid or missing values cause an immediate {@code BindException}
 * rather than a silent NPE or injected value at runtime.
 *
 * <p>All string fields use {@link SafeOperatorString} to reject control characters
 * and Unicode direction-override characters (ADR-P3A-9; SR-P3A-12).
 *
 * <p>Operator keys use the nested {@code glacier.operator.*} form (ADR-P3A-3).
 * Old camelCase keys ({@code glacier.operatorName}, etc.) are no longer accepted.
 * The {@code MY_*} env-var names are UNCHANGED.
 *
 * <p>Bound properties (prefix: {@code glacier.operator}):
 * <ul>
 *   <li>{@code glacier.operator.name} — operator display name (must not be blank)</li>
 *   <li>{@code glacier.operator.streetAndNumber} — street address</li>
 *   <li>{@code glacier.operator.zipcode} — postal code</li>
 *   <li>{@code glacier.operator.city} — city</li>
 *   <li>{@code glacier.operator.country} — country</li>
 *   <li>{@code glacier.operator.phone} — phone number</li>
 *   <li>{@code glacier.operator.mail} — contact email address (@Email validated)</li>
 *   <li>{@code glacier.operator.website} — website URL (must not contain dangerous schemes)</li>
 * </ul>
 *
 * <p>Security references:
 * <ul>
 *   <li>ADR-P3A-3: nested {@code glacier.operator.*} key form</li>
 *   <li>ADR-P3A-9: {@code @SafeOperatorString} on all fields</li>
 *   <li>SR-P3A-11: reject {@code javascript:}, {@code data:}, {@code vbscript:} URIs in website</li>
 *   <li>SR-P3A-12: all 8 operator string fields use {@code @SafeOperatorString}</li>
 *   <li>OWASP A05:2021 — Security Misconfiguration</li>
 *   <li>CWE-79 — XSS (dangerous URI schemes in website field)</li>
 * </ul>
 */
@Component
@ConfigurationProperties(prefix = "glacier.operator")
@Validated
public class GlacierOperatorProperties {

    /**
     * Pattern for operator website.
     *
     * <p>Accepts:
     * <ul>
     *   <li>Bare hostname: {@code example.com}</li>
     *   <li>{@code http://} or {@code https://} prefix followed by hostname</li>
     *   <li>Optional port: {@code :N}</li>
     *   <li>Optional path: {@code /...} (printable ASCII, max 1024 chars)</li>
     * </ul>
     *
     * <p>Rejects (SR-P3A-11; CWE-79):
     * <ul>
     *   <li>{@code javascript:} — XSS via JavaScript execution</li>
     *   <li>{@code data:} — inline HTML/script execution</li>
     *   <li>{@code vbscript:} — IE/legacy scripting</li>
     *   <li>{@code file:} — local file access</li>
     *   <li>Any other non-http(s) scheme</li>
     * </ul>
     *
     * <p>Uses {@code \A}/{\code \z} anchors (SR-P3A-13 pattern for anchors).
     */
    public static final String OPERATOR_WEBSITE_PATTERN =
            "\\A(?:https?://)?[a-zA-Z0-9](?:[a-zA-Z0-9.-]{0,251}[a-zA-Z0-9])?(?::[1-9][0-9]{0,4})?(?:/[!-~]{0,1024})?\\z";

    /**
     * Pattern for operator phone number.
     * Accepts international format: {@code +} optional, digits, spaces, {@code ()}, {@code /}, {@code .}, {@code -}.
     * Uses {@code \A}/{\code \z} anchors.
     */
    public static final String OPERATOR_PHONE_PATTERN =
            "\\A[+]?[0-9 ()/.-]{1,32}\\z";

    /**
     * Pattern for operator postal code.
     * Accepts alphanumeric codes with spaces and hyphens (covers DE, US, UK, etc.).
     * Uses {@code \A}/{\code \z} anchors.
     */
    public static final String OPERATOR_ZIPCODE_PATTERN =
            "\\A[A-Za-z0-9 -]{1,16}\\z";

    /**
     * Operator display name. Must not be blank and must not exceed 256 characters
     * (SEC-P3A-05: served unauthenticated via /rest/operator; ADR-P3A-9; SR-P3A-12).
     */
    @NotBlank(message = "glacier.operator.name must not be blank — set MY_NAME to the operator's display name")
    @Size(max = 256, message = "glacier.operator.name must be at most 256 characters (SEC-P3A-05)")
    @SafeOperatorString
    private String name;

    /** Operator street address (optional blank, but must be safe and bounded if provided). */
    @Size(max = 256, message = "glacier.operator.streetAndNumber must be at most 256 characters (SEC-P3A-05)")
    @SafeOperatorString
    private String streetAndNumber;

    /** Operator postal code. Pattern enforces safe character set; SafeOperatorString adds CRLF guard. */
    @Pattern(regexp = OPERATOR_ZIPCODE_PATTERN,
            message = "glacier.operator.zipcode must be alphanumeric with spaces and hyphens only (max 16 chars)")
    @SafeOperatorString
    private String zipcode;

    /** Operator city. Capped at 128 characters (SEC-P3A-05: served unauthenticated). */
    @Size(max = 128, message = "glacier.operator.city must be at most 128 characters (SEC-P3A-05)")
    @SafeOperatorString
    private String city;

    /** Operator country. Capped at 128 characters (SEC-P3A-05: served unauthenticated). */
    @Size(max = 128, message = "glacier.operator.country must be at most 128 characters (SEC-P3A-05)")
    @SafeOperatorString
    private String country;

    /** Operator phone number. */
    @Pattern(regexp = OPERATOR_PHONE_PATTERN,
            message = "glacier.operator.phone must match +NNN format (digits, spaces, ()/-. only)")
    @SafeOperatorString
    private String phone;

    /**
     * Operator contact email address.
     *
     * <p>{@code @Email} validates the format; {@code @SafeOperatorString} guards
     * against CRLF and Unicode injection in the raw value.
     */
    @Email(message = "glacier.operator.mail must be a valid email address — set MY_MAIL to a valid email")
    @SafeOperatorString
    private String mail;

    /**
     * Operator website URL.
     *
     * <p>The pattern explicitly allows only {@code http://}, {@code https://}, or
     * bare hostname — rejecting {@code javascript:}, {@code data:}, {@code vbscript:},
     * and {@code file:} schemes (SR-P3A-11; CWE-79: XSS).
     */
    @Pattern(regexp = OPERATOR_WEBSITE_PATTERN,
            message = "glacier.operator.website must be a valid http(s) URL or bare hostname "
                    + "(javascript:, data:, and other URI schemes are not permitted — SR-P3A-11)")
    @SafeOperatorString
    private String website;

    // -------------------------------------------------------------------------
    // Standard Java Bean getters and setters
    // Follows the GlacierCoreProperties pattern (ADR-P3A-1)
    // -------------------------------------------------------------------------

    public String getName() {
        return name;
    }

    public void setName(final String name) {
        this.name = name;
    }

    public String getStreetAndNumber() {
        return streetAndNumber;
    }

    public void setStreetAndNumber(final String streetAndNumber) {
        this.streetAndNumber = streetAndNumber;
    }

    public String getZipcode() {
        return zipcode;
    }

    public void setZipcode(final String zipcode) {
        this.zipcode = zipcode;
    }

    public String getCity() {
        return city;
    }

    public void setCity(final String city) {
        this.city = city;
    }

    public String getCountry() {
        return country;
    }

    public void setCountry(final String country) {
        this.country = country;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(final String phone) {
        this.phone = phone;
    }

    public String getMail() {
        return mail;
    }

    public void setMail(final String mail) {
        this.mail = mail;
    }

    public String getWebsite() {
        return website;
    }

    public void setWebsite(final String website) {
        this.website = website;
    }
}
