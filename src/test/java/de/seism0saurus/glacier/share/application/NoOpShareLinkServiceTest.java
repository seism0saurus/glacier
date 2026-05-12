package de.seism0saurus.glacier.share.application;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import de.seism0saurus.glacier.share.domain.SecureRandomTokenGenerator;
import de.seism0saurus.glacier.share.domain.ShareLinkId;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link NoOpShareLinkService}.
 *
 * <p>The no-op service is a stub that guards the application context while the real
 * {@link ShareLinkServiceImpl} is not yet available via {@code @ConditionalOnMissingBean}.
 * These tests verify the stub contracts so that callers can rely on predictable behaviour:
 * {@code create} fails hard, {@code resolve} and {@code listBySharer} return empty,
 * and {@code revoke} only logs a warning.
 */
class NoOpShareLinkServiceTest {

    private final NoOpShareLinkService service = new NoOpShareLinkService();
    private final SecureRandomTokenGenerator tokenGenerator = new SecureRandomTokenGenerator();

    /**
     * {@code create} must throw {@link UnsupportedOperationException} so callers learn
     * immediately that the real implementation is missing — a silent no-op would hide the gap.
     *
     * <p>Arrange: stub service; any wallId and IP.
     * <p>Act:     call {@code create}.
     * <p>Assert:  {@link UnsupportedOperationException} thrown.
     */
    @Test
    void create_throwsUnsupportedOperationException() {
        assertThatThrownBy(() -> service.create("any-wall-id", "10.0.0.1", Instant.now()))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    /**
     * {@code resolve} returns empty regardless of the share-link ID, because the no-op service
     * has no persistence — all links are effectively unknown.
     *
     * <p>Arrange: any share-link ID.
     * <p>Act:     call {@code resolve}.
     * <p>Assert:  {@link Optional#empty()}.
     */
    @Test
    void resolve_returnsEmpty() {
        ShareLinkId id = tokenGenerator.generateShareLinkId();

        Optional<?> result = service.resolve(id, Instant.now());

        assertThat(result).isEmpty();
    }

    /**
     * {@code listSummaryBySharer} returns an empty list — the no-op has no stored links.
     *
     * <p>Arrange: any wallId.
     * <p>Act:     call {@code listSummaryBySharer}.
     * <p>Assert:  empty list returned, no exception thrown.
     */
    @Test
    void listSummaryBySharer_returnsEmptyList() {
        List<?> result = service.listSummaryBySharer("any-wall-id", Instant.now());

        assertThat(result).isNotNull().isEmpty();
    }

    /**
     * {@code listBySharer} (deprecated) returns an empty list — the no-op has no stored links.
     *
     * <p>Arrange: any wallId.
     * <p>Act:     call the deprecated {@code listBySharer}.
     * <p>Assert:  empty list returned, no exception thrown.
     */
    @Test
    @SuppressWarnings("deprecation")
    void listBySharer_returnsEmptyList() {
        List<?> result = service.listBySharer("any-wall-id", Instant.now());

        assertThat(result).isNotNull().isEmpty();
    }

    /**
     * {@code revoke} must not throw — the no-op stub logs a WARN message but completes
     * normally so that callers do not need exception handling for the disabled path.
     *
     * <p>Arrange: any share-link ID and wallId; attach a list appender to the service's logger.
     * <p>Act:     call {@code revoke}.
     * <p>Assert:  no exception; at least one WARN message logged by the service's logger.
     */
    @Test
    void revoke_logsWarnWithoutThrowing() {
        // Attach a list appender to capture log output from NoOpShareLinkService
        Logger noOpLogger = (Logger) LoggerFactory.getLogger(NoOpShareLinkService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        noOpLogger.addAppender(appender);

        try {
            ShareLinkId id = tokenGenerator.generateShareLinkId();

            // Act — must not throw
            service.revoke(id, "any-wall-id", Instant.now());

            // Assert — WARN message logged
            assertThat(appender.list)
                    .as("NoOpShareLinkService.revoke must log at WARN level")
                    .anySatisfy(event -> assertThat(event.getLevel()).isEqualTo(Level.WARN));
        } finally {
            noOpLogger.detachAppender(appender);
            appender.stop();
        }
    }
}
