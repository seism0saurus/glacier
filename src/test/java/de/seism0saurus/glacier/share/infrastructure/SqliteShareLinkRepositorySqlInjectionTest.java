package de.seism0saurus.glacier.share.infrastructure;

import de.seism0saurus.glacier.share.domain.SecureRandomTokenGenerator;
import de.seism0saurus.glacier.share.domain.ShareLink;
import de.seism0saurus.glacier.share.domain.ShareLinkId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import javax.sql.DataSource;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Parameterised SQL-injection fuzzing for {@link SqliteShareLinkRepository} (SR-SQLITE-02;
 * SR-SQLITE-10; OWASP A03:2021).
 *
 * <p>All methods that accept user-controlled strings ({@code sharerWallId}, {@code creatorIp})
 * must handle SQL-injection payloads without throwing, without corrupting data, and without
 * leaking any rows.
 *
 * <p>Because all SQL uses {@link org.springframework.jdbc.core.JdbcTemplate} with parameterised
 * {@code PreparedStatement} binding, injection strings are treated as data values — they cannot
 * escape the parameter context.
 *
 * <p>References: SR-SQLITE-02; SR-SQLITE-10; OWASP A03:2021 Injection; ASVS V5.3.4 (L1).
 */
class SqliteShareLinkRepositorySqlInjectionTest {

    private static final Duration TTL = Duration.ofDays(7);
    private static final Instant T0 = Instant.parse("2025-06-01T00:00:00Z");
    private static final String VALID_KEY = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";
    private static final String LEGITIMATE_SHARER = "sharer-wall-id-sql-injection-test-0000";
    private static final String LEGITIMATE_IP = "10.0.0.1";

    private SqliteShareLinkRepository repository;
    private SecureRandomTokenGenerator tokenGenerator;

    @BeforeEach
    void setUp() {
        tokenGenerator = new SecureRandomTokenGenerator();
        DataSource ds = buildInMemoryDataSource();
        SharePersistenceProperties props = buildProperties(":memory:");
        repository = new SqliteShareLinkRepository(props, ds);
        repository.init();
    }

    /**
     * Passing a SQL-injection string as {@code sharerWallId} to methods that accept it must:
     * <ol>
     *   <li>Not throw any exception.</li>
     *   <li>Not corrupt the row count (legitimate rows unaffected).</li>
     * </ol>
     *
     * <p>Arrange: save 1 legitimate link.
     * <p>Act:     call countActiveForSharer and listSummaryBySharer with an injection payload.
     * <p>Assert:  no exception; count = 0 (injection does not match legitimate sharer);
     *             legitimate link still has count = 1.
     */
    @ParameterizedTest(name = "sharerWallId injection: {0}")
    @ValueSource(strings = {
            "' OR '1'='1",
            "'; DROP TABLE share_links; --",
            "' UNION SELECT 1,2,3,4,5,6 --",
            "\\' OR 1=1--",
            "1; SELECT * FROM share_links--"
    })
    void sqlInjectionAsSharerWallId_doesNotCorruptData(final String injectionPayload) {
        // Arrange: one legitimate link
        ShareLink legitimate = ShareLink.create(
                tokenGenerator.generateShareLinkId(), LEGITIMATE_SHARER, LEGITIMATE_IP, T0, TTL);
        repository.save(legitimate);

        // Act: use injection payload as sharerWallId — must not throw
        assertThatCode(() -> {
            int count = repository.countActiveForSharer(injectionPayload, T0);
            assertThat(count)
                    .as("SQL injection as sharerWallId must not match the legitimate sharer's links")
                    .isEqualTo(0);
        }).doesNotThrowAnyException();

        assertThatCode(() -> {
            var summaries = repository.listSummaryBySharer(injectionPayload, T0);
            assertThat(summaries)
                    .as("SQL injection as sharerWallId must return empty list — not leak legitimate rows")
                    .isEmpty();
        }).doesNotThrowAnyException();

        // Legitimate data must be unaffected
        assertThat(repository.countActiveForSharer(LEGITIMATE_SHARER, T0))
                .as("Legitimate sharer's row count must not be affected by injection attempts")
                .isEqualTo(1);
    }

    /**
     * Passing a SQL-injection string as {@code creatorIp} to countActiveForIp must:
     * <ol>
     *   <li>Not throw any exception.</li>
     *   <li>Return 0 (injection cannot match the HMAC-protected column).</li>
     *   <li>Not corrupt the table.</li>
     * </ol>
     */
    @ParameterizedTest(name = "creatorIp injection: {0}")
    @ValueSource(strings = {
            "' OR '1'='1",
            "'; DROP TABLE share_links; --",
            "' UNION SELECT id FROM share_links--"
    })
    void sqlInjectionAsCreatorIp_doesNotCorruptData(final String injectionPayload) {
        ShareLink legitimate = ShareLink.create(
                tokenGenerator.generateShareLinkId(), LEGITIMATE_SHARER, LEGITIMATE_IP, T0, TTL);
        repository.save(legitimate);

        assertThatCode(() -> {
            int count = repository.countActiveForIp(injectionPayload, T0);
            assertThat(count)
                    .as("SQL injection as creatorIp must not match legitimate rows "
                            + "(HMAC makes injection string a non-matching hash)")
                    .isEqualTo(0);
        }).doesNotThrowAnyException();

        // Legitimate IP still matches
        assertThat(repository.countActiveForIp(LEGITIMATE_IP, T0))
                .as("Legitimate IP count must not be corrupted by injection attempts")
                .isEqualTo(1);
    }

    /**
     * Using an injection string as a {@code sharerWallId} when saving a link must
     * store the string as data and not cause corruption of other rows.
     *
     * <p>SQLite's parameterised binding treats the string as a literal data value.
     */
    @ParameterizedTest(name = "sharerWallId injection in save: {0}")
    @ValueSource(strings = {
            "' OR '1'='1",
            "'; DROP TABLE share_links; --"
    })
    void sqlInjectionAsSharerWallIdInSave_storedAsLiteralData(final String injectionPayload) {
        ShareLink legitimate = ShareLink.create(
                tokenGenerator.generateShareLinkId(), LEGITIMATE_SHARER, LEGITIMATE_IP, T0, TTL);
        repository.save(legitimate);

        // Save a link with the injection string as sharerWallId
        ShareLink injectedLink = ShareLink.create(
                tokenGenerator.generateShareLinkId(), injectionPayload, LEGITIMATE_IP, T0, TTL);
        assertThatCode(() -> repository.save(injectedLink))
                .as("Saving a link with an injection payload as sharerWallId must not throw")
                .doesNotThrowAnyException();

        // Legitimate data is still intact
        assertThat(repository.countActive(T0))
                .as("Both the legitimate and injection-payload links must be saved as separate rows")
                .isEqualTo(2);
        assertThat(repository.countActiveForSharer(LEGITIMATE_SHARER, T0))
                .as("Legitimate sharer count must remain 1 after injection save")
                .isEqualTo(1);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static SharePersistenceProperties buildProperties(final String path) {
        SharePersistenceProperties props = new SharePersistenceProperties();
        props.setPath(path);
        props.setIpHmacKey(VALID_KEY);
        return props;
    }

    private static DataSource buildInMemoryDataSource() {
        SingleConnectionDataSource ds = new SingleConnectionDataSource();
        ds.setUrl("jdbc:sqlite::memory:");
        ds.setSuppressClose(true);
        return ds;
    }
}
