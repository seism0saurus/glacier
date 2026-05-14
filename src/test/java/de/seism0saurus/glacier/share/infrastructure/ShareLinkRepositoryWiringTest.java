package de.seism0saurus.glacier.share.infrastructure;

import de.seism0saurus.glacier.share.domain.ShareLinkRepository;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.autoconfigure.validation.ValidationAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the conditional bean-wiring for {@link ShareLinkRepository} described in
 * ADR-SQLITE-01 and ADR-IMPL-02.
 *
 * <p>Two scenarios are covered:
 * <ol>
 *   <li><strong>SQLite path present</strong>: {@code glacier.share.db.path=:memory:} +
 *       valid {@code glacier.share.db.ip-hmac-key} → {@link SqliteShareLinkRepository} must
 *       be in the context; {@link InMemoryShareLinkRepository} must not.</li>
 *   <li><strong>SQLite path absent</strong>: no {@code glacier.share.db.path} →
 *       {@link InMemoryShareLinkRepository} must be in the context;
 *       {@link SqliteShareLinkRepository} must not.</li>
 * </ol>
 *
 * <p>Uses {@link ApplicationContextRunner} for a lightweight context that avoids loading
 * the full Glacier application. The minimum set of user configurations and auto-configurations
 * is applied to trigger {@code @ConditionalOnProperty} evaluation and
 * {@code @ConfigurationProperties} binding.
 *
 * <p>References: ADR-SQLITE-01 (conditional opt-in wiring); ADR-IMPL-02 (in-memory default).
 */
class ShareLinkRepositoryWiringTest {

    /**
     * Valid 44-character Base64 key satisfying {@code @Size(min=44)} (SR-SQLITE-22).
     *
     * <p>The value {@code "dGVzdC10ZXN0LXRlc3QtdGVzdC10ZXN0LXRlc3QtdGVzdA=="} is
     * {@code Base64("test-test-test-test-test-test-test-test")} — exactly 44 characters.
     */
    private static final String VALID_HMAC_KEY = "dGVzdC10ZXN0LXRlc3QtdGVzdC10ZXN0LXRlc3QtdGVzdA==";

    /**
     * Base runner with property binding and validation auto-configurations plus the
     * four configuration/repository beans relevant to conditional wiring.
     *
     * <p>Registers:
     * <ul>
     *   <li>{@link SharePersistenceProperties} — the {@code @ConfigurationProperties} bean
     *       gated on {@code glacier.share.db.path}.</li>
     *   <li>{@link SqliteDataSourceConfig} — creates the HikariCP DataSource and
     *       {@code shareJdbcTemplate} bean; gated on {@code glacier.share.db.path}.</li>
     *   <li>{@link SqliteShareLinkRepository} — the durable SQLite adapter;
     *       gated on {@code glacier.share.db.path}.</li>
     *   <li>{@link InMemoryShareLinkRepository} — the default in-memory adapter;
     *       active when {@code glacier.share.db.path} is absent.</li>
     * </ul>
     */
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    ValidationAutoConfiguration.class,
                    ConfigurationPropertiesAutoConfiguration.class))
            .withUserConfiguration(
                    SharePersistenceProperties.class,
                    SqliteDataSourceConfig.class,
                    SqliteShareLinkRepository.class,
                    InMemoryShareLinkRepository.class);

    // -------------------------------------------------------------------------
    // Scenario 1: SQLite adapter active when db.path is set (ADR-SQLITE-01)
    // -------------------------------------------------------------------------

    /**
     * When {@code glacier.share.db.path=:memory:} and a valid HMAC key are supplied,
     * the {@link SqliteShareLinkRepository} must be present in the context and
     * {@link InMemoryShareLinkRepository} must be absent.
     *
     * <p>Arrange: property values for in-memory SQLite with a valid HMAC key.
     * <p>Act:     build the ApplicationContext via the runner.
     * <p>Assert:  context started; SqliteShareLinkRepository bean present;
     *             InMemoryShareLinkRepository bean absent.
     */
    @Test
    void dbPathPresent_sqliteRepositoryActiveAndInMemoryAbsent() {
        runner.withPropertyValues(
                        "glacier.share.db.path=:memory:",
                        "glacier.share.db.ip-hmac-key=" + VALID_HMAC_KEY)
                .run(context -> {
                    assertThat(context)
                            .as("Context must start with glacier.share.db.path=:memory: "
                                    + "and a valid ip-hmac-key (ADR-SQLITE-01)")
                            .hasNotFailed();

                    assertThat(context)
                            .as("SqliteShareLinkRepository must be present when glacier.share.db.path "
                                    + "is set (ADR-SQLITE-01)")
                            .hasSingleBean(SqliteShareLinkRepository.class);

                    assertThat(context)
                            .as("InMemoryShareLinkRepository must be absent when glacier.share.db.path "
                                    + "is set — the SQLite adapter takes precedence (ADR-SQLITE-01)")
                            .doesNotHaveBean(InMemoryShareLinkRepository.class);
                });
    }

    // -------------------------------------------------------------------------
    // Scenario 2: In-memory adapter active when db.path is absent (ADR-IMPL-02)
    // -------------------------------------------------------------------------

    /**
     * When {@code glacier.share.db.path} is absent, the {@link InMemoryShareLinkRepository}
     * must be present in the context and {@link SqliteShareLinkRepository} must be absent.
     *
     * <p>Arrange: no database path property (default / property absent).
     * <p>Act:     build the ApplicationContext via the runner.
     * <p>Assert:  context started; InMemoryShareLinkRepository bean present;
     *             SqliteShareLinkRepository bean absent.
     */
    @Test
    void dbPathAbsent_inMemoryRepositoryActiveAndSqliteAbsent() {
        runner.run(context -> {
            assertThat(context)
                    .as("Context must start when glacier.share.db.path is absent "
                            + "(in-memory is the default adapter, ADR-IMPL-02)")
                    .hasNotFailed();

            assertThat(context)
                    .as("InMemoryShareLinkRepository must be present when glacier.share.db.path "
                            + "is absent (ADR-IMPL-02)")
                    .hasSingleBean(InMemoryShareLinkRepository.class);

            assertThat(context)
                    .as("SqliteShareLinkRepository must be absent when glacier.share.db.path "
                            + "is absent — no SQLite wiring without opt-in (ADR-SQLITE-01)")
                    .doesNotHaveBean(SqliteShareLinkRepository.class);
        });
    }
}
