package de.seism0saurus.glacier.share.infrastructure;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/**
 * Startup-validated {@code @ConfigurationProperties} bean for the SQLite share-link
 * persistence adapter.
 *
 * <p>Active only when {@code glacier.share.db.path} is present in the environment.
 * When the property is absent the entire bean is skipped and the in-memory adapter
 * remains active — opt-in, backwards-compatible (ADR-SQLITE-01).
 *
 * <p>Applying {@code @Validated} here causes Spring Boot's binding post-processor to
 * run Jakarta Validation at startup. Any constraint violation produces a
 * {@code BindException} and refuses to start the context — fail-closed behaviour
 * (SR-SQLITE-17).
 *
 * <h2>Bound properties (prefix: {@code glacier.share.db})</h2>
 * <ul>
 *   <li>{@code glacier.share.db.path} — filesystem path to the SQLite DB file;
 *       validated by {@link SafeFilesystemPath} against traversal and metacharacter
 *       attacks (SR-SQLITE-08).</li>
 *   <li>{@code glacier.share.db.ip-hmac-key} — Base64-encoded HMAC-SHA256 key for
 *       pseudonymising creator IPs; minimum 44 characters (256 bits in Base64,
 *       SR-SQLITE-22).</li>
 *   <li>{@code glacier.share.db.max-page-count} — SQLite {@code PRAGMA max_page_count};
 *       default 65536 (= 256 MiB with the default 4096-byte page size); SR-SQLITE-09.</li>
 *   <li>{@code glacier.share.db.busy-timeout-ms} — SQLite {@code PRAGMA busy_timeout};
 *       default 5000 ms; SR-SQLITE-13.</li>
 * </ul>
 *
 * <h2>Security references</h2>
 * <ul>
 *   <li>ADR-SQLITE-01: conditional wiring via {@code @ConditionalOnProperty}</li>
 *   <li>SR-SQLITE-08: {@link SafeFilesystemPath} on the {@code path} field</li>
 *   <li>SR-SQLITE-17: {@code @Validated} — startup {@code BindException} for invalid fields</li>
 *   <li>SR-SQLITE-22: {@code @Size(min=44)} on {@code ipHmacKey} — 256-bit Base64 minimum</li>
 *   <li>OWASP A05:2021 — Security Misconfiguration: fail-closed on missing/invalid secrets</li>
 * </ul>
 */
@Component
@ConfigurationProperties(prefix = "glacier.share.db")
@Validated
@ConditionalOnProperty(name = "glacier.share.db.path")
public class SharePersistenceProperties {

    /**
     * Filesystem path to the SQLite database file.
     *
     * <p>Validated by {@link SafeFilesystemPath}: rejects path-traversal sequences ({@code ..}),
     * home-directory expansion ({@code ~}), shell metacharacters, null bytes, and Unicode
     * direction-override characters (SR-SQLITE-08; CWE-22; CWE-626).
     *
     * <p>The special values {@code :memory:} and {@code jdbc:sqlite::memory:} are allowed
     * verbatim for test contexts.
     */
    @SafeFilesystemPath
    private String path;

    /**
     * Base64-encoded HMAC-SHA256 key for pseudonymising creator IP addresses.
     *
     * <p>Creator IPs are stored as {@code HMAC-SHA256(ip, key)} in the {@code creator_ip_hmac}
     * column — no raw IP addresses are written to disk (SR-SQLITE-04; GDPR Art. 25).
     *
     * <p>{@code @Size(min=44)} enforces a 256-bit minimum key length when Base64-encoded
     * (⌈32 bytes × 8 / 6⌉ = 43, rounded to 44 for standard Base64 padding; SR-SQLITE-22).
     *
     * <p>{@code @NotBlank} ensures a missing or whitespace-only key causes a startup failure
     * rather than silently using an empty HMAC key (fail-closed, SR-SQLITE-17).
     *
     * <p><strong>NEVER</strong> log this field — use {@link de.seism0saurus.glacier.util.LogScrubber#hash8}
     * when correlation is needed (D-13; SR-8).
     */
    @NotBlank
    @Size(min = 44, message = "glacier.share.db.ip-hmac-key must be at least 44 characters (256-bit Base64 minimum, SR-SQLITE-22)")
    private String ipHmacKey;

    /**
     * SQLite {@code PRAGMA max_page_count} — caps the maximum database file size.
     *
     * <p>Default 65536 pages × 4096 bytes/page = 256 MiB maximum DB size.
     * When the limit is hit, writes throw a JDBC exception which the adapter maps to
     * {@link de.seism0saurus.glacier.share.application.CapacityExceededException} (SR-SQLITE-09).
     */
    @Min(1)
    private int maxPageCount = 65536;

    /**
     * SQLite {@code PRAGMA busy_timeout} in milliseconds.
     *
     * <p>Determines how long SQLite waits for a write lock before returning SQLITE_BUSY.
     * Default 5000 ms; aligned with HikariCP {@code connectionTimeout=2000} so HikariCP
     * times out first under load, preventing indefinite lock waits (SR-SQLITE-13;
     * ADR-SQLITE-09).
     */
    @Min(1)
    private int busyTimeoutMs = 5000;

    // -------------------------------------------------------------------------
    // Standard getters / setters — required by @ConfigurationProperties binding
    // -------------------------------------------------------------------------

    /**
     * Returns the filesystem path to the SQLite database file.
     *
     * @return the path; may be null before binding completes
     */
    public String getPath() {
        return path;
    }

    /**
     * Sets the filesystem path to the SQLite database file.
     *
     * @param path the path value from Spring binding; must satisfy {@link SafeFilesystemPath}
     */
    public void setPath(final String path) {
        this.path = path;
    }

    /**
     * Returns the Base64-encoded HMAC-SHA256 key for IP pseudonymisation.
     *
     * <p><strong>NEVER log this value directly</strong> — use
     * {@link de.seism0saurus.glacier.util.LogScrubber#hash8} for correlation.
     *
     * @return the HMAC key; never null or blank after successful binding
     */
    public String getIpHmacKey() {
        return ipHmacKey;
    }

    /**
     * Sets the HMAC key.
     *
     * @param ipHmacKey the key value from Spring binding
     */
    public void setIpHmacKey(final String ipHmacKey) {
        this.ipHmacKey = ipHmacKey;
    }

    /**
     * Returns the SQLite {@code PRAGMA max_page_count} value.
     *
     * @return max page count; default 65536
     */
    public int getMaxPageCount() {
        return maxPageCount;
    }

    /**
     * Sets the max page count.
     *
     * @param maxPageCount the value from Spring binding; must be &ge; 1
     */
    public void setMaxPageCount(final int maxPageCount) {
        this.maxPageCount = maxPageCount;
    }

    /**
     * Returns the SQLite {@code PRAGMA busy_timeout} in milliseconds.
     *
     * @return busy timeout in ms; default 5000
     */
    public int getBusyTimeoutMs() {
        return busyTimeoutMs;
    }

    /**
     * Sets the busy timeout.
     *
     * @param busyTimeoutMs the value from Spring binding; must be &ge; 1
     */
    public void setBusyTimeoutMs(final int busyTimeoutMs) {
        this.busyTimeoutMs = busyTimeoutMs;
    }
}
