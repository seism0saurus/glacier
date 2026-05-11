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
 * <p>This bean is only activated when {@code glacier.share.db.path} is set
 * (ADR-SQLITE-01; {@code @ConditionalOnProperty}).  The in-memory adapter
 * ({@link InMemoryShareLinkRepository}) remains the default when the property is absent.
 *
 * <p>Applying {@code @Validated} here means Spring Boot's
 * {@code ConfigurationPropertiesBindingPostProcessor} runs Jakarta Validation during
 * context startup — a blank HMAC key or traversal path causes an immediate
 * {@code BindException} rather than a silent failure at runtime (SR-SQLITE-17; CWE-20).
 *
 * <h3>Fields</h3>
 * <ul>
 *   <li>{@code path} — filesystem path to the SQLite database file, or {@code :memory:}
 *       for in-memory operation (tests only). Validated by {@link SafeFilesystemPath} to
 *       prevent path traversal (SR-SQLITE-08; CWE-22).</li>
 *   <li>{@code ipHmacKey} — base64-encoded HMAC-SHA256 key for IP pseudonymisation.
 *       Must be at least 44 characters (256-bit base64 minimum, SR-SQLITE-22).
 *       Required — blank or absent causes a {@code BindException} at startup.
 *       NEVER log this value — it is a secret (D-13/SR-8).</li>
 *   <li>{@code maxPageCount} — {@code PRAGMA max_page_count} — limits SQLite file size.
 *       Default: 65536 pages (~256 MB at default 4 KB page size). Must be ≥ 1.</li>
 *   <li>{@code busyTimeoutMs} — {@code PRAGMA busy_timeout} in ms. Default: 5000 ms.
 *       Controls how long SQLite waits for a write lock. Must be ≥ 1.</li>
 * </ul>
 *
 * <p>References: ADR-SQLITE-01; SR-SQLITE-05; SR-SQLITE-08; SR-SQLITE-13; SR-SQLITE-17;
 * SR-SQLITE-22; CWE-22; ASVS V5.1.3 (L1); OWASP A05:2021 Security Misconfiguration.
 */
@Component
@ConfigurationProperties(prefix = "glacier.share.db")
@ConditionalOnProperty(name = "glacier.share.db.path")
@Validated
public class SharePersistenceProperties {

    /**
     * Path to the SQLite database file, or {@code :memory:} for in-memory operation.
     *
     * <p>Validated by {@link SafeFilesystemPath} to reject path traversal sequences,
     * shell metacharacters, null bytes, and Unicode direction controls (SR-SQLITE-08;
     * CWE-22; CWE-626).
     *
     * <p>The {@code :memory:} value is explicitly allowed for test environments.
     */
    @SafeFilesystemPath
    private String path;

    /**
     * Base64-encoded HMAC-SHA256 key for pseudonymising creator IP addresses before storage.
     *
     * <p>Must be at least 44 characters (base64url of 256 bits, SR-SQLITE-22).
     * Required — blank or absent value causes a {@code BindException} at startup.
     *
     * <p><strong>Security: NEVER log this value</strong> — it is a symmetric secret.
     * Key rotation invalidates all existing HMAC values in the {@code creator_ip_hmac}
     * column (see ADR-SQLITE-06 for the accepted trade-off).
     *
     * <p>References: SR-SQLITE-22; ADR-SQLITE-06; D-13/SR-8; ASVS V6.4.1 (L2);
     * GDPR Art. 25 (Data Protection by Design).
     */
    @NotBlank(message = "glacier.share.db.ip-hmac-key must not be blank — "
            + "set GLACIER_SHARE_DB_IP_HMAC_KEY to a base64 256-bit key (SR-SQLITE-22)")
    @Size(min = 44, message = "glacier.share.db.ip-hmac-key must be at least 44 characters "
            + "(base64 256-bit minimum, SR-SQLITE-22)")
    private String ipHmacKey;

    /**
     * SQLite {@code PRAGMA max_page_count} — caps the database file size.
     *
     * <p>Default: 65536 pages × default 4 KB page size = ~256 MB.
     * Must be ≥ 1 (SR-SQLITE-09).
     */
    @Min(value = 1, message = "glacier.share.db.max-page-count must be at least 1")
    private int maxPageCount = 65536;

    /**
     * SQLite {@code PRAGMA busy_timeout} in milliseconds.
     *
     * <p>Controls how long SQLite waits for a write lock before throwing
     * {@code SQLITE_BUSY}. Default: 5000 ms (SR-SQLITE-13). Must be ≥ 1.
     */
    @Min(value = 1, message = "glacier.share.db.busy-timeout-ms must be at least 1")
    private int busyTimeoutMs = 5000;

    public String getPath() {
        return path;
    }

    public void setPath(final String path) {
        this.path = path;
    }

    public String getIpHmacKey() {
        return ipHmacKey;
    }

    public void setIpHmacKey(final String ipHmacKey) {
        this.ipHmacKey = ipHmacKey;
    }

    public int getMaxPageCount() {
        return maxPageCount;
    }

    public void setMaxPageCount(final int maxPageCount) {
        this.maxPageCount = maxPageCount;
    }

    public int getBusyTimeoutMs() {
        return busyTimeoutMs;
    }

    public void setBusyTimeoutMs(final int busyTimeoutMs) {
        this.busyTimeoutMs = busyTimeoutMs;
    }
}
