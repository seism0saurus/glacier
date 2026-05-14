package de.seism0saurus.glacier.share.infrastructure;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

/**
 * Verifies that {@link SqliteShareLinkRepository#scheduledSweep} does not kill the
 * scheduler thread and emits an AUDIT WARN when the underlying JDBC call fails.
 *
 * <p>The sweep is wrapped in a try-catch in {@link SqliteShareLinkRepository#scheduledSweep}.
 * This test uses a Mockito-stubbed {@link JdbcTemplate} that throws a
 * {@link DataAccessException} when the sweep DELETE is attempted, avoiding the
 * fragile POSIX chmod approach.
 *
 * <p>References: SR-SQLITE-14 (scheduler thread must not be killed); ADR-SQLITE-02.
 */
class SqliteShareLinkRepositoryDegradedSweepIT {

    private static final String HMAC_KEY = "A".repeat(44);

    private ListAppender<ILoggingEvent> auditAppender;
    private Logger auditLogger;

    @BeforeEach
    void attachAuditAppender() {
        auditLogger = (Logger) LoggerFactory.getLogger("AUDIT");
        auditAppender = new ListAppender<>();
        auditAppender.start();
        auditLogger.addAppender(auditAppender);
    }

    @AfterEach
    void detachAuditAppender() {
        if (auditLogger != null && auditAppender != null) {
            auditLogger.detachAppender(auditAppender);
            auditAppender.stop();
        }
    }

    /**
     * When the JdbcTemplate throws a DataAccessException during the sweep DELETE,
     * scheduledSweep must not propagate the exception and must log AUDIT WARN.
     *
     * <p>Arrange: a stubbed JdbcTemplate that throws {@link TransientDataAccessResourceException}
     *             (simulating a temporarily unavailable or read-only database) on any
     *             {@code update(...)} call.
     * <p>Act:     call {@code scheduledSweep()} directly.
     * <p>Assert:  no exception propagated; AUDIT logger received a WARN containing
     *             {@code "share.link.sweep.failed"} (SR-SQLITE-14).
     */
    @Test
    void scheduledSweep_onJdbcFailure_logsAuditWarnAndDoesNotThrow() {
        // Arrange: stub JdbcTemplate so the sweep DELETE raises DataAccessException.
        // The varargs form update(String, Object...) requires casting to Object[] for Mockito.
        JdbcTemplate failingJdbcTemplate = mock(JdbcTemplate.class);
        doThrow(new TransientDataAccessResourceException("simulated read-only DB"))
                .when(failingJdbcTemplate).update(anyString(), (Object[]) any());

        SharePersistenceProperties props = makeInMemoryProps();
        SqliteShareLinkRepository repository =
                new SqliteShareLinkRepository(failingJdbcTemplate, props);

        // Act: scheduledSweep must not throw even when sweepExpired fails
        assertThatCode(repository::scheduledSweep)
                .as("scheduledSweep must not propagate DataAccessException to the scheduler "
                        + "thread (SR-SQLITE-14)")
                .doesNotThrowAnyException();

        // Assert: AUDIT logger received a WARN starting with "share.link.sweep.failed"
        assertThat(auditAppender.list)
                .as("scheduledSweep must emit AUDIT.warn on JDBC failure (SR-SQLITE-14)")
                .filteredOn(e -> e.getLevel() == Level.WARN)
                .extracting(ILoggingEvent::getFormattedMessage)
                .anyMatch(m -> m.startsWith("share.link.sweep.failed"));
    }

    // -------------------------------------------------------------------------
    // Helper
    // -------------------------------------------------------------------------

    private static SharePersistenceProperties makeInMemoryProps() {
        SharePersistenceProperties props = new SharePersistenceProperties();
        props.setPath(":memory:");
        props.setIpHmacKey(HMAC_KEY);
        return props;
    }
}
