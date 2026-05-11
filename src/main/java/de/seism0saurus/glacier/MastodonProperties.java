package de.seism0saurus.glacier;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/**
 * Startup-validated {@code @ConfigurationProperties} bean for Mastodon connection settings.
 *
 * <p>Applying {@code @Validated} here means Spring Boot's
 * {@code ConfigurationPropertiesBindingPostProcessor} runs Jakarta Validation during
 * context startup — invalid or missing values cause an immediate {@code BindException}
 * rather than a silent NPE or misconfiguration at runtime.
 *
 * <p>Follows the {@code @Component + @ConfigurationProperties + @Validated} pattern
 * established by {@link GlacierCoreProperties} (ADR-P3A-1).
 *
 * <p>Bound properties (prefix: {@code mastodon}):
 * <ul>
 *   <li>{@code mastodon.instance} — Mastodon instance hostname (bare host or host:port)</li>
 *   <li>{@code mastodon.accessToken} — API access token (must not be blank)</li>
 *   <li>{@code mastodon.https} — whether to use HTTPS; {@link Boolean} (boxed) + {@code @NotNull}
 *       because primitive {@code boolean} silently defaults to {@code false} under
 *       {@code @ConfigurationProperties}, causing a silent HTTP downgrade
 *       (ADR-P3A-8; OWASP A02:2021; ASVS V9.1.1 L1)</li>
 *   <li>{@code mastodon.port} — API port (1–65535)</li>
 *   <li>{@code mastodon.readTimeout}, {@code mastodon.writeTimeout}, {@code mastodon.connectTimeout}
 *       — connection timeouts in seconds; {@code @Min(1)} because OkHttp treats 0 as no-timeout
 *       (SR-P3A-15: no-timeout = potential DoS / resource exhaustion)</li>
 *   <li>{@code mastodon.handle} — Mastodon handle of the bot account
 *       (SR-P3A-13: pattern uses {@code \A}/{\code \z} anchors to prevent multiline injection)</li>
 * </ul>
 *
 * <p>Security references:
 * <ul>
 *   <li>ADR-P3A-1: two beans by ownership/lifecycle</li>
 *   <li>ADR-P3A-8: boxed Boolean + @NotNull for mastodon.https</li>
 *   <li>SR-P3A-03: preserve fail-on-missing for mastodon.https</li>
 *   <li>SR-P3A-13: mastodon.handle must use \A/\z anchors, exclude control chars</li>
 *   <li>SR-P3A-15: timeout @Min(1) not @Min(0)</li>
 *   <li>OWASP A02:2021 — Cryptographic Failures (HTTP downgrade)</li>
 *   <li>ASVS V9.1.1 (L1) — require TLS</li>
 * </ul>
 */
@Component
@ConfigurationProperties(prefix = "mastodon")
@Validated
public class MastodonProperties {

    /**
     * Pattern for validating {@code mastodon.instance}: bare host or host:port.
     *
     * <p>Must start with an alphanumeric character. Optional port suffix {@code :N}
     * where N is a non-zero port number (1–65535 range enforced structurally by
     * requiring the first digit to be non-zero).
     *
     * <p>Uses {@code \A}/{\code \z} anchors (Java string escape: {@code \\A}/{\code \\z})
     * so that multiline strings with embedded newlines do NOT partially match
     * (SR-P3A-13; ADR-P3A-9).
     */
    public static final String MASTODON_INSTANCE_PATTERN =
            "\\A[a-zA-Z0-9](?:[a-zA-Z0-9.-]{0,251}[a-zA-Z0-9])?(?::[1-9][0-9]{0,4})?\\z";

    /**
     * Pattern for validating {@code mastodon.handle}.
     *
     * <p>Format: {@code @?localpart@server} where:
     * <ul>
     *   <li>Leading {@code @} is optional</li>
     *   <li>{@code localpart}: 1–64 chars from {@code [a-zA-Z0-9._-]}</li>
     *   <li>{@code @} separator (mandatory)</li>
     *   <li>{@code server}: 1–253 chars from {@code [a-zA-Z0-9.-]}</li>
     * </ul>
     *
     * <p>Uses {@code \A}/{\code \z} anchors (Java string escape: {@code \\A}/{\code \\z})
     * to prevent multiline injection — {@code ^}/{@code $} would allow a string like
     * {@code "user@srv\r\nX-Injected: 1"} to match because {@code $} matches before the
     * CRLF in multiline mode (SR-P3A-13; CWE-117).
     */
    public static final String MASTODON_HANDLE_PATTERN =
            "\\A@?[a-zA-Z0-9._-]{1,64}@[a-zA-Z0-9.-]{1,253}\\z";

