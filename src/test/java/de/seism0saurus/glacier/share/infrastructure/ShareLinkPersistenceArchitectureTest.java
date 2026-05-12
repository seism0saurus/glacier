package de.seism0saurus.glacier.share.infrastructure;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.sql.Statement;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * ArchUnit structural gates for the SQLite share-link persistence adapter.
 *
 * <p>These rules enforce the security invariants agreed in Phase 1:
 * <ol>
 *   <li>No {@code java.sql.Statement} (non-prepared) calls from {@code share.infrastructure.*}
 *       — all SQL must go through {@code PreparedStatement} (SR-SQLITE-10; OWASP A03 — Injection).</li>
 *   <li>{@code sha256Hex} method is private static within {@code share.infrastructure.*} —
 *       raw token hashing must not leak outside the adapter package (SR-SQLITE-19; ADR-SQLITE-04).
 *       [Disabled until Lane 3 implements {@code SqliteShareLinkRepository}]</li>
 *   <li>{@code ShareLinkSummary} record must not carry a field named {@code id}, {@code token},
 *       {@code secret}, or {@code url}, and must not have a field of type {@code ShareLinkId}
 *       (SR-SQLITE-20; ADR-SQLITE-05).
 *       [Disabled until Lane 3 implements {@code ShareLinkSummary}]</li>
 *   <li>{@code ShareLink#creatorIp()} may only be called from {@code ShareLinkServiceImpl.create}
 *       and {@code SqliteShareLinkRepository} — raw IP must not be exposed elsewhere
 *       (SR-SQLITE-23; GDPR Art. 25).
 *       [Disabled until Lane 3 implements {@code SqliteShareLinkRepository}]</li>
 * </ol>
 *
 * <p>Rules 2–4 are {@link Disabled} until Lane 3 completes the implementation. They will be
 * re-enabled in the acceptance phase. The {@code @Disabled} annotation carries the reason
 * so the gate cannot be silently dropped.
 *
 * <p>References:
 * <ul>
 *   <li>SR-SQLITE-10: no {@code Statement} from adapter package</li>
 *   <li>SR-SQLITE-19: single {@code sha256Hex} call site (ADR-SQLITE-04)</li>
 *   <li>SR-SQLITE-20: {@code ShareLinkSummary} no-token ArchUnit rule (ADR-SQLITE-05)</li>
 *   <li>SR-SQLITE-23: {@code ShareLink#creatorIp()} call-site restriction</li>
 *   <li>OWASP A03:2021 — Injection; ASVS V5.3.4 (L1)</li>
 * </ul>
 */
class ShareLinkPersistenceArchitectureTest {

    private static JavaClasses productionClasses;

    @BeforeAll
    static void loadClasses() {
        productionClasses = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("de.seism0saurus.glacier");
    }

    // -------------------------------------------------------------------------
    // Rule 1: No java.sql.Statement from share.infrastructure.* (active)
    // SR-SQLITE-10; OWASP A03:2021 — Injection; ASVS V5.3.4 (L1)
    // -------------------------------------------------------------------------

    /**
     * SR-SQLITE-10: no class in {@code share.infrastructure} may use {@code java.sql.Statement}
     * directly. All SQL execution must use {@code PreparedStatement} parameter binding to prevent
     * SQL injection (OWASP A03:2021; CWE-89).
     *
     * <p>{@code PreparedStatement} is a sub-interface of {@code Statement}, so this rule
     * forbids direct creation or use of the non-prepared variant. JdbcTemplate's
     * {@code execute(PreparedStatementCreator, ...)} paths are safe because they always use
     * the prepared-statement path internally.
     */
    @Test
    void noDirectStatementUseInShareInfrastructure_onlyPreparedStatementAllowed() {
        // SR-SQLITE-10: Statement (non-prepared) must not be used from share.infrastructure
        // OWASP A03:2021 — Injection: all SQL via parameterized queries (C3)
        //
        // DescribedPredicate required by ArchUnit 1.x — lambdas are not accepted directly.
        DescribedPredicate<JavaMethodCall> callsNonPreparedStatement =
                new DescribedPredicate<>("call to java.sql.Statement (non-prepared)") {
                    @Override
                    public boolean test(final JavaMethodCall call) {
                        String ownerName = call.getTarget().getOwner().getName();
                        // Statement is the parent type; PreparedStatement and CallableStatement
                        // are safe sub-interfaces. We only block direct Statement usage.
                        return ownerName.equals(Statement.class.getName());
                    }
                };

        ArchRule rule = noClasses()
                .that().resideInAPackage("de.seism0saurus.glacier.share.infrastructure..")
                .should().callMethodWhere(callsNonPreparedStatement)
                .because("SR-SQLITE-10: all SQL in share.infrastructure must use PreparedStatement "
                        + "parameter binding, never java.sql.Statement directly "
                        + "(OWASP A03:2021 — Injection; CWE-89)");
        rule.check(productionClasses);
    }

    // -------------------------------------------------------------------------
    // Rule 2: sha256Hex is private static within share.infrastructure (Lane 3)
    // SR-SQLITE-19; ADR-SQLITE-04
    // -------------------------------------------------------------------------

    /**
     * SR-SQLITE-19 / ADR-SQLITE-04: the {@code sha256Hex} method on
     * {@code SqliteShareLinkRepository} must be private — raw token hashing must not be
     * callable from outside the adapter package.
     *
     * <p>Disabled until Lane 3 creates {@code SqliteShareLinkRepository}.
     * Re-enable in the acceptance phase.
     */
    @Test
    @Disabled("implemented in Lane 3 — SqliteShareLinkRepository.sha256Hex() not yet present")
    void sha256HexIsPrivateAndOnlyCallableWithinShareInfrastructure() {
        // When enabled, this rule asserts that sha256Hex has private visibility
        // using noClasses().that().doNotResideInAPackage("share.infrastructure..")
        // .should().callMethod(SqliteShareLinkRepository.class, "sha256Hex", String.class)
        // The exact rule implementation is deferred until the target class exists.
    }

    // -------------------------------------------------------------------------
    // Rule 3: ShareLinkSummary has no raw-token fields (Lane 3)
    // SR-SQLITE-20; ADR-SQLITE-05
    // -------------------------------------------------------------------------

    /**
     * SR-SQLITE-20 / ADR-SQLITE-05: the {@code ShareLinkSummary} record must not contain
     * any field named {@code id}, {@code token}, {@code secret}, or {@code url}, and must
     * not have a field of type {@code ShareLinkId}.
     *
     * <p>This prevents the "shadow token retrieval" attack where the management list is used
     * to recover bearer tokens that were intentionally not stored in the DB.
     *
     * <p>Disabled until Lane 3 creates {@code ShareLinkSummary}.
     */
    @Test
    @Disabled("implemented in Lane 3 — ShareLinkSummary record not yet present")
    void shareLinkSummaryHasNoRawTokenOrIdFields() {
        // When enabled, this rule will use ArchUnit's field-access DSL to assert
        // that no field in ShareLinkSummary is named "id", "token", "secret", "url",
        // or has type ShareLinkId.
    }

    // -------------------------------------------------------------------------
    // Rule 4: creatorIp() called only from permitted sites (Lane 3)
    // SR-SQLITE-23; GDPR Art. 25
    // -------------------------------------------------------------------------

    /**
     * SR-SQLITE-23: {@code ShareLink#creatorIp()} may only be called from
     * {@code ShareLinkServiceImpl.create} and {@code SqliteShareLinkRepository}.
     * All other callers are forbidden — raw IP must not be exposed beyond the cap-accounting
     * and HMAC-pseudonymisation paths (GDPR Art. 25 — Data Protection by Design).
     *
     * <p>Disabled until Lane 3 creates {@code SqliteShareLinkRepository}.
     */
    @Test
    @Disabled("implemented in Lane 3 — SqliteShareLinkRepository not yet present")
    void creatorIpOnlyCalledFromPermittedSites() {
        // When enabled, this rule will verify that ShareLink.creatorIp() is called
        // only from ShareLinkServiceImpl and SqliteShareLinkRepository.
    }
}
