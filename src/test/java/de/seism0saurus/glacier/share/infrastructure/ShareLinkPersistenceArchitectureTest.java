package de.seism0saurus.glacier.share.infrastructure;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import de.seism0saurus.glacier.share.domain.ShareLink;
import de.seism0saurus.glacier.share.domain.ShareLinkId;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.sql.Statement;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * ArchUnit structural gates for the SQLite share-link persistence adapter.
 *
 * <p>These rules enforce the security invariants agreed in Phase 1:
 * <ol>
 *   <li>No {@code java.sql.Statement} (non-prepared) calls from {@code share.infrastructure.*}
 *       — all SQL must go through {@code PreparedStatement} (SR-SQLITE-10; OWASP A03 — Injection).</li>
 *   <li>{@code sha256Hex} methods in {@code share.infrastructure.*} must be private —
 *       raw token hashing must not be callable from outside the adapter package
 *       (SR-SQLITE-19; ADR-SQLITE-04).</li>
 *   <li>{@code ShareLinkSummary} record must not carry a field named {@code id}, {@code token},
 *       {@code secret}, or {@code url}, and must not have a field of type {@code ShareLinkId}
 *       (SR-SQLITE-20; ADR-SQLITE-05).</li>
 *   <li>{@code ShareLink#creatorIp()} may only be called from classes within
 *       {@code share.infrastructure} — raw IP must not be accessed outside the
 *       persistence/cap-accounting layer (SR-SQLITE-23; GDPR Art. 25).</li>
 * </ol>
 *
 * <p>References:
 * <ul>
 *   <li>SR-SQLITE-10: no {@code Statement} from adapter package</li>
 *   <li>SR-SQLITE-19: {@code sha256Hex} is private in share.infrastructure (ADR-SQLITE-04)</li>
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
    // Rule 2: sha256Hex is private in share.infrastructure (active)
    // SR-SQLITE-19; ADR-SQLITE-04
    // -------------------------------------------------------------------------

    /**
     * SR-SQLITE-19 / ADR-SQLITE-04: any {@code sha256Hex} method declared in
     * {@code share.infrastructure} must be private.
     *
     * <p>Raw token hashing is an adapter-internal concern. Exposing it as package-visible
     * or public would allow callers outside the adapter to hash arbitrary values using the
     * same algorithm and potentially reason about the token space. Private visibility
     * enforces the encapsulation contract.
     *
     * <p>Both the static variant in {@code SqliteShareLinkRepository} and any other
     * adapter class in the package are covered by this rule.
     */
    @Test
    void sha256HexIsPrivateWithinShareInfrastructure() {
        // SR-SQLITE-19: sha256Hex must be private — hashing logic must not escape the adapter
        // ADR-SQLITE-04: single, encapsulated hash path for token-at-rest storage
        ArchRule rule = methods()
                .that().haveName("sha256Hex")
                .and().areDeclaredInClassesThat()
                .resideInAPackage("de.seism0saurus.glacier.share.infrastructure..")
                .should().bePrivate()
                .because("SR-SQLITE-19 / ADR-SQLITE-04: sha256Hex is an adapter-internal "
                        + "helper — private visibility prevents callers outside the package "
                        + "from depending on the raw token hashing algorithm");
        rule.check(productionClasses);
    }

    // -------------------------------------------------------------------------
    // Rule 3: ShareLinkSummary has no raw-token fields (active)
    // SR-SQLITE-20; ADR-SQLITE-05
    // -------------------------------------------------------------------------

    /**
     * SR-SQLITE-20 / ADR-SQLITE-05: the {@code ShareLinkSummary} record must not contain
     * any field named {@code id}, {@code token}, {@code secret}, or {@code url}.
     *
     * <p>This prevents the "shadow token retrieval" attack: the management list endpoint
     * must never return data that allows reconstruction of a bearer token. The record
     * carries only {@code idHash8} (first 8 chars of SHA-256(token)) — an opaque
     * visual identifier that cannot reverse-engineer the raw token.
     */
    @Test
    void shareLinkSummaryHasNoForbiddenFieldName() {
        // SR-SQLITE-20: field-name gate — names that could expose raw tokens are forbidden
        // ADR-SQLITE-05: ShareLinkSummary is a safe read-model projection
        ArchRule noIdField = fields()
                .that().areDeclaredInClassesThat().haveSimpleName("ShareLinkSummary")
                .should().notHaveName("id")
                .because("SR-SQLITE-20: 'id' would expose the raw ShareLinkId "
                        + "and allow token reconstruction — use idHash8 instead");
        ArchRule noTokenField = fields()
                .that().areDeclaredInClassesThat().haveSimpleName("ShareLinkSummary")
                .should().notHaveName("token")
                .because("SR-SQLITE-20: 'token' is a forbidden field name in ShareLinkSummary "
                        + "(ADR-SQLITE-05)");
        ArchRule noSecretField = fields()
                .that().areDeclaredInClassesThat().haveSimpleName("ShareLinkSummary")
                .should().notHaveName("secret")
                .because("SR-SQLITE-20: 'secret' is a forbidden field name in ShareLinkSummary "
                        + "(ADR-SQLITE-05)");
        ArchRule noUrlField = fields()
                .that().areDeclaredInClassesThat().haveSimpleName("ShareLinkSummary")
                .should().notHaveName("url")
                .because("SR-SQLITE-20: 'url' is a forbidden field name in ShareLinkSummary "
                        + "(ADR-SQLITE-05)");
        ArchRule noShareLinkIdType = fields()
                .that().areDeclaredInClassesThat().haveSimpleName("ShareLinkSummary")
                .should().notHaveRawType(ShareLinkId.class)
                .because("SR-SQLITE-20: ShareLinkSummary must not hold a ShareLinkId — "
                        + "that would allow callers to reconstruct the bearer token URL");
        noIdField.check(productionClasses);
        noTokenField.check(productionClasses);
        noSecretField.check(productionClasses);
        noUrlField.check(productionClasses);
        noShareLinkIdType.check(productionClasses);
    }

    // -------------------------------------------------------------------------
    // Rule 4: creatorIp() called only from share.infrastructure (active)
    // SR-SQLITE-23; GDPR Art. 25
    // -------------------------------------------------------------------------

    /**
     * SR-SQLITE-23: {@code ShareLink#creatorIp()} may only be called from classes that
     * reside in {@code share.infrastructure}.
     *
     * <p>The raw IP is a personal datum under GDPR Art. 4(1). Callers outside the
     * infrastructure layer — service, web, or other bounded contexts — must not receive
     * the raw value. The only legitimate consumers are the in-memory and SQLite repository
     * adapters: {@code InMemoryShareLinkRepository} (cap counting) and
     * {@code SqliteShareLinkRepository} (HMAC pseudonymisation before storage).
     *
     * <p>GDPR Art. 25 — Data Protection by Design and by Default: minimise access to
     * personal data by restricting call sites to the narrowest necessary scope.
     */
    @Test
    void creatorIpOnlyCalledFromShareInfrastructure() {
        // SR-SQLITE-23: raw-IP accessor must not be reachable from outside the
        // infrastructure layer — GDPR Art. 25 data-minimisation by architecture
        ArchRule rule = noClasses()
                .that().resideOutsideOfPackage("de.seism0saurus.glacier.share.infrastructure..")
                .should().callMethod(ShareLink.class, "creatorIp")
                .because("SR-SQLITE-23: ShareLink.creatorIp() returns a raw personal IP datum "
                        + "and must only be accessed from share.infrastructure adapters "
                        + "(GDPR Art. 25 — Data Protection by Design)");
        rule.check(productionClasses);
    }
}
