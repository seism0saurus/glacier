package de.seism0saurus.glacier.share.infrastructure;

import de.seism0saurus.glacier.util.LogScrubber;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

/**
 * {@link BeanFactoryPostProcessor} that creates and {@code chmod}s the SQLite database
 * file to POSIX {@code 0600} permissions <em>before</em> any other bean (including
 * {@link SqliteDataSourceConfig} and HikariCP) is constructed.
 *
 * <h2>Problem solved (CRIT-2/F-1)</h2>
 * {@link SqliteDataSourceConfig#shareDataSource(SharePersistenceProperties)} calls
 * {@code new HikariDataSource(config)} which eagerly opens a real JDBC connection via
 * {@code HikariPool.checkFailFast()}. For {@code sqlite-jdbc}, opening a connection
 * creates the SQLite file with the JVM's default umask (typically {@code 0644}).
 * {@link SqliteShareLinkRepository#init()} runs only <em>after</em> all beans are
 * constructed — leaving a window where the file is world-readable.
 *
 * <p>This {@link BeanFactoryPostProcessor} runs in the very first phase of Spring's
 * lifecycle (before any bean is instantiated) and ensures the file exists with
 * {@code 0600} permissions before HikariCP constructs its pool.
 *
 * <h2>Why {@code EnvironmentAware}?</h2>
 * {@link BeanFactoryPostProcessor} implementations are instantiated very early, before
 * the full bean factory is ready for arbitrary {@code getBean()} calls. Using
 * {@link EnvironmentAware} is the correct contract for accessing the
 * {@link Environment} at this lifecycle phase — Spring injects it before
 * {@link #postProcessBeanFactory} is called.
 *
 * <h2>Defence-in-depth</h2>
 * {@link SqliteShareLinkRepository}'s {@code enforceFilePermissions()} remains active
 * as a second line of defence — it catches the case where the file was pre-existing or
 * where an operator replaced it between BFP time and {@code @PostConstruct} time.
 *
 * <h2>In-memory paths</h2>
 * The values {@code :memory:} and {@code jdbc:sqlite::memory:} are skipped entirely —
 * they have no file-system representation.
 *
 * <h2>Non-POSIX file systems</h2>
 * On non-POSIX systems (e.g., Windows), an AUDIT WARN is logged and execution
 * continues — operators must enforce permissions manually (consistent with the
 * existing {@code enforceFilePermissions()} behaviour in the repository).
 *
 * <p>References: CRIT-2/F-1; SR-SQLITE-07; NIST SP 800-53 AC-3 (Access Enforcement);
 * ISO/IEC 27002 — 8.3 Information access restriction; OWASP A05:2021 — Security Misconfiguration;
 * C5 — secure-by-default.
 */
@Component
@ConditionalOnProperty(name = "glacier.share.db.path")
public class SqliteDbFileInitializer implements BeanFactoryPostProcessor, EnvironmentAware {

    private static final Logger AUDIT = LoggerFactory.getLogger("AUDIT");

    /** In-memory SQLite path that must not be treated as a real file path. */
    private static final String MEMORY_BARE = ":memory:";

    /** Spring Boot JDBC URL form of the in-memory SQLite path. */
    private static final String MEMORY_JDBC = "jdbc:sqlite::memory:";

    /**
     * Spring {@link Environment} injected before {@link #postProcessBeanFactory} runs.
     * Uses the {@link EnvironmentAware} contract because {@code BeanFactoryPostProcessor}
     * implementations must not call arbitrary {@code getBean()} on the factory.
     */
    private Environment environment;

    @Override
    public void setEnvironment(final Environment environment) {
        this.environment = environment;
    }

    /**
     * Reads {@code glacier.share.db.path} from the environment and creates/chmods the
     * database file to {@code 0600} (owner r/w only) <em>before any bean is instantiated</em>.
     *
     * <p>This prevents HikariCP's eager {@code checkFailFast()} from creating the file
     * with the JVM's default umask (CRIT-2/F-1; SR-SQLITE-07).
     *
     * @param beanFactory the bean factory (not used for bean lookup in this implementation)
     * @throws BeansException wrapped around {@link IllegalStateException} if file creation
     *                        or permission enforcement fails fatally
     */
    @Override
    public void postProcessBeanFactory(final ConfigurableListableBeanFactory beanFactory)
            throws BeansException {
        String path = environment.getProperty("glacier.share.db.path");

        if (path == null || MEMORY_BARE.equals(path) || MEMORY_JDBC.equals(path)) {
            // In-memory or absent — no file to create/chmod
            return;
        }

        Path filePath = Path.of(path);
        try {
            if (!Files.exists(filePath)) {
                // Create parent directories — attempt POSIX rwx------ first
                Path parent = filePath.getParent();
                if (parent != null && !Files.exists(parent)) {
                    try {
                        Files.createDirectories(parent,
                                PosixFilePermissions.asFileAttribute(
                                        PosixFilePermissions.fromString("rwx------")));
                    } catch (UnsupportedOperationException e) {
                        Files.createDirectories(parent);
                        AUDIT.warn("share.link.db.dir.permissions.unavailable: non-POSIX filesystem; "
                                + "parent directory created without rwx------ — operator must restrict "
                                + "access manually (CRIT-2/F-1; SR-SQLITE-07).");
                    }
                }

                // Create the DB file with 0600 BEFORE HikariCP opens a connection (CRIT-2/F-1)
                try {
                    Files.createFile(filePath,
                            PosixFilePermissions.asFileAttribute(
                                    PosixFilePermissions.fromString("rw-------")));
                    AUDIT.info("share.link.db.file.created permissions=0600 source=SqliteDbFileInitializer "
                            + "(CRIT-2/F-1; SR-SQLITE-07)");
                } catch (UnsupportedOperationException e) {
                    Files.createFile(filePath);
                    AUDIT.warn("share.link.db.permissions.unavailable: non-POSIX filesystem; "
                            + "DB file created without 0600 permissions — operator must enforce "
                            + "file permissions manually (CRIT-2/F-1; SR-SQLITE-07).");
                }
            } else {
                // File already exists — enforce 0600 regardless of current permissions
                try {
                    Files.setPosixFilePermissions(filePath,
                            PosixFilePermissions.fromString("rw-------"));
                    AUDIT.info("share.link.db.file.chmod permissions=0600 source=SqliteDbFileInitializer "
                            + "(CRIT-2/F-1; SR-SQLITE-07)");
                } catch (UnsupportedOperationException e) {
                    AUDIT.warn("share.link.db.permissions.unavailable: non-POSIX filesystem; "
                            + "cannot enforce 0600 on existing DB file — operator must enforce "
                            + "file permissions manually (CRIT-2/F-1; SR-SQLITE-07).");
                }
            }
        } catch (IOException e) {
            // Scrub path from the error message before propagating (SR-SQLITE-03; CWE-22)
            throw new IllegalStateException(
                    LogScrubber.forErrorMessage("Failed to create/chmod share-link DB file in BFP"), e);
        }
    }
}
