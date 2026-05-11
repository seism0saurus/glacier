package de.seism0saurus.glacier;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// Note: StartupSanityChecker has a mixed-state constructor (B6, ADR-P3B-1).
// This test directly instantiates the checker; test helpers provide GlacierCookieProperties.

/**
 * Unit tests for {@link StartupSanityChecker}.
 *
 * <p>Covers:
 * <ul>
 *   <li>Sec-01: devmode startup guard — must throw {@link IllegalStateException} when
 *       {@code glacier.devmode=true} outside the {@code dev} or {@code test} profiles.</li>
 *   <li>Sec-23: cookie.secure=true ∧ mastodon.https=false contradiction — must emit WARN.</li>
 * </ul>
 *
 * <p>Security references: OWASP A05:2021 Security Misconfiguration; ASVS V1.14.7 (L2)
 * devmode guard; ASVS V3.4.1 (L1) Secure cookie flag.
 */
class StartupSanityCheckerTest {

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static ListAppender<ILoggingEvent> attachAppender() {
        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(StartupSanityChecker.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    private static void detachAppender(ListAppender<ILoggingEvent> appender) {
        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(StartupSanityChecker.class);
        logger.detachAppender(appender);
    }

    /** Factory helper: creates {@link GlacierCookieProperties} with the given secure flag. */
    private static GlacierCookieProperties cookieProps(boolean secure) {
        GlacierCookieProperties props = new GlacierCookieProperties();
        props.setSecure(secure);
        return props;
    }

    /** Build a checker with the given devmode flag and active profiles. */
    private static StartupSanityChecker checker(boolean devmode, boolean mastodonHttps,
            boolean cookieSecure, String... activeProfiles) {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles(activeProfiles);
        return new StartupSanityChecker(devmode, mastodonHttps, cookieProps(cookieSecure), env);
    }

    // -----------------------------------------------------------------------
    // Sec-01: devmode guard
    // -----------------------------------------------------------------------

    /**
     * Sec-01 (pass): devmode=false, any profile — no exception thrown.
     *
     * <p>Arrange: devmode=false, no active profiles.
     * Act: call {@code checkConfigurationInvariants()}.
     * Assert: no exception.
     */
    @Test
    void devmodeGuard_devmodeFalse_noExceptionThrown() {
        StartupSanityChecker sut = checker(false, true, true);
        // Must not throw
        sut.checkDevmodeNotInProduction();
    }

    /**
     * Sec-01 (pass): devmode=true with the 'dev' profile active — safe, no exception.
     *
     * <p>Arrange: devmode=true, active profile = ["dev"].
     * Act: call {@code checkDevmodeNotInProduction()}.
     * Assert: no exception.
     */
    @Test
    void devmodeGuard_devmodeTrueWithDevProfile_noExceptionThrown() {
        StartupSanityChecker sut = checker(true, false, false, "dev");
        sut.checkDevmodeNotInProduction();
    }

    /**
     * Sec-01 (pass): devmode=true with the 'test' profile active — safe, no exception.
     *
     * <p>Arrange: devmode=true, active profile = ["test"].
     * Act: call {@code checkDevmodeNotInProduction()}.
     * Assert: no exception.
     */
    @Test
    void devmodeGuard_devmodeTrueWithTestProfile_noExceptionThrown() {
        StartupSanityChecker sut = checker(true, false, false, "test");
        sut.checkDevmodeNotInProduction();
    }

    /**
     * Sec-01 (fail): devmode=true with no active profile — must throw.
     *
     * <p>This is the critical production case: {@code glacier.devmode=true} without
     * the {@code dev} or {@code test} profile signals a misconfigured production deployment.
     * The guard must fail-fast to prevent attacker-controlled wall content.
     *
     * <p>Arrange: devmode=true, no active profiles.
     * Act: call {@code checkDevmodeNotInProduction()}.
     * Assert: {@link IllegalStateException} is thrown with a message citing Sec-01.
     */
    @Test
    void devmodeGuard_devmodeTrueWithNoProfile_throwsIllegalStateException() {
        StartupSanityChecker sut = checker(true, false, false);
        assertThatThrownBy(sut::checkDevmodeNotInProduction)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("glacier.devmode=true")
                .hasMessageContaining("FORBIDDEN in production");
    }

    /**
     * Sec-01 (fail): devmode=true with a non-dev/test profile ('production') — must throw.
     *
     * <p>Arrange: devmode=true, active profile = ["production"].
     * Act: call {@code checkDevmodeNotInProduction()}.
     * Assert: {@link IllegalStateException} is thrown.
     */
    @Test
    void devmodeGuard_devmodeTrueWithProductionProfile_throwsIllegalStateException() {
        StartupSanityChecker sut = checker(true, false, false, "production");
        assertThatThrownBy(sut::checkDevmodeNotInProduction)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("glacier.devmode=true");
    }

    /**
     * Sec-01 (pass): devmode=true with the 'dev' profile among multiple profiles — no exception.
     *
     * <p>Arrange: devmode=true, active profiles = ["production", "dev"].
     * Act: call {@code checkDevmodeNotInProduction()}.
     * Assert: no exception.
     */
    @Test
    void devmodeGuard_devmodeTrueWithDevAmongMultipleProfiles_noExceptionThrown() {
        StartupSanityChecker sut = checker(true, false, false, "production", "dev");
        sut.checkDevmodeNotInProduction();
    }

    /**
     * Sec-01 (exception message): the thrown exception must mention 'dev' and 'test' profiles
     * as the required conditions for devmode.
     */
    @Test
    void devmodeGuard_exceptionMessage_mentionsDevAndTestProfiles() {
        StartupSanityChecker sut = checker(true, false, false);
        assertThatThrownBy(sut::checkDevmodeNotInProduction)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("'dev'")
                .hasMessageContaining("'test'");
    }

    // -----------------------------------------------------------------------
    // Sec-23: cookie.secure ∧ mastodon.https contradiction
    // -----------------------------------------------------------------------

    /**
     * Sec-23 (no warn): both cookie.secure and mastodon.https are true — no contradiction.
     *
     * <p>Arrange: cookieSecure=true, mastodonHttps=true.
     * Act: call {@code checkCookieSecureVsMastodonHttps()}.
     * Assert: no WARN logged.
     */
    @Test
    void cookieHttpsGuard_bothTrue_noWarnLogged() {
        ListAppender<ILoggingEvent> appender = attachAppender();
        try {
            StartupSanityChecker sut = checker(false, true, true);
            sut.checkCookieSecureVsMastodonHttps();
            assertThat(appender.list).noneMatch(e -> e.getLevel() == Level.WARN);
        } finally {
            detachAppender(appender);
        }
    }

    /**
     * Sec-23 (no warn): cookie.secure=false, mastodon.https=false — no contradiction.
     *
     * <p>Arrange: cookieSecure=false, mastodonHttps=false.
     * Act: call {@code checkCookieSecureVsMastodonHttps()}.
     * Assert: no WARN logged.
     */
    @Test
    void cookieHttpsGuard_bothFalse_noWarnLogged() {
        ListAppender<ILoggingEvent> appender = attachAppender();
        try {
            StartupSanityChecker sut = checker(false, false, false);
            sut.checkCookieSecureVsMastodonHttps();
            assertThat(appender.list).noneMatch(e -> e.getLevel() == Level.WARN);
        } finally {
            detachAppender(appender);
        }
    }

    /**
     * Sec-23 (no warn): cookie.secure=false, mastodon.https=true — no contradiction.
     *
     * <p>Arrange: cookieSecure=false, mastodonHttps=true.
     * Act: call {@code checkCookieSecureVsMastodonHttps()}.
     * Assert: no WARN logged.
     */
    @Test
    void cookieHttpsGuard_cookieNotSecureMastodonHttps_noWarnLogged() {
        ListAppender<ILoggingEvent> appender = attachAppender();
        try {
            StartupSanityChecker sut = checker(false, true, false);
            sut.checkCookieSecureVsMastodonHttps();
            assertThat(appender.list).noneMatch(e -> e.getLevel() == Level.WARN);
        } finally {
            detachAppender(appender);
        }
    }

    /**
     * Sec-23 (WARN): cookie.secure=true, mastodon.https=false — contradiction must emit WARN.
     *
     * <p>Arrange: cookieSecure=true, mastodonHttps=false.
     * Act: call {@code checkCookieSecureVsMastodonHttps()}.
     * Assert: at least one WARN log event is emitted containing the Sec-23 reference.
     */
    @Test
    void cookieHttpsGuard_cookieSecureMastodonNotHttps_emitsWarn() {
        ListAppender<ILoggingEvent> appender = attachAppender();
        try {
            StartupSanityChecker sut = checker(false, false, true);
            sut.checkCookieSecureVsMastodonHttps();
            assertThat(appender.list)
                    .as("A WARN should be logged for cookie.secure=true ∧ mastodon.https=false (Sec-23)")
                    .anySatisfy(e -> {
                        assertThat(e.getLevel()).isEqualTo(Level.WARN);
                        assertThat(e.getFormattedMessage()).contains("Sec-23");
                    });
        } finally {
            detachAppender(appender);
        }
    }

    /**
     * Sec-23 (WARN message content): the WARN message must mention both
     * {@code cookie.secure} and {@code mastodon.https} so operators can diagnose
     * the issue from the log output alone.
     */
    @Test
    void cookieHttpsGuard_warnMessage_mentionsBothConfigKeys() {
        ListAppender<ILoggingEvent> appender = attachAppender();
        try {
            StartupSanityChecker sut = checker(false, false, true);
            sut.checkCookieSecureVsMastodonHttps();
            assertThat(appender.list).anySatisfy(e -> {
                assertThat(e.getLevel()).isEqualTo(Level.WARN);
                assertThat(e.getFormattedMessage())
                        .contains("glacier.cookie.secure=true")
                        .contains("mastodon.https=false");
            });
        } finally {
            detachAppender(appender);
        }
    }

    // -----------------------------------------------------------------------
    // Sec-01 + Sec-23 combined: checkConfigurationInvariants() integration
    // -----------------------------------------------------------------------

    /**
     * Both guards invoked via {@code checkConfigurationInvariants()} —
     * verifies the devmode guard fires first (fail-fast) even when the
     * cookie/https contradiction is also present.
     */
    @Test
    void checkConfigurationInvariants_devmodeGuardFiresFirst() {
        // devmode=true, no profile, plus cookie/https contradiction
        StartupSanityChecker sut = checker(true, false, true);
        assertThatThrownBy(sut::checkConfigurationInvariants)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("glacier.devmode=true");
    }

    /**
     * Both guards happy path — no exception, no warn for a well-configured production deployment.
     */
    @Test
    void checkConfigurationInvariants_wellConfiguredProduction_noExceptionNoWarn() {
        ListAppender<ILoggingEvent> appender = attachAppender();
        try {
            StartupSanityChecker sut = checker(false, true, true);
            sut.checkConfigurationInvariants();
            assertThat(appender.list).noneMatch(e -> e.getLevel() == Level.WARN);
        } finally {
            detachAppender(appender);
        }
    }
}
