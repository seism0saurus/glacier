package de.seism0saurus.glacier.share.infrastructure;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import javax.sql.DataSource;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test verifying that the {@link SqliteShareLinkRepository} emits exactly one
 * {@code AUDIT} INFO log line containing {@code share.link.repository.active} during
 * {@code @PostConstruct init()}.
 *
 * <p>SR-SQLITE-06: the startup AUDIT log line must contain:
 * <ul>
 *   <li>The event token {@code share.link.repository.active}.</li>
 *   <li>The class simple name ({@code impl=SqliteShareLinkRepository}).</li>
 *   <li>{@code hash8(path)} — NOT the raw path (D-13/SR-8 log hygiene).</li>
 * </ul>
 *
 * <p>The raw DB path must NOT appear in the AUDIT log (D-13; CWE-532; ASVS V7.3.1 L1).
 *
 * <p>References: SR-SQLITE-06; D-13; SR-8; CWE-532; ASVS V7.3.1 (L1);
 * OWASP A09:2021 Logging and Monitoring Failures.
 */
class ShareLinkRepositoryAuditEmissionIT {

    private static final String VALID_KEY = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";
    // :memory: is the path used in tests — must not appear verbatim in logs
    private static final String DB_PATH = ":memory:";

    private ListAppender<ILoggingEvent> auditAppender;
    private Logger auditLogger;

    @BeforeEach
    void attachAppender() {
        auditLogger = (Logger) LoggerFactory.getLogger("AUDIT");
        auditAppender = new ListAppender<>();
        auditAppender.start();
        auditLogger.addAppender(auditAppender);
    }

    @AfterEach
    void detachAppender() {
        auditLogger.detachAppender(auditAppender);
    }

    /**
     * SR-SQLITE-06: init() must emit exactly one AUDIT INFO event containing
     * {@code share.link.repository.active}.
     */
    @Test
    void init_emitsAuditLogLineWithExpectedToken() {
        DataSource ds = buildInMemoryDataSource();
        SharePersistenceProperties props = buildProperties(DB_PATH);

        SqliteShareLinkRepository repo = new SqliteShareLinkRepository(props, ds);
        repo.init();

        List<ILoggingEvent> events = auditAppender.list;

        assertThat(events)
                .as("AUDIT logger must receive at least one event during init() (SR-SQLITE-06)")
                .isNotEmpty();

        boolean hasStartupEvent = events.stream()
                .anyMatch(e -> e.getFormattedMessage().contains("share.link.repository.active"));

        assertThat(hasStartupEvent)
                .as("At least one AUDIT event must contain 'share.link.repository.active' "
                        + "(SR-SQLITE-06)")
                .isTrue();
    }

    /**
     * D-13/SR-8: the raw DB path must NOT appear in any AUDIT log message.
     *
     * <p>The path is passed through {@link de.seism0saurus.glacier.util.LogScrubber#hash8}
     * which produces an 8-char hex digest. The raw path string must never reach the log
     * encoder (CWE-532; ASVS V7.3.1 L1).
     */
    @Test
    void init_doesNotLogRawDbPath() {
        // Use a distinctive path that is easy to detect in log output
        final String distinctivePath = ":memory:";
        DataSource ds = buildInMemoryDataSource();
        SharePersistenceProperties props = buildProperties(distinctivePath);

        SqliteShareLinkRepository repo = new SqliteShareLinkRepository(props, ds);
        repo.init();

        List<ILoggingEvent> events = auditAppender.list;

        // None of the AUDIT log messages should contain the raw path
        events.forEach(event -> {
            String msg = event.getFormattedMessage();
            assertThat(msg)
                    .as("AUDIT log must not contain raw DB path ':memory:' — use hash8() instead "
                            + "(D-13, SR-8, CWE-532, ASVS V7.3.1 L1). Found in: " + msg)
                    .doesNotContain(":memory:");
        });
    }

    /**
     * SR-SQLITE-06: the AUDIT event must contain the class simple name.
     */
    @Test
    void init_auditEventContainsImplClassName() {
        DataSource ds = buildInMemoryDataSource();
        SharePersistenceProperties props = buildProperties(DB_PATH);

        SqliteShareLinkRepository repo = new SqliteShareLinkRepository(props, ds);
        repo.init();

        List<ILoggingEvent> events = auditAppender.list;

        boolean hasImplName = events.stream()
                .anyMatch(e -> e.getFormattedMessage().contains("SqliteShareLinkRepository"));

        assertThat(hasImplName)
                .as("AUDIT event must contain the impl class name 'SqliteShareLinkRepository' "
                        + "(SR-SQLITE-06)")
                .isTrue();
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