    /** The Mastodon instance hostname (bare host or host:port). */
    @NotBlank(message = "mastodon.instance must not be blank — set INSTANCE to the Mastodon server hostname")
    @Pattern(regexp = MASTODON_INSTANCE_PATTERN,
            message = "mastodon.instance must be a bare hostname or host:port (no scheme, no path)")
    @MastodonInstanceValidator
    private String instance;

    /** The Mastodon API access token. Must not be blank. */
    @NotBlank(message = "mastodon.accessToken must not be blank — set ACCESS_KEY to a valid Mastodon access token")
    private String accessToken;

    /**
     * Whether to use HTTPS for the Mastodon connection.
     *
     * <p>MUST be {@link Boolean} (boxed), NOT {@code boolean} (primitive).
     * Primitive {@code boolean} in {@code @ConfigurationProperties} silently defaults
     * to {@code false} when the property is absent — causing a silent HTTP downgrade.
     * Boxed {@code Boolean + @NotNull} causes context startup to fail when absent
     * (ADR-P3A-8; OWASP A02:2021; ASVS V9.1.1 L1).
     */
    @NotNull(message = "mastodon.https must be set explicitly — set USE_HTTPS=true for production; "
            + "omitting it is NOT safe (ADR-P3A-8, ASVS V9.1.1 L1)")
    private Boolean https;

    /** The Mastodon API port (1–65535). */
    @Min(value = 1, message = "mastodon.port must be at least 1")
    @Max(value = 65535, message = "mastodon.port must be at most 65535")
    private int port;

    /**
     * Read timeout in seconds.
     *
     * <p>{@code @Min(1)} because OkHttp treats 0 as "no timeout" — a zero timeout
     * is a resource-exhaustion risk (SR-P3A-15: 0 = no-timeout DoS).
     */
    @Min(value = 1, message = "mastodon.readTimeout must be at least 1 second — 0 disables the timeout (SR-P3A-15)")
    private int readTimeout;

    /** Write timeout in seconds. @Min(1) for the same reason as readTimeout (SR-P3A-15). */
    @Min(value = 1, message = "mastodon.writeTimeout must be at least 1 second — 0 disables the timeout (SR-P3A-15)")
    private int writeTimeout;

    /** Connect timeout in seconds. @Min(1) for the same reason as readTimeout (SR-P3A-15). */
    @Min(value = 1, message = "mastodon.connectTimeout must be at least 1 second — 0 disables the timeout (SR-P3A-15)")
    private int connectTimeout;

    /**
     * The Mastodon handle of the bot account (e.g., {@code glacier@instance.social}).
     *
     * <p>Pattern uses {@code \A}/{\code \z} anchors (SR-P3A-13) to prevent
     * multiline injection attacks via CRLF-embedded handles (CWE-117).
     */
    @NotBlank(message = "mastodon.handle must not be blank — set HANDLE to the bot account handle (e.g., bot@instance.social)")
    @Pattern(regexp = MASTODON_HANDLE_PATTERN,
            message = "mastodon.handle must be in the form localpart@server (SR-P3A-13)")
    private String handle;

    // -------------------------------------------------------------------------
    // Standard Java Bean getters and setters
    // Follows the GlacierCoreProperties pattern (ADR-P3A-1)
    // -------------------------------------------------------------------------

    public String getInstance() {
        return instance;
    }

    public void setInstance(final String instance) {
        this.instance = instance;
    }

    public String getAccessToken() {
        return accessToken;
    }

    public void setAccessToken(final String accessToken) {
        this.accessToken = accessToken;
    }

    /**
     * Returns whether HTTPS should be used.
     *
     * <p>Returns {@link Boolean} (boxed), not {@code boolean} (ADR-P3A-8).
     *
     * @return {@code true} for HTTPS, {@code false} for HTTP
     */
    public Boolean getHttps() {
        return https;
    }

    public void setHttps(final Boolean https) {
        this.https = https;
    }

    public int getPort() {
        return port;
    }

    public void setPort(final int port) {
        this.port = port;
    }

    public int getReadTimeout() {
        return readTimeout;
    }

    public void setReadTimeout(final int readTimeout) {
        this.readTimeout = readTimeout;
    }

    public int getWriteTimeout() {
        return writeTimeout;
    }

    public void setWriteTimeout(final int writeTimeout) {
        this.writeTimeout = writeTimeout;
    }

    public int getConnectTimeout() {
        return connectTimeout;
    }

    public void setConnectTimeout(final int connectTimeout) {
        this.connectTimeout = connectTimeout;
    }

    public String getHandle() {
        return handle;
    }

    public void setHandle(final String handle) {
        this.handle = handle;
    }
}
